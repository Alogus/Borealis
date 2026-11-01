package io.borealis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Mechanical-keyboard click for the home screen: a lighter one for moving focus, a firmer one for opening. The
 * sounds are WAVs in the assets (made for Borealis), copied once to the cache and played quietly through SoundPool. It only plays on the home
 * screen, never inside apps.
 */
final class Click {
    private SoundPool pool;
    private int move, open;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            move = pool.load(asset(c, "click-move.wav", "click-move-v2.wav"), 1);
            open = pool.load(asset(c, "click-open.wav", "click-open-v2.wav"), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        if (pool != null && Prefs.clicks()) pool.play(move, 0.30f, 0.30f, 1, 0, 1f);
    }

    void open() {
        if (pool != null && Prefs.clicks()) pool.play(open, 0.38f, 0.38f, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    /** Copies the sound from the app's assets to the cache once (SoundPool needs a file); a new `name` replaces an older sound. */
    private static String asset(Context c, String asset, String name) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        try (java.io.InputStream in = c.getAssets().open("sounds/" + asset); FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        return f.getPath();
    }
}
