package org.telegram.hardkey;

import android.app.Activity;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Window.Callback decorator (D0). Delegates every call unchanged to the
 * original callback (the activity); intercepts only dispatchKeyEvent to
 * observe and route hardware keys. All other behavior is byte-identical to
 * the unwrapped callback.
 */
public class HardkeyWindowCallback implements Window.Callback {

    private final Window.Callback delegate;
    private final Activity activity;

    public HardkeyWindowCallback(Window.Callback delegate, Activity activity) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate callback must not be null");
        }
        this.delegate = delegate;
        this.activity = activity;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The kill-switch combo is recognized in every state: it is the only
        // path back on once routing is disabled. It is consumed here and
        // never forwarded, so it can never type an H anywhere.
        if (Hardkey.isKillSwitchCombo(event)) {
            Hardkey.toggle();
            return true;
        }
        // D4 combos, same every-state contract. Eink mode is INDEPENDENT
        // of the routing kill-switch (a display-quality toggle needs no
        // routing), so both are consumed before the enabled gate. Like the
        // kill-switch combo, they are never forwarded — CTRL+SHIFT+E/R must
        // not type E/R into anything.
        if (Hardkey.isEinkCombo(event)) {
            EinkMode.toggle(activity);
            return true;
        }
        if (Hardkey.isRefreshCombo(event)) {
            EinkMode.nudge(activity);
            return true;
        }
        if (!Hardkey.isEnabled()) {
            // Kill-switch OFF: wrapper stays installed but behaviorally
            // inert — byte-identical pure delegation, no observation.
            return delegate.dispatchKeyEvent(event);
        }
        DriftReport.key(event, isEditorFocused());
        if (KeyRouter.route(activity, event)) {
            return true;
        }
        return delegate.dispatchKeyEvent(event);
    }

    private boolean isEditorFocused() {
        View focus = activity.getCurrentFocus();
        return focus != null && focus.onCheckIsTextEditor();
    }

    // ── everything below is pure delegation ────────────────────────────────

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return delegate.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return delegate.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return delegate.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return delegate.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return delegate.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public View onCreatePanelView(int featureId) {
        return delegate.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return delegate.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return delegate.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return delegate.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return delegate.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        delegate.onWindowAttributesChanged(attrs);
    }

    @Override
    public void onContentChanged() {
        delegate.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        delegate.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        delegate.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        delegate.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        delegate.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return delegate.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(SearchEvent searchEvent) {
        return delegate.onSearchRequested(searchEvent);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return delegate.onWindowStartingActionMode(callback);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        return delegate.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        delegate.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        delegate.onActionModeFinished(mode);
    }
}
