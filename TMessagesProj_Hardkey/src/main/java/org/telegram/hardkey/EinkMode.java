package org.telegram.hardkey;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SvgHelper;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * D4 "e-ink mode" — an independent, persisted QUALITY toggle for e-ink
 * phones, separate from the routing kill-switch:
 *
 *  - own combo: CTRL+SHIFT+E (recognized in every state, exactly like the
 *    kill-switch combo — see Hardkey.isCtrlShiftExact);
 *  - own persisted state in this module's prefs ("hardkey.pref": eink_*),
 *    completely independent from the routing "enabled" key;
 *  - default OFF in BOTH debug and release (deliberately NOT copying the
 *    kill-switch's debug-default-ON: this toggle mutates the user's
 *    persisted LiteMode settings when it fires, so it must be opt-in even
 *    in debug builds; also, a debug-default-ON would force a LiteMode
 *    write at process start — exactly the pre-init window we refuse to
 *    touch. Release default-off per mission.)
 *
 * ON applies three effects, OFF reverts the first:
 *
 *  1. LiteMode flags: snapshot the user's current flags, then set ALL
 *     flags off (LiteMode.setAllFlags(0)) and fan out so already-drawn
 *     surfaces update. OFF restores the snapshot EXACTLY.
 *  2. Animator suppression: every screen bind / activity resume /
 *     routed-key discovery sweep calls setItemAnimator(null) on all
 *     discovered RecyclerViews (see clearAnimators). Snapshots of the
 *     replaced animators are restored on disable.
 *  3. Full-refresh nudge (CTRL+SHIFT+R): flashes a black overlay on the
 *     decor for ~2 vsyncs then removes it — the generic e-ink
 *     full-refresh trick, no vendor SDK. Independent of the toggle state.
 *
 * GUARANTEE (actual, as implemented — the A-F1/A-F2 + B-F1/B-F2/C-F1
 * review fixes, plus the round-3 a-F1/a-F2 heal unification): no state
 * transition of this class leaves the user's
 * LiteMode settings PERMANENTLY zeroed, and none silently disables
 * their LiteMode power saver. Mechanisms, each receipted:
 *
 *  - enable: the snapshot is written DURABLY (one atomic synchronous
 *    commit() of all three module keys on the user-initiated calling
 *    thread, return value verified true) BEFORE LiteMode is zeroed. If
 *    the commit fails, enable aborts with a receipt and LiteMode is not
 *    touched — the zeroed-with-no-snapshot window cannot exist.
 *    B-F2(a): if the value read for the snapshot is 0 — the signature
 *    of an unhealed corrupt state reaching us anyway (0 is
 *    indistinguishable from a genuine all-off setting, so we cannot
 *    tell them apart) — the performance-class preset default is
 *    persisted as the snapshot instead, with a substitution receipt.
 *  - disable / self-heal restore: the restored value is written durably
 *    (verified commit, see commitLiteModeDurable) into the LiteMode
 *    prefs file BEFORE the eink state is cleared, so no cross-file
 *    flush ordering can leave zeroed flags with eink recorded off.
 *    Since the round-3 a-F1/a-F2 unification this is LITERALLY true on
 *    every heal path — the former "flags-kept" branch cleared eink on
 *    the strength of a non-zero cached read with no LiteMode write at
 *    all (see init self-heal below).
 *    B-F1: the module-prefs tail of disable is the SAME synchronous
 *    verified commit (persistSync), not an async apply() writer — a
 *    daemon tail landing after a later enable's commit could overwrite
 *    it with the stale {off, 0, invalid} triple and reproduce
 *    zeroed-with-no-snapshot (the round-2 blocker). There are NO
 *    daemon writer threads for state in this class.
 *  - init self-heal: eink_enabled=true with a missing/invalid snapshot
 *    is the corrupted-state signature; EinkMode.init restores LiteMode
 *    durably and fails eink to disabled, with a self-heal receipt. ONE
 *    durable path (a-F1/a-F2 round-3 unification): the heal ALWAYS
 *    performs commitLiteModeDurable(selected) — selected = the
 *    lite_mode6 read if non-zero (the user's own flags when they are
 *    genuinely on disk), else the upstream default preset (same
 *    derivation LiteMode.loadPreference uses on first run —
 *    PRESET_LOW/MEDIUM/HIGH by device performance class) — and
 *    requires commit==true BEFORE clearing the eink signature. The read
 *    is CACHE-informed, not disk evidence: a failed
 *    commitLiteModeDurable leaves its value in the in-process prefs
 *    cache (the framework behavior documented on selfHeal), so on a
 *    heal RETRY a non-zero read may be pollution from our own earlier
 *    failed attempt while disk holds 0. The read may therefore SELECT
 *    what is preserved but can NEVER substitute for the durable write.
 *    The former "flags-kept" branch (clear eink on a non-zero cached
 *    read, no LiteMode write) could process-die into lite_mode6=0 on
 *    disk + eink off + no signature — the heal predicate gone, the
 *    user's LiteMode permanently lost (the round-3 blocker, observed on
 *    the emulator in the bf-heallock2-b receipt).
 *    B-F2(b) recovery lock: if a heal attempt fails (readiness timeout,
 *    prefs unavailable, failed durable commit) the corrupt signature
 *    stays on disk and healPending locks the TOGGLE: every combo press
 *    retries the heal (receipted) instead of enabling — an enable at
 *    that moment would snapshot the corrupt 0 as a VALID snapshot and
 *    later disable would restore 0 permanently. Only when the
 *    signature is cleared from disk does toggling resume.
 *    Residual: the durable restore writes the "lite_mode6" key directly
 *    (mirror of LiteMode.savePreference — upstream's own writer only
 *    apply()s, so commit-durability cannot come from upstream without
 *    touching files outside this module). A future upstream key bump
 *    renames the live key, but loadPreference's migration chain reads
 *    the old key, so a healed value still migrates; the D-battery
 *    persistence check (restore after process death) detects a
 *    breakage.
 *  - C-F1/C-F2 power-saver side channel: upstream
 *    LiteMode.setAllFlags -> savePreference persists BOTH lite_mode6
 *    AND lite_mode_battery_level, and at an unloaded seam
 *    powerSaverLevel is the field default 0 — a setAllFlags before the
 *    first loadPreference would silently write
 *    lite_mode_battery_level=0, permanently disabling the user's
 *    LiteMode power saver. Every setAllFlags call in this class
 *    (enable, disable, heal restore) therefore forces the persisted
 *    LiteMode state to load FIRST (ensureLiteModeLoaded — the public
 *    load-forcing idiom getPowerSaverLevel(), which calls the public
 *    loadPreference() when !loaded and reads BOTH keys), so
 *    savePreference persists the REAL powerSaverLevel. Each applyFlags
 *    transition receipts the preserved value.
 *
 * Kill-switch interplay (decision, documented per mission): routing OFF
 * does NOT disable this combo. Eink mode is a display-quality toggle, not
 * navigation; a user who wants a calm screen does not need key routing for
 * it. The eink receipts therefore ride eventAlways/eventEink gates, never
 * the routing gate (DriftReport). Conversely eink ON does not force
 * routing on.
 *
 * Fan-out path (verified choice): upstream LiteMode.setAllFlags does NOT
 * fan out (the onFlagsUpdate call inside it is commented out upstream —
 * "in settings it is already handled"), and the private onFlagsUpdate only
 * reacts to flags turning ON (~old & new), so the pref-rewrite +
 * loadPreference() recipe would fire nothing on X→0 anyway. All three
 * fan-out targets are PUBLIC STATICS that re-read current LiteMode state:
 * AnimatedEmojiDrawable.updateAll(), SvgHelper.SvgDrawable
 * .updateLiteValues(), Theme.reloadWallpaper(true). Calling them directly
 * is the cleanest verified path — no reflection, compiled references
 * survive R8 (same guarantee as D3's RecyclerListView.clickItem).
 *
 * Crash-safety ordering (enable): superseded by the GUARANTEE block
 * above — the snapshot commit is synchronous and verified, not
 * scheduler-best-effort (the async-apply version had exactly the
 * zeroed-with-no-snapshot window the reviewer blocked).
 *
 * Threading: toggle/nudge/clearAnimators run on the main thread
 * (dispatchKeyEvent / lifecycle callbacks); ALL module-pref state
 * writes — enable snapshot, disable tail, heal clears — are
 * synchronous verified commit()s on the calling thread (B-F1: no
 * async apply() writer remains; a user-initiated combo press costs a
 * few ms, the accepted A-F1 tradeoff). The only background thread is
 * the init loader, whose STATE LOADING is reads-only; the self-heal it
 * triggers writes only through the same synchronous verified commit()
 * path (no apply(), no spawned writers) and completes before
 * stateLoaded flips true, so no toggle can interleave with it.
 * SAVED_ANIMATORS is main-thread confined.
 */
final class EinkMode {

    private static final String PREF_EINK_ENABLED = "eink_enabled";
    private static final String PREF_EINK_SNAPSHOT = "eink_snapshot";
    private static final String PREF_EINK_SNAPSHOT_VALID = "eink_snapshot_valid";

    /**
     * Mirror of the key LiteMode.savePreference() persists to (the
     * MessagesController account-0 "mainconfig" prefs file). Written
     * directly — with commit(), verified — wherever a DURABLE,
     * ordered-before-eink-clear LiteMode write is required: upstream's
     * own writer only apply()es, which cannot give the ordering
     * guarantee (see the GUARANTEE block in the class comment for the
     * rename-coupling residual).
     */
    private static final String PREF_LITE_MODE = "lite_mode6";

    /**
     * Companion key LiteMode.savePreference() persists next to
     * lite_mode6 (the power-saver battery threshold). READ here for the
     * C-F1/C-F2 invariance receipts — never written directly: the fix
     * is to make LiteMode load first (ensureLiteModeLoaded) so ITS OWN
     * savePreference persists the user's real value. Same rename-
     * coupling caveat as PREF_LITE_MODE (reads only, so weaker: a
     * renamed key yields -1 "absent" in receipts, never a wrong write).
     */
    private static final String PREF_LITE_BATTERY = "lite_mode_battery_level";

    /** Init-time heal readiness budget: bounded retries on the loader thread. */
    private static final int HEAL_INIT_ATTEMPTS = 20;
    private static final long HEAL_INIT_DELAY_MS = 500L;

    /** Black overlay duration for the full-refresh nudge (~2 vsyncs). */
    private static final long NUDGE_MS = 32;

    /** Toggle state. Default off in every build (see class comment). */
    private static volatile boolean on = false;

    /** The user's LiteMode flags as they were before we zeroed them. */
    private static volatile int snapshot;
    private static volatile boolean snapshotValid;

    /**
     * True once the background loader has read the persisted state. A
     * combo arriving before that is ignored (receipt) rather than acting
     * on the build default — the tiny start-of-process race the kill
     * switch tolerates would here risk snapshotting the zeroed pref and
     * overwriting the real snapshot.
     */
    private static volatile boolean stateLoaded;

    /**
     * B-F2(b) recovery lock: a heal attempt FAILED to verifiably clear
     * the corrupt signature (eink_enabled=true, snapshot invalid) from
     * disk — a deferred attempt, or a clear whose commit() came back
     * false. While true, toggle() routes every combo press to a heal
     * RETRY — never to enable(), which would snapshot the corrupt 0 as
     * a VALID snapshot (later disable would restore 0 permanently).
     * Derived from the VERIFIED COMMIT RESULTS of the last heal attempt
     * (see selfHeal); only a clear with commit()==true unlocks toggling.
     * Backstop: even a wrongly-unlocked enable cannot persist 0 — the
     * B-F2(a) substitution guard in enable() covers it.
     */
    private static volatile boolean healPending;

    private static volatile Context appContext;

    /**
     * Animators replaced by clearAnimators, restored on disable.
     * WeakHashMap: a screen dying must not be pinned by our bookkeeping.
     * Main-thread confined (all sweep call sites are main thread).
     */
    private static final Map<RecyclerView, RecyclerView.ItemAnimator> SAVED_ANIMATORS =
            new WeakHashMap<>();

    private EinkMode() {
    }

    static boolean isOn() {
        return on;
    }

    /**
     * Wiring from Hardkey.init: caches the app context and loads the
     * persisted toggle state on a daemon thread (same pattern as the
     * kill-switch loader). Emits the one eink init line — it states the
     * persisted state so process-death persistence is verifiable from
     * logcat alone. Outside the heal path this NEVER touches LiteMode:
     * at init time the app may not be ready for MessagesController prefs
     * (the combo path is the only other place we mutate LiteMode, and it
     * is guarded).
     *
     * A-F2 self-heal: eink_enabled=true with a missing/invalid snapshot
     * is the corrupted-state signature (user's flags zeroed with nothing
     * restorable). The heal runs on this loader thread BEFORE
     * stateLoaded flips true, so a combo racing the heal is ignored
     * (receipted) rather than acting on half-healed state.
     */
    static void init(Context context) {
        appContext = context != null ? context.getApplicationContext() : null;
        final Context app = appContext;
        if (app == null) {
            stateLoaded = true; // nothing to load; defaults stand
            DriftReport.eventAlways("eink: init state=" + (on ? "enabled" : "disabled")
                    + " (no app context, defaults stand)");
            return;
        }
        Thread loader = new Thread("hardkey-eink-load") {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs =
                            app.getSharedPreferences(Hardkey.PREFS_NAME, Context.MODE_PRIVATE);
                    boolean persistedOn = prefs.getBoolean(PREF_EINK_ENABLED, false);
                    boolean persistedValid = prefs.getBoolean(PREF_EINK_SNAPSHOT_VALID, false);
                    int persistedSnap = prefs.getInt(PREF_EINK_SNAPSHOT, 0);
                    if (persistedOn && !persistedValid) {
                        // Corrupted-state signature: fail to disabled in
                        // memory immediately, then heal (restore preset /
                        // clear eink) before exposing toggleable state.
                        on = false;
                        snapshot = 0;
                        snapshotValid = false;
                        selfHeal(HEAL_INIT_ATTEMPTS, HEAL_INIT_DELAY_MS, "init");
                    } else {
                        on = persistedOn;
                        snapshotValid = persistedValid;
                        snapshot = persistedSnap;
                        if (on) {
                            DriftReport.eventAlways("eink: init state=enabled (persisted, snapshot 0x"
                                    + hex(snapshot) + (snapshotValid ? "" : " INVALID")
                                    + "; LiteMode pref is 0 until disable restores)");
                        } else {
                            // In-memory claim only: state of the loaded
                            // toggle, distinguished by whether a persisted
                            // key existed at all (C-F4 wording honesty).
                            DriftReport.eventAlways("eink: init state=disabled ("
                                    + (prefs.contains(PREF_EINK_ENABLED) ? "persisted" : "default") + ")");
                        }
                    }
                } catch (Throwable t) {
                    // Persistence is best-effort; keep the default (off).
                    DriftReport.eventAlways("eink: init load failed: " + t);
                } finally {
                    stateLoaded = true;
                }
            }
        };
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * A-F2/B-F2 self-heal entry. Wraps the attempt and re-derives the
     * B-F2 recovery lock from the VERIFIED COMMIT RESULTS of the attempt:
     * the lock releases ONLY when a module-prefs clear came back
     * commit()==true. Never from a prefs readback — the in-process
     * SharedPreferences cache keeps editor mutations even when the disk
     * write FAILS (observed on the emulator: a clear with commit=false
     * left the cache "cleared" while run-as showed the signature still
     * on disk), so a cache read cannot prove the signature gone. The
     * SAME cache behavior is why selfHealInner's lite_mode6 read may
     * only SELECT the preserved value — never substitute for the
     * durable write (a-F1/a-F2 round-3 unification).
     */
    private static void selfHeal(int attempts, long delayMs, String origin) {
        boolean clearedDurable = selfHealInner(attempts, delayMs, origin);
        healPending = !clearedDurable;
        if (healPending) {
            DriftReport.eventAlways("eink: " + origin + " — corrupt signature STILL on disk, "
                    + "toggle locked to heal retries (combo presses retry the heal, not enable)");
        }
    }

    /**
     * The heal attempt itself, loader thread (init) or combo thread
     * (B-F2 retry). Precondition: the corrupted-state signature was
     * detected (eink_enabled=true, snapshot invalid/missing) and
     * in-memory state is already failed to disabled. Returns true ONLY
     * when the eink state was verifiably cleared on disk (a
     * persistSync(false,...) whose commit() returned true). Steps, each
     * receipted:
     *  1. derive the preset default with bounded readiness retries (this
     *     runs at the ContentProvider seam, before Application.onCreate
     *     — SharedConfig/MessagesController cannot run yet);
     *  2. SELECT the value to preserve — the lite_mode6 read if non-zero,
     *     else the preset — then DURABLY write it (verified commit, see
     *     commitLiteModeDurable) BEFORE clearing eink_enabled, on EVERY
     *     path (a-F1/a-F2 round-3 unification: that read is cache-
     *     informed, NOT disk evidence — a failed commitLiteModeDurable
     *     leaves its value in the in-process prefs cache, so on a heal
     *     retry a non-zero read may be pollution from our own earlier
     *     failed attempt while disk holds 0 — so it may select what is
     *     preserved but can never substitute for the durable write; the
     *     write is idempotent when the flags really were non-zero on
     *     disk, no clobbering);
     *  3. sync in-memory LiteMode best-effort, FORCING the persisted
     *     LiteMode state to load FIRST (C-F1: setAllFlags ->
     *     savePreference persists lite_mode_battery_level too, which at
     *     an unloaded seam would write the field default 0 and silently
     *     disable the user's power saver);
     *  4. clear eink_enabled durably and fail to disabled.
     * Deferrals keep the on-disk signature so the next launch (or, with
     * B-F2, the next combo press) retries (the heal is idempotent —
     * preset restore converges).
     */
    private static boolean selfHealInner(int attempts, long delayMs, String origin) {
        DriftReport.eventAlways("eink: " + origin + " detected corrupt state "
                + "(enabled=true, snapshot invalid) — self-healing");
        Integer preset = awaitPresetDefault(attempts, delayMs);
        if (preset == null) {
            DriftReport.eventAlways("eink: self-heal DEFERRED (app not ready after "
                    + "bounded retries) — LiteMode untouched this launch, eink off in memory");
            return false;
        }
        final boolean haveKey;
        final int currentRead;
        final int batteryBefore;
        try {
            SharedPreferences lite = MessagesController.getGlobalMainSettings();
            haveKey = lite.contains(PREF_LITE_MODE);
            currentRead = lite.getInt(PREF_LITE_MODE, 0);
            batteryBefore = lite.getInt(PREF_LITE_BATTERY, -1); // -1 = key absent
        } catch (Throwable t) {
            DriftReport.eventAlways("eink: self-heal DEFERRED (LiteMode prefs "
                    + "unavailable: " + t + ") — signature kept for next launch");
            return false;
        }
        // a-F1/a-F2 (round 3) — ONE durable path, no flags-kept shortcut.
        // The read above is CACHE-informed, not disk evidence: a FAILED
        // commitLiteModeDurable still leaves its value in the in-process
        // SharedPreferences cache (the same framework behavior documented
        // on selfHeal), so on a heal RETRY a non-zero read may be pollution
        // from our own earlier failed attempt while disk holds 0. The read
        // may SELECT which value is preserved (the user's own flags when
        // they are genuinely non-zero) but can NEVER substitute for the
        // durable write: every heal performs commitLiteModeDurable(selected)
        // and requires commit==true before the eink signature is cleared.
        // (Idempotent when the flags really were non-zero on disk — the
        // verified rewrite lands the same value; no clobbering.)
        final boolean kept = haveKey && currentRead != 0;
        final int selected = kept ? currentRead : preset;
        if (!commitLiteModeDurable(selected)) {
            // Never clear eink_enabled on the strength of a restore that
            // is not durable — that ordering is exactly the A-F1-class
            // window. The signature stays; the next launch (or, with
            // B-F2, the next combo press) retries.
            DriftReport.eventAlways("eink: self-heal DEFERRED (durable lite_mode6 "
                    + "commit failed) — signature kept for next launch, eink off in memory");
            return false;
        }
        boolean memOk = false;
        try {
            // C-F1: load the persisted LiteMode state (BOTH keys) BEFORE
            // setAllFlags — otherwise savePreference inside it persists
            // powerSaverLevel=0 (the unloaded field default) and silently
            // disables the user's LiteMode power saver.
            ensureLiteModeLoaded();
            LiteMode.setAllFlags(selected);
            memOk = LiteMode.getValue(true) == selected;
            int batteryAfter = safeBatteryLevel(batteryBefore);
            DriftReport.eventAlways("eink: self-heal lite_mode_battery_level invariance "
                    + "pre=" + batteryBefore + " post=" + batteryAfter
                    + (batteryBefore < 0
                        ? " (key was absent; " + batteryAfter + " is loadPreference's default, not our write)"
                        : batteryAfter == batteryBefore ? " (preserved)" : " CHANGED"));
        } catch (Throwable t) {
            DriftReport.eventAlways("eink: self-heal in-memory sync failed "
                    + "(durable disk restore stands): " + t);
        }
        boolean cleared = persistSync(false, 0, false);
        DriftReport.eventAlways("eink: self-heal (corrupt state -> 0x" + hex(selected)
                + (kept ? " kept" : " preset") + ", eink off) [lite_mode6 commit=true"
                + ", in-memory ok=" + memOk + ", eink clear commit=" + cleared + "]");
        return cleared;
    }

    /** CTRL+SHIFT+E. Main thread (dispatchKeyEvent). */
    static void toggle(Activity activity) {
        if (!stateLoaded) {
            DriftReport.eventAlways("eink: toggle ignored (persisted state still loading)");
            return;
        }
        if (healPending) {
            // B-F2(b) recovery lock: an earlier heal failed and the
            // corrupt signature is still on disk. This press RETRIES THE
            // HEAL — it must not reach enable(), which would snapshot
            // the corrupt 0 as a VALID snapshot.
            DriftReport.eventAlways("eink: toggle locked (unhealed corrupt state) — "
                    + "combo consumed by heal retry");
            retryHeal();
            return;
        }
        if (on) {
            disable(activity);
        } else {
            enable(activity);
        }
    }

    /**
     * B-F2(b) heal retry on the combo thread. At combo time the app is
     * fully ready, so the readiness wait is a single immediate check
     * (attempts=1, no sleep); if the environment still refuses, the
     * heal defers again, the signature stays and the lock holds. All
     * writes inside are the same synchronous verified commit()s the
     * init-time heal uses. The retry press itself is CONSUMED — the
     * user presses again once toggling has resumed.
     */
    private static void retryHeal() {
        selfHeal(1, 0L, "retry-heal");
        DriftReport.eventAlways(healPending
                ? "eink: retry-heal FAILED — toggle stays locked, retry the combo"
                : "eink: retry-heal SUCCEEDED — signature cleared, toggling resumed");
    }

    private static void enable(Activity activity) {
        final int before;
        final int snapToPersist;
        final boolean checkBefore;
        try {
            // getValue(true): the user's STORED setting. getValue() can
            // return PRESET_POWER_SAVER (0) while battery is low —
            // snapshotting that would permanently zero their settings on
            // restore. getValue(true) skips the power-saver overlay.
            before = LiteMode.getValue(true);
            // B-F2(a): a 0 read here is the corrupt-state signature
            // reaching enable despite the heal (a failed heal, or a
            // genuine all-off setting — indistinguishable, so the safe
            // interpretation wins). Persisting 0 as a VALID snapshot
            // would make a later disable restore 0 permanently; the
            // performance-class preset default is substituted instead.
            snapToPersist = before != 0 ? before : presetDefault();
            checkBefore = LiteMode.isEnabled(LiteMode.FLAG_AUTOPLAY_GIFS);
        } catch (Throwable t) {
            // App/prefs not ready (account-not-ready, MessagesController
            // unavailable): abort cleanly, no state flip, no crash.
            DriftReport.eventAlways("eink: enable aborted (app not ready): " + t);
            return;
        }
        if (before == 0) {
            DriftReport.eventAlways("eink: snapshot substituted preset 0x"
                    + hex(snapToPersist) + " (read 0x0)");
        }
        // A-F1: DURABLE + ORDERED. All three module keys (enabled,
        // snapshot, valid) are committed SYNCHRONOUSLY on this calling
        // thread — a user-initiated combo press, a few ms — as ONE
        // atomic editor write, and only a verified commit=true unlocks
        // the LiteMode mutation below. A crash at ANY point leaves disk
        // state either fully pre-enable (eink off) or fully enabled WITH
        // the snapshot — the zeroed-with-no-snapshot window cannot exist.
        boolean committed = persistSync(true, snapToPersist, true);
        if (!committed) {
            DriftReport.eventAlways("eink: enable ABORTED (snapshot commit failed, "
                    + "sync) — LiteMode untouched, eink stays off");
            return;
        }
        snapshot = snapToPersist;
        snapshotValid = true;
        on = true;
        applyFlags(0, "enabled (snapshot commit=true sync 0x" + hex(snapToPersist)
                + ", flags 0x" + hex(before) + " -> 0)");
        // Outcome receipts: in-code before/after pair around the change.
        boolean checkAfter = safeIsEnabledGifs();
        int afterValue = safeGetValue();
        DriftReport.eventAlways("eink: verify isEnabled(FLAG_AUTOPLAY_GIFS) before="
                + checkBefore + " after=" + checkAfter
                + ", getValue(true) after=0x" + hex(afterValue));
        // Suppress animators on the screen we are standing on right now.
        clearAnimators(activity);
    }

    private static void disable(Activity activity) {
        final int target;
        final String source;
        if (snapshotValid) {
            target = snapshot;
            source = "snapshot";
        } else {
            // D-F3: no valid snapshot on record (corrupted prefs) —
            // restoring "current" would silently keep the zeroed flags.
            // Restore the upstream PRESET default instead; if even the
            // preset cannot be derived, abort (never write 0 silently).
            Integer preset = null;
            try {
                preset = presetDefault();
            } catch (Throwable t) {
                DriftReport.eventAlways("eink: preset derivation failed: " + t);
            }
            if (preset == null) {
                DriftReport.eventAlways("eink: disable ABORTED (no valid snapshot and "
                        + "no derivable preset) — eink stays on, retry the combo");
                return;
            }
            target = preset;
            source = "preset-default (invalid snapshot)";
        }
        // Same A-F1-class ordering in the restore direction: the restored
        // value is DURABLE in the LiteMode prefs file BEFORE the eink
        // state is cleared, so no cross-file flush ordering can leave
        // zeroed flags with eink recorded off.
        if (!commitLiteModeDurable(target)) {
            DriftReport.eventAlways("eink: disable ABORTED (durable lite_mode6 commit "
                    + "failed) — eink stays on, retry the combo");
            return;
        }
        boolean verified = applyFlags(target, "disabled (restoring 0x" + hex(target)
                + " from " + source + ", durable commit=true)");
        on = false;
        snapshotValid = false;
        snapshot = 0;
        // B-F1: the disable tail is a SYNCHRONOUS verified commit on
        // this calling thread — NOT an async apply() writer. A daemon
        // tail landing after a quick re-enable's commit could overwrite
        // it with the stale {off, 0, invalid} triple while LiteMode
        // stays zeroed on disk: zeroed-with-no-snapshot, and the init
        // heal predicate (persistedOn) never fires. Worst case here is
        // a failed tail commit, which leaves disk at eink=on with the
        // (still valid) snapshot -> next launch restores the same,
        // already-durable value again. No transition through this tail
        // can produce zeroed-without-snapshot.
        boolean tailCleared = persistSync(false, 0, false);
        restoreAnimators();
        DriftReport.eventAlways("eink: disabled (restored 0x" + hex(target)
                + " from " + source + ") verified=" + verified
                + ", tail commit=" + tailCleared + " (sync)");
    }

    /**
     * Set LiteMode flags + fan out. Receipt states the transition; the
     * returned boolean verifies the STORED value actually landed (outcome,
     * not mechanism).
     */
    private static boolean applyFlags(int flags, String transitionReceipt) {
        final int batteryBefore;
        try {
            // C-F1/C-F2: force the persisted LiteMode state to load
            // BEFORE setAllFlags. LiteMode.setAllFlags ->
            // savePreference persists lite_mode_battery_level alongside
            // lite_mode6; if LiteMode has not loaded yet (e.g. a disable
            // restoring a previous session's enable with nothing else
            // having touched LiteMode this process), powerSaverLevel is
            // the field default 0 and the user's power saver is silently
            // and permanently disabled. ensureLiteModeLoaded() calls the
            // public loadPreference() when !loaded, which reads BOTH
            // keys, so savePreference then persists the REAL value.
            batteryBefore = ensureLiteModeLoaded();
        } catch (Throwable t) {
            DriftReport.eventAlways("eink: " + transitionReceipt
                    + " — LiteMode pre-load FAILED: " + t);
            return false;
        }
        try {
            LiteMode.setAllFlags(flags);
        } catch (Throwable t) {
            DriftReport.eventAlways("eink: " + transitionReceipt
                    + " — setAllFlags FAILED: " + t);
            return false;
        }
        fanOut();
        int batteryAfter = safeBatteryLevel(batteryBefore);
        DriftReport.eventAlways("eink: " + transitionReceipt
                + " [lite_mode_battery_level=" + batteryAfter
                + (batteryAfter == batteryBefore ? " preserved" : " CHANGED from " + batteryBefore)
                + "]");
        return safeGetValue() == flags;
    }

    /**
     * The fan-out LiteMode.setAllFlags does not do (upstream comments the
     * call out). Each target is best-effort and independently guarded —
     * one failing surface must not block the others or the receipt.
     */
    private static void fanOut() {
        String failed = "";
        try {
            AnimatedEmojiDrawable.updateAll();
        } catch (Throwable t) {
            failed += " updateAll!";
        }
        try {
            SvgHelper.SvgDrawable.updateLiteValues();
        } catch (Throwable t) {
            failed += " updateLiteValues!";
        }
        try {
            Theme.reloadWallpaper(true);
        } catch (Throwable t) {
            failed += " reloadWallpaper!";
        }
        DriftReport.eventAlways("eink: fan-out via direct public statics"
                + " (AnimatedEmojiDrawable.updateAll, SvgHelper.SvgDrawable.updateLiteValues,"
                + " Theme.reloadWallpaper)" + (failed.isEmpty() ? "" : " — failed:" + failed));
    }

    /**
     * Animator suppression sweep (effect 2). Traverses the decor exactly
     * like KeyRouter's discovery (own traversal so the verified D1–D3
     * paths stay untouched), nulls the item animator of every
     * RecyclerView found, snapshots the replaced animator for restore.
     * No-op (one volatile read) while eink is off. One receipt per sweep.
     */
    static void clearAnimators(Activity activity) {
        if (!on || activity == null) {
            return;
        }
        View decor = activity.getWindow() != null
                ? activity.getWindow().getDecorView() : null;
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        int cleared = 0;
        Deque<View> stack = new ArrayDeque<>();
        stack.push(decor);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) v;
                if (!SAVED_ANIMATORS.containsKey(rv)) {
                    SAVED_ANIMATORS.put(rv, rv.getItemAnimator());
                }
                rv.setItemAnimator(null);
                cleared++;
            }
            if (v instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) v;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    stack.push(group.getChildAt(i));
                }
            }
        }
        DriftReport.animatorsCleared(cleared);
    }

    /** Give screens back the animators the sweeps replaced (disable). */
    private static void restoreAnimators() {
        for (Map.Entry<RecyclerView, RecyclerView.ItemAnimator> e : SAVED_ANIMATORS.entrySet()) {
            try {
                RecyclerView rv = e.getKey();
                if (rv.getParent() != null) { // still on screen
                    rv.setItemAnimator(e.getValue());
                }
            } catch (Throwable ignored) {
                // Screen died mid-restore — its animator died with it.
            }
        }
        SAVED_ANIMATORS.clear();
    }

    /**
     * Full-refresh nudge (CTRL+SHIFT+R): black fullscreen overlay on the
     * decor for ~2 vsyncs, then removed — the generic e-ink full-refresh
     * trick (a large pixel transition makes the panel run its clearing
     * waveform). No vendor SDK, no display mode change. Best-effort and
     * receipt-only by design: the visual effect is NOT verifiable on an
     * LCD emulator — attach/remove receipts are the honest evidence.
     * Works in every toggle/routing state (independent convenience combo).
     */
    static void nudge(Activity activity) {
        try {
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView() : null;
            if (!(decor instanceof ViewGroup)) {
                DriftReport.eventAlways("eink: nudge skipped (no decor view group on "
                        + activity.getClass().getSimpleName() + ")");
                return;
            }
            final ViewGroup parent = (ViewGroup) decor;
            final View overlay = new View(activity);
            overlay.setBackgroundColor(0xFF000000);
            parent.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            DriftReport.eventAlways("eink: nudge (black overlay attached on "
                    + activity.getClass().getSimpleName() + ")");
            parent.postDelayed(() -> {
                try {
                    parent.removeView(overlay);
                    DriftReport.eventAlways("eink: nudge (overlay removed)");
                } catch (Throwable t) {
                    DriftReport.eventAlways("eink: nudge remove failed: " + t);
                }
            }, NUDGE_MS);
        } catch (Throwable t) {
            DriftReport.eventAlways("eink: nudge failed: " + t);
        }
    }

    /**
     * The upstream first-run default derivation, mirrored from
     * LiteMode.loadPreference() (TMessagesProj
     * src/main/java/org/telegram/messenger/LiteMode.java, the
     * defaultValue block at the top of the method): PRESET_HIGH unless
     * the device performance class is LOW (-> PRESET_LOW) or AVERAGE
     * (-> PRESET_MEDIUM). Reads the same public statics loadPreference
     * reads, so server-updated presets (updatePresets) and a persisted
     * performance-class override are honored identically.
     */
    private static int presetDefault() {
        int preset = LiteMode.PRESET_HIGH;
        int perfClass = SharedConfig.getDevicePerformanceClass();
        if (perfClass == SharedConfig.PERFORMANCE_CLASS_LOW) {
            preset = LiteMode.PRESET_LOW;
        } else if (perfClass == SharedConfig.PERFORMANCE_CLASS_AVERAGE) {
            preset = LiteMode.PRESET_MEDIUM;
        }
        return preset;
    }

    /**
     * Bounded readiness wait for the preset derivation. The heal runs at
     * the init seam (ContentProvider slot, before Application.onCreate):
     * SharedConfig/MessagesController cannot run until the app context
     * exists. Retries on the loader thread; null = give up (caller
     * defers the heal, signature kept for the next launch).
     */
    private static Integer awaitPresetDefault(int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            try {
                if (ApplicationLoader.applicationContext != null) {
                    return presetDefault();
                }
            } catch (Throwable ignored) {
                // Not ready yet (class init / perf-class measurement) — retry.
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * DURABLE write of the LiteMode value straight into the prefs file
     * LiteMode itself persists to (MessagesController account-0
     * "mainconfig"; key mirror documented on PREF_LITE_MODE).
     * Synchronous commit() on the calling thread; true only when the
     * write is on disk. Callers treat false as "abort the transition"
     * — never mutate or clear anything on the strength of a write that
     * has not been verified durable (the A-F1 rule).
     */
    private static boolean commitLiteModeDurable(int flags) {
        try {
            return MessagesController.getGlobalMainSettings()
                    .edit().putInt(PREF_LITE_MODE, flags).commit();
        } catch (Throwable t) {
            DriftReport.eventAlways("eink: lite_mode6 durable write FAILED: " + t);
            return false;
        }
    }

    /**
     * Synchronous module-prefs writer: ONE atomic editor covering all
     * three eink keys, commit() on the calling thread, true only when
     * durable. Used for EVERY module-pref state write — enable
     * snapshot, disable tail, heal clear (B-F1: the async apply()
     * writer that could land after a later transition's commit is
     * GONE; no daemon writer threads for state remain in this class).
     * A few ms on a user-initiated combo press — the accepted A-F1
     * tradeoff.
     */
    private static boolean persistSync(boolean onValue, int snapValue, boolean validValue) {
        final Context app = appContext;
        if (app == null) {
            return false;
        }
        try {
            return app.getSharedPreferences(Hardkey.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_EINK_ENABLED, onValue)
                    .putInt(PREF_EINK_SNAPSHOT, snapValue)
                    .putBoolean(PREF_EINK_SNAPSHOT_VALID, validValue)
                    .commit();
        } catch (Throwable t) {
            DriftReport.eventAlways("eink: module prefs commit FAILED: " + t);
            return false;
        }
    }

    /**
     * C-F1 load detection: LiteMode.loaded is PRIVATE with no getter,
     * so "is it loaded?" is not directly observable from this module.
     * The public load-forcing idiom is getPowerSaverLevel() — exactly
     * like getValue(true), it calls the PUBLIC loadPreference() when
     * !loaded, and loadPreference reads BOTH lite_mode6 AND
     * lite_mode_battery_level. After this call, savePreference() (inside
     * any setAllFlags) persists the REAL powerSaverLevel instead of the
     * unloaded-field-default 0. Returns the level for the invariance
     * receipts.
     */
    private static int ensureLiteModeLoaded() {
        return LiteMode.getPowerSaverLevel();
    }

    /** Receipt-safe read of the battery threshold; fallback on failure. */
    private static int safeBatteryLevel(int fallback) {
        try {
            return LiteMode.getPowerSaverLevel();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean safeIsEnabledGifs() {
        try {
            return LiteMode.isEnabled(LiteMode.FLAG_AUTOPLAY_GIFS);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int safeGetValue() {
        try {
            return LiteMode.getValue(true);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String hex(int v) {
        return String.format(Locale.US, "%X", v);
    }
}
