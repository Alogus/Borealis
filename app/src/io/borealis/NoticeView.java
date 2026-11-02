package io.borealis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

/**
 * A modal over the home screen, in the look of the welcome: title, text, an optional animated picture (ShelfDemo) and
 * one or two buttons. It is used for "What's new in this version" and for the update. It takes the remote while it is
 * open. The buttons' actions run after it has closed, so an action may open another one.
 */
final class NoticeView extends View {
    private static final float MW = 1200, MX = (1920 - MW) / 2f, RADIUS = 28, BUTTON_H = 62;

    private final boolean picture;
    private final String title, body, yes, no;
    private final Runnable onYes, onNo, onClose;
    private final Click click;
    private final ShelfDemo demo = new ShelfDemo();
    private final Spring appear = new Spring(0.45f, 0.9f, 0);
    private final Spring[] pop = {new Spring(0.32f, 0.8f, 1), new Spring(0.32f, 0.8f, 0)};
    /** An optional picture of three round icons with a caption each (for the news that has no animated demo). */
    private String[] artIcons, artLabels;
    private int[] artColors;
    private final Spring[] artPop = {new Spring(0.4f, 0.7f, 0), new Spring(0.4f, 0.7f, 0), new Spring(0.4f, 0.7f, 0)};
    private float artT;
    /** A big number with a line under it (the milestone), and a QR code with its text under it. */
    private String bigNumber, bigUnder;
    private String[] qrRows;
    private String qrText;
    /** A weather card that changes city (the weather location news): the pin, the city, a symbol and a temperature. */
    private boolean weatherPic;
    private final PlaceWidget widget = new PlaceWidget();

    /** The QR code of https://github.com/sponsors/Alogus (version 3, 29 × 29 modules; "1" is a dark module). */
    static final String[] SPONSOR_QR = {
            "11111110101010011010001111111",
            "10000010100000001101001000001",
            "10111010111000011010101011101",
            "10111010010110001101101011101",
            "10111010101110111101001011101",
            "10000010001010011010001000001",
            "11111110101010101010101111111",
            "00000000011101011001100000000",
            "10011111111111001101110010111",
            "01101101010001001111010110110",
            "00101010110001011000000110100",
            "01111000011011011101011101001",
            "00111010010111010001001100001",
            "10001000011101111000001111111",
            "00100110011111010111111010101",
            "11000000101110000011000110101",
            "11100010100000111001110001000",
            "10001000010101110101000010110",
            "11100011000000010011000011001",
            "11010100000000111001001001100",
            "11001010110011111110111111110",
            "00000000101000110110100011000",
            "11111110110100111001101011000",
            "10000010111110110101100010000",
            "10111010111101101101111111011",
            "10111010101001011010010000001",
            "10111010001110111010110110111",
            "10000010011111100000001101101",
            "11111110110100100001101010000"};
    private int focus;   // 0 = the main button, 1 = the other
    private boolean okDown, backDown, closed;
    private long lastNanos;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint titlePaint, buttonPaint;
    private TextPaint bodyPaint;
    private float paintK;
    private final RectF box = new RectF(), tmp = new RectF(), yesRect = new RectF(), noRect = new RectF();

    NoticeView(Context c, boolean picture, String title, String body, String yes, Runnable onYes, String no, Runnable onNo, Runnable onClose) {
        super(c);
        this.picture = picture;
        this.title = title;
        this.body = body;
        this.yes = yes;
        this.onYes = onYes;
        this.no = no;
        this.onNo = onNo;
        this.onClose = onClose;
        click = new Click(c);
        setContentDescription(title + ". " + body);
        setFocusable(false);
    }

    NoticeView art(String[] icons, String[] labels, int[] colors) {
        artIcons = icons;
        artLabels = labels;
        artColors = colors;
        return this;
    }

    NoticeView milestone(String number, String under) {
        bigNumber = number;
        bigUnder = under;
        return this;
    }

    NoticeView weather() {
        weatherPic = true;
        return this;
    }

    NoticeView qr(String[] rows, String text) {
        qrRows = rows;
        qrText = text;
        return this;
    }

    private void paints(float k) {
        if (paintK == k && titlePaint != null) return;
        paintK = k;
        titlePaint = Ui.text(Ui.displaySemibold, 44 * k, 0xFFFFFFFF);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        bodyPaint = new TextPaint(Ui.text(Ui.regular, 25 * k, 0xCCFFFFFF));
        buttonPaint = Ui.text(Ui.medium, 27 * k, 0xFFFFFFFF);
        buttonPaint.setTextAlign(Paint.Align.CENTER);
    }

    private StaticLayout layout(String text, Paint p, float width, int maxLines) {
        TextPaint tp = new TextPaint(p);
        tp.setTextAlign(Paint.Align.LEFT);   // the layout centers the lines itself; a centered paint would push them off to the right
        return StaticLayout.Builder.obtain(text, 0, text.length(), tp, Math.round(width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0, 1.12f).build();
    }

    private void finish(Runnable action) {
        if (closed) return;
        closed = true;
        if (onClose != null) onClose.run();
        if (action != null) action.run();
    }

    private void activate() {
        click.open();
        if (focus == 1 && no != null) finish(onNo);
        else finish(onYes);
    }

    /** The remote: Left/Right between the buttons, OK chooses, Back is the secondary answer (or the only one). */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && no != null) {
                    // The secondary button is at the left, the main one at the right.
                    int want = code == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : 0;
                    if (want != focus) { focus = want; click.move(); invalidate(); }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else if (backDown) {
                    backDown = false;
                    click.open();
                    finish(no != null ? onNo : onYes);
                }
                return true;
            default:
                return true;   // nothing else gets through to the screen behind
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        float k = w / 1920f;
        paints(k);
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        appear.target = 1;
        boolean moving = appear.step(dt);
        for (int i = 0; i < pop.length; i++) {
            pop[i].target = i == focus ? 1 : 0;
            moving |= pop[i].step(dt);
        }
        float a = Ui.clamp(appear.value);

        // A scrim over the home screen, then the panel (night, a little transparent, like the welcome's).
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, 0.55f * a));
        c.drawRect(0, 0, w, h, fill);
        float mh = picture ? 980 : weatherPic ? 800 : (artIcons != null || bigNumber != null) ? 720 : qrRows != null ? 780 : 520, my = (1080 - mh) / 2f;
        c.save();
        float s = 0.97f + 0.03f * a;
        c.scale(s, s, w / 2f, h / 2f);
        box.set(MX * k, my * k, (MX + MW) * k, (my + mh) * k);
        fill.setColor(Ui.alpha(Brand.ink(0xF2), a));
        c.drawRoundRect(box, RADIUS * k, RADIUS * k, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * k);
        line.setColor(Ui.alpha(0x33FFFFFF, a));
        tmp.set(box.left + k, box.top + k, box.right - k, box.bottom - k);
        c.drawRoundRect(tmp, RADIUS * k - k, RADIUS * k - k, line);
        line.setStyle(Paint.Style.FILL);

        int layer = a < 0.999f ? c.saveLayerAlpha(box.left, box.top, box.right, box.bottom, Math.round(255 * a)) : -1;
        float cx = w / 2f;
        StaticLayout t = layout(title, titlePaint, 1040 * k, 2);
        float y = (my + 64) * k;
        c.save();
        c.translate(cx - 520 * k, y);
        t.draw(c);
        c.restore();
        y += t.getHeight() + 18 * k;
        StaticLayout b = layout(body, bodyPaint, 1000 * k, 4);
        c.save();
        c.translate(cx - 500 * k, y);
        b.draw(c);
        c.restore();
        y += b.getHeight();

        if (picture) {
            float dw = 800 * k;
            tmp.set(cx - dw / 2, (my + 310) * k, cx + dw / 2, (my + 310) * k + 450 * k);
            demo.draw(c, getContext(), tmp);
            moving = true;
        }

        if (artIcons != null && !picture) {
            artT += dt;
            float gap3 = 300 * k, r = 66 * k, cy3 = (my + 440) * k;
            Paint lab = new Paint(buttonPaint);
            lab.setColor(0xDDFFFFFF);
            lab.setTextSize(26 * k);
            for (int i = 0; i < artIcons.length; i++) {
                // Each icon pops in a little after the previous one, then floats gently.
                artPop[i].target = artT > 0.12f * i + 0.15f ? 1 : 0;
                moving |= artPop[i].step(dt);
                float p = Ui.clamp(artPop[i].value), sc = 0.6f + 0.4f * p;
                float ix = cx + (i - (artIcons.length - 1) / 2f) * gap3;
                float iy = cy3 + (float) Math.sin(artT * 1.6f + i * 1.3f) * 5 * k;
                fill.setShader(null);
                fill.setColor(Ui.alpha(artColors[i], 0.22f * p * a));
                c.drawCircle(ix, iy, r * 1.35f * sc, fill);
                fill.setColor(Ui.alpha(artColors[i], p * a));
                c.drawCircle(ix, iy, r * sc, fill);
                Symbols.draw(getContext(), c, artIcons[i], ix, iy, 52 * k * sc, Ui.alpha(Brand.INK, p));
                lab.setAlpha(Math.round(221 * p * a));
                c.drawText(artLabels[i], ix, (my + 440 + 120) * k, lab);
            }
            moving = true;
        }

        if (bigNumber != null && !picture) {
            artT += dt;
            float p = Ui.clamp(artPop[0].value);
            artPop[0].target = artT > 0.15f ? 1 : 0;
            moving |= artPop[0].step(dt);
            p = Ui.clamp(artPop[0].value);
            Paint big = Ui.text(Logo.wordmark(getContext()), 200 * k * (0.85f + 0.15f * p), 0xFFFFFFFF);
            big.setTextAlign(Paint.Align.CENTER);
            float bw = big.measureText(bigNumber);
            big.setShader(new LinearGradient(cx - bw / 2, 0, cx + bw / 2, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
            big.setAlpha(Math.round(255 * p * a));
            c.drawText(bigNumber, cx, (my + 440) * k, big);
            Paint under = new Paint(buttonPaint);
            under.setColor(0xDDFFFFFF);
            under.setTextSize(36 * k);
            under.setAlpha(Math.round(221 * p * a));
            c.drawText(bigUnder, cx, (my + 500) * k, under);
            moving = true;
        }
        if (weatherPic && !picture) {
            widget.advance(dt);
            float cw = 560 * k, ch = 250 * k;
            tmp.set(cx - cw / 2, (my + 330) * k, cx + cw / 2, (my + 330) * k + ch);
            widget.draw(c, getContext(), tmp, a, null, null, null);
            moving = true;
        }
        if (qrRows != null && !picture) {
            float qs = 300 * k, qy = (my + 300) * k, mod = qs / (qrRows.length + 2);   // two modules of white around the code
            tmp.set(cx - qs / 2, qy, cx + qs / 2, qy + qs);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, a));
            c.drawRoundRect(tmp, 18 * k, 18 * k, fill);
            fill.setColor(Ui.alpha(0xFF171519, a));
            for (int row = 0; row < qrRows.length; row++) {
                for (int col = 0; col < qrRows[row].length(); col++) {
                    if (qrRows[row].charAt(col) != '1') continue;
                    float mx = cx - qs / 2 + (col + 1) * mod, my2 = qy + (row + 1) * mod;
                    c.drawRect(mx - 0.3f, my2 - 0.3f, mx + mod + 0.3f, my2 + mod + 0.3f, fill);
                }
            }
            Paint t2 = new Paint(buttonPaint);
            t2.setColor(0xDDFFFFFF);
            t2.setTextSize(28 * k);
            c.drawText(qrText, cx, qy + qs + 52 * k, t2);
        }

        // The buttons, centered: [secondary] [main].
        float by = (my + mh - 60 - BUTTON_H) * k, bh = BUTTON_H * k, gap = 24 * k;
        float yw = Math.max(260 * k, buttonPaint.measureText(yes) + 110 * k);
        float nw = no == null ? 0 : Math.max(220 * k, buttonPaint.measureText(no) + 110 * k);
        float total = yw + (no == null ? 0 : nw + gap), x = cx - total / 2;
        if (no != null) {
            noRect.set(x, by, x + nw, by + bh);
            x += nw + gap;
        }
        yesRect.set(x, by, x + yw, by + bh);
        if (no != null) drawButton(c, noRect, no, Ui.clamp(pop[1].value), false);
        drawButton(c, yesRect, yes, Ui.clamp(pop[0].value), true);
        if (layer >= 0) c.restoreToCount(layer);
        c.restore();
        if (moving) postInvalidateOnAnimation();
    }

    private void drawButton(Canvas c, RectF base, String label, float f, boolean main) {
        float sc = 1 + 0.05f * f;
        float cx = base.centerX(), cy = base.centerY();
        RectF r = new RectF(cx - base.width() * sc / 2, cy - base.height() * sc / 2, cx + base.width() * sc / 2, cy + base.height() * sc / 2);
        float rad = r.height() / 2;
        if (main) {
            fill.setShader(new LinearGradient(r.left, 0, r.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            buttonPaint.setColor(Brand.INK);
        } else {
            fill.setShader(null);
            fill.setColor(0x26FFFFFF);
            c.drawRoundRect(r, rad, rad, fill);
            buttonPaint.setColor(0xFFFFFFFF);
        }
        if (f > 0.01f) {
            float half = 2 * paintK;
            tmp.set(r.left + half, r.top + half, r.right - half, r.bottom - half);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4 * paintK);
            line.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawRoundRect(tmp, rad - half, rad - half, line);
            line.setStyle(Paint.Style.FILL);
        }
        c.drawText(label, r.centerX(), r.centerY() + Ui.halfCap(buttonPaint), buttonPaint);
    }
}
