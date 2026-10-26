package org.telegram.hardkey;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Window;

/**
 * ActivityLifecycleCallbacks (D0/D2). Wraps every activity's Window.Callback:
 *  - at onActivityCreated — after Activity.attach() has installed the
 *    activity as the window callback, and before user interaction;
 *  - at onActivityResumed (D2) — real replacement detection: if anything
 *    swapped the window callback out from under us while the activity was
 *    paused/stopped, the drift is logged and the wrapper is re-installed
 *    (repair), so hardkey routing survives hostile re-wrapping.
 *
 * Idempotent: activities whose callback is already wrapped are skipped.
 * The wrapper itself stays installed even while the kill-switch is OFF —
 * it is behaviorally inert then (see Hardkey).
 */
public class HardkeyInstaller implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        EinkMode.clearAnimators(activity); // D4: screen-bind animator sweep
        wrap(activity, false);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        EinkMode.clearAnimators(activity); // D4: resume animator sweep
        wrap(activity, true);
    }

    private static void wrap(Activity activity, boolean fromResume) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                DriftReport.event("wrap: " + activity.getClass().getSimpleName() + " has no window");
                return;
            }
            Window.Callback current = window.getCallback();
            if (current instanceof HardkeyWindowCallback) {
                DriftReport.callbackCheck(activity, true);
                return;
            }
            if (current == null) {
                current = activity; // Activity itself implements Window.Callback
            }
            window.setCallback(new HardkeyWindowCallback(current, activity));
            if (fromResume) {
                // Replacement had happened under us; log drift, then repair.
                DriftReport.callbackCheck(activity, false);
            }
            DriftReport.callbackInstalled(activity);
        } catch (Throwable t) {
            DriftReport.event("wrap failed: " + activity.getClass().getSimpleName() + " — " + t);
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
