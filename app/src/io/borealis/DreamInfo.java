package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * What the screensaver shows next to the clock: the song playing now or, if nothing is playing, the last thing that was
 * being watched. The screensaver can live in another process (Android 11), where the home screen's memory is out of
 * reach, so the :home process writes a small file (write) and the screensaver reads it (read).
 */
final class DreamInfo {
    private DreamInfo() {}

    static final class Info {
        String label = "", title = "", subtitle = "";
        float progress = -1;      // 0 to 1, -1 = no bar
        Bitmap cover;             // small, may be null
        boolean session, playing;   // session: a song's session exists (it can be controlled); playing: it is sounding now
        long lastPlay;              // when it last sounded (wall clock)
        long pos, dur, at;          // a song's position and length when the file was written (at: wall clock)
        String app = "";            // the app's name ("Spotify"), for "Press OK to open Spotify"
        String raw = "", pkg = "";   // pkg: the app that plays it (opened with OK)
        long coverStamp;
    }

    private static final String FILE = "dream_info.json", NOW_COVER = "dream_now.jpg";
    private static Bitmap lastCover;

    // MARK: - Writing (the :home process, from MediaWatcher)

    static void write(Context c) {
        try {
            NowPlaying now = NowPlaying.get();
            JSONObject o = new JSONObject();
            // A song's session is shown (and can be controlled) while it plays and for five minutes after a pause.
            boolean live = now.visible() && !now.title.isEmpty()
                    && (now.playing || System.currentTimeMillis() - now.lastPlayingAt < 5 * 60_000L);
            if (live) {
                o.put("live", true).put("session", true).put("playing", now.playing).put("lastPlay", now.lastPlayingAt)
                        .put("pos", now.position()).put("dur", now.dur).put("at", System.currentTimeMillis())
                        .put("app", appName(c, now.pkg)).put("pkg", now.pkg).put("title", now.title).put("subtitle", now.artist)
                        .put("label", now.playing ? Ui.tr("Tocando agora", "Now playing", "Reproduciendo ahora")
                                : Ui.tr("Em pausa", "Paused", "En pausa"));
                File cover = new File(c.getFilesDir(), NOW_COVER);
                if (now.cover != null && now.cover != lastCover) {
                    try (FileOutputStream out = new FileOutputStream(cover)) {
                        now.cover.compress(Bitmap.CompressFormat.JPEG, 88, out);
                    }
                    lastCover = now.cover;
                } else if (now.cover == null) {
                    //noinspection ResultOfMethodCallIgnored
                    cover.delete();
                    lastCover = null;
                }
                o.put("cover", now.cover == null ? "" : cover.getPath());
            } else {
                List<Recs.Pick> picks = Watched.get(c).picks(null);
                if (!picks.isEmpty()) {
                    Recs.Pick p = picks.get(0);
                    String sub = p.detail == null || p.detail.isEmpty() ? p.left : p.left + " · " + p.detail;
                    o.put("live", false).put("app", p.label == null ? "" : p.label).put("pkg", p.pkg == null ? "" : p.pkg).put("title", p.title).put("subtitle", sub == null ? "" : sub)
                            .put("label", p.music ? Ui.tr("Continuar ouvindo", "Keep listening", "Seguir escuchando")
                                    : Ui.tr("Continuar assistindo", "Continue watching", "Continuar viendo"))
                            .put("progress", p.progress).put("cover", p.local == null ? "" : p.local.getPath());
                }
            }
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(c.getFilesDir(), FILE));
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("dream info: " + e);
        }
    }

    // MARK: - Opening the app from the screensaver
    // Android 11 can refuse to start an activity from a screensaver (it has no activity in front), so the request is
    // also left in a small file: when the home screen comes back to the front (right after the screensaver ends) it
    // opens the app itself. The file is only good for a few seconds.

    private static final String OPEN_FILE = "dream_open.txt";

    static void requestOpen(Context c, String pkg) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), OPEN_FILE))) {
            out.write((pkg + "\n" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** The app the screensaver asked to open a moment ago (once), or null. */
    static String takeOpenRequest(Context c) {
        File f = new File(c.getFilesDir(), OPEN_FILE);
        if (!f.exists()) return null;
        try {
            String[] parts = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (parts.length < 2 || System.currentTimeMillis() - Long.parseLong(parts[1].trim()) > 6000) return null;
            return parts[0].trim();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    /** Starts the app the way the launcher does. False if it can't be opened. */
    static boolean launch(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String appName(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }

    // MARK: - Reading (the screensaver, in any process)

    /** The current info (the same object as `previous` if nothing changed), or null if there is nothing to show. */
    static Info read(Context c, Info previous) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return null;
            byte[] bytes = new byte[(int) Math.min(f.length(), 65536)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = 0, r;
                while (n < bytes.length && (r = in.read(bytes, n, bytes.length - n)) > 0) n += r;
            }
            String raw = new String(bytes, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty() || raw.equals("{}")) return null;
            JSONObject o = new JSONObject(raw);
            String title = o.optString("title", "");
            if (title.isEmpty()) return null;
            String coverPath = o.optString("cover", "");
            File coverFile = coverPath.isEmpty() ? null : new File(coverPath);
            long stamp = coverFile != null && coverFile.exists() ? coverFile.lastModified() : 0;
            if (previous != null && previous.raw.equals(raw) && previous.coverStamp == stamp) return previous;
            Info i = new Info();
            i.raw = raw;
            i.coverStamp = stamp;
            i.label = o.optString("label", "");
            i.title = title;
            i.pkg = o.optString("pkg", "");
            i.app = o.optString("app", "");
            i.session = o.optBoolean("session");
            i.playing = o.optBoolean("playing");
            i.lastPlay = o.optLong("lastPlay");
            i.pos = o.optLong("pos");
            i.dur = o.optLong("dur");
            i.at = o.optLong("at");
            i.subtitle = o.optString("subtitle", "");
            i.progress = (float) o.optDouble("progress", -1);
            if (i.session && i.dur > 0) i.progress = Math.min(1f, i.pos / (float) i.dur);   // a song: from its position
            if (stamp != 0) i.cover = decode(coverFile, 192);
            return i;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** A picture from disk at about `side` pixels, center-cropped to a square. */
    private static Bitmap decode(File f, int side) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (opts.inSampleSize * 2) >= side) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) return null;
        int s = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
        if (sq != b) b.recycle();
        return sq;
    }
}
