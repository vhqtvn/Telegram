package org.telegram.hardkey;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Window;

import java.lang.ref.WeakReference;

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

    /**
     * D5: last resumed activity, weakly — the QS tile services have no
     * activity of their own, and EinkMode's enable path wants the LIVE
     * screen for its animator sweep exactly like the combo path gets it
     * from dispatchKeyEvent. Null when nothing is resumed (app in
     * background: the sweep becomes the documented no-op, see
     * EinkMode.clearAnimators).
     */
    private static volatile WeakReference<Activity> lastResumed;

    /** D5: the currently/most-recently resumed activity, or null. */
    static Activity currentActivity() {
        WeakReference<Activity> ref = lastResumed;
        return ref != null ? ref.get() : null;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        EinkMode.clearAnimators(activity); // D4: screen-bind animator sweep
        wrap(activity, false);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        lastResumed = new WeakReference<>(activity); // D5 (see field comment)
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
