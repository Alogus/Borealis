package io.borealis;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * The text inside a home card: title and one line under it, white over a dark fade at the bottom. One per card.
 * Borealis' own code. It allocates nothing while drawing: the paints and the fade are made once, and the shortened
 * (ellipsized) strings are remembered until the text, the width or the card size changes.
 * Two steps, in this order: drawScrim (under the progress line and its labels), then drawText (on top of everything).
 */
final class CardText {
    private final float k;
    private final TextPaint titleWide, titleTall, metaWide, metaTall;
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    /** Clear at the top, 60% dark at 55% of the way, 90% dark at the bottom: even a white picture gets a dark base. */
    private final LinearGradient fade = new LinearGradient(0, 0, 0, 1,
            new int[] {0x00000000, 0x99000000, 0xE6000000}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    private final Slot title = new Slot(), meta = new Slot();

    /** One remembered shortened string. */
    private static final class Slot {
        String src, out;
        int width;
        boolean wide;
    }

    /** `k` is the screen scale of the 1920x1080 design space. */
    CardText(float k) {
        this.k = k;
        titleWide = new TextPaint(Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF));
        titleTall = new TextPaint(Ui.text(Ui.semibold, 23 * k, 0xFFFFFFFF));
        metaWide = new TextPaint(Ui.text(Ui.medium, 21 * k, 0xE6FFFFFF));
        metaTall = new TextPaint(Ui.text(Ui.medium, 17 * k, 0xE6FFFFFF));
        for (TextPaint p : new TextPaint[] {titleWide, titleTall, metaWide, metaTall}) p.setShadowLayer(8 * k, 0, 2 * k, 0xCC000000);   // keeps it readable over bright pictures
    }

    /** The dark fade at the bottom of the card. Draw it before the progress line, so that line stays on top of it. */
    void drawScrim(Canvas c, RectF card, float radius) {
        float h = card.height() * 0.68f;
        matrix.setScale(1, h);
        matrix.postTranslate(0, card.bottom - h);
        fade.setLocalMatrix(matrix);
        scrim.setShader(fade);
        c.drawRoundRect(card, radius, radius, scrim);
    }

    /**
     * The title and the line under it. Draw it last. `wide` is the expanded card (bigger text) or a compact one;
     * `lift` is how far above the card's bottom edge (in design units) the line under the title sits.
     */
    void drawText(Canvas c, RectF card, boolean wide, float lift, String titleText, String metaText) {
        drawText(c, card, wide, lift, titleText, metaText, card.right);
    }

    /** Same, but the text stops at `right` (a song's cover sits in the middle of the card). */
    void drawText(Canvas c, RectF card, boolean wide, float lift, String titleText, String metaText, float right) {
        float x = card.left + 22 * k;
        int maxWidth = Math.max(0, Math.round(Math.min(card.width() - 44 * k, right - x)));
        float metaBase = card.bottom - lift * k;
        float titleBase = metaBase - (wide ? 34 : 28) * k;
        String t = fit(title, titleText, wide ? titleWide : titleTall, maxWidth, wide);
        if (t != null) c.drawText(t, x, titleBase, wide ? titleWide : titleTall);
        String m = fit(meta, metaText, wide ? metaWide : metaTall, maxWidth, wide);
        if (m != null) c.drawText(m, x, metaBase, wide ? metaWide : metaTall);
    }

    private static String fit(Slot s, String text, TextPaint paint, int width, boolean wide) {
        if (text == null || text.isEmpty()) return null;
        if (s.out == null || s.width != width || s.wide != wide || !text.equals(s.src)) {
            s.src = text;
            s.width = width;
            s.wide = wide;
            s.out = TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString();
        }
        return s.out;
    }
}
