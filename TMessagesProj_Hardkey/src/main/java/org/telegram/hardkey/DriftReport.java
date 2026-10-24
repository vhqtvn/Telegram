package org.telegram.hardkey;

import android.app.Activity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

/**
 * Static drift registry for the hardkey spike. Every probe result is
 * appended to a bounded in-memory ring (queryable later for a debug screen)
 * and mirrored to logcat under the "HardkeyDrift" tag.
 *
 * D2 rules:
 *  - Kill-switch: while routing is disabled, event() writes nothing (no
 *    ring growth, no logcat). eventAlways() is the un-gated companion used
 *    only for the single init line and the kill-switch toggle lines.
 *  - Key privacy: raw keycodes that could reconstruct typed text are never
 *    logged. When an editor is focused nothing about the key is recorded
 *    beyond a redaction marker; character-capable keys outside editors
 *    appear only in routing-decision lines (route:/pair:). Only navigation
 *    cluster keys keep their keycode in the key-observation line.
 */
public final class DriftReport {

    private static final int MAX_ENTRIES = 600;
    private static final Deque<String> ENTRIES = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DriftReport() {
    }

    public static void event(String line) {
        if (!Hardkey.isEnabled()) {
            return; // kill-switch OFF: no ring growth, no logcat output
        }
        append(line);
        Log.d(Hardkey.LOG_TAG, line);
    }

    /**
     * Un-gated companion for the init line and kill-switch toggle lines —
     * the only output permitted while routing is disabled.
     */
    public static void eventAlways(String line) {
        append(line);
        Log.d(Hardkey.LOG_TAG, line);
    }

    private static void append(String line) {
        String entry = ts() + " " + line;
        synchronized (LOCK) {
            ENTRIES.addLast(entry);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.removeFirst();
            }
        }
    }

    /** Bounded ring dump for later inspection (debug screen / bug reports). */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            Iterator<String> it = ENTRIES.iterator();
            while (it.hasNext()) {
                sb.append(it.next()).append('\n');
            }
        }
        return sb.toString();
    }

    public static void callbackInstalled(Activity activity) {
        event("callback wrapped on " + activity.getClass().getName());
    }

    /** Real replacement-detection receipt (D2): logs BOTH states. */
    public static void callbackCheck(Activity activity, boolean stillWrapped) {
        event("callback: " + (stillWrapped ? "still wrapped" : "REPLACED under us")
                + " on " + activity.getClass().getName());
    }

    /**
     * Observation probe: record hardware-key ACTION_DOWNs we see, subject
     * to the D2 privacy rules (see class comment).
     *
     * @param editorFocused whether the window's current focus is a text
     *                      editor (input connection active) — computed by
     *                      the caller, which owns the activity reference.
     */
    public static void key(KeyEvent event, boolean editorFocused) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        if (editorFocused) {
            event("key: [redacted, editor focused]");
            return;
        }
        if (!isNavigationOnlyKey(event.getKeyCode())) {
            return; // character-capable/unknown keys: routing decisions only
        }
        event("key: code=" + event.getKeyCode()
                + " repeat=" + event.getRepeatCount()
                + " shift=" + event.isShiftPressed()
                + " device=" + event.getDeviceId());
    }

    /**
     * Whitelist of keys whose keycode is safe to observe verbatim: the
     * navigation cluster only. Everything else (letters, digits, SPACE,
     * punctuation, ENTER, TAB, ...) can reconstruct typed text and is
     * therefore never named in an observation line.
     */
    private static boolean isNavigationOnlyKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    /** Pairing receipt: a follow-up that fell through to the delegate. */
    public static void pairPassthrough(KeyEvent event, boolean editorFocused) {
        event("pair: passthrough UP"
                + (editorFocused
                        ? " (editor focused)"
                        : " keycode=" + event.getKeyCode()));
    }

    public static void listsDiscovered(Activity activity, int found, View primary) {
        event("lists: " + found + " RecyclerView(s) on " + activity.getClass().getSimpleName()
                + (primary != null
                        ? ", primary " + primary.getWidth() + "x" + primary.getHeight()
                        : ", none visible"));
    }

    /** Scroll receipt: actual pixels moved vs. requested (clamped at edges). */
    public static void scrolled(View list, boolean down, int requested, int actual) {
        event("scroll: " + (down ? "down" : "up") + " " + actual + "px"
                + (actual != requested ? " (requested " + requested + "px)" : "")
                + " on " + list.getClass().getSimpleName()
                + " " + list.getWidth() + "x" + list.getHeight());
    }

    // ── D3 receipts: positions and coordinates only, never keycodes ────────

    /** Selection-move receipt: which row the selection landed on. */
    public static void selected(View list, int position) {
        event("select: pos=" + position
                + " on " + list.getClass().getSimpleName()
                + " " + list.getWidth() + "x" + list.getHeight());
    }

    /** Activation receipt: the row was clicked through the real path. */
    public static void activated(View list, int position) {
        event("activate: pos=" + position + " via RecyclerListView.clickItem on "
                + list.getClass().getSimpleName());
    }

    /** Chat-switch receipt (n/p on the dialogs list). */
    public static void chatSwitch(boolean next, int position) {
        event("chat: " + (next ? "next" : "prev") + " pos=" + position);
    }

    /** Ring-overlay position receipt (decor-content coordinates). */
    public static void ringAt(int left, int top, int right, int bottom) {
        event("ring: [" + left + "," + top + " " + right + "," + bottom + "]");
    }

    private static String ts() {
        return TS.format(new Date());
    }
}
