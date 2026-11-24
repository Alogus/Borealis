package io.borealis;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The default transition between screens ("Enfoque", see the design delivery): the screen below is captured once (sharp and
 * blurred), then it zooms a little (1 → 1.04), melts from sharp to blurred, a night veil comes over it and the new content
 * rises in. One curve for everything: cubic-bezier(0.22, 1, 0.36, 1). It never shows a dark frame: the first frame IS the
 * screen below, and the window is transparent (see Borealis.Settings).
 *
 * In 380 ms (coming in) / 260 ms (leaving):
 *   layers 1 and 2 (scale), 2 (alpha), veil (alpha): 380 ms in; leaving 208 ms after 52 ms
 *   content (alpha, 12 px up): 247 ms after 133 ms in; leaving 156 ms at once
 */
final class ScreenTransition {
    static final int IN_MS = 380, OUT_MS = 260, OUT_BG_MS = 208, OUT_BG_DELAY = 52;
    static final int CONTENT_IN_MS = 247, CONTENT_IN_DELAY = 133, CONTENT_OUT_MS = 156;
    static final float VEIL = 0.74f, ZOOM = 0.04f;
    private static final int BLUR_W = 192, BLUR_H = 108;

    private static volatile Bitmap sharp, blur;
    private static volatile long capturedAt;

    static PathInterpolator curve() {
        return new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    }

    /**
     * Leaving: the entry curve (fast start, long tail) made the veil and the blur vanish in the first ~40 ms, which felt like a
     * jump. Leaving uses the standard ease in-out instead (to confirm with design: the delivery asked for one curve).
     */
    static PathInterpolator curveOut() {
        return new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    }

    /**
     * Captures what is on screen (the window of `from`), then runs `go` (which starts the next screen). If the capture is not
     * possible or takes too long, `go` runs anyway and the next screen simply has no capture (it fades in by itself).
     */
    static void open(final Activity from, final Runnable go) {
        sharp = null;
        blur = null;
        HomeView.driftFreezeUntil = android.os.SystemClock.uptimeMillis() + 1500;   // the home's background holds still while it is captured
        View decor = from.getWindow() == null ? null : from.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT < 26 || decor == null || decor.getWidth() == 0 || decor.getHeight() == 0) {
            go.run();
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean fired = new AtomicBoolean();
        final Runnable once = () -> {
            if (fired.compareAndSet(false, true)) go.run();
        };
        main.postDelayed(once, 350);   // never wait long for it
        try {
            final Bitmap shot = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(from.getWindow(), shot, result -> {
                if (result == PixelCopy.SUCCESS && !fired.get()) {
                    blur = blurred(shot);
                    sharp = shot;
                    capturedAt = android.os.SystemClock.uptimeMillis();
                }
                main.removeCallbacks(once);
                once.run();
            }, main);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("transition capture: " + e.getMessage());
            main.removeCallbacks(once);
            once.run();
        }
    }

    /** Captures the window of `from` and hands the pictures (sharp, blurred) to `done` on the main thread; null when it can't. */
    static void capture(final Activity from, final java.util.function.Consumer<Bitmap[]> done) {
        HomeView.driftFreezeUntil = android.os.SystemClock.uptimeMillis() + 1500;
        View decor = from.getWindow() == null ? null : from.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT < 26 || decor == null || decor.getWidth() == 0 || decor.getHeight() == 0) {
            done.accept(null);
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean fired = new AtomicBoolean();
        final Runnable late = () -> {
            if (fired.compareAndSet(false, true)) done.accept(null);
        };
        main.postDelayed(late, 350);
        try {
            final Bitmap shot = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(from.getWindow(), shot, result -> {
                if (!fired.compareAndSet(false, true)) return;
                main.removeCallbacks(late);
                done.accept(result == PixelCopy.SUCCESS ? new Bitmap[] {shot, blurred(shot)} : null);
            }, main);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("transition capture: " + e.getMessage());
            main.removeCallbacks(late);
            if (fired.compareAndSet(false, true)) done.accept(null);
        }
    }

    /**
     * The same recipe for a screen that is a view laid over another (the keyboard, the weather location): it draws the layers
     * and says how visible its content is. The curve of coming in is the soft one, the one of leaving the standard one.
     */
    static final class Run {
        private final Bitmap sharp, blur;
        private final float veil;
        private final PathInterpolator in = curve(), out = curveOut();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();
        private long t0 = -1, exitAt = -1;
        private float q0, c0;

        Run(Bitmap[] caps, float veil) {
            sharp = caps[0];
            blur = caps[1];
            this.veil = veil;
        }

        private static float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        float q(long now) {
            if (t0 < 0) t0 = now;
            if (exitAt >= 0) return q0 * (1 - out.getInterpolation(clamp((now - exitAt - OUT_BG_DELAY) / (float) OUT_BG_MS)));
            return in.getInterpolation(clamp((now - t0) / (float) IN_MS));
        }

        /** How visible the content is (0..1): it rises in after 133 ms and fades out first when leaving. */
        float content(long now) {
            if (t0 < 0) t0 = now;
            if (exitAt >= 0) return c0 * (1 - out.getInterpolation(clamp((now - exitAt) / (float) CONTENT_OUT_MS)));
            return in.getInterpolation(clamp((now - t0 - CONTENT_IN_DELAY) / (float) CONTENT_IN_MS));
        }

        boolean entering(long now) {
            return exitAt < 0 && t0 >= 0 && now - t0 < IN_MS;
        }

        boolean leaving() {
            return exitAt >= 0;
        }

        boolean finished(long now) {
            return exitAt >= 0 && now - exitAt >= OUT_MS;
        }

        /** From wherever it is now. */
        void exit(long now) {
            if (exitAt >= 0) return;
            q0 = q(now);
            c0 = content(now);
            exitAt = now;
        }

        boolean moving(long now) {
            return t0 < 0 || entering(now) || (exitAt >= 0 && !finished(now));
        }

        void drawBackdrop(Canvas c, int w, int h, long now) {
            float q = q(now);
            dst.set(0, 0, w, h);
            c.save();
            float s = 1 + ZOOM * q;
            c.scale(s, s, w / 2f, h / 2f);
            paint.setAlpha(255);
            c.drawBitmap(sharp, null, dst, paint);
            if (q > 0.001f) {
                paint.setAlpha(Math.round(255 * q));
                c.drawBitmap(blur, null, dst, paint);
            }
            c.restore();
            if (q > 0.001f) c.drawColor(Color.argb(Math.round(255 * veil * q), 23, 21, 25));
        }
    }

    /** The captures made just before this screen was started (or null, null). Each can be taken once. */
    static Bitmap[] take() {
        Bitmap s = sharp, b = blur;
        sharp = null;
        blur = null;
        if (s == null || b == null || android.os.SystemClock.uptimeMillis() - capturedAt > 4000) return null;
        return new Bitmap[] {s, b};
    }

    /** Small and blurred, once: 14 px of blur on a 1280 px board is a sigma of ~2.4 px on 192. */
    static Bitmap blurred(Bitmap src) {
        Bitmap cur = src;
        while (cur.getWidth() / 2 >= BLUR_W) {
            Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        Bitmap small = Bitmap.createScaledBitmap(cur, BLUR_W, BLUR_H, true);
        if (cur != src && cur != small) cur.recycle();
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        int w = out.getWidth(), h = out.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        out.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(a, b, w, h, true);
            box(b, a, w, h, false);
        }
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    /** One box blur of radius 2 along a row (horizontal) or a column. */
    private static void box(int[] in, int[] out, int w, int h, boolean horizontal) {
        final int r = 2;
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int sa = 0, sr = 0, sg = 0, sb = 0, n = 0;
                for (int d = -r; d <= r; d++) {
                    int j = Math.max(0, Math.min(len - 1, i + d));
                    int p = horizontal ? in[l * w + j] : in[j * w + l];
                    sa += p >>> 24;
                    sr += (p >> 16) & 255;
                    sg += (p >> 8) & 255;
                    sb += p & 255;
                    n++;
                }
                int v = ((sa / n) << 24) | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                if (horizontal) out[l * w + i] = v; else out[i * w + l] = v;
            }
        }
    }

    /** Layers 1 to 3 behind the screen's content: the capture, the same blurred, and the night veil. q goes 0 → 1 coming in. */
    static final class Bg extends View {
        private Bitmap sharpPic, blurPic;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();
        private float q;

        Bg(android.content.Context c, Bitmap[] caps) {
            super(c);
            sharpPic = caps[0];
            blurPic = caps[1];
        }

        float q() {
            return q;
        }

        Bitmap[] pics() {
            return new Bitmap[] {sharpPic, blurPic};
        }

        void setPics(Bitmap[] caps) {
            sharpPic = caps[0];
            blurPic = caps[1];
            invalidate();
        }

        void q(float v) {
            q = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            dst.set(0, 0, w, h);
            float s = 1 + ZOOM * q;
            c.save();
            c.scale(s, s, w / 2f, h / 2f);
            paint.setAlpha(255);
            c.drawBitmap(sharpPic, null, dst, paint);
            if (q > 0.001f) {
                paint.setAlpha(Math.round(255 * q));
                c.drawBitmap(blurPic, null, dst, paint);
            }
            c.restore();
            if (q > 0.001f) c.drawColor(Color.argb(Math.round(255 * VEIL * q), 23, 21, 25));
        }
    }
}
