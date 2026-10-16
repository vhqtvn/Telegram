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
 * appended to a bounded in-memory ring (queryable later for a D2 debug
 * screen) and mirrored to logcat under the "HardkeyDrift" tag.
 *
 * No UI in this slice.
 */
public final class DriftReport {

    private static final int MAX_ENTRIES = 600;
    private static final Deque<String> ENTRIES = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DriftReport() {
    }

    public static void event(String line) {
        String entry = ts() + " " + line;
        synchronized (LOCK) {
            ENTRIES.addLast(entry);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.removeFirst();
            }
        }
        Log.d(Hardkey.LOG_TAG, line);
    }

    /** Bounded ring dump for later inspection (D2 debug screen / bug reports). */
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

    /** Called when onActivityCreated sees an already-wrapped callback. */
    public static void callbackCheck(Activity activity, boolean stillWrapped) {
        if (!stillWrapped) {
            event("callback REPLACED under us on " + activity.getClass().getName());
        }
    }

    /** Observation probe: record every hardware-key ACTION_DOWN we see. */
    public static void key(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        event("key: code=" + event.getKeyCode()
                + " repeat=" + event.getRepeatCount()
                + " shift=" + event.isShiftPressed()
                + " device=" + event.getDeviceId());
    }

    public static void listsDiscovered(Activity activity, int found, View primary) {
        event("lists: " + found + " RecyclerView(s) on " + activity.getClass().getSimpleName()
                + (primary != null
                        ? ", primary " + primary.getWidth() + "x" + primary.getHeight()
                        : ", none visible"));
    }

    public static void scrolled(View list, boolean down, int amount) {
        event("scroll: " + (down ? "down" : "up") + " " + amount + "px on "
                + list.getClass().getSimpleName() + " " + list.getWidth() + "x" + list.getHeight());
    }

    private static String ts() {
        return TS.format(new Date());
    }
}
