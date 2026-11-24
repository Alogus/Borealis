package io.borealis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * The opening of Borealis (brand manual): over the aurora background and the giant "curtains" at 10 %, the three lights rise
 * from the horizon one after another and Polaris shines once, under them the name, then the slogan fades up; a short hold,
 * then everything (background and logo together) fades out into the home screen. With "reduce motion" the logo only fades in.
 * It can be put in place "waiting" (solid night black, nothing moving) while the screen is off, so that when the TV wakes
 * the opening is already in front of the home screen; {@link #start()} then plays it. Any key skips it.
 */
final class SplashView extends View {
    private static final long HOLD_UNTIL = 2900, OUT_MS = 450;
    private static final long SLOGAN_AT = 900, SLOGAN_MS = 600, WELCOME_AT = 0, WELCOME_HOLD = 5400, WELCOME_TEXT_AT = 1900;   // the welcome opening: the logo, then the greeting under it, held long enough to read it
    private static final PathInterpolator CURVE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    interface Done { void onDone(SplashView v); }

    private long start = -1, skipAt = -1;
    private Done done;
    private boolean finished;
    private int w, h;
    private final boolean welcome, reduced;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean soundPlayed;
    private final long born = SystemClock.uptimeMillis();

    SplashView(Context c, boolean waiting) { this(c, waiting, false); }

    SplashView(Context c, boolean waiting, boolean welcome) {
        super(c);
        this.welcome = welcome;
        this.reduced = Logo.reducedMotion(c);
        Logo.wordmark(c);   // load the face now, not in the middle of the animation
        setFocusable(false);
        if (!waiting) { start = SystemClock.uptimeMillis(); playSound(); }
    }

    private void playSound() {
        if (soundPlayed) return;
        soundPlayed = true;
        final Context app = getContext().getApplicationContext();
        postDelayed(() -> StartupSound.play(app), welcome ? WELCOME_AT : 0);
    }

    void setDone(Done d) { done = d; }

    /** Plays it now (if it was waiting). */
    void start() {
        if (start < 0) { start = SystemClock.uptimeMillis(); playSound(); }
        invalidate();
    }

    boolean waiting() { return start < 0 && !finished; }

    /** Playing (not waiting, not over): it takes the keys. */
    boolean active() { return start >= 0 && !finished; }

    /** Playing for far longer than the opening lasts: it got stuck (its frames stopped), so it must not keep the keys. */
    boolean stuck() {
        if (finished || start < 0) return false;
        long limit = (welcome ? WELCOME_HOLD : HOLD_UNTIL) + OUT_MS + 2500;
        return SystemClock.uptimeMillis() - start > limit;
    }

    /** Waiting in front of the home for a long time. */
    long waitedMs() { return start < 0 && !finished ? SystemClock.uptimeMillis() - born : 0; }

    /** Any key: leave now (a short fade). */
    void skip() {
        if (start >= 0 && skipAt < 0) skipAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Take it away without playing it. */
    void dismiss() {
        finished = true;
        if (done != null) done.onDone(this);
    }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw;
        h = nh;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** The aurora background of the opening and the giant curtains (the three lights, cropped, at 10 %). */
    private void drawBack(Canvas c, float sc) {
        Logo.drawAurora(c, w, h, 0.30f, 0.32f, 0.22f);
        Logo.drawCurtains(c, 720 * sc, 40 * sc, 1100 * sc, 0.10f);
    }

    @Override protected void onDraw(Canvas c) {
        if (finished) return;
        if (w == 0) { postInvalidateOnAnimation(); return; }   // not measured yet: try again, never stop
        float sc = h / 720f;   // the design is 1280 x 720: one design pixel in this view
        if (start < 0) {   // waiting: just the background (the same as the first frame, so nothing jumps when it starts)
            drawBack(c, sc);
            return;
        }
        long now = SystemClock.uptimeMillis();
        long t = now - start;
        float out = skipAt >= 0 ? clamp((now - skipAt) / 180f) : clamp((t - (welcome ? WELCOME_HOLD : HOLD_UNTIL)) / (float) OUT_MS);
        if (out >= 1f) {
            dismiss();
            return;
        }
        // Everything is drawn on one layer that fades as a whole: the background and the logo leave together.
        int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * (1 - out)));
        drawBack(c, sc);

        long at = welcome ? WELCOME_AT : 0;
        float sym = 170 * sc * (welcome ? 0.78f : 1f);   // the symbol's height (design: 170)
        float blockH = sym * (1 + Logo.V_GAP + Logo.V_TEXT);
        float top = welcome ? h * 0.43f - blockH / 2f : 196 * sc;
        float bottom = Logo.drawVertical(c, getContext(), w / 2f, top, sym, Math.max(0, t - at), reduced, 1f);
        {   // the slogan under the logo, once the logo is in (the welcome opening holds it longer)
            float tp = clamp((t - (welcome ? WELCOME_TEXT_AT : SLOGAN_AT)) / (float) SLOGAN_MS), te = CURVE.getInterpolation(tp);
            text.setTypeface(Ui.medium);
            text.setTextSize(20 * sc);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0xFFFFFFFF);
            text.setAlpha(Math.round(189 * te));   // white at 74 %
            float lift = reduced ? 0f : (1 - te) * 8 * sc;
            c.drawText(Ui.tr("Sua TV, sob uma nova luz.", "Your TV, in a new light.", "Tu TV, bajo una nueva luz."), w / 2f,
                    bottom + 34 * sc - text.getFontMetrics().ascent + lift, text);
        }
        c.restoreToCount(layer);
        postInvalidateOnAnimation();
    }
}
