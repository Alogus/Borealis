package io.borealis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The opening of Borealis: the waves rise one after another over the night black, the star and dot pop in, the name
 * fades up under them, a short hold, then everything (background and logo together) fades out into the home screen.
 * It can be put in place "waiting" (solid night black, nothing moving) while the screen is off, so that when the TV wakes
 * the opening is already in front of the home screen; {@link #start()} then plays it. Any key skips it.
 */
final class SplashView extends View {
    private static final long RISE_AT = 150, RISE_MS = 950, STAR_AT = 900, NAME_AT = 750, NAME_MS = 550, HOLD_UNTIL = 2900, OUT_MS = 450;
    private static final long SLOGAN_AT = 1250, WELCOME_AT = 0, WELCOME_HOLD = 5400, WELCOME_TEXT_AT = 1900;   // the welcome opening: the logo, then the greeting under it, held long enough to read it

    interface Done { void onDone(SplashView v); }

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long start = -1, skipAt = -1;
    private Done done;
    private boolean finished;
    private int w, h;
    private final boolean welcome;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean soundPlayed;

    SplashView(Context c, boolean waiting) { this(c, waiting, false); }

    SplashView(Context c, boolean waiting, boolean welcome) {
        super(c);
        this.welcome = welcome;
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
        glow.setShader(new RadialGradient(w / 2f, h * 0.46f, Math.max(w, h) * 0.45f, 0x2EE99794, 0x00171519, Shader.TileMode.CLAMP));
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    @Override protected void onDraw(Canvas c) {
        if (finished || w == 0) return;
        if (start < 0) {   // waiting: just the night black
            c.drawColor(Brand.INK);
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
        c.drawColor(Brand.INK);
        c.drawRect(0, 0, w, h, glow);

        long at = welcome ? WELCOME_AT : 0;
        float u = h / 1000f * (welcome ? 0.78f : 1f);   // the logo's board is square; its content spans x 166-842, y 269-770
        float ox = w / 2f - 504 * u, oy = (welcome ? h * 0.43f : h / 2f) - 520 * u;
        {   // the slogan under the logo, once the logo is in (the welcome opening holds it longer, a bit higher)
            float tp = clamp((t - (welcome ? WELCOME_TEXT_AT : SLOGAN_AT)) / 800f), te = 1f - (1f - tp) * (1f - tp);
            text.setTypeface(Ui.displayMedium);
            text.setTextSize(h * 0.038f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0xFFFFFFFF);
            text.setAlpha(Math.round(235 * te));
            c.drawText(Ui.tr("Sua TV, sob uma nova luz.", "Your TV, in a new light.", "Tu TV, bajo una nueva luz."), w / 2f, h * (welcome ? 0.685f : 0.81f) + (1 - te) * 12f, text);
        }
        Logo.drawWaves(c, ox, oy, u, clamp((t - at - RISE_AT) / (float) RISE_MS));
        Logo.drawStar(c, ox, oy, u, clamp((t - at - STAR_AT) / 350f));
        float np = clamp((t - at - NAME_AT) / (float) NAME_MS);
        float ease = 1f - (1f - np) * (1f - np);
        Logo.drawName(c, getContext(), ox, oy, u, ease, (1 - ease) * 18f);
        c.restoreToCount(layer);
        postInvalidateOnAnimation();
    }
}
