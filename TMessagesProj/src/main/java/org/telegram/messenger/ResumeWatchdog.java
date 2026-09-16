package org.telegram.messenger;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fork-added resume watchdog: on some devices (Samsung OneUI) the process is unfrozen after
 * being cached and the main thread is starved by freezer/vsync/display churn before it can
 * produce a single Java frame; the splash is NOT_FOCUSABLE, so the system never raises an ANR
 * and the user stares at it indefinitely. If a front-of-queue canary posted at resume time has
 * still not run HANG_TIMEOUT_MS later, we kill our own process so Android relaunches the app
 * cleanly within seconds. The timeout is deliberately conservative: only a truly starved main
 * thread fails to drain a front-of-queue canary within it.
 * On detection the fire path first captures the main thread stack and a thread-state summary
 * via FileLog before killing, as evidence for the Samsung freezer/vsync starvation diagnosis.
 */
public class ResumeWatchdog {

    private static final long HANG_TIMEOUT_MS = 15000;

    private static final Object lock = new Object();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Thread mainThread = Looper.getMainLooper().getThread();

    private static volatile boolean resumed;
    private static volatile int armGeneration;
    private static volatile long lastCanaryRunUptimeMs;
    private static volatile int lastCanaryGeneration;
    private static ScheduledExecutorService executor;
    private static ScheduledFuture<?> pendingCheck;

    public static void onResumed(Activity activity) {
        resumed = true;
        final long armUptimeMs = SystemClock.uptimeMillis();
        final int generation;
        synchronized (lock) {
            generation = ++armGeneration;
        }
        if (BuildVars.DEBUG_VERSION) {
            FileLog.d("ResumeWatchdog: armed gen=" + generation);
        }
        mainHandler.postAtFrontOfQueue(() -> {
            long canaryUptimeMs = SystemClock.uptimeMillis();
            lastCanaryRunUptimeMs = canaryUptimeMs;
            lastCanaryGeneration = generation;
            if (BuildVars.DEBUG_VERSION) {
                FileLog.d("ResumeWatchdog: canary healthy gen=" + generation + " dt=" + (canaryUptimeMs - armUptimeMs) + "ms");
            }
            ScheduledFuture<?> check;
            synchronized (lock) {
                check = pendingCheck;
                pendingCheck = null;
            }
            if (check != null) {
                check.cancel(false);
            }
        });
        synchronized (lock) {
            if (executor == null) {
                executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ResumeWatchdog");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            if (pendingCheck != null) {
                pendingCheck.cancel(false);
            }
            pendingCheck = executor.schedule(() -> runCheck(generation), HANG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    public static void onPaused() {
        resumed = false;
        if (BuildVars.DEBUG_VERSION) {
            FileLog.d("ResumeWatchdog: disarmed gen=" + armGeneration);
        }
        synchronized (lock) {
            if (pendingCheck != null) {
                pendingCheck.cancel(false);
                pendingCheck = null;
            }
        }
    }

    private static void runCheck(int generation) {
        if (!resumed || generation != armGeneration || lastCanaryGeneration >= generation) {
            return;
        }
        try {
            Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
            StringBuilder builder = new StringBuilder();
            builder.append("ResumeWatchdog: main thread starved for >15s after resume; stack at detection: gen=").append(generation).append('\n');
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
            FileLog.e("ResumeWatchdog: " + allStackTraces.size() + " threads: RUNNABLE=" + runnable + " BLOCKED=" + blocked + " WAITING=" + waiting + " TIMED_WAITING=" + timedWaiting + " NEW=" + newborn + " TERMINATED=" + terminated + "; main=" + mainThread.getState());
        } catch (Throwable t) {
            // Evidence capture must never prevent the recovery kill below.
        }
        FileLog.e("main thread starved for >15s after resume; killing process to recover — known Samsung freezer/vsync hang");
        Process.killProcess(Process.myPid());
    }
}
