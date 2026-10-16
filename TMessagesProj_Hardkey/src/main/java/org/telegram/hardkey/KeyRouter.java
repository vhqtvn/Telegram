package org.telegram.hardkey;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * D1 key routing:
 *
 *   SPACE / PAGE_DOWN      → primary RecyclerView down one page
 *   SHIFT+SPACE / PAGE_UP  → primary RecyclerView up one page
 *   ESCAPE                 → activity.onBackPressed()
 *
 * Typing passthrough: when the focused view is a text editor, scroll keys
 * fall through to the original callback so typing is unaffected.
 *
 * BACK is deliberately NOT routed (double-handling risk — Telegram handles
 * BACK extensively itself). ESC-only for back navigation in D1.
 */
final class KeyRouter {

    private static final int PAGE_OVERLAP_KEEP = 10; // keep 10% of height visible for context

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
        // Act only on ACTION_DOWN; swallow the matching ACTION_UP so it
        // cannot leak to the delegate (e.g. SPACE-up firing a focused
        // button click after we already handled the page scroll).
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        // Never hijack typing.
        View focus = activity.getCurrentFocus();
        if (focus != null && focus.onCheckIsTextEditor()) {
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            DriftReport.event("route: back (ESCAPE) on " + activity.getClass().getSimpleName());
            activity.onBackPressed();
            return true;
        }

        boolean up = event.isShiftPressed() || keyCode == KeyEvent.KEYCODE_PAGE_UP;
        DriftReport.event("route: " + (up ? "page-up" : "page-down")
                + " (keycode " + keyCode + (event.isShiftPressed() ? " +shift" : "") + ")"
                + " on " + activity.getClass().getSimpleName());
        return scrollPrimaryList(activity, !up);
    }

    private static boolean isRoutedKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SPACE
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_ESCAPE;
    }

    private static boolean scrollPrimaryList(Activity activity, boolean down) {
        RecyclerView primary = findPrimaryList(activity);
        if (primary == null) {
            DriftReport.event("route: no RecyclerView found on " + activity.getClass().getSimpleName());
            return false;
        }
        int page = Math.max(1, primary.getHeight() * (100 - PAGE_OVERLAP_KEEP) / 100);
        primary.scrollBy(0, down ? page : -page);
        DriftReport.scrolled(primary, down, page);
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
