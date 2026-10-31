package org.telegram.hardkey;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.Components.RecyclerListView;

import java.util.Locale;

/**
 * D3 selection layer. Owns the arrow-key selection state for the primary
 * RecyclerView discovered by KeyRouter (the same D2 discovery used for
 * page-scrolling):
 *
 *   DPAD_DOWN / DPAD_UP  → move selection one row (one step per DOWN event,
 *                          so held-key repeats move continuously)
 *   ENTER / DPAD_CENTER  → activate the selected row through the REAL click
 *                          path (RecyclerListView.clickItem — the exact
 *                          listener a touch tap fires)
 *   n / p (chat switch)  → dialogs list: move one row and activate
 *
 * Scrolling integration: when a move lands outside the fully-visible range,
 * the target row is scrolled into view INSTANTLY (scrollToPositionWithOffset
 * — no smooth scroll, e-ink). SPACE/PAGE keys still page-scroll while a
 * selection is active; both modes coexist (arrows = item movement,
 * Space/PgDn = page).
 *
 * Selection state is per active list and cleared on screen change: any key
 * re-runs the D2 discovery, and a changed primary-list identity (fragment
 * navigation creates a new RecyclerListView instance) or a changed activity
 * resets the selection. The ring overlay follows the selected child and
 * hides itself when that child is not attached/visible.
 *
 * Threading: everything here runs on the main thread (dispatchKeyEvent and
 * the layout-pass post()s); no locking needed, same confinement contract as
 * KeyRouter.CONSUMED_DOWNS.
 */
final class SelectionController {

    /** Main-thread confined binding + selection state. */
    private static Activity boundActivity;
    private static RecyclerView boundList;
    private static int selectedPos = -1;

    /** Ring overlay attached to boundActivity's decor (created lazily). */
    private static FocusRingView ring;

    /**
     * Keeps the ring glued to its row while ANY code scrolls the bound list
     * (selection moves, SPACE/PAGE page-scroll). Added with
     * addOnScrollListener — additive by contract, never replaces a screen's
     * own scroll listener.
     */
    private static final RecyclerView.OnScrollListener RING_GLUE =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    if (recyclerView == boundList) {
                        updateRing(false);
                    }
                }
            };

    private SelectionController() {
    }

    /**
     * Kill-switch turned OFF (Hardkey.toggle): drop the selection and hide
     * the ring immediately so no visual hardkey state survives the switch.
     * Runs on the main thread (dispatchKeyEvent context).
     */
    static void onRoutingDisabled() {
        selectedPos = -1;
        boundList = null;
        if (ring != null) {
            ring.hide();
        }
    }

    /**
     * DPAD_DOWN/DPAD_DOWN-repeat/DPAD_UP: move the selection one row.
     *
     * @return true if consumed (selection state exists or was created);
     *         false when there is nothing to select on (falls through).
     */
    static boolean moveSelection(Activity activity, boolean down) {
        RecyclerView list = KeyRouter.findPrimaryList(activity);
        syncBinding(activity, list);
        if (list == null) {
            DriftReport.event("select: no RecyclerView on "
                    + activity.getClass().getSimpleName());
            return false;
        }
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        int count = adapter != null ? adapter.getItemCount() : 0;
        if (count <= 0) {
            DriftReport.event("select: empty list on "
                    + activity.getClass().getSimpleName());
            return false;
        }
        int newPos = selectedPos < 0 ? 0 : selectedPos + (down ? 1 : -1);
        if (newPos < 0 || newPos >= count) {
            // At a list edge with a live selection: consume (arrows mean row
            // movement while a selection exists), hold the position.
            if (selectedPos >= 0) {
                DriftReport.event("select: hold pos=" + selectedPos + " (edge)");
                return true;
            }
            return false; // defensive: cannot happen with count > 0
        }
        selectedPos = newPos;
        DriftReport.selected(list, newPos);
        boolean scrolled = ensureVisible(list, newPos, down);
        if (!scrolled) {
            updateRing(true);
        }
        return true;
    }

    /**
     * ENTER / DPAD_CENTER: activate the selected row through the real click
     * path. With no live selection the key is NOT ours — return false so the
     * delegate keeps its stock ENTER meaning.
     */
    static boolean activateSelected(Activity activity) {
        syncBinding(activity, KeyRouter.findPrimaryList(activity));
        if (boundActivity != activity || boundList == null || selectedPos < 0) {
            DriftReport.event("activate: no selection — passthrough");
            return false;
        }
        final RecyclerView list = boundList;
        if (!(list instanceof RecyclerListView)) {
            // Selection exists but the list has no programmatic click API;
            // consume rather than letting ENTER click an arbitrary focused
            // view underneath a visible ring.
            DriftReport.event("activate: unsupported list type "
                    + list.getClass().getSimpleName());
            return true;
        }
        if (list.findViewHolderForAdapterPosition(selectedPos) == null) {
            // Page-scroll can leave the selected row detached; bring it back,
            // then activate after the layout pass.
            final int pos = selectedPos;
            ensureVisible(list, pos, true);
            list.post(() -> activateHolder(list, pos));
            return true;
        }
        activateHolder(list, selectedPos);
        return true;
    }

    /**
     * n/p chat switching (D3, best-effort): on the dialogs list move the
     * selection one row and activate it (open that chat). The dialogs list
     * is identified by its adapter class name containing "dialogsadapter"
     * (case-insensitive) — deliberately conservative: on any other screen,
     * or under release obfuscation that renames the adapter, the key falls
     * through stock instead of clicking a wrong row. Safe-by-default.
     *
     * Mission keycode note: N is Android KEYCODE_N (42). The mission lists
     * "P=25", but 25 is KEYCODE_VOLUME_DOWN (Android's KEYCODE_P is 44);
     * both 44 and 25 route to prev-chat, so the mission's literal keycode
     * and the letter it names both work. VOLUME_UP stays stock.
     */
    static boolean switchChat(Activity activity, boolean next) {
        RecyclerView list = KeyRouter.findPrimaryList(activity);
        syncBinding(activity, list);
        if (list == null) {
            DriftReport.event("chat: no list on "
                    + activity.getClass().getSimpleName());
            return false;
        }
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        String adapterName = adapter != null
                ? adapter.getClass().getSimpleName() : "none";
        if (!adapterName.toLowerCase(Locale.US).contains("dialogsadapter")) {
            DriftReport.event("chat: primary list is not the dialogs list ("
                    + adapterName + ") — inert");
            return false;
        }
        int count = adapter.getItemCount();
        if (count <= 0) {
            DriftReport.event("chat: dialogs list empty — inert");
            return true;
        }
        int newPos = selectedPos < 0 ? 0 : selectedPos + (next ? 1 : -1);
        if (newPos < 0 || newPos >= count) {
            DriftReport.event("chat: hold pos=" + selectedPos + " (edge)");
            return true;
        }
        selectedPos = newPos;
        DriftReport.chatSwitch(next, newPos);
        if (!ensureVisible(list, newPos, next)) {
            updateRing(true);
        }
        if (list instanceof RecyclerListView
                && list.findViewHolderForAdapterPosition(newPos) != null) {
            activateHolder(list, newPos);
        } else {
            final RecyclerView rl = list;
            rl.post(() -> activateHolder(rl, newPos));
        }
        return true;
    }

    /**
     * Fire the REAL item-click path: RecyclerListView.clickItem invokes the
     * exact onItemClickListener a touch tap fires (screens close, countries
     * get selected, chats open — no synthetic tap coordinates involved).
     */
    private static void activateHolder(RecyclerView list, int pos) {
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(pos);
        if (holder == null) {
            DriftReport.event("activate: pos=" + pos + " holder vanished");
            return;
        }
        if (list instanceof RecyclerListView) {
            ((RecyclerListView) list).clickItem(holder.itemView, pos);
            DriftReport.activated(list, pos);
        } else {
            DriftReport.event("activate: pos=" + pos
                    + " skipped (not a RecyclerListView)");
        }
    }

    /**
     * Scroll the target row fully into view when a move leaves the fully
     * visible range. INSTANT only (no smooth scroll — e-ink).
     *
     * @return true if a scroll was requested (ring update is deferred to the
     *         layout pass via post()).
     */
    private static boolean ensureVisible(RecyclerView list, int pos, boolean movingDown) {
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) {
            if (list.findViewHolderForAdapterPosition(pos) == null) {
                list.scrollToPosition(pos);
                list.post(() -> updateRing(true));
                return true;
            }
            return false;
        }
        LinearLayoutManager llm = (LinearLayoutManager) lm;
        int first = llm.findFirstCompletelyVisibleItemPosition();
        int last = llm.findLastCompletelyVisibleItemPosition();
        if (pos >= first && pos <= last) {
            return false; // already fully visible — no scroll
        }
        if (movingDown) {
            // Target belongs at the bottom edge: offset from layout start so
            // the row's bottom lands on the list's bottom padding edge.
            int offset = Math.max(list.getPaddingTop(),
                    list.getHeight() - list.getPaddingBottom()
                            - estimateChildHeight(list));
            llm.scrollToPositionWithOffset(pos, offset);
        } else {
            // Target belongs at the top edge.
            llm.scrollToPositionWithOffset(pos, list.getPaddingTop());
        }
        list.post(() -> updateRing(true));
        return true;
    }

    private static int estimateChildHeight(RecyclerView list) {
        int n = list.getChildCount();
        if (n == 0) {
            return Math.max(1, list.getHeight() / 6);
        }
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += list.getChildAt(i).getHeight();
        }
        return Math.max(1, total / n);
    }

    /**
     * Position/hide the ring over the selected child. Called after selection
     * moves (receipt=true — emits a "ring:" coordinate line) and from the
     * scroll listener (receipt=false — silent, keeps the ring glued).
     */
    private static void updateRing(boolean receipt) {
        if (ring == null || selectedPos < 0 || boundList == null) {
            if (ring != null) {
                ring.hide();
            }
            return;
        }
        RecyclerView.ViewHolder holder =
                boundList.findViewHolderForAdapterPosition(selectedPos);
        if (holder == null || !holder.itemView.isShown()) {
            ring.hide(); // row recycled/offscreen: selection persists, ring waits
            return;
        }
        View item = holder.itemView;
        int[] itemLoc = new int[2];
        int[] ringLoc = new int[2];
        item.getLocationInWindow(itemLoc);
        ring.getLocationInWindow(ringLoc);
        int left = itemLoc[0] - ringLoc[0];
        int top = itemLoc[1] - ringLoc[1];
        boolean moved = ring.positionAt(left, top,
                left + item.getWidth(), top + item.getHeight());
        if (receipt && moved) {
            DriftReport.ringAt(left, top, left + item.getWidth(), top + item.getHeight());
        }
    }

    /**
     * Bind to (activity, primary list), clearing selection on any screen
     * change: new activity, or new primary-list identity (fragment
     * navigation). Also re-parents the ring overlay to the active decor.
     */
    private static void syncBinding(Activity activity, RecyclerView list) {
        if (activity != boundActivity) {
            detachRing();
            boundActivity = activity;
            boundList = null;
            selectedPos = -1;
            attachRing(activity);
        }
        if (list != boundList) {
            if (boundList != null) {
                boundList.removeOnScrollListener(RING_GLUE);
            }
            boundList = list;
            selectedPos = -1; // screen change ⇒ selection cleared (D3 rule)
            if (list != null) {
                list.addOnScrollListener(RING_GLUE);
            }
        }
    }

    private static void attachRing(Activity activity) {
        try {
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (decor instanceof ViewGroup) {
                ring = new FocusRingView(activity);
                ((ViewGroup) decor).addView(ring, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Throwable t) {
            ring = null; // decor not a ViewGroup / not attached — ring-less mode
        }
    }

    private static void detachRing() {
        if (ring != null) {
            try {
                if (ring.getParent() instanceof ViewGroup) {
                    ((ViewGroup) ring.getParent()).removeView(ring);
                }
            } catch (Throwable ignored) {
                // Window already torn down — nothing to remove.
            }
            ring = null;
        }
    }
}
