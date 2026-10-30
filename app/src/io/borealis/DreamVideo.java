package io.borealis;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.IOException;

/**
 * The screensaver with a video: a nebula in a loop (assets/aurora.mp4, 1080p, the end dissolves into the start so the
 * loop has no cut), with the clock, the date and the weather in the lower left and a small credit in the lower right.
 * The video is decoded by the TV's own chip (MediaPlayer on a TextureView), so it costs less than drawing; the text is
 * redrawn once a minute. Without the file (or if it cannot play) the dark background stays and the clock still shows.
 */
final class DreamVideo extends FrameLayout implements Dreamer, HomeData.Listener {
    /** The video picked in Settings (Prefs.screensaver()). */
    static String asset() { return Prefs.SAVER_FILES[Prefs.screensaver()]; }
    private final int index;                 // which screensaver (the picker previews the one in focus, not the saved one)
    private final boolean sample;            // inside the picker's TV: transparent, with a sample "now playing" box
    private String file() { return Prefs.SAVER_FILES[index]; }
    /** Own work (made with Adobe Firefly): no credit line. Put a text here to show one at the lower right. */
    private static final String CREDIT = "";

    private final HomeData data;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextureView texture;
    private final Overlay overlay;
    private final boolean image;                 // the pick is a wallpaper: a still with a very slow zoom, not a video
    private android.widget.ImageView still;
    private MediaPlayer player;
    private boolean running;
    private DreamInfo.Info info;
    private Runnable onExit;               // asks whoever shows the screensaver to close it

    /** True when the video is inside the app (otherwise the photo screensaver is used). */
    static boolean available(Context c) {
        return available(c, Prefs.screensaver());
    }

    static boolean available(Context c, int index) {
        String file = Prefs.SAVER_FILES[index];
        if (!file.endsWith(".mp4")) {
            try (java.io.InputStream in = c.getAssets().open(file)) {
                return in.read() >= 0;
            } catch (IOException e) {
                return false;
            }
        }
        try (AssetFileDescriptor f = c.getAssets().openFd(file)) {
            return f.getLength() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    DreamVideo(Context c) {
        this(c, Prefs.screensaver(), false);
    }

    DreamVideo(Context c, int index, boolean sample) {
        super(c);
        this.index = index;
        this.sample = sample;
        data = HomeData.get(c);
        image = !file().endsWith(".mp4");
        setBackgroundColor(sample ? 0 : 0xFF050509);
        texture = new TextureView(c);
        texture.setAlpha(0);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                if (running) play(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                release();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        if (image) {
            still = new android.widget.ImageView(c);
            still.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            still.setAlpha(0f);
            addView(still, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        } else {
            addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        overlay = new Overlay(c);
        if (sample) overlay.sampleTime = "-2:41";
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override public void start() {
        if (running) return;
        running = true;
        data.addListener(this);
        data.refreshIfStale();
        if (image) showStill();
        else if (texture.isAvailable()) play(texture.getSurfaceTexture());
        if (sample) {
            info = sampleInfo();
            overlay.setInfo(info);
        } else {
            refreshInfo();
        }
        overlay.invalidate();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, untilNextMinute());
        handler.removeCallbacks(infoTick);
        handler.postDelayed(infoTick, 10_000);
        handler.removeCallbacks(secondTick);
        handler.postDelayed(secondTick, 250);
    }

    /** What is playing (or was being watched), from the file the home screen's process keeps; redrawn only if it changed. */
    private void refreshInfo() {
        if (sample) return;
        DreamInfo.Info next = DreamInfo.read(getContext(), info);
        if (next != info) {
            info = next;
            overlay.setInfo(next);
        }
    }

    /** The picker's sample: a song at 1:30 of 4:00, standing still. */
    private DreamInfo.Info sampleInfo() {
        DreamInfo.Info i = new DreamInfo.Info();
        i.label = Ui.tr("Tocando agora", "Now playing", "Reproduciendo ahora");
        i.title = Ui.tr("Título da música", "Song title", "Título de la canción");
        i.subtitle = Ui.tr("Artista", "Artist", "Artista");
        i.progress = 0.375f;   // fixed: the sample does not count down (the overlay shows a fixed "-2:41")
        return i;
    }

    /** The picker hides the elements over the preview (↑ / →): the overlay fades, the picture stays. */
    void setElementsAlpha(float a) {
        if (Math.abs(overlay.getAlpha() - a) > 0.004f) overlay.setAlpha(a);
    }

    /** OK on the widget: opens the app that is playing (or was being watched). False if there is nothing to open. */
    boolean openCurrent() {
        if (info == null || info.pkg.isEmpty()) return false;
        DreamInfo.requestOpen(getContext(), info.pkg);          // the home screen opens it if the system refuses us here
        DreamInfo.launch(getContext(), info.pkg);
        return true;
    }

    void setExit(Runnable exit) { onExit = exit; }

    /** A song plays (or played in the last five minutes): the screensaver then stays until Home or Back; OK opens the app. */
    boolean controlMode() {
        return info != null && info.session && !info.pkg.isEmpty()
                && (info.playing || System.currentTimeMillis() - info.lastPlay < 5 * 60_000L);
    }

    /** Keys while a song plays: OK opens its app (and the screensaver ends). True if the key was used. */
    boolean onControlKey(android.view.KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!ok) return false;
        if (e.getAction() == android.view.KeyEvent.ACTION_UP && openCurrent() && onExit != null) onExit.run();
        return true;
    }

    private final Runnable infoTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshInfo();
            handler.postDelayed(this, controlMode() ? 4_000 : 10_000);
        }
    };

    /** While a song plays, the progress line and the countdown move four times a second (the line, a pixel at a time). */
    private final Runnable secondTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (info != null && info.playing && info.dur > 0) overlay.invalidate();
            handler.postDelayed(this, 250);
        }
    };

    @Override public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(infoTick);
        handler.removeCallbacks(secondTick);
        data.removeListener(this);
        release();
        releaseStill();
    }

    @Override public void onData() {
        overlay.invalidate();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            overlay.invalidate();
            handler.postDelayed(this, untilNextMinute());
        }
    };

    private static long untilNextMinute() {
        return 60_000 - System.currentTimeMillis() % 60_000 + 50;
    }

    private void play(SurfaceTexture st) {
        release();
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try (AssetFileDescriptor f = getContext().getAssets().openFd(file())) {
            mp.setSurface(new Surface(st));
            mp.setDataSource(f.getFileDescriptor(), f.getStartOffset(), f.getLength());
            mp.setLooping(true);
            mp.setVolume(0, 0);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setOnInfoListener((m, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player == m) {
                    texture.animate().alpha(1).setDuration(1500).start();   // from black, softly
                }
                return false;
            });
            mp.setOnErrorListener((m, what, extra) -> {
                AppLog.w("screensaver video error " + what + "/" + extra);
                release();
                return true;
            });
            mp.setOnPreparedListener(m -> {
                if (player == m && running) m.start();
            });
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            AppLog.w("screensaver video: " + e.getMessage());
            release();
        }
    }

    /** The wallpaper, fading in from black and moving slowly (see ZOOM_MS), so a still picture is never quite still. */
    private void showStill() {
        releaseStill();
        int sw = getResources().getDisplayMetrics().widthPixels;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        if (sw > 0 && sw < 1920) {
            o.inScaled = true;
            o.inDensity = 1920;
            o.inTargetDensity = sw;
        }
        try (java.io.InputStream in = getContext().getAssets().open(file())) {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in, null, o);
            if (b == null) return;
            still.setImageBitmap(b);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            AppLog.w("screensaver image: " + e.getMessage());
            return;
        }
        still.setScaleX(1f);
        still.setScaleY(1f);
        still.setAlpha(0f);
        still.animate().alpha(1).setDuration(1500).start();
        zoomStart = android.os.SystemClock.uptimeMillis();
        still.postOnAnimation(kenBurns);
    }

    /**
     * Motion of a still: it zooms in 30%, drifts toward a corner and turns 1.5 degrees, 22 s each way (then back, eased). The
     * same numbers drive the app, the picker's live preview and the wallpaper picker's sample, so all three move alike.
     */
    static final long ZOOM_MS = 22_000L;
    static final float ZOOM_AMOUNT = 0.30f, PAN_X = 0.05f, PAN_Y = 0.03f, TURN_DEGREES = 1.5f;

    /** 0..1: how far along the zoom is, eased, going in and then back out. */
    static float zoomPhase(long elapsedMs) {
        float t = (elapsedMs % (2 * ZOOM_MS)) / (float) ZOOM_MS, p = t <= 1 ? t : 2 - t;
        return p * p * (3 - 2 * p);
    }

    private long zoomStart;
    private final Runnable kenBurns = new Runnable() {
        @Override public void run() {
            if (!running || still == null) return;
            float p = zoomPhase(android.os.SystemClock.uptimeMillis() - zoomStart);
            still.setScaleX(1 + ZOOM_AMOUNT * p);
            still.setScaleY(1 + ZOOM_AMOUNT * p);
            still.setTranslationX(-PAN_X * still.getWidth() * p);
            still.setTranslationY(-PAN_Y * still.getHeight() * p);
            still.setRotation(TURN_DEGREES * p);
            still.postOnAnimation(this);
        }
    };

    private void releaseStill() {
        if (still != null) {
            still.removeCallbacks(kenBurns);
            still.animate().cancel();
            still.setImageDrawable(null);
            still.setAlpha(0f);
        }
    }

    private void release() {
        MediaPlayer mp = player;
        player = null;
        texture.animate().cancel();
        texture.setAlpha(0);
        if (mp == null) return;
        try {
            mp.setOnInfoListener(null);
            mp.setOnErrorListener(null);
            mp.setOnPreparedListener(null);
            mp.release();
        } catch (RuntimeException ignored) {
        }
    }

    /** Clock, date, weather and credit over the video (a soft dark band behind the text). */
    private static final class Overlay extends View {
        private final HomeData data;
        private final Paint scrim = new Paint();
        private Paint clockPaint, datePaint, weatherPaint, creditPaint, labelPaint, titlePaint, subPaint, barPaint, coverPaint;
        private final Paint topScrim = new Paint();
        private final android.graphics.RectF box = new android.graphics.RectF();
        private final android.graphics.Path clip = new android.graphics.Path();
        private DreamInfo.Info info;
        String sampleTime;     // the picker's sample: a fixed time left, without the real countdown
        private float k = 1;
        private Paint hintPaint, homePaint, timePaint;

        void setInfo(DreamInfo.Info i) {
            info = i;
            invalidate();
        }

        private boolean control() {
            return info != null && info.session && !info.pkg.isEmpty()
                    && (info.playing || System.currentTimeMillis() - info.lastPlay < 5 * 60_000L);
        }

        Overlay(Context c) {
            super(c);
            data = HomeData.get(c);
            setWillNotDraw(false);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            scrim.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0, 0xA6000000, android.graphics.Shader.TileMode.CLAMP));
            clockPaint = Ui.numbers(Ui.thin, 250 * k, 0xF2FFFFFF);
            clockPaint.setLetterSpacing(-0.01f);
            datePaint = Ui.numbers(Ui.semibold, 34 * k, 0xE6FFFFFF);
            weatherPaint = Ui.numbers(Ui.medium, 34 * k, 0xE6FFFFFF);
            creditPaint = Ui.text(Ui.regular, 19 * k, 0x80FFFFFF);
            creditPaint.setTextAlign(Paint.Align.RIGHT);
            labelPaint = Ui.text(Ui.semibold, 17 * k, 0xB3FFFFFF);
            labelPaint.setLetterSpacing(0.14f);
            titlePaint = Ui.text(Ui.semibold, 29 * k, 0xF2FFFFFF);
            subPaint = Ui.text(Ui.medium, 21 * k, 0xCCFFFFFF);
            barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            hintPaint = Ui.text(Ui.medium, 20 * k, 0xC7FFFFFF);
            homePaint = Ui.text(Ui.medium, 24 * k, 0xCCFFFFFF);
            timePaint = Ui.numbers(Ui.medium, 20 * k, 0xE6FFFFFF);
            timePaint.setTextAlign(Paint.Align.RIGHT);
        }

        /**
         * Lower right, in a dark rounded box: cover, "NOW PLAYING" / "CONTINUE WATCHING", title, detail, the progress line,
         * a hint ("Press OK to open Spotify") and, while a song can be controlled, previous / play-pause / next.
         */
        private void drawInfo(Canvas c, int w) {
            if (info == null || titlePaint == null) return;
            boolean hasApp = !info.pkg.isEmpty();
            float progress = info.progress;
            if (info.playing && info.dur > 0) {   // a song: the position moves on from when the file was written
                progress = Math.min(1f, (info.pos + (System.currentTimeMillis() - info.at)) / (float) info.dur);
            }
            float pad = 26 * k, cover = 100 * k, gap = 26 * k, textW = 430 * k, inner = cover + gap + textW;
            float boxW = pad + inner + pad, boxH = 2 * pad + cover;
            if (progress >= 0) boxH += 44 * k;
            if (hasApp) boxH += 34 * k;
            float right = w - 110 * k, bottom = getHeight() - 120 * k + 18 * k, left = right - boxW, top = bottom - boxH;
            box.set(left, top, right, bottom);
            barPaint.setShader(null);
            barPaint.setColor(Brand.ink(0xCC));          // the design system's night gray at 80%
            c.drawRoundRect(box, 30 * k, 30 * k, barPaint);
            float cx = left + pad, cy = top + pad;
            box.set(cx, cy, cx + cover, cy + cover);
            if (info.cover != null) {
                clip.reset();
                clip.addRoundRect(box, 18 * k, 18 * k, android.graphics.Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                c.drawBitmap(info.cover, null, box, coverPaint);
                c.restore();
            } else {
                barPaint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                        Brand.accents(), null, android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(box, 18 * k, 18 * k, barPaint);
                barPaint.setShader(null);
            }
            float tx = cx + cover + gap, top2 = cy;
            c.drawText(info.label.toUpperCase(Ui.locale()), tx, top2 + 24 * k, labelPaint);
            c.drawText(android.text.TextUtils.ellipsize(info.title, new android.text.TextPaint(titlePaint), textW, android.text.TextUtils.TruncateAt.END).toString(),
                    tx, top2 + 62 * k, titlePaint);
            if (!info.subtitle.isEmpty()) {
                c.drawText(android.text.TextUtils.ellipsize(info.subtitle, new android.text.TextPaint(subPaint), textW, android.text.TextUtils.TruncateAt.END).toString(),
                        tx, top2 + 94 * k, subPaint);
            }
            float y = cy + cover;
            if (progress >= 0) {
                // Full width under the cover: what was played in color (the focus gradient), what is left in white.
                float bh = 10 * k, bx = cx;
                y += 34 * k;
                if (info.playing && info.dur > 0) {   // the time left, counting down, at the top right of the line
                    long remainMs = Math.max(0, info.dur - info.pos - (System.currentTimeMillis() - info.at));
                    c.drawText(NowPlaying.remaining(remainMs), bx + inner, y - 10 * k, timePaint);
                } else if (sampleTime != null) {
                    c.drawText(sampleTime, bx + inner, y - 10 * k, timePaint);
                }
                box.set(bx, y, bx + inner, y + bh);
                barPaint.setColor(0xE6FFFFFF);
                c.drawRoundRect(box, bh / 2, bh / 2, barPaint);
                box.set(bx, y, bx + Math.max(bh, inner * Math.min(1f, progress)), y + bh);
                barPaint.setShader(new LinearGradient(bx, 0, bx + inner, 0, Brand.accents(), null, android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(box, bh / 2, bh / 2, barPaint);
                barPaint.setShader(null);
                y += bh;
            }
            if (hasApp) {
                String app = info.app.isEmpty() ? Ui.tr("o app", "the app", "la app") : info.app;
                String hint = Ui.tr("Pressione \u201COK\u201D para abrir ", "Press \u201COK\u201D to open ", "Presiona \u201COK\u201D para abrir ") + app;
                y += 24 * k;
                c.drawText(android.text.TextUtils.ellipsize(hint, new android.text.TextPaint(hintPaint), inner, android.text.TextUtils.TruncateAt.END).toString(),
                        cx, y + 10 * k, hintPaint);
                y += 10 * k;
            }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || clockPaint == null) return;
            c.drawRect(0, h * 0.5f, w, h, scrim);
            long now = System.currentTimeMillis();
            float x = 110 * k;
            String date = Ui.date(now);
            HomeData.Weather wx = data.weather;
            boolean sampleWx = sampleTime != null && Double.isNaN(wx.temp) && Prefs.weather();   // the picker's sample, before the weather arrives
            boolean hasWx = (!Double.isNaN(wx.temp) || sampleWx) && Prefs.weather();
            String symbol = hasWx ? (sampleWx ? "cloud" : Symbols.weather(wx.code, wx.isDay)) : null;
            float size = 34 * k, sw = hasWx ? Symbols.width(getContext(), symbol, size) : 0;
            String temp = hasWx ? (sampleWx ? "18°" : HomeData.degrees(wx.temp)) : "";
            float lineW = datePaint.measureText(date) + (hasWx ? 36 * k + sw + 12 * k + weatherPaint.measureText(temp) : 0);
            // The clock is sized so that it ends where the date and weather line ends.
            String time = Ui.clock(now);
            clockPaint.setTextSize(250 * k);
            float natural = clockPaint.measureText(time);
            clockPaint.setTextSize(Math.max(110 * k, Math.min(250 * k, 250 * k * lineW / Math.max(1, natural))));
            float clockBase = h - 120 * k;
            c.drawText(time, x - 6 * k, clockBase, clockPaint);
            float line = clockBase - Ui.cap(clockPaint) - 40 * k;
            c.drawText(date, x, line, datePaint);
            if (hasWx) {
                float wx0 = x + datePaint.measureText(date) + 36 * k;
                Symbols.draw(getContext(), c, symbol, wx0 + sw / 2, line - Ui.halfCap(weatherPaint), size, 0xE6FFFFFF);
                c.drawText(temp, wx0 + sw + 12 * k, line, weatherPaint);
            }
            if (control()) {   // the screensaver stays until Home: say so, under the clock
                String say = Ui.tr("Pressione \u201CHome\u201D para ir à tela principal", "Press \u201CHome\u201D to go to the main screen", "Presiona \u201CHome\u201D para ir a la pantalla principal");
                float iconW = Symbols.width(getContext(), "home", 24 * k), by = clockBase + 52 * k;
                Symbols.draw(getContext(), c, "home", x + iconW / 2, by - Ui.halfCap(homePaint), 24 * k, 0xCCFFFFFF);
                c.drawText(say, x + iconW + 12 * k, by, homePaint);
            }
            if (!CREDIT.isEmpty()) c.drawText(CREDIT, w - 60 * k, h - 44 * k, creditPaint);
            drawInfo(c, w);
        }
    }
}
