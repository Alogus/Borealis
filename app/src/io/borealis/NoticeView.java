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
        float mh = picture ? 980 : 520, my = (1080 - mh) / 2f;
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
