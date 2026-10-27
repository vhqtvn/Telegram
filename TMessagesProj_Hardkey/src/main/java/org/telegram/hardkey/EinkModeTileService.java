package org.telegram.hardkey;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * D5 Quick Settings tile for e-ink mode — lets a phone user flip it
 * without the CTRL+SHIFT+E keyboard combo.
 *
 * Contract (D5):
 *  - onClick routes through the ONE existing toggle path, EinkMode.toggle
 *    (same entry the combo uses). The live screen, when one is resumed, is
 *    handed over via HardkeyInstaller.currentActivity() so the enable-time
 *    animator sweep covers the visible screen exactly like the combo path
 *    (which gets the activity from dispatchKeyEvent); with nothing
 *    resumed the null falls through to clearAnimators' documented no-op,
 *    and the installer's create/resume sweeps cover the next screen. No
 *    additive helper in EinkMode was needed.
 *  - EinkMode's synchronous verified-commit state machine runs unchanged
 *    on the TileService main thread — the same thread contract as the
 *    combo path (dispatchKeyEvent).
 *  - If the toggle is ignored (persisted state still loading) or locked
 *    (unhealed corrupt state, B-F2(b)), the post-call state read below
 *    stays unchanged and the receipt says so; EinkMode's own eventAlways
 *    lines ("toggle ignored"/"toggle locked") disambiguate in logcat.
 *  - Tile state/label always reflect the ACTUAL in-memory state
 *    (EinkMode.isOn()); receipts ride eventAlways (eink is independent of
 *    the routing gate by D4 decision) with no keycodes/user content.
 */
public class EinkModeTileService extends TileService {

    /** Must match android:label in this module's manifest entry. */
    private static final String BASE_LABEL = "Hardkey e-ink";

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        DriftReport.eventAlways("tile: eink added");
        updateTile();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        // Bind receipt: ACTUAL state the tile is about to show; also the
        // combo->tile sync receipt for the eink half of the pair.
        DriftReport.eventAlways("tile: bind eink state="
                + (EinkMode.isOn() ? "on" : "off"));
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        // The existing toggle path (snapshot/restore/self-heal state
        // machine). The live screen (if any) is handed over for the
        // enable-time animator sweep via HardkeyInstaller's weak
        // last-resumed reference — the same sweep the combo path triggers
        // from dispatchKeyEvent's activity. Null when nothing is resumed;
        // clearAnimators(null) is the documented no-op.
        EinkMode.toggle(HardkeyInstaller.currentActivity());
        boolean on = EinkMode.isOn();
        DriftReport.eventAlways("tile: eink clicked -> " + (on ? "on" : "off"));
        updateTile();
    }

    @Override
    public void onStopListening() {
        // Nothing to release; next onStartListening re-syncs from ground
        // truth.
        super.onStopListening();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return; // not listening
        }
        boolean on = EinkMode.isOn();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(BASE_LABEL + (on ? " · on" : " · off"));
        tile.setContentDescription(BASE_LABEL + (on ? " on" : " off"));
        tile.updateTile();
    }
}
