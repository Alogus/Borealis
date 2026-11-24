package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import java.io.InputStream;

/**
 * The home screen's sample (the same one the wallpaper picker shows: the user's wallpaper, the capsule, the greeting,
 * the corner buttons, three empty cards and the bottom panel with the shelf) with a looping story on the shelf: hold OK
 * on an app (a ring fills), a menu opens (Move, Remove from shelf, Hide), and the app slides one place over with its
 * arrows. Drawn in a 1920 x 1080 design space, scaled to the 16:9 box it is given. It always animates (the caller keeps
 * invalidating while it is on screen). Nothing here uses layers or alpha groups, so the picture never flickers.
 */
final class ShelfDemo {
    private static final long LOOP = 7600;
    private static final String[] ICONS = {"play", "tv", "speaker", "hdmi", "wifi", "bluetooth", "settings"};
    private static final int[] TINTS = {Brand.LILY, Brand.PEACH, 0xFFFFFFFF, Brand.MELON, Brand.LILY, Brand.PEACH, Brand.MELON};
    // The geometry of the real home (HomeView): content from 96 to 1824, the shelf as a glass bar hugging 7 tiles (188 x 108, 27 apart, 40 of padding).
    private static final float MARGIN = 96, CLIP_R = 1824, TILE_W = 188, TILE_H = 108, TILE_Y = 875, GAP = 27, STEP = TILE_W + GAP;
    private static final float SL = (1920 - (7 * TILE_W + 6 * GAP)) / 2f;   // left edge of the first tile

    private static Bitmap wall;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF(), m = new RectF(), screen = new RectF();
    private final Path tri = new Path(), clip = new Path();
    private Paint capsule, greetSmall, greetBig, section, menuText, ok;

    private static float clamp(float x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    private static float span(long t, long from, long to) { return clamp((t - from) / (float) (to - from)); }

    private static float ease(float x) { return x * x * (3 - 2 * x); }

    /** The welcome's background (the same picture behind the welcome), loaded once. */
    private static void loadWall(Context c) {
        wall = OnboardingBackdrop.image(c);
    }

    private void pill(Canvas c, RectF box, int color) {
        fill.setShader(null);
        fill.setColor(color);
        c.drawRoundRect(box, box.height() / 2, box.height() / 2, fill);
    }

    void draw(Canvas c, Context ctx, RectF area) {
        if (capsule == null) {
            capsule = Ui.text(Ui.medium, 26, 0xFFFFFFFF);
            greetSmall = Ui.text(Ui.regular, 26, 0xCCFFFFFF);
            greetBig = Ui.text(Ui.displaySemibold, 52, 0xFFFFFFFF);
            section = Ui.text(Ui.semibold, 28, 0xFFFFFFFF);
            menuText = Ui.text(Ui.medium, 32, 0xFFFFFFFF);
            ok = Ui.text(Ui.semibold, 30, 0xFFFFFFFF);
            ok.setTextAlign(Paint.Align.CENTER);
        }
        loadWall(ctx);
        float s = area.width() / 1920f;
        long t = SystemClock.uptimeMillis() % LOOP;
        float slide = ease(span(t, 4700, 5500)) - ease(span(t, 6700, 7500));
        float ringA = ease(span(t, 0, 500));
        float holdP = span(t, 600, 2400);
        float okA = ringA * (1 - ease(span(t, 2300, 2600)));
        float menuA = ease(span(t, 2500, 2900)) * (1 - ease(span(t, 4400, 4700)));
        float arrowA = ease(span(t, 4600, 5000)) * (1 - ease(span(t, 6300, 6700)));

        c.save();
        c.translate(area.left, area.top);
        c.scale(s, s);
        screen.set(0, 0, 1920, 1080);
        clip.reset();
        clip.addRoundRect(screen, 56, 56, Path.Direction.CW);
        c.clipPath(clip);

        // The wallpaper in use (or the dark base while it loads).
        c.drawColor(Brand.INK);
        if (wall != null) c.drawBitmap(wall, null, screen, image);

        // The corner pill (time, date and weather) and the system button: only their silhouettes, like the other sample parts.
        r.set(1824 - 6 - 56 - 6 - 330, 46, 1824, 46 + 68);
        pill(c, r, Brand.ink(0xB3));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2);
        line.setColor(0x26FFFFFF);
        c.drawRoundRect(r, 34, 34, line);
        line.setStyle(Paint.Style.FILL);
        fill.setShader(null);
        fill.setColor(0x26FFFFFF);
        c.drawCircle(1824 - 6 - 28, 80, 28, fill);
        m.set(r.left + 28, 80 - 7, r.left + 28 + 150, 80 + 7);
        pill(c, m, 0x4DFFFFFF);

        // The avatar (round, white ring, level with the buttons), then the greeting beside it.
        float off = 0;
        String avatar = Avatars.current();
        if (!avatar.isEmpty()) {
            float ar = 44, acx = MARGIN + ar, acy = 147;
            Avatars.draw(c, ctx, avatar, acx, acy, ar, 1f);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3);
            line.setColor(0xFFFFFFFF);
            c.drawCircle(acx, acy, ar + 1.5f, line);
            line.setStyle(Paint.Style.FILL);
            off = 2 * ar + 26;
        }
        String name = Prefs.name();
        c.drawText(name.isEmpty() ? Ui.tr("Olá!", "Hi!", "¡Hola!") : Ui.tr("Olá, ", "Hi, ", "¡Hola, ") + name + "!", MARGIN + off, 118, greetSmall);
        c.drawText(Ui.tr("O que você quer assistir hoje?", "What do you want to watch today?", "¿Qué quieres ver hoy?"), MARGIN + off, 184, greetBig);

        // The three cards, empty (the real grid: one wide, two compact).
        float wide = (CLIP_R - MARGIN) - 2 * 336 - 2 * 36;
        float[] cardWs = {wide, 336, 336};
        int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON};
        float x0 = MARGIN;
        for (int i = 0; i < 3; i++) {
            r.set(x0, 296, x0 + cardWs[i], 716);
            fill.setShader(new LinearGradient(0, 296, 0, 716, Ui.alpha(tint[i], 0.55f), Brand.ink(0xE6), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 18, 18, fill);
            fill.setShader(null);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2);
            line.setColor(0x40FFFFFF);
            c.drawRoundRect(r, 18, 18, line);
            x0 += cardWs[i] + 36;
        }
        line.setStyle(Paint.Style.FILL);

        // The shelf: only the app cards (no bar behind them), like the new home.
        for (int i = 0; i < 7; i++) {
            float x = SL + i * STEP;
            if (i == 1) x += STEP * slide;
            else if (i == 2) x -= STEP * slide;
            r.set(x, TILE_Y, x + TILE_W, TILE_Y + TILE_H);
            fill.setColor(i == 1 ? 0xFF3C3448 : 0x26FFFFFF);
            c.drawRoundRect(r, 17, 17, fill);
            Symbols.draw(ctx, c, ICONS[i], r.centerX(), r.centerY(), 46, i == 1 ? 0xFFFFFFFF : TINTS[i]);
        }

        // The focus: the lily -> peach -> melon ring, on the second app (it travels with it).
        float fx = SL + STEP + STEP * slide, ring = 4.5f, gap = 3 + ring / 2;
        r.set(fx - gap, TILE_Y - gap, fx + TILE_W + gap, TILE_Y + TILE_H + gap);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(ring);
        line.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
        line.setAlpha(Math.round(255 * ringA));
        c.drawRoundRect(r, 17 + gap, 17 + gap, line);
        line.setShader(null);
        line.setStyle(Paint.Style.FILL);

        // The arrows beside the app while it moves.
        if (arrowA > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, arrowA));
            float cy = TILE_Y + TILE_H / 2f;
            tri.reset();
            tri.moveTo(fx - 22, cy - 22);
            tri.lineTo(fx - 22, cy + 22);
            tri.lineTo(fx - 54, cy);
            tri.close();
            c.drawPath(tri, fill);
            tri.reset();
            tri.moveTo(fx + TILE_W + 22, cy - 22);
            tri.lineTo(fx + TILE_W + 22, cy + 22);
            tri.lineTo(fx + TILE_W + 54, cy);
            tri.close();
            c.drawPath(tri, fill);
        }

        // "Hold OK": a ring that fills above the focused app.
        if (okA > 0.01f) {
            float cx = SL + STEP + TILE_W / 2f, cy = TILE_Y - 105;
            fill.setColor(Ui.alpha(0xFF000000, 0.45f * okA));
            c.drawCircle(cx, cy, 52, fill);
            r.set(cx - 52, cy - 52, cx + 52, cy + 52);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(9);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(Ui.alpha(0xFFFFFFFF, okA));
            c.drawArc(r, -90, 360 * holdP, false, line);
            line.setStrokeCap(Paint.Cap.BUTT);
            line.setStyle(Paint.Style.FILL);
            ok.setAlpha(Math.round(255 * okA));
            c.drawText("OK", cx, cy + 10, ok);
            ok.setAlpha(255);
        }

        // The menu that opens above the app (alpha applied color by color: no layers).
        if (menuA > 0.01f) {
            float mx = SL + STEP - 10, mw = 540, rowH = 84, mh = 3 * rowH + 24, my = TILE_Y - 30 - mh + (1 - menuA) * 20;
            m.set(mx, my, mx + mw, my + mh);
            fill.setColor(Ui.alpha(0xFF232027, 0.95f * menuA));
            c.drawRoundRect(m, 34, 34, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2);
            line.setColor(Ui.alpha(0xFFFFFFFF, 0.2f * menuA));
            c.drawRoundRect(m, 34, 34, line);
            line.setStyle(Paint.Style.FILL);
            String[] names = {Ui.tr("Mover", "Move", "Mover"), Ui.tr("Desafixar", "Unpin", "Dejar de fijar"), Ui.tr("Ocultar", "Hide", "Ocultar")};
            String[] sym = {"swap_horiz", "close", "visibility_off"};
            menuText.setAlpha(Math.round(255 * menuA));
            for (int i = 0; i < 3; i++) {
                float ry = my + 12 + i * rowH;
                if (i == 0) {
                    m.set(mx + 12, ry, mx + mw - 12, ry + rowH - 4);
                    fill.setColor(Ui.alpha(0xFFFFFFFF, 0.2f * menuA));
                    c.drawRoundRect(m, 26, 26, fill);
                }
                Symbols.draw(ctx, c, sym[i], mx + 52, ry + rowH / 2f - 2, 38, Ui.alpha(0xFFFFFFFF, menuA));
                c.drawText(names[i], mx + 100, ry + rowH / 2f - 2 + Ui.halfCap(menuText), menuText);
            }
            menuText.setAlpha(255);
        }

        // The edge of the sample: the same fine line the wallpaper pictures have.
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(4);
        line.setColor(0x40FFFFFF);
        c.drawRoundRect(screen, 56, 56, line);
        line.setStyle(Paint.Style.FILL);
        c.restore();
    }
}
