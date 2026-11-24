package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.PathInterpolator;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The four looping pictures of "Get to know Borealis", drawn on a canvas in a 960 x 360 box (the design's 640 x 240 times 1.5).
 * The apps are the design's SAMPLE tiles (rounded tiles with a small brand-colored square), never the installed apps' icons,
 * so it looks the same on any TV. Scenes: 0 pin an app, 1 grid and shapes, 2 continue watching (or recommendations), 3 wallpapers
 * and screensavers. It always animates (the caller keeps invalidating while it is on screen).
 */
final class TourDemo {
    static final float W = 960, H = 360;
    /** Aurora Lily, Lumen Peach, Velum Night, Pictura Peach, Luna Melon (Prefs.WALLPAPER_NAMES 4..8). */
    private static final String[] WALLS = {"wave.jpg", "wall2.jpg", "wall3.jpg", "wall4.jpg", "wall5.jpg"};
    private static final String[] WALL_NAMES = {"Aurora Lily", "Lumen Peach", "Velum Night", "Pictura Peach", "Luna Melon"};
    private static final Bitmap[] walls = new Bitmap[WALLS.length];
    private static final int[] SAMPLE = {0xFFB79CFE, 0xFFE99794, 0xFFFE9565, 0xFF7FB2FF, 0xFF7ED6A8, 0xFFF2C46D};
    private static final PathInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), q = new RectF(), z = new RectF();
    private final Path clip = new Path();
    private Paint label, pill, small, gloock, gloockS, bold, med, clock;
    private DashPathEffect dash;
    private boolean paintsReady;

    /** Loads the wallpapers (off the main thread). */
    static void preload(Context c) {
        for (int i = 0; i < WALLS.length; i++) {
            if (walls[i] != null) continue;
            try (InputStream in = c.getAssets().open(WALLS[i])) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                walls[i] = BitmapFactory.decodeStream(in, null, o);
            } catch (Exception | OutOfMemoryError ignored) {
            }
        }
    }

    static boolean ready() { return walls[0] != null; }

    private static float clamp(float x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    private static float span(float t, float a, float b) { return clamp((t - a) / (b - a)); }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private void paints(Context ctx) {
        if (paintsReady) return;
        paintsReady = true;
        label = Ui.text(Ui.bold, 18, 0x99FFFFFF);
        label.setLetterSpacing(0.08f);
        pill = Ui.text(Ui.bold, 21, 0xFFFFFFFF);
        pill.setTextAlign(Paint.Align.CENTER);
        small = Ui.text(Ui.semibold, 21, 0xD9FFFFFF);
        small.setTextAlign(Paint.Align.CENTER);
        gloock = Ui.text(Logo.wordmark(ctx), 39, 0xFFFFFFFF);
        gloockS = Ui.text(Logo.wordmark(ctx), 22.5f, 0xFFFFFFFF);
        bold = Ui.text(Ui.bold, 19.5f, 0xFFFFFFFF);
        med = Ui.text(Ui.medium, 21, 0xCCFFFFFF);
        clock = Ui.text(Ui.regular, 60, 0xFFFFFFFF);
        clock.setLetterSpacing(0.02f);
        dash = new DashPathEffect(new float[] {9, 7}, 0);
    }

    // MARK: - Pieces

    /** The dark box of the first three scenes: 960 x 339, radius 33, a very dark brand gradient, a white 14 % border. */
    private void darkBox(Canvas c) {
        r.set(0, 0, 960, 339);
        float len = 960 * 0.5f + 339 * 0.866f, cx = 480, cy = 169.5f, dx = 0.5f * len / 2, dy = 0.866f * len / 2;
        fill.setShader(new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, new int[] {0xD93C2E5A, 0x99784650, 0xE6171519},
                new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 33, 33, fill);
        fill.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f);
        line.setColor(0x24FFFFFF);
        z.set(0.75f, 0.75f, 959.25f, 338.25f);
        c.drawRoundRect(z, 32, 32, line);
        line.setStyle(Paint.Style.FILL);
    }

    /** A sample app tile: white 12 % with a small rounded square of a brand color. shape 0 wide, 1 square, 2 circle. */
    private void tile(Canvas c, float x, float y, float w, float h, int color, float alpha, int shape, float inner) {
        if (alpha <= 0.01f) return;
        float rad = shape == 2 ? w / 2 : shape == 1 ? 18 : 15;
        fill.setShader(null);
        fill.setColor(Ui.alpha(0x1FFFFFFF, alpha));
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, rad, rad, fill);
        fill.setColor(Ui.alpha(color, alpha));
        z.set(x + w / 2 - inner / 2, y + h / 2 - inner / 2, x + w / 2 + inner / 2, y + h / 2 + inner / 2);
        c.drawRoundRect(z, inner * 0.3f, inner * 0.3f, fill);
    }

    private void shadow(Canvas c, RectF b, float rad, float spread, float dy, float alpha) {
        for (int i = 6; i >= 1; i--) {
            float g = spread * i / 6f;
            z.set(b.left - g, b.top - g + dy, b.right + g, b.bottom + g + dy);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, alpha / 6f));
            c.drawRoundRect(z, rad + g, rad + g, fill);
        }
    }

    private void gradientRing(Canvas c, RectF b, float rad, float alpha) {
        fill.setShader(new LinearGradient(b.left, 0, b.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
        fill.setColor(Ui.alpha(0xFFFFFFFF, alpha));
        c.drawRoundRect(b, rad, rad, fill);
        fill.setShader(null);
    }

    private void cover(Canvas c, Bitmap b, RectF dst, float alpha, float focusX) {
        if (b == null || alpha <= 0.01f) return;
        float s = Math.max(dst.width() / b.getWidth(), dst.height() / b.getHeight());
        float bw = b.getWidth() * s, bh = b.getHeight() * s;
        float left = dst.left - (bw - dst.width()) * focusX, top = dst.top - (bh - dst.height()) / 2;
        image.setAlpha(Math.round(255 * alpha));
        c.save();
        c.clipRect(dst);
        RectF to = new RectF(left, top, left + bw, top + bh);
        c.drawBitmap(b, null, to, image);
        c.restore();
    }

    private void labelAt(Canvas c, String text, float x, float y) {
        c.drawText(text, x, y + 14, label);
    }

    // MARK: - Scenes

    void draw(Canvas c, Context ctx, RectF area, int scene, long ms, boolean recs) {
        paints(ctx);
        float s = area.width() / W;
        c.save();
        c.translate(area.left, area.top);
        c.scale(s, s);
        switch (scene) {
            case 0: pinScene(c, ms); break;
            case 1: gridScene(c, ms); break;
            case 2: if (recs) recsScene(c); else resumeScene(c, ms); break;
            default: wallScene(c, ctx, ms); break;
        }
        c.restore();
    }

    /** 1 · Your apps, at hand: the "+" of the pinned row, and an app from "All apps" that flies into it. */
    private void pinScene(Canvas c, long ms) {
        darkBox(c);
        float p = (ms % 4200) / 4200f;
        labelAt(c, Ui.tr("SEUS APPS FIXADOS", "YOUR PINNED APPS", "TUS APPS FIJADAS"), 90, 27);
        for (int i = 0; i < 5; i++) tile(c, 90 + i * 126, 66, 105, 60, SAMPLE[i], 1f, 0, 24);
        // the free place: a dashed Lily "+"
        r.set(90 + 5 * 126 + 1.1f, 67.1f, 90 + 5 * 126 + 105 - 1.1f, 124.9f);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.25f);
        line.setColor(Ui.alpha(Brand.LILY, 0.8f));
        line.setPathEffect(dash);
        c.drawRoundRect(r, 15, 15, line);
        line.setPathEffect(null);
        line.setStyle(Paint.Style.FILL);
        pill.setTextSize(30);
        pill.setColor(Brand.LILY);
        c.drawText("+", 90 + 5 * 126 + 52.5f, 66 + 30 + 10, pill);
        pill.setColor(0xFFFFFFFF);
        pill.setTextSize(21);

        labelAt(c, Ui.tr("TODAS AS APPS", "ALL APPS", "TODAS LAS APPS"), 90, 192);
        int[] bottom = {4, 1, 3, -1, 0, 2};
        for (int i = 0; i < 6; i++) {
            if (bottom[i] < 0) tile(c, 90 + i * 126, 234, 105, 60, SAMPLE[5], 0.35f, 0, 24);
            else tile(c, 90 + i * 126, 234, 105, 60, SAMPLE[bottom[i]], 0.75f, 0, 24);
        }
        // the focus: a ring around ONE tile (the one that will fly)
        float ring = p < 0.04f ? 0 : p < 0.08f ? (p - 0.04f) / 0.04f : p <= 0.34f ? 1 : p < 0.38f ? 1 - (p - 0.34f) / 0.04f : 0;
        if (ring > 0.01f) {
            r.set(90 + 3 * 126 - 13.5f, 234 - 13.5f, 90 + 3 * 126 + 105 + 13.5f, 234 + 60 + 13.5f);
            gradientRing(c, r, 21, ring);
            z.set(r.left + 4.5f, r.top + 4.5f, r.right - 4.5f, r.bottom - 4.5f);
            fill.setColor(Ui.alpha(0xFF2A2333, ring));
            c.drawRoundRect(z, 16.5f, 16.5f, fill);
        }
        // the flying copy
        float fx = 90 + 3 * 126, fy = 234, sc = 1, fa;
        if (p > 0.30f && p <= 0.36f) sc = lerp(1, 1.1f, span(p, 0.30f, 0.36f));
        else if (p > 0.36f) {
            float u = EASE.getInterpolation(span(p, 0.36f, 0.58f));
            fx = lerp(fx, 90 + 5 * 126, u);
            fy = lerp(fy, 66, u);
            sc = lerp(1.1f, 1f, u);
        }
        fa = p < 0.06f ? p / 0.06f : p < 0.92f ? 1 : 1 - (p - 0.92f) / 0.08f;
        if (fa > 0.01f) {
            c.save();
            c.scale(sc, sc, fx + 52.5f, fy + 30);
            r.set(fx, fy, fx + 105, fy + 60);
            shadow(c, r, 15, 18, 15, 0.45f * fa);
            fill.setColor(Ui.alpha(0xFF423850, fa));
            c.drawRoundRect(r, 15, 15, fill);
            fill.setColor(Ui.alpha(SAMPLE[5], fa));
            z.set(fx + 52.5f - 12, fy + 30 - 12, fx + 52.5f + 12, fy + 30 + 12);
            c.drawRoundRect(z, 7.2f, 7.2f, fill);
            c.restore();
        }
        // the check badge on the app that was pinned
        if (p >= 0.58f) {
            float ba = p < 0.64f ? span(p, 0.58f, 0.64f) : p < 0.92f ? 1 : 1 - span(p, 0.92f, 1f);
            float bs = p < 0.64f ? lerp(0.6f, 1f, span(p, 0.58f, 0.64f)) : 1f;
            float bx = (368 + 11) * 1.5f, by = (150 + 11) * 1.5f;
            fill.setColor(Ui.alpha(0xFF2A2333, ba));
            c.drawCircle(bx, by, 16.5f * bs, fill);
            fill.setColor(Ui.alpha(Brand.LILY, ba));
            c.drawCircle(bx, by, 12 * bs, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2.6f * bs);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Ui.alpha(Brand.INK, ba));
            clip.rewind();
            clip.moveTo(bx - 5 * bs, by + 0.5f * bs);
            clip.lineTo(bx - 1.5f * bs, by + 4 * bs);
            clip.lineTo(bx + 5.5f * bs, by - 3.5f * bs);
            c.drawPath(clip, line);
            line.setStrokeCap(Paint.Cap.BUTT);
            line.setStyle(Paint.Style.FILL);
        }
    }

    /** 2 · Made to measure: three layouts (6 x 4 wide, 8 x 5 square, 10 x 6 circular) that cross-fade. */
    private void gridScene(Canvas c, long ms) {
        darkBox(c);
        float p = (ms % 9000) / 9000f;
        float a1 = p <= 0.30f ? 1 : p < 0.34f ? 1 - (p - 0.30f) / 0.04f : p < 0.96f ? 0 : (p - 0.96f) / 0.04f;
        float a2 = p <= 0.30f ? 0 : p < 0.34f ? (p - 0.30f) / 0.04f : p <= 0.63f ? 1 : p < 0.67f ? 1 - (p - 0.63f) / 0.04f : 0;
        float a3 = p <= 0.63f ? 0 : p < 0.67f ? (p - 0.63f) / 0.04f : p <= 0.96f ? 1 : 1 - (p - 0.96f) / 0.04f;
        grid(c, a1, 60, 36, 6, 3, 125, 70, 143, 88, 0, 27, "6 × 4 · " + Ui.tr("Ampla", "Wide", "Amplia") + " · " + Ui.tr("Retangular", "Rectangular", "Rectangular"));
        grid(c, a2, 60, 60, 8, 2, 91.8f, 91.8f, 106.8f, 106.8f, 1, 19.5f, "8 × 5 · " + Ui.tr("Equilibrada", "Balanced", "Equilibrada") + " · " + Ui.tr("Quadrada", "Square", "Cuadrada"));
        grid(c, a3, 60, 79.5f, 10, 2, 73.2f, 73.2f, 85.2f, 85.2f, 2, 16.5f, "10 × 6 · " + Ui.tr("Compacta", "Compact", "Compacta") + " · " + Ui.tr("Circular", "Circular", "Circular"));
    }

    private void grid(Canvas c, float alpha, float x0, float y0, int cols, int rows, float tw, float th, float sx, float sy, int shape, float inner, String text) {
        if (alpha <= 0.01f) return;
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < cols; col++) tile(c, x0 + col * sx, y0 + row * sy, tw, th, SAMPLE[(row * cols + col) % 6], alpha, shape, inner);
        float w = pill.measureText(text) + 42, h = 48;
        r.set(960 - 27 - w, 339 - 21 - h, 960 - 27, 339 - 21);
        fill.setColor(Ui.alpha(0xBF171519, alpha));
        c.drawRoundRect(r, 24, 24, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f);
        line.setColor(Ui.alpha(0x29FFFFFF, alpha));
        z.set(r.left + 0.75f, r.top + 0.75f, r.right - 0.75f, r.bottom - 0.75f);
        c.drawRoundRect(z, 23, 23, line);
        line.setStyle(Paint.Style.FILL);
        pill.setColor(Ui.alpha(0xFFFFFFFF, alpha));
        c.drawText(text, r.centerX(), r.centerY() + Ui.halfCap(pill), pill);
        pill.setColor(0xFFFFFFFF);
    }

    /** 3 · Pick up where you left off: the "Continue watching" card with a picture, the app chip, the title and the progress. */
    private void resumeScene(Canvas c, long ms) {
        labelAt(c, Ui.tr("CONTINUAR ASSISTINDO", "CONTINUE WATCHING", "CONTINUAR VIENDO"), 120, 3);
        float t = (ms % 2400) / 2400f, tri = t < 0.5f ? t * 2 : (1 - t) * 2;
        r.set(120, 36, 750, 336);
        shadow(c, r, 30, 30, 30, 0.5f);
        if (tri > 0.01f) {
            z.set(r.left - 9 * tri, r.top - 9 * tri, r.right + 9 * tri, r.bottom + 9 * tri);
            fill.setColor(Ui.alpha(Brand.LILY, 0.25f * tri));
            c.drawRoundRect(z, 30 + 9 * tri, 30 + 9 * tri, fill);
        }
        z.set(r.left - 4.5f, r.top - 4.5f, r.right + 4.5f, r.bottom + 4.5f);
        fill.setShader(null);
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(z, 34.5f, 34.5f, fill);
        c.save();
        clip.rewind();
        clip.addRoundRect(r, 30, 30, Path.Direction.CW);
        c.clipPath(clip);
        fill.setColor(0xFF2A2333);
        c.drawRect(r, fill);
        cover(c, walls[3], r, 1f, 0.5f);
        fill.setShader(new LinearGradient(0, r.top + r.height() * 0.3f, 0, r.bottom, 0x00171519, 0xEB171519, Shader.TileMode.CLAMP));
        c.drawRect(r, fill);
        fill.setShader(null);
        // the app chip
        String chip = Ui.tr("Seu app de séries", "Your series app", "Tu app de series");
        float cw = bold.measureText(chip) + 18 + 18 + 12 + 18;
        z.set(r.left + 27, r.top + 24, r.left + 27 + cw, r.top + 24 + 42);
        fill.setColor(0xB3171519);
        c.drawRoundRect(z, 21, 21, fill);
        fill.setColor(Brand.PEACH);
        q.set(z.left + 18, z.centerY() - 9, z.left + 36, z.centerY() + 9);
        c.drawRoundRect(q, 6, 6, fill);
        c.drawText(chip, z.left + 48, z.centerY() + Ui.halfCap(bold), bold);
        // title, line, progress
        float tx = r.left + 27, barTop = r.bottom - 27 - 7.5f;
        med.setColor(0xCCFFFFFF);
        c.drawText(Ui.tr("Temporada 2 · Episódio 4 · faltam 18 min", "Season 2 · Episode 4 · 18 min left", "Temporada 2 · Episodio 4 · quedan 18 min"), tx, barTop - 6 - 6, med);
        c.drawText(Ui.tr("Sob o céu do norte", "Under the northern sky", "Bajo el cielo del norte"), tx, barTop - 6 - 6 - 21 - 9 - 4, gloock);
        z.set(tx, barTop, r.right - 27, barTop + 7.5f);
        fill.setColor(0x40FFFFFF);
        c.drawRoundRect(z, 4.5f, 4.5f, fill);
        float u = (ms % 8000) / 4000f;
        if (u > 1) u = 2 - u;
        float frac = 0.32f + 0.36f * EASE.getInterpolation(u);
        fill.setColor(0xFFFFFFFF);
        z.right = z.left + z.width() * frac;
        c.drawRoundRect(z, 4.5f, 4.5f, fill);
        c.restore();
        // "OK" · Continue
        String ok = Ui.tr("“OK” · Continuar", "“OK” · Continue", "“OK” · Seguir");
        float ow = small.measureText(ok) + 42;
        r.set(780, 162, 780 + ow, 213);
        fill.setColor(0x1AFFFFFF);
        c.drawRoundRect(r, 25.5f, 25.5f, fill);
        c.drawText(ok, r.centerX(), r.centerY() + Ui.halfCap(small), small);
    }

    /** 3 · (Recommendations mode) Discover what to watch: a row of sample posters, the second one focused. */
    private void recsScene(Canvas c) {
        labelAt(c, Ui.tr("RECOMENDADO PARA VOCÊ", "RECOMMENDED FOR YOU", "RECOMENDADO PARA TI"), 105, 3);
        fill.setShader(new LinearGradient(105, 0, 186, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
        r.set(105, 36, 186, 51);
        c.drawRoundRect(r, 7.5f, 7.5f, fill);
        fill.setShader(null);
        fill.setColor(0x33FFFFFF);
        for (int i = 0; i < 3; i++) {
            r.set(198 + i * 78, 36, 198 + i * 78 + 66, 51);
            c.drawRoundRect(r, 7.5f, 7.5f, fill);
        }
        String[] names = {Ui.tr("Luz do norte", "Northern light", "Luz del norte"), Ui.tr("A última aurora", "The last aurora", "La última aurora"),
                Ui.tr("Céu aberto", "Open sky", "Cielo abierto"), Ui.tr("Ondas", "Waves", "Ondas")};
        float[] fx = {0.3f, 0.45f, 0.6f, 0.75f};
        int[] src = {0, 3, 4, 1};
        for (int i = 0; i < 4; i++) {
            float x = 105 + i * (174 + 24), y = 72;
            r.set(x, y, x + 174, y + 252);
            boolean sel = i == 1;
            c.save();
            if (sel) {
                c.scale(1.06f, 1.06f, r.centerX(), r.centerY());
                shadow(c, r, 21, 24, 24, 0.5f);
                z.set(r.left - 4.5f, r.top - 4.5f, r.right + 4.5f, r.bottom + 4.5f);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(z, 25.5f, 25.5f, fill);
            }
            c.save();
            clip.rewind();
            clip.addRoundRect(r, 21, 21, Path.Direction.CW);
            c.clipPath(clip);
            fill.setColor(0xFF2A2333);
            c.drawRect(r, fill);
            cover(c, walls[src[i]], r, 1f, fx[i]);
            fill.setShader(new LinearGradient(0, r.top + r.height() * 0.45f, 0, r.bottom, 0x00171519, 0xE6171519, Shader.TileMode.CLAMP));
            c.drawRect(r, fill);
            fill.setShader(null);
            c.drawText(names[i], r.left + 15, r.bottom - 15 - 4, gloockS);
            c.restore();
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f);
            line.setColor(0x2EFFFFFF);
            z.set(r.left + 0.75f, r.top + 0.75f, r.right - 0.75f, r.bottom - 0.75f);
            c.drawRoundRect(z, 20, 20, line);
            line.setStyle(Paint.Style.FILL);
            c.restore();
        }
    }

    /** Alpha (0..1) of wallpaper `j` of 5 at phase `p` (0..1 over 15 s): the design's ph0..ph4 keyframes, with the wrap. */
    private static float wallAlpha(int j, float p) {
        float a = 0f;
        for (int w = -1; w <= 1; w++) {
            float pp = p + w, s = 0.2f * j - 0.02f;
            float v = pp < s ? 0 : pp < s + 0.04f ? (pp - s) / 0.04f : pp <= s + 0.18f ? 1 : pp < s + 0.22f ? 1 - (pp - s - 0.18f) / 0.04f : 0;
            a = Math.max(a, v);
        }
        return a;
    }

    /** 4 · Your TV, under a new light: the wallpapers carousel (a center card, neighbors, arrows, the name) in two modes. */
    private void wallScene(Canvas c, Context ctx, long ms) {
        float cyc = (ms % 6500) / 6500f;
        float modeA = cyc <= 0.46f ? 1 : cyc < 0.52f ? 1 - (cyc - 0.46f) / 0.06f : cyc < 0.96f ? 0 : (cyc - 0.96f) / 0.04f;
        // the two pills
        String a = Ui.tr("Papel de parede", "Wallpaper", "Fondo de pantalla"), b = Ui.tr("Protetor de tela", "Screensaver", "Protector de pantalla");
        pill.setTextSize(18);
        float wa = pill.measureText(a) + 36, wb = pill.measureText(b) + 36, x = 480 - (wa + 12 + wb) / 2;
        for (int i = 0; i < 2; i++) {
            float pw = i == 0 ? wa : wb, on = i == 0 ? modeA : 1 - modeA;
            r.set(x, 0, x + pw, 36);
            fill.setColor(blend(0x1AFFFFFF, 0xFFFFFFFF, on));
            c.drawRoundRect(r, 18, 18, fill);
            pill.setColor(blend(0xBFFFFFFF, Brand.INK, on));
            c.drawText(i == 0 ? a : b, r.centerX(), r.centerY() + Ui.halfCap(pill), pill);
            x += pw + 12;
        }
        pill.setColor(0xFFFFFFFF);
        pill.setTextSize(21);

        float p15 = (ms % 15000) / 15000f;
        float tp = (ms % 3000) / 3000f;
        float push = tp < 0.14f ? EASE.getInterpolation(tp / 0.14f) : 1f;
        float shiftX = 39 * (1 - push), content = 0.55f + 0.45f * push;
        // the strip, with its edges faded
        int lyr = c.saveLayer(0, 51, 960, 321, null);
        c.save();
        c.translate(shiftX, 51);
        int fade = c.saveLayerAlpha(-100, -10, 1100, 290, Math.round(255 * content));
        // neighbors
        z.set(-60, 63, 192, 63 + 142.5f);
        wallCard(c, z, 15, -1, p15, 0.85f, 0.2f);
        z.set(768, 63, 1020, 63 + 142.5f);
        wallCard(c, z, 15, 1, p15, 0.85f, 0.2f);
        // the center card: gradient border, the wallpaper and the mode on top
        RectF card = new RectF(240, -51 + 51, 720, 270);
        shadow(c, card, 24, 24, 24, 0.5f);
        gradientRing(c, card, 24, 1f);
        RectF in = new RectF(244.5f, 4.5f, 715.5f, 265.5f);
        c.save();
        clip.rewind();
        clip.addRoundRect(in, 19.5f, 19.5f, Path.Direction.CW);
        c.clipPath(clip);
        for (int j = 0; j < 5; j++) cover(c, walls[j], in, wallAlpha(j, p15), 0.5f);
        if (modeA > 0.01f) miniHome(c, in, modeA);
        if (modeA < 0.99f) miniSaver(c, in, 1 - modeA);
        c.restore();
        c.restoreToCount(fade);
        c.restore();
        // the edge fade (14 %)
        fill.setShader(new LinearGradient(0, 0, 960, 0, new int[] {0x00000000, 0xFF000000, 0xFF000000, 0x00000000}, new float[] {0f, 0.14f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        fill.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRect(0, 51, 960, 321, fill);
        fill.setXfermode(null);
        fill.setShader(null);
        c.restoreToCount(lyr);
        // the arrows
        arrow(c, 157.5f, 223.5f, -1);
        arrow(c, 802.5f, 223.5f, 1);
        // the name
        bold.setTextSize(25.5f);
        bold.setTextAlign(Paint.Align.CENTER);
        for (int j = 0; j < 5; j++) {
            float al = wallAlpha(j, p15);
            if (al <= 0.01f) continue;
            bold.setColor(Ui.alpha(0xFFFFFFFF, al));
            c.drawText(WALL_NAMES[j], 480, 327 + 19, bold);
        }
        bold.setTextSize(19.5f);
        bold.setTextAlign(Paint.Align.LEFT);
        bold.setColor(0xFFFFFFFF);
    }

    private static int blend(int from, int to, float t) {
        int a = Math.round(lerp(from >>> 24, to >>> 24, t)), rr = Math.round(lerp((from >> 16) & 255, (to >> 16) & 255, t));
        int g = Math.round(lerp((from >> 8) & 255, (to >> 8) & 255, t)), bb = Math.round(lerp(from & 255, to & 255, t));
        return (a << 24) | (rr << 16) | (g << 8) | bb;
    }

    /** A neighbor of the center card: the wallpaper before (side < 0) or after (side > 0) the one in the center. */
    private void wallCard(Canvas c, RectF box, float rad, int side, float p15, float alpha, float border) {
        c.save();
        clip.rewind();
        clip.addRoundRect(box, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        fill.setColor(0xFF2A2333);
        c.drawRect(box, fill);
        for (int j = 0; j < 5; j++) {
            float al = wallAlpha(j, p15);
            if (al > 0.01f) cover(c, walls[(j + (side < 0 ? 4 : 1)) % 5], box, al * alpha, 0.5f);
        }
        c.restore();
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f);
        line.setColor(Ui.alpha(0xFFFFFFFF, border * alpha));
        q.set(box.left + 0.75f, box.top + 0.75f, box.right - 0.75f, box.bottom - 0.75f);
        c.drawRoundRect(q, rad - 1, rad - 1, line);
        line.setStyle(Paint.Style.FILL);
    }

    private void arrow(Canvas c, float cx, float cy, int dir) {
        fill.setColor(0x99171519);
        c.drawCircle(cx, cy, 19.5f, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3.3f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFFFFFFFF);
        clip.rewind();
        clip.moveTo(cx - dir * 4, cy - 8);
        clip.lineTo(cx + dir * 4, cy);
        clip.lineTo(cx - dir * 4, cy + 8);
        c.drawPath(clip, line);
        line.setStrokeCap(Paint.Cap.BUTT);
        line.setStyle(Paint.Style.FILL);
    }

    private Paint hiP, greetP;

    /** The wallpaper mode on the center card: a little Home (date, greeting, three outline cards, the pinned apps on a dark strip). */
    private void miniHome(Canvas c, RectF b, float alpha) {
        if (hiP == null) {
            hiP = Ui.text(Ui.bold, 18, 0xFFFFFFFF);
            greetP = Ui.text(Ui.semibold, 10.5f, 0xD9FFFFFF);
        }
        int save = c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, Math.round(255 * alpha));
        float x = b.left, y = b.top;
        fill.setColor(0x99171519);
        r.set(b.centerX() - 63, y + 12, b.centerX() + 63, y + 30);
        c.drawRoundRect(r, 9, 9, fill);
        String name = Prefs.name();
        c.drawText(name.isEmpty() ? Ui.tr("Olá!", "Hi!", "¡Hola!") : Ui.tr("Olá, ", "Hi, ", "¡Hola, ") + name + "!", x + 27, y + 44, greetP);
        c.drawText(Ui.tr("O que você quer ver hoje?", "What do you want to watch today?", "¿Qué quieres ver hoy?"), x + 27, y + 64, hiP);
        float[][] cards = {{27, 84, 210, 87}, {246, 84, 78, 87}, {333, 84, 78, 87}};
        for (float[] cd : cards) {
            r.set(x + cd[0], y + cd[1], x + cd[0] + cd[2], y + cd[1] + cd[3]);
            fill.setColor(0x40171519);
            c.drawRoundRect(r, 9, 9, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f);
            line.setColor(0x59FFFFFF);
            z.set(r.left + 0.75f, r.top + 0.75f, r.right - 0.75f, r.bottom - 0.75f);
            c.drawRoundRect(z, 8.5f, 8.5f, line);
            line.setStyle(Paint.Style.FILL);
        }
        fill.setColor(0x8C171519);
        c.drawRect(x, b.bottom - 60, b.right, b.bottom, fill);
        int[] cols = {0xFFE50914, 0xFF7FB2FF, 0xFFB79CFE, 0xFFFE9565, 0xFF7ED6A8, 0xFFE99794, 0xFFF2C46D};
        for (int i = 0; i < 7; i++) {
            float tx = x + 27 + i * 61.5f, ty = b.bottom - 18 - 27;
            r.set(tx, ty, tx + 51, ty + 27);
            fill.setColor(0xD9FFFFFF);
            c.drawRoundRect(r, 6, 6, fill);
            fill.setColor(cols[i]);
            z.set(r.centerX() - 6, r.centerY() - 6, r.centerX() + 6, r.centerY() + 6);
            c.drawRoundRect(z, 3, 3, fill);
        }
        c.restoreToCount(save);
    }

    /** The screensaver mode: date and temperature, the big thin clock, and the "now playing" card. */
    private void miniSaver(Canvas c, RectF b, float alpha) {
        if (greetP == null) {
            hiP = Ui.text(Ui.bold, 18, 0xFFFFFFFF);
            greetP = Ui.text(Ui.semibold, 10.5f, 0xD9FFFFFF);
        }
        int save = c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, Math.round(255 * alpha));
        float x = b.left;
        greetP.setTextSize(12);
        String date = new SimpleDateFormat("EEEE, d MMMM", Ui.locale()).format(new Date());
        c.drawText(date + " · 18°", x + 30, b.bottom - 75, greetP);
        greetP.setTextSize(10.5f);
        String time = new SimpleDateFormat("HH:mm", Ui.locale()).format(new Date());
        c.drawText(time, x + 27, b.bottom - 22, clock);
        r.set(b.right - 21 - 174, b.bottom - 21 - 54, b.right - 21, b.bottom - 21);
        fill.setColor(0xB3171519);
        c.drawRoundRect(r, 10.5f, 10.5f, fill);
        z.set(r.left + 9, r.top + 9, r.left + 45, r.top + 45);
        fill.setShader(new LinearGradient(z.left, 0, z.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
        c.drawRoundRect(z, 6, 6, fill);
        fill.setShader(null);
        greetP.setColor(0xFFFFFFFF);
        c.drawText(Ui.tr("Título da música", "Song title", "Título de la canción"), r.left + 54, r.top + 25, greetP);
        greetP.setColor(0xD9FFFFFF);
        q.set(r.left + 54, r.top + 33, r.right - 9, r.top + 36);
        fill.setColor(0x40FFFFFF);
        c.drawRoundRect(q, 1.5f, 1.5f, fill);
        fill.setColor(0xFFFFFFFF);
        q.right = q.left + q.width() * 0.45f;
        c.drawRoundRect(q, 1.5f, 1.5f, fill);
        c.restoreToCount(save);
    }
}
