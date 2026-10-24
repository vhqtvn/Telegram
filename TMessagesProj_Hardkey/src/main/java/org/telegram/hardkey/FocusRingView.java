package org.telegram.hardkey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * E-ink focus ring overlay (D3). A static double-stroke rectangle — black
 * outer, white inner — positioned over the currently selected list row.
 *
 * E-ink constraints, by design:
 *  - no animation, no gradient, no alpha fade — a full, static rectangle;
 *  - invalidate() is called with the OLD and NEW rect only, so a move
 *    dirties just the two ring regions, never the whole screen;
 *  - double stroke keeps the ring visible on light AND dark themes without
 *    any theme coupling (monochrome-safe contrast).
 *
 * The view is runtime-attached as a fullscreen sibling in the activity decor
 * content (added by SelectionController) — zero edits to any cell or screen:
 * it lays out over the whole window, never takes focus, and returns false
 * from touch handling (not clickable), so touches keep reaching the real
 * content underneath.
 */
final class FocusRingView extends View {

    private final Paint outerPaint = new Paint();
    private final Paint innerPaint = new Paint();
    private final Rect rect = new Rect();

    private boolean visible;

    FocusRingView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        // 1.5–2dp spec: 1.5dp outer stroke (min 2px so it survives low-dpi),
        // thin white counter-stroke inside it.
        outerPaint.setStyle(Paint.Style.STROKE);
        outerPaint.setStrokeWidth(Math.max(2f, 1.5f * density));
        outerPaint.setColor(Color.BLACK);
        innerPaint.setStyle(Paint.Style.STROKE);
        innerPaint.setStrokeWidth(Math.max(1f, 0.75f * density));
        innerPaint.setColor(Color.WHITE);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Move the ring to the given rect (overlay-local coordinates).
     *
     * @return true if the rect changed (caller may use it as a receipt).
     */
    boolean positionAt(int left, int top, int right, int bottom) {
        if (visible && rect.left == left && rect.top == top
                && rect.right == right && rect.bottom == bottom) {
            return false;
        }
        if (visible) {
            invalidate(rect); // dirty only the old ring region
        }
        rect.set(left, top, right, bottom);
        visible = true;
        invalidate(rect);     // dirty only the new ring region
        return true;
    }

    void hide() {
        if (visible) {
            visible = false;
            invalidate(rect); // dirty only the abandoned ring region
            rect.setEmpty();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible) {
            return;
        }
        canvas.drawRect(rect, outerPaint);
        canvas.drawRect(rect, innerPaint);
    }
}
