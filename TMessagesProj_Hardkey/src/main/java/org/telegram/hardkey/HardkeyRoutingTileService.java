package org.telegram.hardkey;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * D5 Quick Settings tile for the routing kill-switch — lets a phone user
 * flip the hardkey module without the CTRL+SHIFT+H keyboard combo.
 *
 * Contract (D5):
 *  - onClick routes through the ONE existing toggle path, Hardkey.toggle()
 *    (same call the combo makes in HardkeyWindowCallback) — no duplicated
 *    state logic here. TileService callbacks run on the main thread, which
 *    is exactly the thread contract of that path (the prefs writer it
 *    spawns is the module's own daemon thread).
 *  - Tile state/label always reflect the ACTUAL in-memory state
 *    (Hardkey.isEnabled(), the same volatile the combo path reads); the
 *    persisted override converges to it asynchronously per D2.
 *  - Receipts ride DriftReport.eventAlways — same class as the kill-switch
 *    toggle lines: rare, user-initiated single lines that must be visible
 *    regardless of the routing gate (a tile must be operable while routing
 *    is OFF — it is one of the two ways back on). No keycodes, no user
 *    content (D2 privacy rules; combo names in lines follow the
 *    "CTRL+SHIFT+H" precedent).
 *
 * QS tiles exist from API 24; the app's minSdk is 24 (upstream bump), and
 * on older devices the system never binds this service (it only appears in
 * the tile picker), so no runtime guard is needed.
 */
public class HardkeyRoutingTileService extends TileService {

    /** Must match android:label in this module's manifest entry. */
    private static final String BASE_LABEL = "Hardkey keys";

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        DriftReport.eventAlways("tile: routing added");
        updateTile();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        // Bind receipt: states the ACTUAL state the tile is about to show —
        // this is also the combo->tile sync receipt (shade reopens fire
        // onStartListening, so a combo toggle is reflected on next open).
        DriftReport.eventAlways("tile: bind routing state="
                + (Hardkey.isEnabled() ? "on" : "off"));
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        // The existing kill-switch path — flips the volatile, drops the D3
        // selection when turning off, persists the override, emits the
        // "kill-switch: routing ..." combo receipt.
        Hardkey.toggle();
        boolean on = Hardkey.isEnabled();
        DriftReport.eventAlways("tile: routing clicked -> " + (on ? "on" : "off"));
        updateTile();
    }

    @Override
    public void onStopListening() {
        // Nothing to release: no listeners, no resources held. The next
        // onStartListening re-syncs state from ground truth.
        super.onStopListening();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return; // not listening (e.g. onTileAdded before first bind)
        }
        boolean on = Hardkey.isEnabled();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(BASE_LABEL + (on ? " · on" : " · off"));
        tile.setContentDescription(BASE_LABEL + (on ? " on" : " off"));
        tile.updateTile();
    }
}
