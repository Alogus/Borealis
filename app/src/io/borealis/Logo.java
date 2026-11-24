package io.borealis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.animation.PathInterpolator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Borealis's mark (brand manual, 2026-11-22): three flat lights, Lily, Peach and Melon, plus Polaris, a white dot.
 * Always flat: no gradient, glow or shadow inside the symbol. It is drawn from the master SVG's own paths (LogoPaths),
 * never redrawn by eye. The symbol's box is the master's viewBox (22.92, 14.48, 74.62 × 93.60 on the 120 grid), so
 * every size below is "the symbol's height" and matches the design's numbers.
 *
 * Versions: the symbol alone, the vertical lockup (splash, welcome), the horizontal "Borealis | TV" signature
 * (About, banner) and the "curtains" (the three lights huge and cropped, at 10–14 %, as a background).
 */
final class Logo {
    private Logo() {}

    // The symbol's box on the 120 grid, and Polaris.
    static final float BX = 22.92f, BY = 14.48f, BW = 74.62f, BH = 93.60f;
    static final float ASPECT = BW / BH;
    private static final float PX = 86f, PY = 28f, PR = 6f;

    // Entrance (brand manual): each light rises from the horizon 600 ms, 120 ms apart; Polaris shines once, from 420 ms for 700 ms.
    static final long RISE_MS = 600, RISE_GAP = 120, POLARIS_AT = 420, POLARIS_MS = 700, FADE_MS = 300;
    /** When the whole entrance is over (the last light, Polaris, whichever ends later). */
    static final long ENTRANCE_MS = Math.max(2 * RISE_GAP + RISE_MS, POLARIS_AT + POLARIS_MS);
    private static final PathInterpolator IN = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final PathInterpolator OUT = new PathInterpolator(0f, 0f, 0.58f, 1f);

    private static Path[] lights;
    private static float[] bottoms;
    private static final int[] COLORS = {Brand.LILY, Brand.PEACH, Brand.MELON};
    private static Typeface gloock;
    private static boolean gloockTried;
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TEXT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

    /** The brand face for the name (Gloock); a plain serif if the file is missing. */
    static synchronized Typeface wordmark(Context c) {
        if (!gloockTried) {
            gloockTried = true;
            try {
                gloock = Typeface.createFromAsset(c.getAssets(), "fonts/Gloock-Regular.ttf");
            } catch (RuntimeException ignored) {
            }
        }
        return gloock != null ? gloock : Typeface.SERIF;
    }

    // ---- The master paths (M, C and Z only), parsed once.

    private static final Pattern TOKEN = Pattern.compile("([MCZ])|(-?\\d*\\.?\\d+)");

    private static Path parse(String d) {
        Path p = new Path();
        Matcher m = TOKEN.matcher(d);
        float[] n = new float[6];
        int have = 0;
        char cmd = 'M';
        while (m.find()) {
            if (m.group(1) != null) {
                cmd = m.group(1).charAt(0);
                have = 0;
                if (cmd == 'Z') p.close();
                continue;
            }
            n[have++] = Float.parseFloat(m.group(2));
            if (cmd == 'M' && have == 2) {
                p.moveTo(n[0], n[1]);
                have = 0;
            } else if (cmd == 'C' && have == 6) {
                p.cubicTo(n[0], n[1], n[2], n[3], n[4], n[5]);
                have = 0;
            }
        }
        return p;
    }

    private static synchronized void load() {
        if (lights != null) return;
        Path[] ps = {parse(LogoPaths.LILY), parse(LogoPaths.PEACH), parse(LogoPaths.MELON)};
        float[] b = new float[3];
        RectF r = new RectF();
        for (int i = 0; i < 3; i++) {
            ps[i].computeBounds(r, true);
            b[i] = r.bottom;
        }
        bottoms = b;
        lights = ps;
    }

    /** True when the person turned animations off (then the logo only fades in). */
    static boolean reducedMotion(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    // ---- The symbol

    /**
     * The symbol with its box's top-left at (left, top), `h` tall. `t` is the time (ms) since the entrance began, or -1 at rest;
     * `reduced` makes the entrance a plain fade. `alpha` (0–1) multiplies everything.
     */
    static void drawSymbol(Canvas c, float left, float top, float h, long t, boolean reduced, float alpha) {
        load();
        float s = h / BH;
        c.save();
        c.translate(left, top);
        c.scale(s, s);
        c.translate(-BX, -BY);
        float fade = reduced && t >= 0 ? clamp(t / (float) FADE_MS) : 1f;
        for (int i = 0; i < 3; i++) {
            float e = 1f;
            if (t >= 0 && !reduced) e = IN.getInterpolation(clamp((t - i * RISE_GAP) / (float) RISE_MS));
            if (e <= 0f) continue;
            FILL.setColor(Ui.alpha(COLORS[i], e * fade * alpha));
            c.save();
            if (e < 1f) c.scale(1f, e, 60f, bottoms[i]);   // grows from its own base
            c.drawPath(lights[i], FILL);
            c.restore();
        }
        float pq = 1f, ps = 1f;
        if (t >= 0 && !reduced) {   // one shine: 0.4 -> 1.5 -> 1, fading in
            float q = clamp((t - POLARIS_AT) / (float) POLARIS_MS);
            pq = clamp(q * 2f);
            ps = q < 0.5f ? 0.4f + 1.1f * OUT.getInterpolation(q * 2f) : 1.5f - 0.5f * OUT.getInterpolation((q - 0.5f) * 2f);
        }
        if (pq > 0f) {
            FILL.setColor(Ui.alpha(Brand.WHITE, pq * fade * alpha));
            c.drawCircle(PX, PY, PR * ps, FILL);
        }
        c.restore();
    }

    /** The symbol at rest. */
    static void drawSymbol(Canvas c, float left, float top, float h) { drawSymbol(c, left, top, h, -1, false, 1f); }

    /** The symbol in one flat color, centered at (cx, cy) (for the small menu icon). Under 20 px it drops Polaris, as the manual asks. */
    static void drawFlat(Canvas c, float cx, float cy, float height, int color) {
        load();
        float s = height / BH;
        c.save();
        c.translate(cx - BW * s / 2f, cy - height / 2f);
        c.scale(s, s);
        c.translate(-BX, -BY);
        FILL.setColor(color);
        for (Path p : lights) c.drawPath(p, FILL);
        if (height >= 20f) c.drawCircle(PX, PY, PR, FILL);
        c.restore();
    }

    /** The "curtains": the three lights without Polaris, huge, flat and cropped by the screen, at `alpha` (0.10–0.14). */
    static void drawCurtains(Canvas c, float left, float top, float h, float alpha) {
        drawSymbolNoPolaris(c, left, top, h, alpha);
    }

    private static void drawSymbolNoPolaris(Canvas c, float left, float top, float h, float alpha) {
        load();
        float s = h / BH;
        c.save();
        c.translate(left, top);
        c.scale(s, s);
        c.translate(-BX, -BY);
        for (int i = 0; i < 3; i++) {
            FILL.setColor(Ui.alpha(COLORS[i], alpha));
            c.drawPath(lights[i], FILL);
        }
        c.restore();
    }

    // ---- The vertical lockup (splash, welcome)

    /** CSS-like baseline of a text with line-height 1: the content area (ascent + descent) centered in a box `boxH` tall. */
    static float baseline(Paint p, float boxTop, float boxH) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return boxTop + (boxH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
    }

    /** Gap between the symbol and the name, and the name's size, as fractions of the symbol's height (170 -> 17 and 105.4 in the design). */
    static final float V_GAP = 0.10f, V_TEXT = 0.62f;

    /**
     * The vertical lockup: the symbol (`h` tall) with the name under it, centered on cx, its top at `top`. `t` as in drawSymbol.
     * Returns the bottom of the name's line box (where the slogan's margin starts).
     */
    static float drawVertical(Canvas c, Context ctx, float cx, float top, float h, long t, boolean reduced, float alpha) {
        drawSymbol(c, cx - h * ASPECT / 2f, top, h, t, reduced, alpha);
        float size = h * V_TEXT, boxTop = top + h + h * V_GAP;
        float fade = reduced && t >= 0 ? clamp(t / (float) FADE_MS) : 1f;
        TEXT.setTypeface(wordmark(ctx));
        TEXT.setTextSize(size);
        TEXT.setTextAlign(Paint.Align.CENTER);
        TEXT.setColor(Ui.alpha(Brand.WHITE, alpha * fade));
        c.drawText("Borealis", cx, baseline(TEXT, boxTop, size), TEXT);
        return boxTop + size;
    }

    // ---- The horizontal signature "Borealis | TV" (About, banner)

    // Proportions of the signature, as fractions of the symbol's height (78 -> 20, 62.4, 16.8, 17.2, 48.4, 23.4 in the design).
    private static final float S_GAP = 20f / 78f, S_TEXT = 0.8f, S_LIFT = 16.8f / 78f, S_SEP = 17.2f / 78f, S_LINE = 48.4f / 78f, S_TV = 0.3f;

    /** Width of the signature for a symbol `h` tall. */
    static float signatureWidth(Context ctx, float h) {
        TEXT.setTypeface(wordmark(ctx));
        TEXT.setTextSize(h * S_TEXT);
        float name = TEXT.measureText("Borealis");
        Paint tv = tvPaint(h);
        return h * ASPECT + h * S_GAP + name + h * S_SEP + lineWidth + h * S_SEP + tv.measureText("TV");
    }

    private static float lineWidth = 2f;   // the separator's thickness in pixels (1.5 in the 1280 design: 2 on the TV)

    private static Paint tvPaint(float h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(Ui.bold);
        p.setTextSize(h * S_TV);
        p.setLetterSpacing(0.14f);
        p.setColor(Brand.LILY);
        return p;
    }

    /**
     * The signature centered on (cx, cy): the symbol and "Borealis" on a common base, a thin white line at 30 %, and "TV" in Lily.
     * `h` is the symbol's height; `sepWidth` the separator's thickness in pixels.
     */
    static void drawSignature(Canvas c, Context ctx, float cx, float cy, float h, float sepWidth, float alpha) {
        lineWidth = sepWidth;
        float w = signatureWidth(ctx, h), x = cx - w / 2f, top = cy - h / 2f;
        drawSymbol(c, x, top, h, -1, false, alpha);
        x += h * ASPECT + h * S_GAP;
        float size = h * S_TEXT;
        TEXT.setTypeface(wordmark(ctx));
        TEXT.setTextSize(size);
        TEXT.setTextAlign(Paint.Align.LEFT);
        TEXT.setColor(Ui.alpha(Brand.WHITE, alpha));
        // The name's line box sits on the symbol's base, lifted by S_LIFT.
        float boxTop = top + h - h * S_LIFT - size;
        c.drawText("Borealis", x, baseline(TEXT, boxTop, size), TEXT);
        x += TEXT.measureText("Borealis") + h * S_SEP;
        float lh = h * S_LINE;
        FILL.setColor(Ui.alpha(Brand.WHITE, 0.30f * alpha));
        c.drawRect(x, cy - lh / 2f, x + sepWidth, cy + lh / 2f, FILL);
        x += sepWidth + h * S_SEP;
        Paint tv = tvPaint(h);
        tv.setColor(Ui.alpha(Brand.LILY, alpha));
        c.drawText("TV", x, baseline(tv, cy - h * S_TV / 2f, h * S_TV), tv);
    }

    // ---- The aurora background used by the splash

    private static RadialGradient g1, g2, g3;
    private static int gw, gh;
    private static float ga1, ga2, ga3;
    private static final Paint BACK = new Paint();

    /** CSS "radial-gradient(circle at x% y%, color a, transparent r%)": r is a fraction of the distance to the farthest corner. */
    private static RadialGradient glow(float w, float h, float fx, float fy, int color, float a, float r) {
        float x = w * fx, y = h * fy;
        float dx = Math.max(x, w - x), dy = Math.max(y, h - y);
        int c0 = Ui.alpha(color, a), c1 = color & 0x00FFFFFF;
        return new RadialGradient(x, y, (float) Math.hypot(dx, dy) * r, c0, c1, Shader.TileMode.CLAMP);
    }

    /** Night (#1B1720) with the three glows of the design (Lily top-left, Peach top-right, Melon bottom). */
    static void drawAurora(Canvas c, float w, float h, float lily, float peach, float melon) {
        int iw = Math.round(w), ih = Math.round(h);
        if (g1 == null || iw != gw || ih != gh || lily != ga1 || peach != ga2 || melon != ga3) {
            gw = iw;
            gh = ih;
            ga1 = lily;
            ga2 = peach;
            ga3 = melon;
            g1 = glow(w, h, 0.18f, 0.12f, Brand.LILY, lily, 0.46f);
            g2 = glow(w, h, 0.88f, 0.22f, Brand.PEACH, peach, 0.42f);
            g3 = glow(w, h, 0.70f, 0.95f, Brand.MELON, melon, 0.42f);
        }
        c.drawColor(0xFF1B1720);
        BACK.setShader(g1);
        c.drawRect(0, 0, w, h, BACK);
        BACK.setShader(g2);
        c.drawRect(0, 0, w, h, BACK);
        BACK.setShader(g3);
        c.drawRect(0, 0, w, h, BACK);
        BACK.setShader(null);
    }
}
