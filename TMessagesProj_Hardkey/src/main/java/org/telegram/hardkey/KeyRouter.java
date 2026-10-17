package org.telegram.hardkey;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;

/**
 * D1/D2 key routing:
 *
 *   SPACE / PAGE_DOWN      → primary RecyclerView down one page
 *   SHIFT+SPACE / PAGE_UP  → primary RecyclerView up one page
 *   ESCAPE                 → activity.onBackPressed()
 *
 * Typing passthrough: when the focused view is a text editor, scroll keys
 * fall through to the original callback so typing is unaffected.
 *
 * UP/DOWN pairing (D2): follow-up events (UP/MULTIPLE) of routed keys are
 * consumed ONLY when their DOWN was consumed here. A DOWN that falls
 * through to the delegate gets a follow-up that falls through too, so the
 * delegate always sees symmetric pairs.
 *
 * BACK is deliberately NOT routed (double-handling risk — Telegram handles
 * BACK extensively itself). ESC-only for back navigation in D1.
 */
final class KeyRouter {

    private static final int PAGE_OVERLAP_KEEP = 10; // keep 10% of height visible for context

    /**
     * DOWNs consumed by the router, keyed by (keyCode, deviceId). Cleared
     * when the matching follow-up is consumed. Main-thread confined —
     * dispatchKeyEvent only ever runs on the main thread. A follow-up lost
     * to a focus change leaks at most one long per physical key press.
     */
    private static final HashSet<Long> CONSUMED_DOWNS = new HashSet<>();

    private KeyRouter() {
    }

    /**
     * @return true if the event was consumed here (do not forward to the
     * delegate); false to pass through to the original callback.
     */
    static boolean route(Activity activity, KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (!isRoutedKey(keyCode)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return routeDown(activity, event, keyCode);
        }
        if (event.getAction() == KeyEvent.ACTION_UP
                && CONSUMED_DOWNS.remove(pairKey(event))) {
            // Our DOWN consumed this key; swallow the follow-up so it cannot
            // leak to the delegate (e.g. SPACE-up firing a focused button).
            return true;
        }
        DriftReport.pairPassthrough(event, isEditorFocused(activity));
        return false;
    }

    private static boolean routeDown(Activity activity, KeyEvent event, int keyCode) {
        // Never hijack typing.
        if (isEditorFocused(activity)) {
            DriftReport.event("route: passthrough DOWN (editor focused) on "
                    + activity.getClass().getSimpleName());
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            DriftReport.event("route: back (ESCAPE) on " + activity.getClass().getSimpleName());
            activity.onBackPressed();
            CONSUMED_DOWNS.add(pairKey(event));
            return true;
        }

        boolean up = event.isShiftPressed() || keyCode == KeyEvent.KEYCODE_PAGE_UP;
        DriftReport.event("route: " + (up ? "page-up" : "page-down")
                + " (keycode " + keyCode + (event.isShiftPressed() ? " +shift" : "") + ")"
                + " on " + activity.getClass().getSimpleName());
        boolean handled = scrollPrimaryList(activity, !up);
        if (handled) {
            CONSUMED_DOWNS.add(pairKey(event));
        }
        return handled;
    }

    private static boolean isRoutedKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SPACE
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_ESCAPE;
    }

    private static boolean isEditorFocused(Activity activity) {
        View focus = activity.getCurrentFocus();
        return focus != null && focus.onCheckIsTextEditor();
    }

    private static long pairKey(KeyEvent event) {
        return ((long) event.getKeyCode() << 32) | (event.getDeviceId() & 0xffffffffL);
    }

    private static boolean scrollPrimaryList(Activity activity, boolean down) {
        RecyclerView primary = findPrimaryList(activity);
        if (primary == null) {
            DriftReport.event("route: no RecyclerView found on " + activity.getClass().getSimpleName());
            return false;
        }
        int page = Math.max(1, primary.getHeight() * (100 - PAGE_OVERLAP_KEEP) / 100);
        // Receipt: measure the real movement, not the request. scrollBy
        // clamps at the list edges, so |actual| can be smaller than |page|
        // (and 0 when already at an edge).
        int before = primary.computeVerticalScrollOffset();
        primary.scrollBy(0, down ? page : -page);
        int actual = primary.computeVerticalScrollOffset() - before;
        DriftReport.scrolled(primary, down, page, actual);
        return true;
   }

    /**
     * Primary list = largest visible RecyclerView found by decor-view
     * traversal. Records the discovery to DriftReport for every routed key
     * press (cheap at human key-press rates).
     */
    private static RecyclerView findPrimaryList(Activity activity) {
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (!(decor instanceof ViewGroup)) {
            return null;
        }
        RecyclerView best = null;
        int bestArea = 0;
        int found = 0;
        Deque<View> stack = new ArrayDeque<>();
        stack.push(decor);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v instanceof RecyclerView) {
                found++;
                if (v.isShown() && v.getWidth() > 0 && v.getHeight() > 0) {
                    int area = v.getWidth() * v.getHeight();
                    if (area > bestArea) {
                        bestArea = area;
                        best = (RecyclerView) v;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) v;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    stack.push(group.getChildAt(i));
                }
            }
        }
        DriftReport.listsDiscovered(activity, found, best);
        return best;
    }
}
