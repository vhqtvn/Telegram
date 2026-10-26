package org.telegram.hardkey;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.view.KeyEvent;

/**
 * Hardkey D0/D1/D2 bootstrap — hardware-keyboard navigation for e-ink phones.
 *
 * D2 turns the master switch into runtime state:
 *  - default ON in debug builds, OFF in release builds (BuildConfig.DEBUG);
 *  - a persisted override lives in SharedPreferences, loaded and stored on a
 *    private daemon thread so the main thread never blocks on disk;
 *  - runtime toggle: CTRL+SHIFT+H (exactly those modifiers), documented here.
 *
 * While disabled the module is inert: the window callback stays installed
 * (it is the only path through which the toggle combo can arrive) but does
 * pure delegation — no routing, no key observation, no logging beyond the
 * init/toggle lines emitted through DriftReport.eventAlways().
 */
public final class Hardkey {

    public static final String LOG_TAG = "HardkeyDrift";

    /** Kill-switch state. Default: on in debug builds, off in release builds. */
    private static volatile boolean enabled = BuildConfig.DEBUG;

    /** Module prefs file. Package-private: EinkMode (D4) stores its eink_* keys here too. */
    static final String PREFS_NAME = "hardkey.pref";
    private static final String PREF_HAS_OVERRIDE = "enabled_override_present";
    private static final String PREF_ENABLED = "enabled";

    private static volatile Context appContext;

    private Hardkey() {
    }

    /**
     * Wiring from HardkeyInit: caches the application context, emits the one
     * init line stating the routing state, and kicks off the asynchronous
     * override load (background thread, never main-thread disk I/O).
     *
     * Note the small inherent race: until the loader thread finishes, the
     * state is the build default rather than the persisted override. For a
     * hardware-navigation switch this is acceptable (worst case: one extra
     * or missing routed key in the first milliseconds of process life).
     */
    static void init(Context context, boolean installerRegistered) {
        appContext = context != null ? context.getApplicationContext() : null;
        DriftReport.eventAlways("init: lifecycle installer "
                + (installerRegistered ? "registered" : "NOT registered")
                + " (pid " + Process.myPid() + "), routing state="
                + (enabled ? "enabled" : "disabled")
                + (BuildConfig.DEBUG ? " (debug default)" : " (release default)"));
        // D4: eink mode loads its own persisted state behind the same
        // init seam (own loader thread, own init line, never touches
        // LiteMode at init time EXCEPT the corrupt-state self-heal
        // restore — see EinkMode.init).
        EinkMode.init(context);
        final Context app = appContext;
        if (app != null) {
            Thread loader = new Thread("hardkey-prefs-load") {
                @Override
                public void run() {
                    try {
                        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        if (prefs.getBoolean(PREF_HAS_OVERRIDE, false)) {
                            enabled = prefs.getBoolean(PREF_ENABLED, BuildConfig.DEBUG);
                            DriftReport.eventAlways("kill-switch: persisted override loaded (state="
                                    + (enabled ? "enabled" : "disabled") + ")");
                        }
                    } catch (Throwable t) {
                        // Persistence is best-effort; keep the build default.
                    }
                }
            };
            loader.setDaemon(true);
            loader.start();
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    /**
     * Flips the kill-switch and persists the override asynchronously
     * (apply() on the loader-style daemon thread; main thread never waits).
     * Turning OFF also drops the D3 selection and hides the ring overlay
     * immediately (main-thread call from dispatchKeyEvent) so no hardkey
     * visual state survives the switch.
     */
    static void toggle() {
        enabled = !enabled;
        DriftReport.eventAlways("kill-switch: routing " + (enabled ? "enabled" : "disabled")
                + " (CTRL+SHIFT+H)");
        if (!enabled) {
            SelectionController.onRoutingDisabled();
        }
        final Context app = appContext;
        final boolean value = enabled;
        if (app != null) {
            Thread writer = new Thread("hardkey-prefs-write") {
                @Override
                public void run() {
                    try {
                        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(PREF_HAS_OVERRIDE, true)
                                .putBoolean(PREF_ENABLED, value)
                                .apply();
                    } catch (Throwable t) {
                        // Best-effort persistence; in-memory state already flipped.
                    }
                }
            };
            writer.setDaemon(true);
            writer.start();
        }
    }

    /**
     * The documented runtime toggle combo: H with CTRL and SHIFT held, no
     * other modifiers. Recognized even while the kill-switch is OFF — it is
     * the only way back on. Consumed by the wrapper, never forwarded.
     */
    static boolean isKillSwitchCombo(KeyEvent event) {
        return isCtrlShiftExact(event, KeyEvent.KEYCODE_H);
    }

    /**
     * D4 e-ink mode toggle combo: E with CTRL and SHIFT held (exact same
     * modifier contract as the kill-switch combo). Recognized in EVERY
     * state — eink mode is an independent quality toggle and stays
     * available with routing disabled (interplay decision, see EinkMode).
     */
    static boolean isEinkCombo(KeyEvent event) {
        return isCtrlShiftExact(event, KeyEvent.KEYCODE_E);
    }

    /**
     * D4 full-refresh nudge combo: R with CTRL and SHIFT held. Recognized
     * in every state; receipt-only effect (see EinkMode.nudge).
     */
    static boolean isRefreshCombo(KeyEvent event) {
        return isCtrlShiftExact(event, KeyEvent.KEYCODE_R);
    }

    /**
     * Exact CTRL+SHIFT modifier test shared by all module combos. The
     * lock-state mask caveat from the D2 review is a known dropped
     * advisory — deliberately NOT re-fixed here, so all three combos keep
     * byte-identical recognition semantics.
     */
    private static boolean isCtrlShiftExact(KeyEvent event, int keyCode) {
        if (event.getAction() != KeyEvent.ACTION_DOWN
                || event.getKeyCode() != keyCode) {
            return false;
        }
        int meta = event.getMetaState();
        boolean ctrl = (meta & (KeyEvent.META_CTRL_ON
                | KeyEvent.META_CTRL_LEFT_ON
                | KeyEvent.META_CTRL_RIGHT_ON)) != 0;
        boolean shift = (meta & (KeyEvent.META_SHIFT_ON
                | KeyEvent.META_SHIFT_LEFT_ON
                | KeyEvent.META_SHIFT_RIGHT_ON)) != 0;
        boolean other = (meta & ~(KeyEvent.META_CTRL_ON
                | KeyEvent.META_CTRL_LEFT_ON
                | KeyEvent.META_CTRL_RIGHT_ON
                | KeyEvent.META_SHIFT_ON
                | KeyEvent.META_SHIFT_LEFT_ON
                | KeyEvent.META_SHIFT_RIGHT_ON)) != 0;
        return ctrl && shift && !other;
    }
}
