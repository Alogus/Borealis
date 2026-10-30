package io.borealis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Borealis's startup sound (assets/sounds/borealis.ogg): a sustained choir-like chord with a deep space pad and small sparkles for the stars. It plays with
 * the opening (when the TV turns on, and in the welcome) unless it is turned off in Settings > Appearance. It follows
 * the TV's volume.
 */
final class StartupSound {
    private StartupSound() {}

    private static MediaPlayer player;

    static synchronized void play(Context c) {
        if (!Prefs.startupSound()) return;
        stop();
        try {
            // The cached copy is named after the app's version, so an update that changes the sound replaces it.
            long version = 0;
            try {
                version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
            } catch (Exception ignored) {
            }
            File dir = c.getApplicationContext().getCacheDir();
            File f = new File(dir, "borealis-startup-" + version + ".ogg");
            File[] old = dir.listFiles((d, name) -> name.startsWith("borealis-startup") && !name.equals(f.getName()));
            if (old != null) for (File o : old) o.delete();
            if (!f.exists() || f.length() < 1000) {
                try (InputStream in = c.getAssets().open("sounds/borealis.ogg"); OutputStream out = new FileOutputStream(f)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
            }
            final MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(f.getPath());
            mp.setVolume(0.9f, 0.9f);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> release(m));
            mp.setOnErrorListener((m, what, extra) -> { release(m); return true; });
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            AppLog.w("startup sound unavailable: " + e.getMessage());
            stop();
        }
    }

    static synchronized void stop() {
        if (player != null) release(player);
        player = null;
    }

    private static synchronized void release(MediaPlayer m) {
        try { m.release(); } catch (RuntimeException ignored) {}
        if (player == m) player = null;
    }
}
