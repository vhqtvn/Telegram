package org.telegram.hardkey;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import java.util.Locale;

/**
 * D6 auto-detect policy engine — runs ONCE per install on the FIRST
 * activity resume (the HardkeyInstaller hook), before any account is
 * required (onboarding screens are plain activities; nothing here touches
 * account state).
 *
 * Structure (mission requirement): detection is expressed as PURE STATIC
 * methods taking (manufacturer, brand, model, keyboard, hardKeyboardHidden,
 * refreshRate) so every branch is unit-inspectable without a device; the
 * live run gathers the real values, applies policy through the EXISTING
 * toggle paths only (Hardkey.toggle() / EinkMode.toggle() — no duplicated
 * state logic anywhere), and receipts both the raw environment and the
 * policy outcome through DriftReport.eventAlways (single init-class lines:
 * visible regardless of the routing gate, no keycodes/user content — D2
 * privacy rules).
 *
 * ONCE-PER-INSTALL ordering: the applied flag is persisted DURABLY BEFORE
 * any state change (synchronous commit() with the return value verified —
 * the D4 verified-commit pattern). If the flag commit fails, policy is
 * deferred to the next launch (nothing applied, flag not set). A failure
 * AFTER the flag (e.g. the e-ink toggle being ignored while the persisted
 * state is still loading) is receipted honestly and not retried — the
 * policy fired once; the user can still toggle from the settings screen.
 * pm clear wipes hardkey.pref and re-arms the policy (fresh install
 * semantics). An UPGRADE install over an old install keeps prefs: if the
 * user already left an explicit routing override or an e-ink state, that
 * channel is SKIPPED (receipt states the reason) — the policy never
 * overrides a deliberate user choice.
 *
 * Crash-safety: the whole run is wrapped in try/catch(Throwable) with a
 * receipt; a policy failure can never crash the app at resume.
 *
 * Threading: runs on the main thread (first activity resume) — the same
 * thread contract as the combo paths in HardkeyWindowCallback, which the
 * EinkMode state machine requires (its sweep touches views).
 */
final class Autodetect {

    /**
     * E-ink vendor substrings — ONE constant, lowercase substring match
     * against Build.MANUFACTURER / BRAND / MODEL. "palm" from the mission
     * sketch was deliberately NOT included: the 2018 Palm Phone (TCL) is
     * an LCD device, so the substring would false-positive; the policy
     * stays conservative (known e-reader vendors only).
     */
    static final String[] EINK_VENDORS = {
            "onyx", "boox", "hisense", "bigme", "ireader", "hanvon", "meebook",
    };

    /**
     * Display refresh rate at/below which the panel is plausibly e-ink.
     * Typical LCD/OLED reports 60+; this only CORROBORATES (vendor match
     * alone already fires), and a refresh-only hit additionally requires
     * a hardware keyboard, so the false-positive surface is small.
     */
    static final float LOW_REFRESH_HZ = 30.0f;

    /**
     * Applied flag in Hardkey.PREFS_NAME ("hardkey.pref"). Key of this
     * module's own prefs file — not a duplicate of any state key.
     */
    private static final String PREF_AUTODETECT_APPLIED = "autodetect_applied";

    /**
     * Read-only mirrors of the PRIVATE key constants in Hardkey
     * (enabled_override_present) and EinkMode (eink_enabled). Both files
     * are frozen by the D6 invariants (EinkMode byte-identical, Hardkey
     * additive-only-if-touched — zero-touch chosen), so the keys cannot
     * be referenced directly. Used ONLY for read-only existence checks
     * ("did the user already leave an explicit state?"); every state
     * CHANGE goes through the existing toggle paths.
     */
    private static final String KEY_ROUTING_OVERRIDE_PRESENT = "enabled_override_present";
    private static final String KEY_EINK_ENABLED = "eink_enabled";

    private static final String CHANNEL_ID = "hardkey";
    private static final int NOTIFICATION_ID = 0x686B; // "hk"

    /** In-process once-guard: the env receipt is emitted once per process. */
    private static volatile boolean attempted;

    private Autodetect() {
    }

    // ── pure static detection (unit-inspectable branches) ─────────────────

    /**
     * Hardware-keyboard detection. Two independent signals, OR per the
     * mission ("keyboard != NOKEYS and/or hardKeyboardHidden == NO"):
     * the device HAS a keyboard at all (keyboard != NOKEYS), or a
     * keyboard is currently exposed (hardKeyboardHidden == NO).
     */
    static boolean hasHardwareKeyboard(int keyboard, int hardKeyboardHidden) {
        return keyboard != Configuration.KEYBOARD_NOKEYS
                || hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    /** First e-ink vendor substring matched in the given identity strings. */
    static String matchEinkVendor(String manufacturer, String brand, String model) {
        String[] haystacks = {manufacturer, brand, model};
        for (String hay : haystacks) {
            if (hay == null || hay.isEmpty()) {
                continue;
            }
            String lower = hay.toLowerCase(Locale.US);
            for (String vendor : EINK_VENDORS) {
                if (lower.contains(vendor)) {
                    return vendor;
                }
            }
        }
        return null;
    }

    /**
     * E-ink detection, conservative: a known e-reader vendor substring
     * ANYWHERE in manufacturer/brand/model, OR (unusually low refresh
     * rate AND a hardware keyboard present). refreshRateHz <= 0 (unknown)
     * never corroborates.
     */
    static boolean detectEink(String manufacturer, String brand, String model,
            float refreshRateHz, boolean hardwareKeyboard) {
        if (matchEinkVendor(manufacturer, brand, model) != null) {
            return true;
        }
        return refreshRateHz > 0f && refreshRateHz <= LOW_REFRESH_HZ && hardwareKeyboard;
    }

    /** Human-readable names for the Configuration constants (receipts). */
    private static String keyboardName(int keyboard) {
        switch (keyboard) {
            case Configuration.KEYBOARD_NOKEYS: return "NOKEYS";
            case Configuration.KEYBOARD_QWERTY: return "QWERTY";
            case Configuration.KEYBOARD_12KEY: return "12KEY";
            default: return "UNDEFINED(" + keyboard + ")";
        }
    }

    private static String hiddenName(int hardKeyboardHidden) {
        switch (hardKeyboardHidden) {
            case Configuration.HARDKEYBOARDHIDDEN_NO: return "NO";
            case Configuration.HARDKEYBOARDHIDDEN_YES: return "YES";
            default: return "UNDEFINED(" + hardKeyboardHidden + ")";
        }
    }

    /**
     * The detected-environment line — the live-values probe shown both in
     * DriftReport and on the settings screen's diagnostics view. Pure:
     * all inputs are parameters.
     */
    static String describe(String manufacturer, String brand, String model,
            int keyboard, int hardKeyboardHidden, float refreshRateHz) {
        boolean kb = hasHardwareKeyboard(keyboard, hardKeyboardHidden);
        String vendor = matchEinkVendor(manufacturer, brand, model);
        boolean eink = detectEink(manufacturer, brand, model, refreshRateHz, kb);
        return "autodetect env: manufacturer=" + nullSafe(manufacturer)
                + " brand=" + nullSafe(brand)
                + " model=" + nullSafe(model)
                + " keyboard=" + keyboardName(keyboard)
                + " hardKeyboardHidden=" + hiddenName(hardKeyboardHidden)
                + " refresh=" + String.format(Locale.US, "%.1f", refreshRateHz) + "Hz"
                + " -> keyboard=" + (kb ? "yes" : "no")
                + " eink=" + (eink ? "yes" : "no")
                + " (vendor=" + (vendor != null ? vendor : "none") + ")";
    }

    /**
     * Live display refresh rate in Hz, 0f when unavailable. NOTE:
     * DisplayMetrics.refreshRate is gone from the android-36 SDK stubs —
     * Display.getRefreshRate() (public since API 1) is the durable source;
     * getDefaultDisplay() is deprecated-but-functional on 30+ and the only
     * app-context route below 30.
     */
    static float liveRefreshRate(Context context) {
        try {
            WindowManager wm =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return 0f;
            }
            Display display = wm.getDefaultDisplay();
            return display != null ? display.getRefreshRate() : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** Live environment snapshot → describe(...) for the diagnostics view. */
    static String environmentDescription(Context context) {
        Configuration cfg = context.getResources().getConfiguration();
        return describe(Build.MANUFACTURER, Build.BRAND, Build.MODEL,
                cfg.keyboard, cfg.hardKeyboardHidden, liveRefreshRate(context));
    }

    private static String nullSafe(String s) {
        return s != null ? s : "?";
    }

    // ── the run (first activity resume, main thread) ──────────────────────

    /**
     * HardkeyInstaller hook. Once per process in memory; the durable
     * once-per-install gate is the applied flag below. Whole run is
     * crash-safe.
     */
    static void onFirstResume(Activity activity) {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            run(activity);
        } catch (Throwable t) {
            DriftReport.eventAlways("autodetect: run failed (policy inert): " + t);
        }
    }

    private static void run(Activity activity) {
        Context app = activity.getApplicationContext();
        if (app == null) {
            DriftReport.eventAlways("autodetect: no app context — policy skipped");
            return;
        }
        // Live values + the env receipt (also the emulator-visible probe).
        Configuration cfg = app.getResources().getConfiguration();
        float refreshRate = liveRefreshRate(app);
        boolean keyboard = hasHardwareKeyboard(cfg.keyboard, cfg.hardKeyboardHidden);
        String vendor = matchEinkVendor(Build.MANUFACTURER, Build.BRAND, Build.MODEL);
        boolean eink = detectEink(Build.MANUFACTURER, Build.BRAND, Build.MODEL,
                refreshRate, keyboard);
        DriftReport.eventAlways(describe(Build.MANUFACTURER, Build.BRAND, Build.MODEL,
                cfg.keyboard, cfg.hardKeyboardHidden, refreshRate));

        final SharedPreferences prefs =
                app.getSharedPreferences(Hardkey.PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_AUTODETECT_APPLIED, false)) {
            return; // policy already fired in an earlier launch of this install
        }
        // ONCE-PER-INSTALL gate, durable BEFORE any state change (D4
        // verified-commit pattern). A failed commit defers the whole
        // policy to the next launch — nothing applied.
        boolean flagCommitted;
        try {
            flagCommitted = prefs.edit().putBoolean(PREF_AUTODETECT_APPLIED, true).commit();
        } catch (Throwable t) {
            flagCommitted = false;
        }
        if (!flagCommitted) {
            DriftReport.eventAlways("autodetect: applied-flag commit failed — "
                    + "policy deferred to next launch");
            return;
        }

        // Routing channel: enable through the EXISTING toggle path only.
        String routingResult;
        boolean routingApplied = false;
        if (!keyboard) {
            routingResult = "skipped(no keyboard)";
        } else if (prefs.getBoolean(KEY_ROUTING_OVERRIDE_PRESENT, false)) {
            // Upgrade install with a pre-existing explicit user choice.
            routingResult = "skipped(user override present)";
        } else if (Hardkey.isEnabled()) {
            // Already on (e.g. debug default) — desired state, nothing to flip.
            routingResult = "on (already default)";
        } else {
            Hardkey.toggle(); // the one existing toggle/persist path
            routingApplied = Hardkey.isEnabled();
            routingResult = routingApplied ? "on" : "failed";
        }

        // E-ink channel: same contract via EinkMode.toggle (its snapshot
        // machinery — verified durable commit before LiteMode is touched —
        // is exactly what makes auto-enable safe). Applied only when the
        // user has no pre-existing eink state (upgrade installs).
        String einkResult;
        boolean einkApplied = false;
        if (!eink) {
            einkResult = "skipped(not detected)";
        } else if (prefs.contains(KEY_EINK_ENABLED)) {
            einkResult = EinkMode.isOn()
                    ? "on (already)" : "skipped(user state present)";
        } else {
            EinkMode.toggle(activity); // existing enable path (off -> on here)
            einkApplied = EinkMode.isOn();
            einkResult = einkApplied ? "on" : "skipped(toggle ignored — state loading)";
        }

        DriftReport.eventAlways("autodetect: keyboard=" + (keyboard ? "yes" : "no")
                + " eink=" + (eink ? "yes" : "no")
                + " (vendor=" + (vendor != null ? vendor : "none") + ")"
                + " applied routing=" + routingResult
                + " eink=" + einkResult);

        if (routingApplied || einkApplied) {
            notifyHeadsUp(app, routingApplied, einkApplied);
        }
    }

    // ── best-effort heads-up notification ────────────────────────────────

    /**
     * Module channel, best-effort: silently no-ops without notification
     * permission (on 33+ areNotificationsEnabled() is false until the
     * user grants POST_NOTIFICATIONS — exactly the documented no-op), on
     * any manager failure, and below API 24. contentIntent opens the
     * settings screen (explicit PendingIntent — same app, no export
     * implications).
     */
    private static void notifyHeadsUp(Context app, boolean routing, boolean eink) {
        try {
            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) {
                return; // silent no-op by design
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        app.getString(R.string.hardkey_notify_channel),
                        NotificationManager.IMPORTANCE_HIGH); // heads-up class
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            Intent open = new Intent(app, HardkeySettingsActivity.class);
            open.setPackage(app.getPackageName());
            PendingIntent pi = PendingIntent.getActivity(app, 0, open,
                    PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_ID)
                    : new Notification.Builder(app);
            builder.setSmallIcon(R.drawable.ic_hardkey_settings)
                    .setContentTitle(app.getString(R.string.hardkey_notify_title))
                    .setContentText(app.getString(R.string.hardkey_notify_text)
                            + " (" + (routing ? "routing " : "") + (eink ? "eink" : "") + ")")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTIFICATION_ID, builder.build());
            DriftReport.eventAlways("autodetect: heads-up notification posted");
        } catch (Throwable t) {
            DriftReport.eventAlways("autodetect: notification skipped: " + t);
        }
    }
}
