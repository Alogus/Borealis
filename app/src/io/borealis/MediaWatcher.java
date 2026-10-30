package io.borealis;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the playback sessions of the other apps (what is playing and how far it got). It needs the "Notification
 * access" permission only because Android ties session reading to it; it never reads a notification. What it sees
 * stays on the TV: video goes to Watched (the "Seguir viendo" cards) and music to NowPlaying (the player strip).
 * Runs in the :home process, next to the home screen.
 */
public final class MediaWatcher extends NotificationListenerService {
    private static final long SAMPLE_MS = 30_000;
    /** Video apps: never treated as music even when they report an "artist" (YouTube reports the channel there). */
    private static final String[] VIDEO_APPS = {"netflix", "disney", "hulu", "amazon", "primevideo", "hbo", "wbd", "paramount", "cbs",
        "peacock", "apple.atve", "plex", "kodi", "vlc", "crunchyroll", "globo", "tubi", "pluto"};
    private static final String[] MUSIC_APPS = {"spotify", "music", "deezer", "tidal", "podcast", "audio", "radio", "pandora", "soundcloud"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> tracked = new HashMap<>();
    private MediaSessionManager manager;
    private ComponentName me;
    private boolean connected;
    private final Runnable writeInfo = () -> DreamInfo.write(getApplicationContext());
    private final NowPlaying.Listener nowListener = () -> scheduleInfo();
    private final Watched.Listener watchedListener = () -> scheduleInfo();

    /** The screensaver reads what is playing from a small file: it is rewritten (once in a while) when something changes. */
    private void scheduleInfo() {
        main.removeCallbacks(writeInfo);
        main.postDelayed(writeInfo, 1500);
    }
    private final Map<String, String> coverUri = new HashMap<>();   // last cover address fetched for each app

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::sync;

    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (!connected) return;
            for (MediaController c : new ArrayList<>(tracked.keySet())) {   // keeps the position fresh while it plays (paused ones have nothing new)
                try {
                    PlaybackState ps = c.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) publish(c);
                } catch (RuntimeException ignored) {
                }
            }
            main.postDelayed(this, SAMPLE_MS);
        }
    };

    @Override public void onListenerConnected() {
        connected = true;
        manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        me = new ComponentName(this, MediaWatcher.class);
        try {
            manager.addOnActiveSessionsChangedListener(sessions, me);
            sync(manager.getActiveSessions(me));
        } catch (SecurityException e) {
            AppLog.w("playback sessions: " + e.getMessage());
        }
        main.removeCallbacks(sample);
        main.postDelayed(sample, SAMPLE_MS);
        NowPlaying.get().addListener(nowListener);
        Watched.get(this).addListener(watchedListener);
        scheduleInfo();
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        main.removeCallbacks(sample);
        NowPlaying.get().removeListener(nowListener);
        Watched.get(this).removeListener(watchedListener);
        try {
            if (manager != null) manager.removeOnActiveSessionsChangedListener(sessions);
        } catch (RuntimeException ignored) {
        }
        for (Map.Entry<MediaController, MediaController.Callback> e : tracked.entrySet()) e.getKey().unregisterCallback(e.getValue());
        tracked.clear();
        for (Runnable r : pending.values()) main.removeCallbacks(r);
        pending.clear();
        squareCache.clear();
        squareKey.clear();
        NowPlaying.get().clear(null);
    }

    private void sync(List<MediaController> list) {
        List<MediaController> now = list == null ? new ArrayList<>() : list;
        for (MediaController c : new ArrayList<>(tracked.keySet())) {
            boolean still = false;
            for (MediaController n : now) still |= n.getSessionToken().equals(c.getSessionToken());
            if (!still) {
                Runnable waiting = pending.remove(c);
                if (waiting != null) main.removeCallbacks(waiting);
                publish(c);   // its last position
                c.unregisterCallback(tracked.remove(c));
                NowPlaying.get().clear(c);
            }
        }
        for (MediaController c : now) {
            boolean known = false;
            for (MediaController t : tracked.keySet()) known |= t.getSessionToken().equals(c.getSessionToken());
            if (known) continue;
            final MediaController controller = c;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { later(controller); }
                @Override public void onMetadataChanged(MediaMetadata metadata) { later(controller); }
                @Override public void onSessionDestroyed() {
                    publish(controller);
                    NowPlaying.get().clear(controller);
                }
            };
            c.registerCallback(cb, main);
            tracked.put(c, cb);
            publish(c);
        }
    }

    /** Apps fire several events in a row (state, metadata, position): they become one reading, a moment later. */
    private final Map<MediaController, Runnable> pending = new HashMap<>();

    private void later(final MediaController c) {
        Runnable old = pending.remove(c);
        if (old != null) main.removeCallbacks(old);
        Runnable r = () -> {
            pending.remove(c);
            publish(c);
        };
        pending.put(c, r);
        main.postDelayed(r, 400);
    }

    /** The last cover shrunk for each app and track: the same song is not cropped and scaled again at every reading. */
    private final Map<String, Bitmap> squareCache = new HashMap<>();

    private Bitmap squareOf(String pkg, String title, Bitmap art) {
        if (art == null) return null;
        String key = pkg + "|" + title;
        Bitmap b = squareCache.get(pkg);
        if (b != null && key.equals(squareKey.get(pkg))) return b;
        b = square(art);
        squareCache.put(pkg, b);
        squareKey.put(pkg, key);
        return b;
    }

    private final Map<String, String> squareKey = new HashMap<>();

    /** Sends what one session says now to the right place (music: the player strip; video: the history). */
    private void publish(MediaController c) {
        try {
            if (c.getPackageName().equals(getPackageName())) return;
            MediaMetadata md = c.getMetadata();
            PlaybackState ps = c.getPlaybackState();
            int state = ps == null ? PlaybackState.STATE_NONE : ps.getState();
            if (isMusic(c.getPackageName(), md)) {
                if (state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED || md == null) {
                    NowPlaying.get().clear(c);
                    return;
                }
                boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
                String title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
                String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                if (!playing && !NowPlaying.get().visible()) return;   // a paused session nobody listened to: not worth showing
                Bitmap art = cover(md);
                String uri = art == null ? uri(md) : null;
                Bitmap shrunk = squareOf(c.getPackageName(), title + "|" + artist, art);
                NowPlaying.get().set(c, title, artist, shrunk, playing);
                long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                NowPlaying.get().setPosition(ps == null ? 0 : position(ps), dur);
                Watched.get(this).recordMusic(c.getPackageName(), title, artist, ps == null ? 0 : position(ps), dur, playing,
                        shrunk, uri != null);   // the history keeps the last song, for the card
                if (uri != null && !uri.equals(coverUri.get(c.getPackageName()))) {   // the app gave an address, not a picture
                    coverUri.put(c.getPackageName(), uri);
                    final String pkg = c.getPackageName(), address = uri;
                    new Thread(() -> {
                        Bitmap b = Watched.fetch(getApplicationContext(), address);
                        if (b != null) {
                            final Bitmap sq = square(b);
                            main.post(() -> {
                                NowPlaying.get().setCover(pkg, sq);
                                Watched.get(getApplicationContext()).musicCover(pkg, sq);
                            });
                        }
                    }).start();
                }
                return;
            }
            if (state == PlaybackState.STATE_NONE || ps == null) return;
            long pos = position(ps);
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String title = md == null ? null : text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            String series = md == null ? null : text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_ARTIST);
            Watched.get(this).record(c.getPackageName(), title, series, pos, dur, md == null ? null : cover(md), md == null ? null : uri(md));
        } catch (RuntimeException e) {
            AppLog.w("playback session: " + e);
        }
    }

    /** Music or other audio: the metadata has an artist or an album, or the app is a known music app. */
    private static boolean isMusic(String pkg, MediaMetadata md) {
        String lower = pkg.toLowerCase(Locale.ROOT);
        if (lower.contains("youtube") && !lower.contains("music")) return false;
        for (String v : VIDEO_APPS) if (lower.contains(v)) return false;
        if (md != null && (!text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST).isEmpty()
                || !text(md, MediaMetadata.METADATA_KEY_ALBUM).isEmpty())) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String m : MUSIC_APPS) if (p.contains(m)) return true;
        return false;
    }

    private static long position(PlaybackState ps) {
        long pos = ps.getPosition();
        if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getPlaybackSpeed() > 0 && ps.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    private static String text(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return "";
    }

    /** Center-cropped to a square and shrunk to 192 px (it is drawn at about 50). */
    private static Bitmap square(Bitmap b) {
        int side = Math.min(b.getWidth(), b.getHeight());
        Bitmap crop = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
        return side <= 192 ? crop : Bitmap.createScaledBitmap(crop, 192, 192, true);
    }

    private static Bitmap cover(MediaMetadata md) {
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = md.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    private static String uri(MediaMetadata md) {
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String u = md.getString(k);
            if (u != null && !u.isEmpty()) return u;
        }
        return null;
    }
}
