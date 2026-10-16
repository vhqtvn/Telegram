package org.telegram.hardkey;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Window;

/**
 * ActivityLifecycleCallbacks (D0). Wraps every activity's Window.Callback at
 * onActivityCreated — after Activity.attach() has installed the activity as
 * the window callback, and before user interaction. Idempotent: activities
 * whose callback is already wrapped are skipped.
 */
public class HardkeyInstaller implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
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
            window.setCallback(new HardkeyWindowCallback(current, activity));
            DriftReport.callbackInstalled(activity);
        } catch (Throwable t) {
            DriftReport.event("wrap failed: " + activity.getClass().getSimpleName() + " — " + t);
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
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
