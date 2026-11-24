package io.borealis;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The click sounds: a light one for moving focus (_mover), a firmer one for OK (_ok). Five sounds to choose from (see Prefs.clickSound),
 * all normalized to the same loudness; the volume is the gain (level / 100) over the system volume. The WAVs live in the assets, are copied
 * once to the cache and played through SoundPool.
 */
final class Click {
    private SoundPool pool;
    private final Context ctx;
    private final Map<String, int[]> ids = new HashMap<>();   // sound -> {move, ok}
    private final Map<Integer, Boolean> ready = new java.util.concurrent.ConcurrentHashMap<>();

    Click(Context c) {
        ctx = c.getApplicationContext();
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((p, id, status) -> ready.put(id, status == 0));
            ensure(Prefs.clickSound());
        } catch (RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    /** Loads a sound's pair (once). */
    private int[] ensure(String name) {
        int[] r = ids.get(name);
        if (r != null || pool == null) return r;
        try {
            r = new int[] {pool.load(asset(ctx, name + "_mover.wav"), 1), pool.load(asset(ctx, name + "_ok.wav"), 1)};
            ids.put(name, r);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
        }
        return r;
    }

    /** All five (the sound picker plays each one when it is focused). */
    void loadAll() {
        for (String n : Prefs.CLICK_SOUNDS) ensure(n);
    }

    private void play(String name, boolean ok) {
        if (pool == null) return;
        float g = Prefs.clickLevel() / 100f;
        if (g <= 0f) return;
        int[] r = ensure(name);
        if (r == null) return;
        int id = r[ok ? 1 : 0];
        if (Boolean.TRUE.equals(ready.get(id))) pool.play(id, g, g, 1, 0, 1f);
    }

    void move() { play(Prefs.clickSound(), false); }

    void open() { play(Prefs.clickSound(), true); }

    /** A given sound, at the volume in use (the sound picker). */
    void preview(String name, boolean ok) { play(name, ok); }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }

    /** Copies the sound from the app's assets to the cache once (SoundPool needs a file). */
    private static String asset(Context c, String asset) throws IOException {
        File f = new File(c.getCacheDir(), "click4-" + asset);
        if (f.exists() && f.length() > 44) return f.getPath();
        try (java.io.InputStream in = c.getAssets().open("sounds/" + asset); FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        return f.getPath();
    }
}
