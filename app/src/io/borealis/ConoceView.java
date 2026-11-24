package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * "Get to know Borealis", over the real Home screen (blurred behind it). It first invites ("Shall we show you what you can do?");
 * "Show now" opens the four screens (apps at hand, made to measure, pick up where you left off, wallpapers). From Settings it opens
 * straight at the first screen. Closing from "Later" or "Skip the tour" says where to find it again. Designed in 1920 x 1080 units.
 */
final class ConoceView extends View {
    interface Host {
        /** The modal started to leave (the Home takes the keys back); `toast`: it says where to find this again. */
        void leaving();

        /** Everything is over (the toast too): remove the view. */
        void gone();
    }

    private static final float MX = 240, MY = 84, MW = 1440, MH = 912, RADIUS = 28;
    private static final float BUTTONS_Y = MY + 723, BUTTON_H = 78, LINK_Y = MY + 835, LINK_H = 44;
    private static final int INVITE = 0, TOUR = 1, OUT = 2;
    private static final long SLIDE_MS = 6500;

    private final Host host;
    private final ScreenTransition.Run run;
    private final TourDemo demo = new TourDemo();
    private final PathInterpolator in = ScreenTransition.curve(), out = ScreenTransition.curveOut();
    private int mode, tour, focus = 1;
    private long t0 = SystemClock.uptimeMillis(), panelAt = t0, tourAt = t0, closeAt, toastAt = -1;
    private boolean closing, auto;
    private float k = 1;
    private final Click click;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), tmp = new RectF(), tmp2 = new RectF();
    private final Path path = new Path();
    private Paint eyebrow, titleP, bodyP, subP, iconLabel, btnB, btnS, linkP, toastP, tourTitle, tourBody;
    private float paintK;
    private final java.util.Map<String, StaticLayout> layouts = new java.util.HashMap<>();

    ConoceView(Context c, Bitmap[] caps, boolean invitation, Host host) {
        super(c);
        this.host = host;
        click = new Click(c);
        run = caps == null ? null : new ScreenTransition.Run(caps, 0.6f);
        mode = invitation ? INVITE : TOUR;
        auto = !screenReaderOn(c);
        focus = 1;
        final Context app = c.getApplicationContext();
        new Thread(() -> {   // the wallpapers of the pictures
            TourDemo.preload(app);
            postInvalidate();
        }, "conoce-walls").start();
        setFocusable(false);
        setWillNotDraw(false);
    }

    @Override protected void onDetachedFromWindow() {
        click.release();
        super.onDetachedFromWindow();
    }

    private static boolean screenReaderOn(Context c) {
        try {
            android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && !am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        return false;
    }

    /** True while the modal holds the remote (not while only the toast is left). */
    boolean takesKeys() {
        return mode != OUT && !closing;
    }

    // MARK: - Texts

    private boolean recsMode() { return !Prefs.cardsMode().equals("recent"); }

    private String tourTitleText(int i) {
        switch (i) {
            case 0: return Ui.tr("Seus apps, à mão", "Your apps, at hand", "Tus apps, a la mano");
            case 1: return Ui.tr("Do seu jeito", "Made to measure", "A tu medida");
            case 2: return recsMode() ? Ui.tr("Descubra o que assistir", "Discover what to watch", "Descubre qué ver")
                    : Ui.tr("Retome de onde parou", "Pick up where you left off", "Retoma donde ibas");
            default: return Ui.tr("Sua TV, sob uma nova luz", "Your TV, in a new light", "Tu TV, bajo una nueva luz");
        }
    }

    private String tourBodyText(int i) {
        switch (i) {
            case 0: return Ui.tr("Com o “+” você fixa seus favoritos no início. Segure “OK” sobre um para movê-lo.",
                    "With the “+” you pin your favorites on the home screen. Hold “OK” on one to move it.",
                    "Con el “+” fijas tus favoritas en el inicio. Mantén “OK” sobre una para moverla.");
            case 1: return Ui.tr("Escolha quantos apps ver e a forma deles: ampla, equilibrada ou compacta. Em Ajustes › Tela principal.",
                    "Choose how many apps you see and their shape: wide, balanced or compact. In Settings › Home screen.",
                    "Elige cuántas apps ves y su forma: amplia, equilibrada o compacta. En Ajustes › Pantalla principal.");
            case 2: return recsMode() ? Ui.tr("O Borealis sugere filmes e séries dos serviços que você tem, sem contas nem cadastros.",
                    "Borealis suggests movies and shows from the services you have, with no accounts or sign-ups.",
                    "Borealis te sugiere películas y series de los servicios que tienes, sin cuentas ni registros.")
                    : Ui.tr("O Borealis mostra o que você deixou pela metade para continuar com “OK”, sem procurar.",
                    "Borealis shows what you left half-watched so you can go on with “OK”, no searching.",
                    "Borealis te muestra lo que dejaste a medias para que sigas con “OK”, sin buscarlo.");
            default: return Ui.tr("Escolha entre papéis de parede e protetores de tela pensados para a sua sala. Mude quando quiser em Ajustes › Aparência.",
                    "Choose between wallpapers and screensavers made for your living room. Change them any time in Settings › Appearance.",
                    "Elige entre fondos y protectores de pantalla pensados para tu sala. Cámbialos cuando quieras en Ajustes › Apariencia.");
        }
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        if (!takesKeys()) return false;
        if (down && e.getRepeatCount() > 0) return true;
        if (mode == INVITE) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) { focus = 0; click.move(); invalidate(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) { focus = 1; click.move(); invalidate(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!down) { if (focus == 0) later(); else startTour(); }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (!down) later();
                    return true;
                default:
                    return true;
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) next(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) prev();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && focus != 2) { focus = 2; click.move(); invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && focus == 2) { focus = 1; click.move(); invalidate(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) {
                    if (focus == 0) prev();
                    else if (focus == 2) skip();
                    else next(true);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) { if (tour > 0) prev(); else skip(); }
                return true;
            default:
                return true;
        }
    }

    private void startTour() {
        mode = TOUR;
        tour = 0;
        focus = 1;
        panelAt = tourAt = SystemClock.uptimeMillis();
        click.open();
        announce();
        invalidate();
    }

    private void next(boolean manual) {
        if (tour < 3) {
            tour++;
            tourAt = SystemClock.uptimeMillis();
            if (manual) click.open();
            announce();
            invalidate();
        } else {
            close(false);
        }
    }

    private void prev() {
        if (tour == 0) return;
        tour--;
        tourAt = SystemClock.uptimeMillis();
        click.move();
        announce();
        invalidate();
    }

    private void announce() {
        announceForAccessibility(Ui.tr("Conheça o Borealis, ", "Get to know Borealis, ", "Conoce Borealis, ") + (tour + 1) + Ui.tr(" de 4. ", " of 4. ") + tourTitleText(tour) + ". " + tourBodyText(tour));
    }

    private void later() {
        click.move();
        close(true);
    }

    private void skip() {
        click.move();
        close(true);
    }

    private void close(boolean toast) {
        if (closing) return;
        closing = true;
        long now = SystemClock.uptimeMillis();
        closeAt = now;
        if (run != null) run.exit(now);
        toastAt = toast ? now + 200 : -1;
        host.leaving();
        invalidate();
    }

    // MARK: - Drawing

    private void paints() {
        if (paintK == k && titleP != null) return;
        paintK = k;
        layouts.clear();
        eyebrow = Ui.text(Ui.bold, 19.5f * k, Brand.LILY);
        eyebrow.setLetterSpacing(0.1f);
        eyebrow.setTextAlign(Paint.Align.CENTER);
        titleP = Ui.text(Logo.wordmark(getContext()), 60 * k, 0xFFFFFFFF);
        titleP.setTextAlign(Paint.Align.CENTER);
        subP = Ui.text(Ui.medium, 28.5f * k, 0xD6FFFFFF);
        subP.setTextAlign(Paint.Align.CENTER);
        iconLabel = Ui.text(Ui.semibold, 21 * k, 0xDBFFFFFF);
        iconLabel.setTextAlign(Paint.Align.CENTER);
        bodyP = Ui.text(Ui.medium, 22.5f * k, 0xA8FFFFFF);
        bodyP.setTextAlign(Paint.Align.CENTER);
        btnB = Ui.text(Ui.bold, 27 * k, 0xFFFFFFFF);
        btnB.setTextAlign(Paint.Align.CENTER);
        btnS = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
        btnS.setTextAlign(Paint.Align.CENTER);
        linkP = Ui.text(Ui.semibold, 25.5f * k, 0xB3FFFFFF);
        linkP.setTextAlign(Paint.Align.CENTER);
        toastP = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        tourTitle = Ui.text(Logo.wordmark(getContext()), 63 * k, 0xFFFFFFFF);
        tourTitle.setTextAlign(Paint.Align.CENTER);
        tourBody = Ui.text(Ui.medium, 28.5f * k, 0xD6FFFFFF);
    }

    private StaticLayout wrapped(String text, float width, Paint p, int maxLines) {
        String key = text + "|" + Math.round(width) + "|" + p.getTextSize();
        StaticLayout l = layouts.get(key);
        if (l == null) {
            l = StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(p), Math.round(width))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(maxLines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0, 1.12f).build();
            if (layouts.size() > 24) layouts.clear();
            layouts.put(key, l);
        }
        return l;
    }

    private Shader gradient(RectF r) {
        return new LinearGradient(r.left, 0, r.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP);
    }

    private void ring(Canvas c, RectF r, float radius, float width, float alpha, boolean white) {
        float half = width / 2;
        tmp2.set(r.left + half, r.top + half, r.right - half, r.bottom - half);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(width);
        line.setShader(white ? null : gradient(tmp2));
        line.setColor(Ui.alpha(0xFFFFFFFF, Math.min(1, alpha)));
        c.drawRoundRect(tmp2, Math.max(0, radius - half), Math.max(0, radius - half), line);
        line.setShader(null);
        line.setStyle(Paint.Style.FILL);
    }

    /** A button like the onboarding's: primary = brand gradient (white ring when focused), secondary = white 8 % with the outline. */
    private void button(Canvas c, RectF r, String text, boolean primary, boolean focused, float alpha) {
        float rad = r.height() / 2;
        fill.setShader(null);
        if (primary) {
            fill.setShader(gradient(r));
            fill.setColor(Ui.alpha(0xFFFFFFFF, alpha));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            if (focused) ring(c, r, rad, 4.5f * k, alpha, true);
            btnB.setColor(Ui.alpha(Brand.INK, alpha));
            c.drawText(text, r.centerX(), r.centerY() + Ui.halfCap(btnB), btnB);
        } else {
            fill.setColor(Ui.alpha(0xFFFFFFFF, (focused ? 0.22f : 0.08f) * alpha));
            c.drawRoundRect(r, rad, rad, fill);
            if (focused) ring(c, r, rad, 4.5f * k, alpha, false);
            else {
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(2 * k);
                line.setColor(Ui.alpha(0x4DFFFFFF, alpha));
                tmp2.set(r.left + k, r.top + k, r.right - k, r.bottom - k);
                c.drawRoundRect(tmp2, rad - k, rad - k, line);
                line.setStyle(Paint.Style.FILL);
            }
            btnS.setColor(Ui.alpha(0xFFFFFFFF, (focused ? 1f : 0.88f) * alpha));
            c.drawText(text, r.centerX(), r.centerY() + Ui.halfCap(btnS), btnS);
        }
    }

    private void shadow(Canvas c, RectF b, float rad, float alpha) {
        for (int i = 7; i >= 1; i--) {
            float g = 40 * k * i / 7f;
            tmp2.set(b.left - g, b.top - g + 30 * k, b.right + g, b.bottom + g + 30 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, alpha / 7f));
            c.drawRoundRect(tmp2, rad + g, rad + g, fill);
        }
    }

    private void panel(Canvas c, RectF b, float rad, float fillAlpha, float alpha) {
        shadow(c, b, rad, 0.5f * alpha);
        fill.setShader(null);
        fill.setColor(Ui.alpha(Brand.ink(Math.round(255 * fillAlpha)), alpha));
        c.drawRoundRect(b, rad, rad, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * k);
        line.setColor(Ui.alpha(0x29FFFFFF, alpha));
        tmp2.set(b.left + 0.75f * k, b.top + 0.75f * k, b.right - 0.75f * k, b.bottom - 0.75f * k);
        c.drawRoundRect(tmp2, rad - k, rad - k, line);
        line.setStyle(Paint.Style.FILL);
    }

    private float cv(PathInterpolator p, float x) {
        return p.getInterpolation(Math.max(0, Math.min(1, x)));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        k = w / 1920f;
        paints();
        long now = SystemClock.uptimeMillis();
        boolean moving = true;
        if (run != null) {
            run.drawBackdrop(c, w, h, now);
            if (closing && run.finished(now)) mode = OUT;
        } else {
            float q = closing ? 1 - cv(out, (now - closeAt) / 260f) : cv(in, (now - t0) / 380f);
            c.drawColor(Ui.alpha(Brand.ink(0xFF), 0.88f * q));
            if (closing && now - closeAt >= 260) mode = OUT;
        }
        float exit = closing ? 1 - cv(out, (now - closeAt) / 156f) : 1f;
        if (mode != OUT && exit > 0.003f) {
            if (mode == INVITE) drawInvite(c, now, exit);
            else drawTour(c, now, exit);
        }
        boolean toastOn = toastAt >= 0 && drawToast(c, w, h, now);
        if (mode == OUT && !toastOn) {
            host.gone();
            return;
        }
        moving = true;
        if (mode == TOUR && !closing && auto && now - tourAt >= SLIDE_MS) next(false);
        if (moving) postInvalidateOnAnimation();
    }

    private boolean drawToast(Canvas c, int w, int h, long now) {
        long t = now - toastAt;
        if (t < 0) return true;
        if (t > 3300) {
            toastAt = -1;
            return false;
        }
        float e = t < 420 ? cv(in, t / 420f) : t > 3000 ? 1 - cv(out, (t - 3000) / 300f) : 1f;
        String msg = Ui.tr("Você encontra em Ajustes › Preferências › Conheça o Borealis.", "You find it in Settings › Preferences › Get to know Borealis.",
                "Lo encuentras en Ajustes › Preferencias › Conoce Borealis.");
        float pw = toastP.measureText(msg) + 2 * 33 * k + 15 * k + 15 * k, ph = 72 * k;
        float x = w / 2f - pw / 2, y = h - 60 * k - ph + (t < 420 ? 12 * k * (1 - e) : 0);
        tmp.set(x, y, x + pw, y + ph);
        fill.setShader(null);
        fill.setColor(Ui.alpha(Brand.ink(0xE0), e));
        c.drawRoundRect(tmp, ph / 2, ph / 2, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * k);
        line.setColor(Ui.alpha(0x29FFFFFF, e));
        tmp2.set(tmp.left + 0.75f * k, tmp.top + 0.75f * k, tmp.right - 0.75f * k, tmp.bottom - 0.75f * k);
        c.drawRoundRect(tmp2, ph / 2 - k, ph / 2 - k, line);
        line.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.alpha(Brand.LILY, e));
        c.drawCircle(x + 33 * k + 7.5f * k, y + ph / 2, 7.5f * k, fill);
        toastP.setColor(Ui.alpha(0xFFFFFFFF, e));
        c.drawText(msg, x + 33 * k + 15 * k + 15 * k, y + ph / 2 + Ui.halfCap(toastP), toastP);
        return true;
    }

    // MARK: - The invitation

    private void drawInvite(Canvas c, long now, float exit) {
        float e = cv(in, (now - panelAt - 60) / 420f) * exit;
        float pw = 1140 * k, ph = 690 * k, px = (getWidth() - pw) / 2f, py = (getHeight() - ph) / 2f + 12 * k * (1 - cv(in, (now - panelAt - 60) / 420f));
        box.set(px, py, px + pw, py + ph);
        panel(c, box, 42 * k, 0.82f, e);
        float cx = box.centerX();
        eyebrow.setColor(Ui.alpha(Brand.LILY, e));
        c.drawText(Ui.tr("CONHEÇA O BOREALIS", "GET TO KNOW BOREALIS", "CONOCE BOREALIS"), cx, py + 72 * k, eyebrow);
        titleP.setColor(Ui.alpha(0xFFFFFFFF, e));
        float size = titleP.getTextSize(), maxW = pw - 120 * k, tw = titleP.measureText(titleText());
        if (tw > maxW) titleP.setTextSize(size * maxW / tw);
        c.drawText(titleText(), cx, py + 143 * k, titleP);
        titleP.setTextSize(size);
        subP.setColor(Ui.alpha(0xD6FFFFFF, e));
        c.drawText(Ui.tr("São 4 coisas e leva menos de um minuto.", "It is 4 things and takes under a minute.", "Son 4 cosas y toma menos de un minuto."), cx, py + 197 * k, subP);
        // four icons with their names
        String[][] names = {{Ui.tr("Seus apps,", "Your apps,", "Tus apps,"), Ui.tr("à mão", "at hand", "a la mano")},
                {Ui.tr("Do seu", "Made to", "A tu"), Ui.tr("jeito", "measure", "medida")},
                {Ui.tr("Retome de", "Pick up where", "Retoma"), Ui.tr("onde parou", "you left off", "donde ibas")},
                {Ui.tr("Papéis de parede", "Wallpapers and", "Fondos y"), Ui.tr("e protetores", "screensavers", "protectores")}};
        int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON, Brand.LILY};
        float colW = 180 * k, gap = 33 * k, x0 = cx - (4 * colW + 3 * gap) / 2;
        for (int i = 0; i < 4; i++) {
            float ccx = x0 + i * (colW + gap) + colW / 2, ccy = py + 255 * k + 37.5f * k;
            fill.setShader(null);
            fill.setColor(Ui.alpha(tint[i], e));
            c.drawCircle(ccx, ccy, 37.5f * k, fill);
            icon(c, i, ccx, ccy, 1.5f * k, e);
            iconLabel.setColor(Ui.alpha(0xDBFFFFFF, e));
            c.drawText(names[i][0], ccx, py + 255 * k + 75 * k + 12 * k + 19 * k, iconLabel);
            c.drawText(names[i][1], ccx, py + 255 * k + 75 * k + 12 * k + 19 * k + 26 * k, iconLabel);
        }
        // buttons: "Later" and "Show now" (the focus)
        float bw = 279 * k, bh = 78 * k, bg = 33 * k, by = py + ph - 123 * k - bh, bx = cx - (2 * bw + bg) / 2;
        tmp.set(bx, by, bx + bw, by + bh);
        button(c, tmp, Ui.tr("Mais tarde", "Later", "Más tarde"), false, focus == 0, e);
        tmp.set(bx + bw + bg, by, bx + 2 * bw + bg, by + bh);
        button(c, tmp, Ui.tr("Ver agora", "Show me", "Ver ahora"), true, focus == 1, e);
        bodyP.setColor(Ui.alpha(0xA8FFFFFF, e));
        c.drawText(Ui.tr("Você pode ver quando quiser em Ajustes › Preferências.", "You can see it any time in Settings › Preferences.",
                "Puedes verlo cuando quieras en Ajustes › Preferencias."), cx, py + ph - 51 * k - 4 * k, bodyP);
    }

    private String titleText() {
        return Ui.tr("Mostramos o que você pode fazer?", "Shall we show you what you can do?", "¿Te mostramos lo que puedes hacer?");
    }

    /** The four icons of the invitation, in Night, in a 24-unit box scaled by `u`. */
    private void icon(Canvas c, int i, float cx, float cy, float u, float alpha) {
        int ink = Ui.alpha(Brand.INK, alpha);
        fill.setShader(null);
        fill.setColor(ink);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * u);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(ink);
        float ox = cx - 12 * u, oy = cy - 12 * u;
        switch (i) {
            case 0:   // three squares and a plus
                for (float[] s : new float[][] {{3.5f, 3.5f}, {13.5f, 3.5f}, {3.5f, 13.5f}}) {
                    tmp2.set(ox + s[0] * u, oy + s[1] * u, ox + (s[0] + 7) * u, oy + (s[1] + 7) * u);
                    c.drawRoundRect(tmp2, 2 * u, 2 * u, line);
                }
                c.drawLine(ox + 17 * u, oy + 14 * u, ox + 17 * u, oy + 20 * u, line);
                c.drawLine(ox + 14 * u, oy + 17 * u, ox + 20 * u, oy + 17 * u, line);
                break;
            case 1:   // a grid of dots
                for (int row = 0; row < 3; row++)
                    for (int col = 0; col < 3; col++) c.drawCircle(ox + (6 + col * 6) * u, oy + (6 + row * 6) * u, 2.2f * u, fill);
                break;
            case 2:   // play
                path.rewind();
                path.moveTo(ox + 8 * u, oy + 5 * u);
                path.lineTo(ox + 8 * u, oy + 19 * u);
                path.lineTo(ox + 19 * u, oy + 12 * u);
                path.close();
                c.drawPath(path, fill);
                break;
            default:   // the sparkle
                path.rewind();
                path.moveTo(ox + 12 * u, oy + 2 * u);
                path.lineTo(ox + 14.2f * u, oy + 9.8f * u);
                path.lineTo(ox + 22 * u, oy + 12 * u);
                path.lineTo(ox + 14.2f * u, oy + 14.2f * u);
                path.lineTo(ox + 12 * u, oy + 22 * u);
                path.lineTo(ox + 9.8f * u, oy + 14.2f * u);
                path.lineTo(ox + 2 * u, oy + 12 * u);
                path.lineTo(ox + 9.8f * u, oy + 9.8f * u);
                path.close();
                c.drawPath(path, fill);
                break;
        }
        line.setStrokeCap(Paint.Cap.BUTT);
        line.setStyle(Paint.Style.FILL);
    }

    // MARK: - The four screens

    private void drawTour(Canvas c, long now, float exit) {
        float pe = cv(in, (now - panelAt) / 420f) * exit;
        float top = MY * k, cx = getWidth() / 2f;
        box.set(MX * k, top, (MX + MW) * k, (MY + MH) * k);
        c.save();
        c.translate(0, 12 * k * (1 - cv(in, (now - panelAt) / 420f)));
        panel(c, box, RADIUS * k, 0.78f, pe);
        // the four segments
        float segW = 180 * k, segH = 9 * k, segGap = 12 * k, sx = cx - (4 * segW + 3 * segGap) / 2, sy = top + 42 * k;
        for (int i = 0; i < 4; i++) {
            tmp.set(sx + i * (segW + segGap), sy, sx + i * (segW + segGap) + segW, sy + segH);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x38FFFFFF, pe));
            c.drawRoundRect(tmp, segH / 2, segH / 2, fill);
            float f = i < tour ? 1f : i == tour ? (auto ? Math.min(1f, (now - tourAt) / (float) SLIDE_MS) : 1f) : 0f;
            if (f > 0.005f) {
                tmp2.set(tmp.left, tmp.top, tmp.left + segW * f, tmp.bottom);
                fill.setShader(new LinearGradient(sx, 0, sx + 4 * segW + 3 * segGap, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
                fill.setColor(Ui.alpha(0xFFFFFFFF, pe));
                c.drawRoundRect(tmp2, segH / 2, segH / 2, fill);
                fill.setShader(null);
            }
        }
        eyebrow.setColor(Ui.alpha(Brand.LILY, pe));
        c.drawText(Ui.tr("CONHEÇA O BOREALIS · " + (tour + 1) + " DE 4", "GET TO KNOW BOREALIS · " + (tour + 1) + " OF 4", "CONOCE BOREALIS · " + (tour + 1) + " DE 4"),
                cx, top + 90 * k, eyebrow);
        // the screen: it rises 12 px with a fade each time
        float e = cv(in, (now - tourAt) / 420f) * pe;
        int layer = c.saveLayerAlpha(MX * k, top + 100 * k, (MX + MW) * k, top + 680 * k, Math.round(255 * e));
        c.translate(0, 12 * k * (1 - cv(in, (now - tourAt) / 420f)));
        c.drawText(tourTitleText(tour), cx, top + 164 * k, tourTitle);
        StaticLayout l = wrapped(tourBodyText(tour), 990 * k, tourBody, 3);
        c.save();
        c.translate(cx - 495 * k, top + 190 * k);
        l.draw(c);
        c.restore();
        tmp.set(MX * k + 240 * k, top + 294 * k, MX * k + 240 * k + 960 * k, top + 294 * k + 360 * k);
        demo.draw(c, getContext(), tmp, tour, now - tourAt, recsMode());
        c.restoreToCount(layer);
        c.restore();
        // the buttons
        float bw = 279 * k, bh = BUTTON_H * k, gap = 20 * k, bx = cx - (2 * bw + gap) / 2, by = BUTTONS_Y * k;
        tmp.set(bx, by, bx + bw, by + bh);
        button(c, tmp, Ui.tr("Voltar", "Back", "Atrás"), false, focus == 0, pe);
        tmp.set(bx + bw + gap, by, bx + 2 * bw + gap, by + bh);
        button(c, tmp, tour == 3 ? Ui.tr("Pronto", "Done", "Listo") : Ui.tr("Seguinte", "Next", "Siguiente"), true, focus == 1, pe);
        // "Skip the tour"
        float lw = 150 * k;
        tmp.set(cx - lw, LINK_Y * k, cx + lw, (LINK_Y + LINK_H) * k);
        float rad = tmp.height() / 2;
        if (focus == 2) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x1FFFFFFF, pe));
            c.drawRoundRect(tmp, rad, rad, fill);
            ring(c, tmp, rad, 3 * k, pe, false);
        }
        linkP.setColor(Ui.alpha(focus == 2 ? 0xFFFFFFFF : 0xB3FFFFFF, pe));
        c.drawText(Ui.tr("Pular passeio", "Skip the tour", "Saltar recorrido"), cx, tmp.centerY() + Ui.halfCap(linkP), linkP);
    }
}
