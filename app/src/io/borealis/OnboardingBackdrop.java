package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.io.InputStream;

/**
 * The welcome's background: its own picture (not the TV's wallpaper), drifting slowly exactly like the home screen's (an oversized picture
 * moved with a view transform, so nothing is redrawn). Drawn once; only its position changes every frame.
 */
final class OnboardingBackdrop extends View {
    private Bitmap wall;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            float t = SystemClock.uptimeMillis() / 1000f;   // the same clock as the home screen: no jump when the welcome ends
            int w = getWidth(), h = getHeight();
            setScaleX(1.12f);
            setScaleY(1.12f);
            setTranslationX((float) Math.sin(t * 0.24) * 0.045f * w);
            setTranslationY((float) Math.cos(t * 0.16) * 0.045f * h);
            postOnAnimation(this);
        }
    };

    OnboardingBackdrop(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        wall = image(c);
    }

    private static Bitmap cached;

    /** The welcome's own background (assets/onboarding.jpg, the one in the design), the same on every TV whatever wallpaper is chosen. */
    static Bitmap image(Context c) {
        if (cached != null) return cached;
        try (InputStream in = c.getAssets().open("onboarding.jpg")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            cached = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception | OutOfMemoryError ignored) {
            cached = null;
        }
        return cached;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        postOnAnimation(tick);
    }

    @Override protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Brand.INK);
        if (wall != null) {
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(wall, null, dst, paint);
        }
    }
}
