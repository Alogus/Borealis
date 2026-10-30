package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Borealis's logo, exactly as designed on a 1000 × 1000 board (logo/Borealis-logo-Gloock.svg):
 * three aurora waves under one Lily → Peach → Melon gradient that fades out toward the top, a four-point star and a dot
 * to their right, and the name in Gloock underneath. Everything is positioned in board units: (ox, oy) is where the
 * board's top-left corner lands on screen and `u` is the size of one board unit in pixels.
 */
final class Logo {
    private Logo() {}

    // Waves: the design's box (x 200–830, y 260–690) scaled by 0.78 and placed with its bottom center at (500, 605).
    private static final float BX = 200, BY = 260, BW = 660, BH = 430;
    private static final float WL = 254.3f, WT = 269.6f, WW = 514.8f, WH = 335.4f;   // the waves on the board
    private static final int BMP_W = 792, BMP_H = 516;
    /** Waves' height in board units (for sizing the symbol alone). */
    static final float WAVES_H = WH;

    private static Bitmap waves;
    private static Typeface gloock;
    private static boolean gloockTried;

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

    // Each wave keeps its original edges (width and taper) but its centerline sways gently, like an aurora curtain:
    // x moves by AMP * sin(2π (690 - y) / WAVE_LEN + phase), growing from 25% at the base to 100% at the top.
    private static final float AMP = 28f, WAVE_LEN = 300f;
    private static final float[] PHASE = {0f, 1.0f, 2.0f};
    private static final float[][][] EDGES = {   // per wave: left edge (bottom -> top) and right edge (top -> bottom), as cubic Béziers
        {{200, 690, 260, 520, 300, 400, 360, 260}, {470, 260, 440, 420, 420, 560, 430, 690}},
        {{420, 690, 480, 540, 560, 420, 620, 260}, {720, 260, 660, 420, 600, 560, 600, 690}},
        {{600, 690, 650, 560, 720, 450, 800, 340}, {830, 380, 800, 500, 770, 600, 780, 690}}};
    private static final int STEPS = 48;

    private static void edge(Path p, float[] e, float phase, boolean first) {
        for (int i = first ? 0 : 1; i <= STEPS; i++) {
            float t = i / (float) STEPS, m = 1 - t;
            float x = m * m * m * e[0] + 3 * m * m * t * e[2] + 3 * m * t * t * e[4] + t * t * t * e[6];
            float y = m * m * m * e[1] + 3 * m * m * t * e[3] + 3 * m * t * t * e[5] + t * t * t * e[7];
            float k = Math.max(0f, Math.min(1f, (690 - y) / 430f));
            x += AMP * (0.25f + 0.75f * k) * (float) Math.sin(2 * Math.PI * (690 - y) / WAVE_LEN + phase);
            if (first && i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
    }

    private static Path[] shapes() {
        Path[] out = new Path[3];
        for (int i = 0; i < 3; i++) {
            Path p = new Path();
            edge(p, EDGES[i][0], PHASE[i], true);
            edge(p, EDGES[i][1], PHASE[i], false);
            p.close();
            out[i] = p;
        }
        return out;
    }

    /** One wave (or all three when `only` < 0) under the shared gradient, fading to transparent toward the top. */
    private static Bitmap render(int only) {
        Bitmap bmp = Bitmap.createBitmap(BMP_W, BMP_H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float s = BMP_W / BW;
        cv.save();
        cv.scale(s, s);
        cv.translate(-BX, -BY);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(170, 0, 830, 0, Brand.accents(), new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        Path[] all = shapes();
        for (int i = 0; i < all.length; i++) if (only < 0 || only == i) cv.drawPath(all[i], p);
        cv.restore();
        Paint fade = new Paint();
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        fade.setShader(new LinearGradient(0, BMP_H, 0, 0, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, BMP_W, BMP_H, fade);
        return bmp;
    }

    /** The three waves together (how the logo is drawn at rest). */
    private static synchronized Bitmap waves() {
        if (waves == null) waves = render(-1);
        return waves;
    }

    private static Bitmap[] each;

    /** Each wave on its own, for the entrance (the swaying waves overlap, so they can't be cut apart by a vertical clip). */
    private static synchronized Bitmap[] each() {
        if (each == null) each = new Bitmap[] {render(0), render(1), render(2)};
        return each;
    }

    private static final Paint BMP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint NAME = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private static final Paint STAR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path STAR_PATH = new Path();
    private static final RectF DST = new RectF();

    /**
     * The waves. `rise` 1 = all there; 0–1 = rising from their base, each wave starting a little after the one before it
     * and growing from the bottom up with a small overshoot.
     */
    static void drawWaves(Canvas c, float ox, float oy, float u, float rise) {
        float left = ox + WL * u, top = oy + WT * u, bottom = top + WH * u, w = WW * u, h = WH * u;
        DST.set(left, top, left + w, bottom);
        if (rise >= 1f) {
            BMP.setAlpha(255);
            c.drawBitmap(waves(), null, DST, BMP);
            return;
        }
        Bitmap[] parts = each();
        for (int i = 0; i < 3; i++) {
            float p = Math.max(0f, Math.min(1f, (rise - i * 0.16f) / 0.62f));
            if (p <= 0f) continue;
            c.save();
            c.scale(1f, overshoot(p), 0f, bottom);
            BMP.setAlpha(Math.round(255 * Math.min(1f, p * 2.2f)));
            c.drawBitmap(parts[i], null, DST, BMP);
            BMP.setAlpha(255);
            c.restore();
        }
    }

    /** Ease-out with a small overshoot (about 6%), settling at 1. */
    private static float overshoot(float p) {
        float q = p - 1f;
        return 1f + 2.4f * q * q * q + 1.4f * q * q;
    }

    /** The star (same gradient as the waves, so it reads Melon) and the small Melon dot; `pop` 0–1 for the entrance. */
    static void drawStar(Canvas c, float ox, float oy, float u, float pop) {
        if (pop <= 0f) return;
        float cx = ox + 780 * u, cy = oy + 396.8f * u, r = 30 * u * (0.4f + 0.6f * pop), k = r * 0.22f;
        STAR_PATH.reset();
        STAR_PATH.moveTo(cx, cy - r); STAR_PATH.lineTo(cx + k, cy - k); STAR_PATH.lineTo(cx + r, cy); STAR_PATH.lineTo(cx + k, cy + k);
        STAR_PATH.lineTo(cx, cy + r); STAR_PATH.lineTo(cx - k, cy + k); STAR_PATH.lineTo(cx - r, cy); STAR_PATH.lineTo(cx - k, cy - k);
        STAR_PATH.close();
        STAR.setShader(new LinearGradient(ox + 170 * u, 0, ox + 830 * u, 0, Brand.accents(), new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        STAR.setAlpha(Math.round(255 * Math.min(1f, pop)));
        c.drawPath(STAR_PATH, STAR);
        STAR.setShader(null);
        STAR.setColor(Brand.MELON);
        STAR.setAlpha(Math.round(255 * Math.min(1f, pop)));
        c.drawCircle(ox + 842 * u, oy + 487 * u, 6 * u, STAR);
    }

    /** "Borealis" in Gloock, centered on the board's x = 500 with its baseline at y = 760 (`dy` in board units, for the entrance). */
    static void drawName(Canvas c, Context ctx, float ox, float oy, float u, float alpha, float dy) {
        NAME.setTypeface(wordmark(ctx));
        NAME.setTextSize(175 * u);
        NAME.setTextAlign(Paint.Align.CENTER);
        NAME.setColor(0xFFFFFFFF);
        NAME.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
        c.drawText("Borealis", ox + 500 * u, oy + (760 + dy) * u, NAME);
    }

    /** The whole logo, as designed. */
    static void drawLockup(Canvas c, Context ctx, float ox, float oy, float u) {
        drawWaves(c, ox, oy, u, 1f);
        drawStar(c, ox, oy, u, 1f);
        drawName(c, ctx, ox, oy, u, 1f, 0f);
    }

    /** Waves, star and dot without the name (small places: the shelf card), with the waves centered at (cx, cy) and `h` tall. */
    static void drawSymbol(Canvas c, float cx, float cy, float h) {
        float u = h / WH;
        float ox = cx - (WL + WW / 2) * u, oy = cy - (WT + WH / 2) * u;
        drawWaves(c, ox, oy, u, 1f);
        drawStar(c, ox, oy, u, 1f);
    }
}
