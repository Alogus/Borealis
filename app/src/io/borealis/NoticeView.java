package io.borealis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
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
    /** The celebration (the milestone): the number counts up inside a mini screen, with a sheen, sparkles, confetti and streamers. */
    private int bigValue;
    private String bigSep = ".";
    private final Confetti confetti = new Confetti();
    private boolean shot1, shot2;
    private final Spring punch = new Spring(0.45f, 0.3f, 0);
    private Paint numPaint, underPaint, sheenPaint;
    private float digitW, sepW;
    private LinearGradient unitShader, numShader, sheenShader;
    private RadialGradient glowLily, glowPeach;
    private final Matrix mat = new Matrix();
    private final Path star = new Path(), clipPath = new Path();
    private final RectF mini = new RectF();
    private final char[] one = new char[1];
    private String[] qrRows;
    private String qrText;
    /** The sponsor screen: a label over the link and three reasons (a title and a line each). */
    private String sponsorLabel;
    private String[] sponsorTitles, sponsorDescs;
    private float qrT;
    private final Spring[] spPop = {new Spring(0.4f, 0.7f, 0), new Spring(0.4f, 0.7f, 0), new Spring(0.4f, 0.7f, 0), new Spring(0.4f, 0.7f, 0)};
    private Paint rowTitle, rowDesc, labelPaint, urlPaint;
    private SweepGradient ringShader;
    private final Path heart = new Path();
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
    private final RectF box = new RectF(), tmp = new RectF(), tmp2 = new RectF(), yesRect = new RectF(), noRect = new RectF();

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

    NoticeView milestone(int value, String separator, String under) {
        bigValue = value;
        bigSep = separator;
        bigNumber = format(value);
        bigUnder = under;
        return this;
    }

    private String format(int v) {
        if (v < 1000) return String.valueOf(v);
        String r = String.valueOf(v % 1000);
        while (r.length() < 3) r = "0" + r;
        return (v / 1000) + bigSep + r;
    }

    NoticeView weather() {
        weatherPic = true;
        return this;
    }

    NoticeView sponsor(String label, String[] titles, String[] descs) {
        sponsorLabel = label;
        sponsorTitles = titles;
        sponsorDescs = descs;
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
        if (qrRows != null) {
            rowTitle = Ui.text(Ui.displaySemibold, 27 * k, 0xFFFFFFFF);
            rowDesc = Ui.text(Ui.regular, 21 * k, 0xB8FFFFFF);
            labelPaint = Ui.text(Ui.displaySemibold, 20 * k, 0x9EFFFFFF);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setLetterSpacing(0.2f);
            urlPaint = Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF);
            urlPaint.setTextAlign(Paint.Align.CENTER);
            ringShader = new SweepGradient(0, 0, new int[] {Brand.LILY, Brand.PEACH, Brand.MELON, Brand.LILY}, new float[] {0, 0.33f, 0.66f, 1});
            heart.rewind();
            heart.moveTo(0, 0.9f);
            heart.cubicTo(-1.2f, 0, -0.9f, -0.9f, 0, -0.35f);
            heart.cubicTo(0.9f, -0.9f, 1.2f, 0, 0, 0.9f);
            heart.close();
        }
        if (bigNumber != null || qrRows != null) {
            numPaint = Ui.text(Logo.wordmark(getContext()), 176 * k, 0xFFFFFFFF);
            numPaint.setTextAlign(Paint.Align.CENTER);
            float m = 0;
            for (char d = '0'; d <= '9' && bigNumber != null; d++) m = Math.max(m, numPaint.measureText(String.valueOf(d)));
            digitW = m * 1.02f;
            sepW = numPaint.measureText(bigSep) * 1.1f;
            underPaint = new Paint(buttonPaint);
            underPaint.setTextSize(38 * k);
            sheenPaint = new Paint(numPaint);
            unitShader = new LinearGradient(0, 0, 1, 0, Brand.accents(), null, Shader.TileMode.CLAMP);
            sheenShader = new LinearGradient(0, 0, 1, 0, new int[] {0x00FFFFFF, 0xF2FFFFFF, 0x00FFFFFF}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
            glowLily = new RadialGradient(0, 0, 1, new int[] {0x66B79CFE, 0x00B79CFE}, null, Shader.TileMode.CLAMP);
            glowPeach = new RadialGradient(0, 0, 1, new int[] {0x55FE9565, 0x00FE9565}, null, Shader.TileMode.CLAMP);
        }
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
        float mh = picture ? 980 : weatherPic ? 800 : bigNumber != null ? 940 : artIcons != null ? 720 : qrRows != null ? 900 : 520, my = (1080 - mh) / 2f;
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
        float titleCx = cx;
        if (qrRows != null && sponsorTitles != null) {   // a beating heart before the title, the two centered as one group
            float tw = titlePaint.measureText(title), tot = 72 * k + tw, hs = 20 * k * (1 + 0.1f * (float) Math.sin(qrT * 4.5f));
            titleCx = cx - tot / 2 + 72 * k + tw / 2;
            c.save();
            c.translate(cx - tot / 2 + 24 * k, y + 22 * k);
            c.scale(hs, hs);
            fill.setShader(unitShader);
            mat.setScale(2.4f, 1);
            mat.postTranslate(-1.2f, 0);
            unitShader.setLocalMatrix(mat);
            c.drawPath(heart, fill);
            fill.setShader(null);
            c.restore();
        }
        c.save();
        c.translate(titleCx - 520 * k, y);
        t.draw(c);
        c.restore();
        y += t.getHeight() + 18 * k;
        float bodyW = (qrRows != null && sponsorTitles != null ? 840 : 1000) * k;
        StaticLayout b = layout(body, bodyPaint, bodyW, 4);
        c.save();
        c.translate(cx - bodyW / 2, y);
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
            celebrate(c, k, cx, my, dt);
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
            qrT += dt;
            if (sponsorTitles == null) {
                plainQr(c, k, cx, my, a);
            } else {
                sponsorBody(c, k, cx, my, dt);
            }
            moving = true;
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
        if (bigNumber != null && !picture) {
            confetti.size(w, h);
            if (!shot1 && artT > 0.5f) { shot1 = true; confetti.cannons(90, 10); }
            if (!shot2 && artT > 2.7f) { shot2 = true; confetti.shower(110); confetti.cannons(80, 8); }
            confetti.step(dt);
            confetti.draw(c);
        }
        if (moving) postInvalidateOnAnimation();
    }

    // MARK: - The celebration

    private void grad(Paint p, float left, float width) {
        mat.setScale(width, 1);
        mat.postTranslate(left, 0);
        unitShader.setLocalMatrix(mat);
        p.setShader(unitShader);
    }

    /** Three mini screens in the look of the welcome (fanned, the middle one with the number), glow, count-up, sheen and sparkles. */
    private void celebrate(Canvas c, float k, float cx, float my, float dt) {
        artT += dt;
        float[] delay = {0.15f, 0.27f, 0.42f};   // left, right, middle
        float[] p = new float[3];
        for (int i = 0; i < 3; i++) {
            artPop[i].target = artT > delay[i] ? 1 : 0;
            artPop[i].step(dt);
            p[i] = Ui.clamp(artPop[i].value);
        }
        punch.step(dt);
        float cy = (my + 535) * k;

        // A glow behind the middle screen that breathes.
        float breath = 0.75f + 0.25f * (float) Math.sin(artT * 2.2f);
        float gr = 470 * k * breath;
        mat.setScale(gr, gr);
        mat.postTranslate(cx - 90 * k, cy - 40 * k);
        glowLily.setLocalMatrix(mat);
        fill.setShader(glowLily);
        fill.setAlpha(Math.round(255 * p[2]));
        c.drawCircle(cx - 90 * k, cy - 40 * k, gr, fill);
        mat.setScale(gr, gr);
        mat.postTranslate(cx + 110 * k, cy + 40 * k);
        glowPeach.setLocalMatrix(mat);
        fill.setShader(glowPeach);
        fill.setAlpha(Math.round(255 * p[2]));
        c.drawCircle(cx + 110 * k, cy + 40 * k, gr, fill);
        fill.setShader(null);
        fill.setAlpha(255);

        // Left and right screens, tilted, a little behind; the middle one on top.
        miniScreen(c, k, cx - 440 * k, cy + 34 * k, 380 * k, -7, 0, p[0]);
        miniScreen(c, k, cx + 440 * k, cy + 34 * k, 380 * k, 7, 2, p[1]);
        float lift = (float) Math.sin(artT * 1.3f) * 4 * k;
        miniScreen(c, k, cx, cy + lift, 640 * k, 0, 1, p[2]);

        // The number: fixed-width digits, so it counts up without wobbling.
        String fin = bigNumber;
        float t = Ui.clamp((artT - 0.95f) / 1.7f), e = 1 - (1 - t) * (1 - t) * (1 - t);
        String cur = format(Math.round(bigValue * e));
        int pad = fin.length() - cur.length();
        float fw = 0;
        for (int i = 0; i < fin.length(); i++) fw += Character.isDigit(fin.charAt(i)) ? digitW : sepW;
        float base = cy + lift + 40 * k;
        float sc = (0.88f + 0.12f * p[2]) * (1 + 0.12f * punch.value);
        c.save();
        c.translate(cx, base - 60 * k);
        c.scale(sc, sc);
        c.translate(-cx, -(base - 60 * k));
        float left = cx - fw / 2;
        grad(numPaint, left, fw);
        numPaint.setAlpha(Math.round(255 * p[2]));
        float x = left;
        for (int i = 0; i < fin.length(); i++) {
            float cw = Character.isDigit(fin.charAt(i)) ? digitW : sepW;
            if (i >= pad) {
                one[0] = cur.charAt(i - pad);
                c.drawText(one, 0, 1, x + cw / 2, base, numPaint);
            }
            x += cw;
        }
        // A band of light crossing the number every few seconds.
        float cyc = (artT - 2.6f) % 3.6f;
        if (artT > 2.6f && cyc < 1.2f) {
            float f = cyc / 1.2f, bw = 230 * k;
            mat.setScale(bw, 1);
            mat.postRotate(18);
            mat.postTranslate(left - bw + (fw + bw * 1.6f) * f, base - 150 * k);
            sheenShader.setLocalMatrix(mat);
            sheenPaint.setShader(sheenShader);
            sheenPaint.setAlpha(255);
            x = left;
            for (int i = 0; i < fin.length(); i++) {
                float cw = Character.isDigit(fin.charAt(i)) ? digitW : sepW;
                if (i >= pad) {
                    one[0] = cur.charAt(i - pad);
                    c.drawText(one, 0, 1, x + cw / 2, base, sheenPaint);
                }
                x += cw;
            }
        }
        underPaint.setColor(0xFFFFFFFF);
        underPaint.setAlpha(Math.round(222 * p[2]));
        c.drawText(bigUnder, cx, base + 78 * k, underPaint);

        // Sparkles around the number.
        if (artT > 1.0f) {
            star.rewind();
            star.moveTo(0, -1);
            star.quadTo(0, 0, 1, 0);
            star.quadTo(0, 0, 0, 1);
            star.quadTo(0, 0, -1, 0);
            star.quadTo(0, 0, 0, -1);
            star.close();
            float[] fx = {-0.58f, -0.34f, 0.04f, 0.40f, 0.60f, 0.24f, -0.46f, 0.52f};
            float[] fy = {-0.5f, -1.05f, -1.2f, -0.95f, -0.35f, 0.2f, 0.25f, -1.35f};
            float[] fs = {20, 14, 24, 16, 22, 12, 16, 12};
            fill.setShader(null);
            for (int i = 0; i < fx.length; i++) {
                float tw = (float) Math.sin(artT * 2.6f + i * 1.9f);
                if (tw <= 0) continue;
                float r = fs[i] * k * (0.4f + 0.6f * tw) * 1.5f;
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha(Math.round(255 * tw * tw * Ui.clamp((artT - 1.0f) * 2)));
                c.save();
                c.translate(cx + fx[i] * fw, base - 60 * k + fy[i] * 100 * k);
                c.scale(r, r);
                c.drawPath(star, fill);
                c.restore();
            }
            fill.setAlpha(255);
        }
        c.restore();
        if (artT >= 2.65f && !punchDone) {
            punchDone = true;
            punch.value = 1;
            punch.velocity = 0;
            punch.target = 0;
        }
    }
    private boolean punchDone;

    /** One mini screen (a sample of the welcome): `variant` 0 = welcome, 1 = home, 2 = choices. */
    private void miniScreen(Canvas c, float k, float mx, float my, float w, float deg, int variant, float p) {
        if (p <= 0.01f) return;
        float h = w * 9f / 16f, u = w / 640f;
        c.save();
        c.translate(mx, my + (1 - p) * 90 * k);
        c.rotate(deg * (0.3f + 0.7f * p));
        float s = 0.7f + 0.3f * p;
        c.scale(s, s);
        mini.set(-w / 2, -h / 2, w / 2, h / 2);
        float rr = 26 * u;
        // a soft shadow, then the body
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, 0.22f * p));
        tmp.set(mini.left - 8 * u, mini.top + 6 * u, mini.right + 8 * u, mini.bottom + 26 * u);
        c.drawRoundRect(tmp, rr + 8 * u, rr + 8 * u, fill);
        fill.setColor(Ui.alpha(0xFF211C2B, p));
        c.drawRoundRect(mini, rr, rr, fill);
        c.save();
        clipPath.rewind();
        clipPath.addRoundRect(mini, rr, rr, Path.Direction.CW);
        c.clipPath(clipPath);
        fill.setColor(Ui.alpha(Brand.LILY, 0.16f * p));
        c.drawCircle(mini.right - 40 * u, mini.top + 30 * u, 190 * u, fill);
        fill.setColor(Ui.alpha(Brand.MELON, 0.13f * p));
        c.drawCircle(mini.left + 60 * u, mini.bottom, 170 * u, fill);
        float L = mini.left, T = mini.top, W = mini.width(), H = mini.height();
        if (variant == 0) {
            // welcome: the avatar, a title, two lines and two buttons
            float ax = L + W / 2, ay = T + 112 * u;
            fill.setColor(Ui.alpha(0xFF2E2838, p));
            c.drawCircle(ax, ay, 52 * u, fill);
            grad(fill, ax - 56 * u, 112 * u);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(6 * u);
            fill.setAlpha(Math.round(255 * p));
            c.drawCircle(ax, ay, 56 * u, fill);
            fill.setStyle(Paint.Style.FILL);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.92f * p));
            tmp.set(ax - 130 * u, T + 196 * u, ax + 130 * u, T + 214 * u);
            c.drawRoundRect(tmp, 9 * u, 9 * u, fill);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.38f * p));
            tmp.set(ax - 170 * u, T + 232 * u, ax + 170 * u, T + 242 * u);
            c.drawRoundRect(tmp, 5 * u, 5 * u, fill);
            tmp.set(ax - 120 * u, T + 252 * u, ax + 120 * u, T + 262 * u);
            c.drawRoundRect(tmp, 5 * u, 5 * u, fill);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f * p));
            tmp.set(ax - 190 * u, T + H - 72 * u, ax - 20 * u, T + H - 28 * u);
            c.drawRoundRect(tmp, 22 * u, 22 * u, fill);
            tmp.set(ax + 10 * u, T + H - 72 * u, ax + 210 * u, T + H - 28 * u);
            grad(fill, tmp.left, tmp.width());
            fill.setAlpha(Math.round(255 * p));
            c.drawRoundRect(tmp, 22 * u, 22 * u, fill);
            fill.setShader(null);
        } else if (variant == 2) {
            // choices: a title and three options, the first one selected
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.92f * p));
            tmp.set(L + 40 * u, T + 40 * u, L + 300 * u, T + 58 * u);
            c.drawRoundRect(tmp, 9 * u, 9 * u, fill);
            int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON};
            for (int i = 0; i < 3; i++) {
                float ry = T + 98 * u + i * 82 * u;
                tmp.set(L + 40 * u, ry, L + W - 40 * u, ry + 66 * u);
                if (i == 0) {
                    fill.setColor(Ui.alpha(0xFF26212B, p));
                    c.drawRoundRect(tmp, 20 * u, 20 * u, fill);
                    grad(fill, tmp.left, tmp.width());
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(4 * u);
                    fill.setAlpha(Math.round(255 * p));
                    tmp2.set(tmp.left + 2 * u, tmp.top + 2 * u, tmp.right - 2 * u, tmp.bottom - 2 * u);
                    c.drawRoundRect(tmp2, 18 * u, 18 * u, fill);
                    fill.setStyle(Paint.Style.FILL);
                    fill.setShader(null);
                } else {
                    fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f * p));
                    c.drawRoundRect(tmp, 20 * u, 20 * u, fill);
                }
                fill.setColor(Ui.alpha(tint[i], 0.9f * p));
                c.drawCircle(tmp.left + 36 * u, tmp.centerY(), 15 * u, fill);
                fill.setColor(Ui.alpha(0xFFFFFFFF, (i == 0 ? 0.9f : 0.5f) * p));
                tmp2.set(tmp.left + 70 * u, tmp.centerY() - 7 * u, tmp.left + 250 * u, tmp.centerY() + 7 * u);
                c.drawRoundRect(tmp2, 7 * u, 7 * u, fill);
            }
        } else {
            // home: the greeting, the corner buttons (the number is the star, so nothing under it)
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.45f * p));
            tmp.set(L + 40 * u, T + 36 * u, L + 120 * u, T + 46 * u);
            c.drawRoundRect(tmp, 5 * u, 5 * u, fill);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.7f * p));
            tmp.set(L + 40 * u, T + 56 * u, L + 230 * u, T + 74 * u);
            c.drawRoundRect(tmp, 9 * u, 9 * u, fill);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.2f * p));
            for (int i = 0; i < 4; i++) c.drawCircle(L + W - 50 * u - i * 36 * u, T + 56 * u, 12 * u, fill);
        }
        c.restore();
        // the frame
        fill.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * u);
        line.setColor(Ui.alpha(0xFFFFFFFF, 0.26f * p));
        tmp.set(mini.left + u, mini.top + u, mini.right - u, mini.bottom - u);
        c.drawRoundRect(tmp, rr - u, rr - u, line);
        line.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** The first version of the QR screen: the code and its link (kept for a notice without the sponsor texts). */
    private void plainQr(Canvas c, float k, float cx, float my, float a) {
        float qs = 300 * k, qy = (my + 300) * k;
        drawCode(c, cx, qy, qs, a);
        Paint t2 = new Paint(buttonPaint);
        t2.setColor(0xDDFFFFFF);
        t2.setTextSize(28 * k);
        c.drawText(qrText, cx, qy + qs + 52 * k, t2);
    }

    private void drawCode(Canvas c, float cx, float qy, float qs, float a) {
        float mod = qs / (qrRows.length + 2);   // two modules of white around the code
        tmp.set(cx - qs / 2, qy, cx + qs / 2, qy + qs);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawRoundRect(tmp, qs * 0.08f, qs * 0.08f, fill);
        fill.setColor(Ui.alpha(0xFF171519, a));
        for (int row = 0; row < qrRows.length; row++) {
            for (int col = 0; col < qrRows[row].length(); col++) {
                if (qrRows[row].charAt(col) != '1') continue;
                float mx = cx - qs / 2 + (col + 1) * mod, my2 = qy + (row + 1) * mod;
                c.drawRect(mx - 0.3f, my2 - 0.3f, mx + mod + 0.3f, my2 + mod + 0.3f, fill);
            }
        }
    }

    /** The sponsor screen: the code with a turning gradient ring, a glow and rising hearts; three reasons at its right. */
    private void sponsorBody(Canvas c, float k, float cx, float my, float dt) {
        float[] delay = {0.12f, 0.28f, 0.40f, 0.52f};
        float[] p = new float[4];
        for (int i = 0; i < 4; i++) {
            spPop[i].target = qrT > delay[i] ? 1 : 0;
            spPop[i].step(dt);
            p[i] = Ui.clamp(spPop[i].value);
        }
        int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON};
        float qs = 300 * k, qcx = cx - 290 * k, qy = (my + 270) * k, qcy = qy + qs / 2;

        // the glow behind the code
        float breath = 0.8f + 0.2f * (float) Math.sin(qrT * 2.2f), gr = 330 * k;
        mat.setScale(gr, gr);
        mat.postTranslate(qcx, qcy);
        glowLily.setLocalMatrix(mat);
        fill.setShader(glowLily);
        fill.setAlpha(Math.round(255 * breath * p[0]));
        c.drawCircle(qcx, qcy, gr, fill);
        mat.setScale(gr * 0.8f, gr * 0.8f);
        mat.postTranslate(qcx, qcy + 40 * k);
        glowPeach.setLocalMatrix(mat);
        fill.setShader(glowPeach);
        fill.setAlpha(Math.round(200 * breath * p[0]));
        c.drawCircle(qcx, qcy + 40 * k, gr * 0.8f, fill);
        fill.setShader(null);
        fill.setAlpha(255);

        // hearts rising around the code
        for (int h = 0; h < 12; h++) {
            float ph = (qrT * 0.32f + h / 12f) % 1f;
            float hx = qcx + (float) Math.sin(h * 7.3) * qs * 0.62f + (float) Math.sin(qrT * 1.4f + h) * 14 * k;
            float hy = qy + qs + 30 * k - ph * (qs + 90 * k), sz = (8 + (h % 3) * 4) * k;
            fill.setColor(Ui.alpha(tint[h % 3], (float) Math.sin(Math.PI * ph) * 0.85f * p[0]));
            c.save();
            c.translate(hx, hy);
            c.scale(sz, sz);
            c.drawPath(heart, fill);
            c.restore();
        }

        // the code on a white card, inside a gradient ring that turns
        c.save();
        float sc = 0.9f + 0.1f * p[0];
        c.scale(sc, sc, qcx, qcy);
        mat.setRotate(qrT * 0.9f * 57.2958f);
        mat.postTranslate(qcx, qcy);
        ringShader.setLocalMatrix(mat);
        line.setShader(ringShader);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(8 * k);
        line.setAlpha(Math.round(255 * p[0]));
        tmp.set(qcx - qs / 2 - 18 * k, qy - 18 * k, qcx + qs / 2 + 18 * k, qy + qs + 18 * k);
        c.drawRoundRect(tmp, 42 * k, 42 * k, line);
        line.setShader(null);
        line.setStyle(Paint.Style.FILL);
        drawCode(c, qcx, qy, qs, p[0]);
        c.restore();

        // the label and the link, with room above the button
        labelPaint.setAlpha(Math.round(158 * p[0]));
        c.drawText(sponsorLabel, qcx, (my + 648) * k, labelPaint);
        urlPaint.setAlpha(Math.round(255 * p[0]));
        c.drawText(qrText, qcx, (my + 692) * k, urlPaint);

        // three reasons, aligned with the ring
        float x0 = cx + 30 * k, w = 520 * k, rh = 100 * k;
        for (int i = 0; i < 3; i++) {
            float pp = p[i + 1], ry = (my + 252) * k + i * (rh + 18 * k) + (1 - pp) * 30 * k, rx = x0 + (1 - pp) * 40 * k;
            tmp.set(rx, ry, rx + w, ry + rh);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.07f * pp));
            c.drawRoundRect(tmp, 26 * k, 26 * k, fill);
            float icx = rx + 56 * k, icy = ry + rh / 2;
            fill.setColor(Ui.alpha(tint[i], pp));
            c.drawCircle(icx, icy, 32 * k, fill);
            if (i == 0) {
                float pu = (1 + 0.1f * (float) Math.sin(qrT * 4.5f)) * 13 * k;
                c.save();
                c.translate(icx, icy + k);
                c.scale(pu, pu);
                fill.setColor(Ui.alpha(Brand.INK, pp));
                c.drawPath(heart, fill);
                c.restore();
            } else {
                Symbols.draw(getContext(), c, i == 1 ? "check" : "sparkles", icx, icy, 28 * k, Ui.alpha(Brand.INK, pp));
            }
            rowTitle.setAlpha(Math.round(255 * pp));
            c.drawText(sponsorTitles[i], rx + 110 * k, icy - 6 * k, rowTitle);
            rowDesc.setAlpha(Math.round(184 * pp));
            c.drawText(sponsorDescs[i], rx + 110 * k, icy + 28 * k, rowDesc);
        }
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
