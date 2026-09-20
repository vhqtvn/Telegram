package org.telegram.messenger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;

import java.util.Map;

/**
 * Fork-added continuous main-thread liveness monitor (v4). The story so far, three bugreport
 * sightings (latest 2026-09-20): after a Samsung OneUI frozen-cached -> unfreeze ->
 * task-to-front resume, the app draws ONE healthy frame, then the main thread wedges inside
 * the NEXT vsync dispatch — native stack NativeDisplayEventReceiver::dispatchVsync ->
 * CheckJNI::CallObjectMethodV -> ConditionVariable::WaitHoldingLocks, i.e. the ART suspend
 * handshake at JNI re-entry. Main is only intermittently alive in that state, so the v2
 * front-of-queue canary passes; and the v3 first-frame-after-resume check disarmed itself
 * after that first healthy frame — both predicates were structurally blind MID-SESSION.
 *
 * v4 replaces the one-shot first-frame check with CONTINUOUS frame-evidence stall detection:
 * while resumed and the screen is interactive, the watchdog loop posts a one-shot
 * Choreographer probe frame callback every FRAME_PROBE_INTERVAL_MS (5000). Every delivered
 * probe frame — and every onResumed() — refreshes lastFrameEvidenceMs. If no frame evidence
 * has arrived for FRAME_STALL_TIMEOUT_MS (15000) while resumed and interactive, the app is
 * mid-session vsync-wedged: fire() first asks ART for a full thread dump via SIGQUIT
 * self-capture (the wedge lives BETWEEN Java frames, so the native traces are the only
 * revelation — they land in tombstoned/logcat), then captures the Java main stack and a
 * thread-state census, then kills the process so Android relaunches the app cleanly.
 *
 * The continuous canary below predates v4 and is unchanged: a daemon thread posts a
 * front-of-queue canary every CHECK_INTERVAL_MS and MISSES_TO_KILL consecutive misses
 * (~15s of true starvation — a healthy main drains a queue-head canary between any
 * messages) takes the same fire() path. It covers TOTAL main starvation; the frame-evidence
 * predicate covers the intermittent-alive vsync wedge. Screen-off/doze suspends both probing
 * and firing (screenInteractive() defaults true when PowerManager is unavailable); a paused
 * activity never fires. Steady-state cost when healthy: one Choreographer callback per 5s —
 * negligible. Debug builds are freezer-exempt, which is why debug never hangs — this guard
 * matters for release.
 */
public class MainThreadWatchdog {

    private static final long CHECK_INTERVAL_MS = 3000;
    private static final int MISSES_TO_KILL = 5;
    private static final long FRAME_STALL_TIMEOUT_MS = 15000;
    private static final long FRAME_PROBE_INTERVAL_MS = 5000;

    private static final Object lock = new Object();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Thread mainThread = Looper.getMainLooper().getThread();

    private static volatile boolean started;
    private static volatile int postedGen;
    private static volatile int ranGen;

    private static volatile boolean resumed;
    private static volatile long lastFrameEvidenceMs;
    private static volatile long lastFrameProbePostMs;
    private static volatile int probeGen;
    private static volatile PowerManager powerManager;

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
     * Refreshes the frame-evidence baseline and marks the activity resumed. Called from
     * LaunchActivity.onResume right after ensureStarted(). The resume moment itself counts as
     * frame evidence, so a resume that never produces a first frame still fires at
     * FRAME_STALL_TIMEOUT_MS; every delivered probe frame thereafter keeps re-arming the
     * window for the whole session. No Choreographer is touched here — first probe is posted
     * by the watchdog loop within a cycle.
     */
    public static void onResumed() {
        lastFrameEvidenceMs = SystemClock.uptimeMillis();
        resumed = true;
        if (BuildVars.DEBUG_VERSION) {
            FileLog.d("MainThreadWatchdog: resumed, frame-stall baseline reset lastFrameEvidenceMs=" + lastFrameEvidenceMs);
        }
    }

    /**
     * Marks the activity paused; the continuous canary loop keeps running regardless. While
     * paused there is no probing and no frame-stall firing.
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
            if (resumed && screenInteractive() && (lastFrameProbePostMs == 0 || now - lastFrameProbePostMs >= FRAME_PROBE_INTERVAL_MS)) {
                lastFrameProbePostMs = now;
                final int probeGeneration = ++probeGen;
                if (BuildVars.DEBUG_VERSION) {
                    FileLog.d("MainThreadWatchdog: frame-probe tick, dt=" + (lastFrameEvidenceMs == 0 ? -1 : now - lastFrameEvidenceMs) + "ms since last frame evidence");
                }
                mainHandler.post(() -> {
                    // Resilient to main slowness: if a newer probe is already pending, this
                    // stale one is a no-op — the newest probe alone arms the frame callback.
                    if (probeGeneration != probeGen) {
                        return;
                    }
                    // One-shot: does not re-post itself; the next probe cycle re-arms it.
                    Choreographer.getInstance().postFrameCallback(frameTimeNanos -> lastFrameEvidenceMs = SystemClock.uptimeMillis());
                });
            }
            if (resumed && screenInteractive() && lastFrameEvidenceMs != 0 && now - lastFrameEvidenceMs >= FRAME_STALL_TIMEOUT_MS) {
                if (BuildVars.DEBUG_VERSION) {
                    FileLog.d("MainThreadWatchdog: frame stall detected, dt=" + (now - lastFrameEvidenceMs) + "ms since last frame evidence");
                }
                fire("no frame evidence for 15s while resumed and screen interactive (mid-session vsync wedge)");
                return;
            }
        }
    }

    /**
     * True when the display is interactive. PowerManager is resolved lazily from the
     * application context; any failure (context null, service null, binder error) defaults
     * to true — fail toward detection rather than toward blindness.
     */
    private static boolean screenInteractive() {
        try {
            PowerManager manager = powerManager;
            if (manager == null) {
                Context context = ApplicationLoader.applicationContext;
                if (context == null) {
                    return true;
                }
                manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (manager == null) {
                    return true;
                }
                powerManager = manager;
            }
            return manager.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void fire(String conditionDescription) {
        try {
            FileLog.e("MainThreadWatchdog: requesting thread dump (SIGQUIT) before recovery kill");
            android.os.Process.sendSignal(android.os.Process.myPid(), 3);
            Thread.sleep(500);
        } catch (Throwable t) {
            // Best-effort native dump; it must never prevent the recovery kill below.
        }
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
