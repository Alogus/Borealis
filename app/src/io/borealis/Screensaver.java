package io.borealis;

import android.service.dreams.DreamService;

/**
 * The Borealis screensaver (enabled in Borealis mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 */
public class Screensaver extends DreamService {
    private Dreamer view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(true);   // so OK can reach us; every other key, touch or mouse still exits (below)
        setFullscreen(true);
        setScreenBright(true);
        view = DreamVideo.available(this) ? new DreamVideo(this) : new DreamView(this);
        if (view instanceof DreamVideo) ((DreamVideo) view).setExit(this::finish);
        setContentView((android.view.View) view);
    }

    /** OK opens the app of the widget (video or music); any other key, or OK with nothing to open, exits like before. */
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int code = e.getKeyCode();
        if (view instanceof DreamVideo && ((DreamVideo) view).controlMode()) {   // a song plays: controls; it ends with Home or Back
            if (code == android.view.KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) finish();
                return true;
            }
            if (((DreamVideo) view).onControlKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        boolean ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (e.getAction() != android.view.KeyEvent.ACTION_UP) return true;   // act once, on release
        if (ok && view instanceof DreamVideo && ((DreamVideo) view).openCurrent()) {
            finish();
            return true;
        }
        finish();
        return true;
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        finish();
        return true;
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() != android.view.MotionEvent.ACTION_HOVER_EXIT) finish();
        return true;
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
