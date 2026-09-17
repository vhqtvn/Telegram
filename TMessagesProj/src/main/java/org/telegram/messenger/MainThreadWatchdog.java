package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

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
 */
public class MainThreadWatchdog {

    private static final long CHECK_INTERVAL_MS = 3000;
    private static final int MISSES_TO_KILL = 5;

    private static final Object lock = new Object();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Thread mainThread = Looper.getMainLooper().getThread();

    private static volatile boolean started;
    private static volatile int postedGen;
    private static volatile int ranGen;

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
                        fire();
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
        }
    }

    private static void fire() {
        try {
            Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
            StringBuilder builder = new StringBuilder();
            builder.append("MainThreadWatchdog: main thread starved ~15s (5 consecutive canary misses); stack at detection: postedGen=").append(postedGen).append(" ranGen=").append(ranGen).append('\n');
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
            FileLog.e("MainThreadWatchdog: main thread starved ~15s (5 consecutive canary misses); killing process to recover — known Samsung freezer/vsync hang");
        } catch (Throwable t) {
            // The kill line itself is also best-effort logging — it must never prevent the kill.
        }
        Process.killProcess(Process.myPid());
    }
}
