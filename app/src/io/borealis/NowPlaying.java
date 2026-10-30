package io.borealis;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;

import java.util.ArrayList;
import java.util.List;

/**
 * The music (or any audio) that is playing now, for the player strip on the home screen: title, artist, cover and the
 * controls. MediaWatcher feeds it from the playback session; the controls go straight to that session, so the app
 * doesn't need to open. Lives in the :home process, with the home screen. Everything runs on the main thread.
 */
final class NowPlaying {
    interface Listener { void onNowPlaying(); }

    private static final NowPlaying INSTANCE = new NowPlaying();

    static NowPlaying get() { return INSTANCE; }

    private final List<Listener> listeners = new ArrayList<>();
    private MediaController controller;
    String pkg = "", title = "", artist = "";
    Bitmap cover;
    boolean playing;
    long lastPlayingAt;     // wall clock of the last time something was playing (the screensaver keeps its controls a while after a pause)
    long pos, dur, posAt;   // where the song is (extrapolated with position()) and how long it lasts; 0 = unknown

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** True while there is a session to show and control. */
    boolean visible() { return controller != null; }

    /** The session that is playing (or the last one that was); `cover` may be null. */
    void set(MediaController c, String title, String artist, Bitmap cover, boolean playing) {
        boolean same = controller != null && controller.getSessionToken().equals(c.getSessionToken());
        boolean sameTrack = same && safe(title).equals(this.title) && safe(artist).equals(this.artist);
        if (sameTrack && this.cover != null) cover = this.cover;    // already have this song's cover
        boolean unchanged = sameTrack && this.playing == playing && (cover == null || cover == this.cover);
        controller = c;
        pkg = c.getPackageName();
        this.title = safe(title);
        this.artist = safe(artist);
        if (cover != null || !sameTrack) this.cover = cover;
        this.playing = playing;
        if (playing) lastPlayingAt = System.currentTimeMillis();
        if (!unchanged) notifyListeners();
    }

    /** Where the session is now (no redraw: whoever shows it asks position() when it draws). */
    void setPosition(long pos, long dur) {
        this.pos = Math.max(0, pos);
        this.dur = Math.max(0, dur);
        posAt = android.os.SystemClock.elapsedRealtime();
    }

    /** "-2:35" (or "-1:02:35"): the time left, for the countdown. */
    static String remaining(long ms) {
        long sec = Math.max(0, ms / 1000);
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "-%d:%02d:%02d", h, m, s) : String.format(java.util.Locale.ROOT, "-%d:%02d", m, s);
    }

    long position() {
        return playing ? pos + (android.os.SystemClock.elapsedRealtime() - posAt) : pos;
    }

    /** The cover arrived later (the app gave an address). */
    void setCover(String pkg, Bitmap b) {
        if (controller == null || !controller.getPackageName().equals(pkg)) return;
        cover = b;
        notifyListeners();
    }

    /** The session is gone (or stopped for good). */
    void clear(MediaController c) {
        if (controller == null || (c != null && !controller.getSessionToken().equals(c.getSessionToken()))) return;
        controller = null;
        cover = null;
        playing = false;
        notifyListeners();
    }

    void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }

    void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    void toggle() {
        if (controller == null) return;
        if (playing) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onNowPlaying();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
