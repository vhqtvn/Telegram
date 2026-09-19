package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;

import java.util.Map;

/**
 * Fork-added continuous main-thread liveness monitor: a Samsung OneUI freezer thaw can leave the
 * main thread starved by freezer/vsync/display churn BEFORE any lifecycle message is deliverable,
 * so a resume-armed watchdog is structurally blind to that hang path (the splash is NOT_FOCUSABLE,
 * so the system never raises an ANR there either). A daemon thread posts a front-of-queue canary
 * every CHECK_INTERVAL_MS and, after MISSES_TO_KILL consecutive misses (~15s of true starvation —
 * a healthy main drains a queue-head canary between any messages), captures the main stack and a
 * thread-state census as evidence, then kills the process so Android relaunches the app cleanly.
 * Debug builds are freezer-exempt, which is why debug never hangs — this guard matters for release.
 *
 * Bugreport-verified live hang (2026-09-19): the main thread can be INTERMITTENTLY alive — it keeps
 * draining binder/receiver messages — while vsync dispatch is wedged BEFORE the Java layer, so no
 * Choreographer callback ever runs, the first draw never happens, the black 0-byte
 * SnapshotStartingWindow stays attached and input reports NOT_VISIBLE. The canary above can pass in
 * that state (queue-head runnables still drain), which is why first-frame-after-resume is the robust
 * predicate: onResumed() arms a one-shot Choreographer frame callback (never re-posted — zero
 * steady-state cost), and if no frame has arrived within FIRST_FRAME_TIMEOUT_MS while resumed, the
 * same fire() path captures evidence and kills the process so Android relaunches the app cleanly.
 */
public class MainThreadWatchdog {

    private static final long CHECK_INTERVAL_MS = 3000;
    private static final int MISSES_TO_KILL = 5;
    private static final long FIRST_FRAME_TIMEOUT_MS = 15000;

    private static final Object lock = new Object();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Thread mainThread = Looper.getMainLooper().getThread();

    private static volatile boolean started;
    private static volatile int postedGen;
    private static volatile int ranGen;

    private static volatile boolean resumed;
    private static volatile long frameArmUptimeMs;
    private static volatile boolean frameSeen;
    private static volatile long lastFrameUptimeMs;

    public static void ensureStarted() {
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
        }
        Thread thread = new Thread(MainThreadWatchdog::monitor, "MainThreadWatchdog");
        thread.setDaemon(true);
        thread.start();
        if (BuildVars.DEBUG_VERSION) {
            FileLog.d("MainThreadWatchdog: continuous main-thread liveness monitor started");
        }
    }

    /**
     * Arms the one-shot first-frame-after-resume check. Called from LaunchActivity.onResume right
     * after ensureStarted(). The Choreographer registration is posted to the main handler because
     * Choreographer.getInstance() may only be touched on the main thread (this also stays correct
     * if a future caller is off-main). The frame callback does NOT re-post itself: one-shot, zero
     * steady-state cost.
     */
    public static void onResumed() {
        frameArmUptimeMs = SystemClock.uptimeMillis();
        frameSeen = false;
        resumed = true;
        if (BuildVars.DEBUG_VERSION) {
            FileLog.d("MainThreadWatchdog: armed first-frame check, frameArmUptimeMs=" + frameArmUptimeMs);
        }
        mainHandler.post(() -> Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
            lastFrameUptimeMs = SystemClock.uptimeMillis();
            frameSeen = true;
            if (BuildVars.DEBUG_VERSION) {
                FileLog.d("MainThreadWatchdog: first frame after resume dt=" + (lastFrameUptimeMs - frameArmUptimeMs) + "ms");
            }
        }));
    }

    /**
     * Disarms the first-frame check only; the continuous canary loop keeps running regardless.
     */
    public static void onPaused() {
        resumed = false;
    }

    private static void monitor() {
        int missStreak = 0;
        while (true) {
            final int generation = ++postedGen;
            mainHandler.postAtFrontOfQueue(() -> {
                // Monotonic acknowledgement: accumulated front-of-queue canaries run newest-first,
                // so an unconditional store could move ranGen backward after a recovery drains them.
                if (generation > ranGen) {
                    ranGen = generation;
                }
            });
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (ranGen < postedGen) {
                missStreak++;
                if (BuildVars.DEBUG_VERSION && missStreak == 1) {
                    FileLog.d("MainThreadWatchdog: canary miss, main thread has not drained queue head (postedGen=" + postedGen + " ranGen=" + ranGen + ")");
                }
                if (missStreak >= MISSES_TO_KILL) {
                    if (ranGen < postedGen) {
                        fire("main thread starved ~15s (5 consecutive canary misses)");
                        return;
                    }
                    if (BuildVars.DEBUG_VERSION) {
                        FileLog.d("MainThreadWatchdog: canary recovered at kill threshold, streak cleared (postedGen=" + postedGen + " ranGen=" + ranGen + ")");
                    }
                    missStreak = 0;
                }
            } else {
                if (BuildVars.DEBUG_VERSION && missStreak > 0) {
                    FileLog.d("MainThreadWatchdog: canary recovered after " + missStreak + " miss(es)");
                }
                missStreak = 0;
            }
            long now = SystemClock.uptimeMillis();
            if (frameArmUptimeMs != 0 && frameSeen) {
                // One-shot per resume: disarm until the next onResumed() re-arms. A late frame
                // callback from a previous arm may set frameSeen after a fresh arm — benign and
                // conservative-safe, because frameSeen only ever disarms, never arms.
                frameArmUptimeMs = 0;
            }
            if (resumed && frameArmUptimeMs != 0 && !frameSeen && now - frameArmUptimeMs >= FIRST_FRAME_TIMEOUT_MS) {
                if (BuildVars.DEBUG_VERSION) {
                    FileLog.d("MainThreadWatchdog: first-frame timeout miss, dt=" + (now - frameArmUptimeMs) + "ms arm=" + frameArmUptimeMs);
                }
                fire("no frame within 15s after resume (vsync-dispatch wedge)");
                return;
            }
        }
    }

    private static void fire(String conditionDescription) {
        try {
            Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
            StringBuilder builder = new StringBuilder();
            builder.append("MainThreadWatchdog: ").append(conditionDescription).append("; stack at detection: postedGen=").append(postedGen).append(" ranGen=").append(ranGen).append('\n');
            StackTraceElement[] mainStack = allStackTraces.get(mainThread);
            if (mainStack == null) {
                mainStack = mainThread.getStackTrace();
            }
            for (StackTraceElement element : mainStack) {
                builder.append("\tat ").append(element).append('\n');
            }
            FileLog.e(builder.toString());
            int runnable = 0, blocked = 0, waiting = 0, timedWaiting = 0, newborn = 0, terminated = 0;
            for (Thread thread : allStackTraces.keySet()) {
                switch (thread.getState()) {
                    case RUNNABLE: runnable++; break;
                    case BLOCKED: blocked++; break;
                    case WAITING: waiting++; break;
                    case TIMED_WAITING: timedWaiting++; break;
                    case NEW: newborn++; break;
                    case TERMINATED: terminated++; break;
                }
            }
            FileLog.e("MainThreadWatchdog: " + allStackTraces.size() + " threads: RUNNABLE=" + runnable + " BLOCKED=" + blocked + " WAITING=" + waiting + " TIMED_WAITING=" + timedWaiting + " NEW=" + newborn + " TERMINATED=" + terminated + "; main=" + mainThread.getState());
        } catch (Throwable t) {
            // Evidence capture must never prevent the recovery kill below.
        }
        try {
            FileLog.e("MainThreadWatchdog: " + conditionDescription + "; killing process to recover — known Samsung freezer/vsync hang");
        } catch (Throwable t) {
            // The kill line itself is also best-effort logging — it must never prevent the kill.
        }
        Process.killProcess(Process.myPid());
    }
}
