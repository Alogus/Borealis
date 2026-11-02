package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.InputStream;

/**
 * Settings > Home screen > Weather location: a full screen over the settings, like the wallpaper picker. The wallpaper
 * blurred behind, a title, a line of text, the weather card (PlaceWidget), the two fields (PlaceForm) and the buttons
 * Back and Save (Save waits until country and city are both chosen). The Activity hands it the keys.
 */
final class PlaceScreen extends View {
    interface Done { void done(); }

    private static final float CARD_W = 504, CARD_H = 225, CARD_Y = 310, FORM_Y = 590, BUTTON_Y = 884, BUTTON_H = 62;

    private final Done onClose;
    private final PlaceForm form;
    private final PlaceWidget widget = new PlaceWidget();
    private final Click click;
    private final Spring appear = new Spring(0.45f, 0.9f, 0);
    private final Spring[] bpop = {new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 1)};
    private boolean inForm = true, okDown, backDown, closed;
    private int btn = 1;   // 0 = Back, 1 = Save
    private long lastNanos;
    private Bitmap wall;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), photo = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF(), tmp = new RectF(), backRect = new RectF(), saveRect = new RectF();
    private Paint titleP, buttonP;
    private TextPaint bodyP;
    private float paintK;

    PlaceScreen(Context c, Done onClose) {
        super(c);
        this.onClose = onClose;
        click = new Click(c);
        form = new PlaceForm(c, this::invalidate);
        setContentDescription(Ui.tr("Localização do clima", "Weather location", "Ubicación del clima"));
        setFocusable(false);
        // The wallpaper, really blurred (a small, soft copy stretched back up).
        try (InputStream in = c.getAssets().open(Prefs.WALLPAPER_FILES[Prefs.wallpaper()])) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap full = BitmapFactory.decodeStream(in, null, o);
            wall = full == null ? null : Backdrop.blurred(full);
            if (full != null) full.recycle();
        } catch (Exception | OutOfMemoryError ignored) {
            wall = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        form.release();
        click.release();
        super.onDetachedFromWindow();
    }

    private void close() {
        if (closed) return;
        closed = true;
        click.open();
        onClose.done();
    }

    private void press() {
        if (btn == 0) { close(); return; }
        if (!form.complete()) return;   // Save is off until both are chosen
        form.save();
        close();
    }

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (inForm && form.onKey(e)) {
            invalidate();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && inForm) { inForm = false; btn = form.complete() ? 1 : 0; click.move(); invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && !inForm) { inForm = true; form.focusLast(); click.move(); invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && !inForm && btn == 1) { btn = 0; click.move(); invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && !inForm && btn == 0) { btn = 1; click.move(); invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!inForm) {
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) press();
                        okDown = false;
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else if (backDown) {
                    backDown = false;
                    close();
                }
                return true;
            default:
                return true;   // nothing else gets through to the settings behind
        }
    }

    private void paints(float k) {
        if (paintK == k && titleP != null) return;
        paintK = k;
        titleP = Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF);
        titleP.setTextAlign(Paint.Align.CENTER);
        bodyP = new TextPaint(Ui.text(Ui.regular, 28 * k, 0xCCFFFFFF));
        buttonP = Ui.text(Ui.medium, 27 * k, 0xFFFFFFFF);
        buttonP.setTextAlign(Paint.Align.CENTER);
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
        float a = Ui.clamp(appear.value);
        bpop[0].target = !inForm && btn == 0 ? 1 : 0;
        bpop[1].target = !inForm && btn == 1 ? 1 : 0;
        moving |= bpop[0].step(dt) | bpop[1].step(dt);

        // The wallpaper, soft, under a dark veil.
        fill.setShader(null);
        fill.setColor(Brand.INK);
        c.drawRect(0, 0, w, h, fill);
        if (wall != null) {
            box.set(0, 0, w, h);
            c.drawBitmap(wall, null, box, photo);
        }
        fill.setColor(Ui.alpha(Brand.INK, 0.62f * a));
        c.drawRect(0, 0, w, h, fill);

        int layer = a < 0.999f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : -1;
        float cx = w / 2f;
        c.drawText(Ui.tr("Localização do clima", "Weather location", "Ubicación del clima"), cx, 136 * k, titleP);
        String body = Ui.tr("Escolha o país e a cidade para ver o clima e a hora do pôr do sol do seu lugar.",
                "Pick your country and city to see the weather and sunset time of your place.",
                "Elige tu país y tu ciudad para ver el clima y la hora del atardecer de tu lugar.");
        StaticLayout bl = StaticLayout.Builder.obtain(body, 0, body.length(), bodyP, Math.round(1000 * k))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0, 1.12f).build();
        c.save();
        c.translate(cx - 500 * k, 168 * k);
        bl.draw(c);
        c.restore();

        widget.advance(dt);
        tmp.set((1920 - CARD_W) / 2f * k, CARD_Y * k, (1920 + CARD_W) / 2f * k, (CARD_Y + CARD_H) * k);
        widget.draw(c, getContext(), tmp, 1f, form.widgetCity(), form.widgetSymbol(), form.widgetTemp());
        moving = true;

        moving |= form.draw(c, k, 960, FORM_Y, inForm, dt);

        // The buttons: Back at the left, Save at the right (off until both fields are chosen).
        float by = BUTTON_Y * k, bh = BUTTON_H * k, gap = 24 * k;
        float bw = Math.max(240 * k, buttonP.measureText(Ui.tr("Voltar", "Back", "Volver")) + 110 * k);
        String saveText = Ui.tr("Salvar", "Save", "Guardar");
        float sw = Math.max(280 * k, buttonP.measureText(saveText) + 110 * k);
        float x = cx - (bw + gap + sw) / 2;
        backRect.set(x, by, x + bw, by + bh);
        saveRect.set(x + bw + gap, by, x + bw + gap + sw, by + bh);
        drawButton(c, backRect, Ui.tr("Voltar", "Back", "Volver"), Ui.clamp(bpop[0].value), false, true);
        drawButton(c, saveRect, saveText, Ui.clamp(bpop[1].value), true, form.complete());

        if (layer >= 0) c.restoreToCount(layer);
        if (moving) postInvalidateOnAnimation();
    }

    private void drawButton(Canvas c, RectF base, String text, float f, boolean main, boolean enabled) {
        float sc = 1 + 0.05f * f;
        float cx = base.centerX(), cy = base.centerY();
        RectF r = new RectF(cx - base.width() * sc / 2, cy - base.height() * sc / 2, cx + base.width() * sc / 2, cy + base.height() * sc / 2);
        float rad = r.height() / 2;
        if (main && enabled) {
            fill.setShader(new LinearGradient(r.left, 0, r.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            buttonP.setColor(Brand.INK);
        } else {
            fill.setShader(null);
            fill.setColor(main ? 0x26FFFFFF : Ui.alpha(0xFFFFFFFF, 0.10f + 0.12f * f));
            c.drawRoundRect(r, rad, rad, fill);
            buttonP.setColor(main ? 0x66FFFFFF : 0xFFFFFFFF);
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
        c.drawText(text, r.centerX(), r.centerY() + Ui.halfCap(buttonP), buttonP);
    }
}
