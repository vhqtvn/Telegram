package org.telegram.hardkey;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * D6 module-owned settings screen — the app-mode control surface that
 * replaces the removed D5 Quick Settings tiles (operator decision: tiles
 * were bad UX for app-mode control).
 *
 * Plain and e-ink friendly BY DESIGN: the device-default DayNight theme
 * (see themes.xml / themes.xml in values-v29), no custom styling beyond
 * minimal spacing, default text sizes, no animations. All labels are
 * resources (D5 review nit).
 *
 * Behavior contract:
 *  - Switch rows call ONLY the existing toggle paths — Hardkey.toggle()
 *    and EinkMode.toggle(activity) — never duplicated state logic. After
 *    each tap the row re-syncs from the ACTUAL module state, so a toggle
 *    that was ignored (eink state still loading) or locked (unhealed
 *    corrupt state) is shown truthfully.
 *  - Rows reflect ACTUAL state on every onResume (combo-parity: flip via
 *    CTRL+SHIFT+H/E elsewhere, cycle this screen through home/recents,
 *    the rows must match reality).
 *  - The e-ink toggle hands over the best-available activity via
 *    HardkeyInstaller.currentActivity() (the D5 mechanism, re-purposed:
 *    while THIS screen is resumed that is this screen itself, so the
 *    enable-time animator sweep covers the live screen with combo
 *    parity — on this screen it honestly finds no RecyclerViews).
 *  - The nudge button calls EinkMode.nudge(this) — the same code path
 *    as the CTRL+SHIFT+R combo.
 *  - The diagnostics view is the promised UI home of the DriftReport
 *    ring buffer: read-only, scrollable, refreshed on resume, prefixed
 *    by the live detected-environment line (Autodetect probe).
 *
 * Exported posture: see the manifest — NOT exported, no intent-filters.
 * Launched only from inside this app: the "Hardkey" row this fork adds
 * to Telegram's own Settings screen (explicit setClassName intent) and
 * this module's own PendingIntents (autodetect notification). The D6
 * shortcut mechanism (MAIN/LAUNCHER + custom action + static shortcut)
 * was removed by operator decision (option B: one launcher icon).
 */
public class HardkeySettingsActivity extends Activity {

    private Switch routingSwitch;
    private Switch einkSwitch;
    private TextView envView;
    private TextView driftView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.hardkey_settings_title);

        // Small-screen reality (the hardkey AVD is 480x854): the static
        // rows + key map + diagnostics do NOT fit one fold. One outer
        // scroll makes everything reachable; the drift dump itself stays
        // an inner scrollable pane of its own (nested scroll is fine for
        // a diagnostics view).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // targetSdk 36 edge-to-edge: the window content spans under the
        // status bar; consume its inset as padding so row 0 is never
        // covered (the D6 empirical fix, see themes.xml).
        root.setFitsSystemWindows(true);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, 0);

        // In-content title (the theme carries no action bar by design).
        TextView title = new TextView(this);
        title.setText(R.string.hardkey_settings_title);
        title.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large);
        title.setPadding(0, 0, 0, pad);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        routingSwitch = new Switch(this);
        routingSwitch.setText(R.string.hardkey_settings_routing);
        routingSwitch.setPadding(0, pad, 0, pad);
        routingSwitch.setOnClickListener(v -> {
            // The existing kill-switch path (flip + persist + receipts).
            Hardkey.toggle();
            routingSwitch.setChecked(Hardkey.isEnabled());
        });
        root.addView(routingSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        einkSwitch = new Switch(this);
        einkSwitch.setText(R.string.hardkey_settings_eink);
        einkSwitch.setPadding(0, 0, 0, pad);
        einkSwitch.setOnClickListener(v -> {
            // The existing verified-commit state machine; best-available
            // activity for the enable-time animator sweep (this screen
            // while it is the resumed one).
            EinkMode.toggle(HardkeyInstaller.currentActivity());
            einkSwitch.setChecked(EinkMode.isOn());
        });
        root.addView(einkSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button nudge = new Button(this);
        nudge.setText(R.string.hardkey_settings_nudge);
        nudge.setOnClickListener(v -> EinkMode.nudge(this)); // same path as CTRL+SHIFT+R
        root.addView(nudge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView keymapTitle = new TextView(this);
        keymapTitle.setText(R.string.hardkey_settings_keymap_title);
        keymapTitle.setPadding(0, pad, 0, pad / 2);
        keymapTitle.setTextAppearance(android.R.style.TextAppearance_Small);
        root.addView(keymapTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView keymap = new TextView(this);
        keymap.setText(R.string.hardkey_settings_keymap);
        keymap.setTextAppearance(android.R.style.TextAppearance_Small);
        keymap.setPadding(0, 0, 0, pad);
        root.addView(keymap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView diagTitle = new TextView(this);
        diagTitle.setText(R.string.hardkey_settings_diagnostics);
        diagTitle.setPadding(0, 0, 0, pad / 2);
        root.addView(diagTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        envView = new TextView(this);
        envView.setTextAppearance(android.R.style.TextAppearance_Small);
        try {
            envView.setText(Autodetect.environmentDescription(this));
        } catch (Throwable t) {
            envView.setText("autodetect env: unavailable (" + t + ")");
        }
        root.addView(envView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        driftView = new TextView(this);
        driftView.setTextAppearance(android.R.style.TextAppearance_Small);
        driftView.setTextIsSelectable(true); // read-only: selectable, not editable
        ScrollView scroller = new ScrollView(this);
        scroller.setMinimumHeight(dp(220)); // generous pane, reachable via the outer scroll
        scroller.setFillViewport(true);
        scroller.addView(driftView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        outer.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(outer);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rows reflect ACTUAL state on resume (never a cached wish), and
        // the diagnostics view re-reads the ring buffer fresh.
        if (routingSwitch != null) {
            routingSwitch.setChecked(Hardkey.isEnabled());
        }
        if (einkSwitch != null) {
            einkSwitch.setChecked(EinkMode.isOn());
        }
        if (envView != null) {
            try {
                envView.setText(Autodetect.environmentDescription(this));
            } catch (Throwable t) {
                envView.setText("autodetect env: unavailable (" + t + ")");
            }
        }
        if (driftView != null) {
            driftView.setText(DriftReport.dump());
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
