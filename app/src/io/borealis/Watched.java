package io.borealis;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Seguir viendo": what was playing on the TV and how far it got, kept only on the TV (nothing is sent anywhere).
 * MediaWatcher reads the playback sessions the apps publish (title, series, position, duration, picture) and calls
 * record(); the home screen shows the latest ones as its three cards, with a progress line. The picture is the one the
 * app gives; without it, the title's photo from TMDB (if there is a key); without that, the app's logo on a dark card.
 * Lives in the :home process, like the home screen and MediaWatcher.
 */
final class Watched {
    static final class Entry {
        String key, pkg, appLabel, title, series, episode, kind = "movie";
        String sub = "", genres = "";       // sub: the channel (YouTube); genres: from TMDB, "Acción, Drama"
        int season, number;                 // from TMDB, 0 = unknown
        boolean untitled, enriched;         // untitled: the app gave no title (Netflix); enriched: TMDB already looked it up
        long pos, dur, at, art, tried;      // art: when its picture file was made (0 = not yet); tried: last TMDB attempt
    }

    interface Listener { void onWatched(); }

    /** "T1:E3", "S1E3", "Temporada 1 Episodio 3", "Temp. 1 Cap. 3"... (season in group 1, episode in group 2). */
    private static final Pattern SEASON_EPISODE = Pattern.compile(
            "(?i)\\b(?:T|S|Temp(?:orada)?\\.?|Season)\\s*(\\d{1,2})\\s*[:\\u00B7,.\\-\\s]*\\s*(?:E|Ep(?:isodio|isode)?\\.?|Cap(?:[i\\u00ED]tulo)?\\.?)\\s*(\\d{1,3})");
    private static final int MAX = 12;
    private static final long MIN_POS_MS = 2_000, DONE_LEFT_MS = 60_000;
    private static Watched instance;

    static synchronized Watched get(Context c) {
        if (instance == null) instance = new Watched(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> making = new HashSet<>();

    private Watched(Context c) {
        context = c;
        store = c.getSharedPreferences("watched", Context.MODE_PRIVATE);
        try {
            if (store.getInt("version", 0) != 3) store.edit().clear().putInt("version", 3).apply();   // older entries were parsed differently
            JSONArray a = new JSONArray(store.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.key = o.getString("key");
                e.pkg = o.getString("pkg");
                e.appLabel = o.optString("app", "");
                e.title = o.optString("title", "");
                e.series = o.isNull("series") ? null : o.optString("series");
                e.episode = o.isNull("episode") ? null : o.optString("episode");
                e.kind = o.optString("kind", "movie");
                e.pos = o.optLong("pos");
                e.dur = o.optLong("dur");
                e.at = o.optLong("at");
                e.art = o.optLong("art");
                e.sub = o.optString("sub", "");
                e.genres = o.optString("genres", "");
                e.season = o.optInt("season");
                e.number = o.optInt("number");
                e.untitled = o.optBoolean("untitled");
                e.enriched = o.optBoolean("enriched");
                entries.add(e);
            }
        } catch (Exception ignored) {
        }
        for (Entry x : new ArrayList<>(entries)) if (!x.untitled) dropUntitledShadows(x.pkg);   // old duplicates (YouTube)
        if (store.getInt("logo", 0) < 2) {   // the logo cards now fill the whole card: make them again, once
            main.post(() -> {
                for (Entry x : new ArrayList<>(entries)) if (x.untitled && x.art != 0) makeArt(x, null, null);
                store.edit().putInt("logo", 2).apply();
            });
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    // MARK: - Access to the playback sessions (Notification access)

    /** True when the user has allowed MediaWatcher in Settings > Notification access (or with adb). */
    static boolean permitted(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(c.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Opens the system screen where the access is granted. False if this TV has no such screen (use the adb command). */
    static boolean openAccessSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /**
     * An app that gives titles (YouTube) can first report a session without one (the app opening, an ad), which makes a
     * "Continue on YouTube" card; once a titled card of that app exists that card is only an empty duplicate, so it goes.
     */
    private void dropUntitledShadows(String pkg) {
        boolean titled = false;
        for (Entry x : entries) if (x.pkg.equals(pkg) && !x.untitled && !"music".equals(x.kind)) titled = true;
        if (!titled) return;
        for (Entry x : new ArrayList<>(entries)) {
            if (x.pkg.equals(pkg) && x.untitled && !"music".equals(x.kind)) {
                entries.remove(x);
                deleteArt(x);
            }
        }
    }

    // MARK: - Recording

    /**
     * A video session changed. `title` may be null (the app gives no details: only its position is known).
     * `series` is the show or channel when the app gives one. Main thread.
     */
    void record(String pkg, String title, String series, long pos, long dur, Bitmap art, String artUri) {
        if (pkg == null) return;
        // Prime Video reports its own name ("PrimeVideo") as the title: the same as having none.
        boolean untitled = title == null || title.trim().isEmpty() || plain(title).equals(plain(appLabel(pkg)));
        Entry e = null;
        String key = pkg + "|" + (untitled ? "" : title.trim());
        for (Entry x : entries) {
            if (x.key.equals(key) || (untitled && x.pkg.equals(pkg))) {   // no title: it continues this app's latest one
                e = x;
                break;
            }
        }
        if (dur > 0 && dur - pos < DONE_LEFT_MS && e != null) {    // finished: it is no longer something to continue
            entries.remove(e);
            changed();
            return;
        }
        if (e == null) {
            if (pos < MIN_POS_MS || (dur > 0 && dur - pos < DONE_LEFT_MS)) return;
            e = new Entry();
            e.key = key;
            e.pkg = pkg;
            e.appLabel = appLabel(pkg);
            e.untitled = untitled;
            e.title = untitled ? Ui.tr("Continuar no ", "Continue on ", "Continuar en ") + e.appLabel : title.trim();
            boolean video = pkg.toLowerCase(Locale.ROOT).contains("youtube");   // a video and its channel, not an episode of a show
            String t = untitled ? "" : title.trim(), sub = series == null ? "" : series.trim();
            boolean hasSub = !untitled && !sub.isEmpty() && !sub.equalsIgnoreCase(t);
            // Some apps write "T1:E3 Name" in the title or in the subtitle: the season and episode are taken out of it.
            Matcher inTitle = SEASON_EPISODE.matcher(t), inSub = SEASON_EPISODE.matcher(sub);
            if (!untitled && !video && inTitle.find()) {
                e.season = Integer.parseInt(inTitle.group(1));
                e.number = Integer.parseInt(inTitle.group(2));
                String rest = tidy(t.substring(0, inTitle.start()) + " " + t.substring(inTitle.end()));
                e.series = hasSub ? sub : null;
                e.episode = rest.isEmpty() ? null : rest;
                if (e.series == null) e.title = rest.isEmpty() ? t : rest;
                e.kind = "tv";
            } else if (!untitled && !video && hasSub && inSub.find()) {
                e.season = Integer.parseInt(inSub.group(1));
                e.number = Integer.parseInt(inSub.group(2));
                String rest = tidy(sub.substring(0, inSub.start()) + " " + sub.substring(inSub.end()));
                e.series = t;
                e.episode = rest.isEmpty() ? null : rest;
                e.kind = "tv";
            } else {
                boolean show = hasSub && !video;
                e.series = show ? sub : null;
                e.episode = show ? t : null;
                e.kind = show ? "tv" : "movie";
            }
            e.sub = hasSub && video ? sub : "";
            if (video || untitled) e.enriched = true;   // nothing to look up
            entries.add(e);
        }
        if (!e.untitled) dropUntitledShadows(pkg);
        boolean same = e.art != 0 && Math.abs(e.pos - pos) < 3000 && (dur <= 0 || dur == e.dur);
        e.pos = pos;
        if (dur > 0) e.dur = dur;
        e.at = System.currentTimeMillis();
        if (!e.enriched) enrich(e);
        if (same && entries.get(0) == e) return;   // nothing new to show or save
        Collections.sort(entries, (a, b) -> Long.compare(b.at, a.at));
        while (entries.size() > MAX) deleteArt(entries.remove(entries.size() - 1));
        if (e.art == 0) makeArt(e, art, artUri);
        changed();
    }

    // MARK: - Music (the last song, as one more card)

    private boolean musicPlaying, musicPending;

    /**
     * The song playing (or paused) now. There is only ever one music entry: the last song. The cover comes with the call or,
     * if the app gave an address, later through musicCover().
     */
    void recordMusic(String pkg, String title, String artist, long pos, long dur, boolean playing, Bitmap cover, boolean coverComing) {
        if (pkg == null || title == null || title.trim().isEmpty()) return;
        String t = title.trim(), a = artist == null ? "" : artist.trim();
        Entry e = null;
        for (Entry x : entries) if (x.kind.equals("music")) { e = x; break; }
        boolean fresh = e == null;
        boolean songChanged = fresh || !e.pkg.equals(pkg) || !e.title.equals(t) || !e.sub.equals(a);
        boolean toggled = musicPlaying != playing;
        musicPlaying = playing;
        if (fresh) {
            e = new Entry();
            e.key = "music";
            e.kind = "music";
            e.enriched = true;
            entries.add(e);
        }
        e.pkg = pkg;
        e.appLabel = appLabel(pkg);
        e.title = t;
        e.sub = a;
        e.pos = Math.max(0, pos);
        e.dur = Math.max(0, dur);
        e.at = System.currentTimeMillis();
        Collections.sort(entries, (x, y) -> Long.compare(y.at, x.at));
        while (entries.size() > MAX) deleteArt(entries.remove(entries.size() - 1));
        if (songChanged) {
            musicPending = cover == null && coverComing;
            if (cover != null || !coverComing) makeMusicArt(e, cover);
        }
        if (songChanged || toggled) changed();
    }

    /** The cover of the current song arrived (the app had given an address). */
    void musicCover(String pkg, Bitmap b) {
        if (b == null || !musicPending) return;
        for (Entry e : entries) {
            if (e.kind.equals("music") && e.pkg.equals(pkg)) {
                musicPending = false;
                makeMusicArt(e, b);
            }
        }
    }

    private void makeMusicArt(Entry e, Bitmap cover) {
        final Bitmap copy = cover == null ? null : cover.copy(Bitmap.Config.ARGB_8888, false);
        final File file = artFile(e);
        final String pkg = e.pkg;
        io.execute(() -> {
            boolean ok = false;
            try {
                ok = copy != null ? musicCard(copy, file) : logoCard(pkg, file);
            } catch (Exception | OutOfMemoryError ex) {
                AppLog.w("music picture: " + ex);
            }
            final boolean done = ok;
            main.post(() -> {
                if (!done) return;
                for (Entry x : entries) {
                    if (x.kind.equals("music")) {
                        x.art = System.currentTimeMillis();
                        changed();
                    }
                }
            });
        });
    }

    /** The cover blurred as the background, and sharp and square in the middle (safe for the narrow cards too). */
    private static boolean musicCard(Bitmap src, File file) throws java.io.IOException {
        Bitmap out = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        // Blurred: the cover cut to 16:9, shrunk to a handful of pixels and stretched back.
        Bitmap tiny = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Canvas tc = new Canvas(tiny);
        float s = Math.max(32f / src.getWidth(), 18f / src.getHeight());
        float sw = src.getWidth() * s, sh = src.getHeight() * s;
        tc.drawBitmap(src, null, new android.graphics.RectF((32 - sw) / 2, (18 - sh) / 2, (32 + sw) / 2, (18 + sh) / 2), p);
        c.drawBitmap(tiny, null, new android.graphics.RectF(0, 0, 1280, 720), p);
        tiny.recycle();
        p.setColor(Brand.ink(0x66));
        c.drawRect(0, 0, 1280, 720, p);
        // The cover: 360 px square, rounded, with a soft shadow.
        android.graphics.RectF box = new android.graphics.RectF(640 - 180, 360 - 180, 640 + 180, 360 + 180);
        Paint sh2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh2.setColor(0xFF000000);
        sh2.setShadowLayer(44, 0, 18, 0x99000000);
        c.drawRoundRect(box, 28, 28, sh2);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(box, 28, 28, android.graphics.Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        float side = Math.min(src.getWidth(), src.getHeight());
        android.graphics.Rect from = new android.graphics.Rect((int) ((src.getWidth() - side) / 2), (int) ((src.getHeight() - side) / 2),
                (int) ((src.getWidth() + side) / 2), (int) ((src.getHeight() + side) / 2));
        c.drawBitmap(src, from, box, p);
        c.restore();
        src.recycle();
        return save(out, file);
    }

    private static String ago(long at) {
        long min = Math.max(1, (System.currentTimeMillis() - at) / 60000);
        if (min < 60) return Ui.tr("há " + min + " min", min + " min ago", "hace " + min + " min");
        if (min < 48 * 60) return Ui.tr("há " + (min / 60) + " h", (min / 60) + " h ago", "hace " + (min / 60) + " h");
        return Ui.tr("há " + (min / 1440) + " dias", (min / 1440) + " days ago", "hace " + (min / 1440) + " días");
    }

    private static String plain(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Trims separators left at the ends after cutting the season and episode out of a text. */
    private static String tidy(String s) {
        return s.replaceAll("^[\\s:\\u00B7,.\\-\\u2013]+|[\\s:\\u00B7,.\\-\\u2013]+$", "").replaceAll("\\s{2,}", " ");
    }

    private void changed() {
        save();
        for (Listener l : new ArrayList<>(listeners)) l.onWatched();
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("key", e.key).put("pkg", e.pkg).put("app", e.appLabel).put("title", e.title)
                        .put("series", e.series == null ? JSONObject.NULL : e.series)
                        .put("episode", e.episode == null ? JSONObject.NULL : e.episode)
                        .put("kind", e.kind).put("pos", e.pos).put("dur", e.dur).put("at", e.at).put("art", e.art)
                        .put("sub", e.sub).put("genres", e.genres).put("season", e.season).put("number", e.number)
                        .put("untitled", e.untitled).put("enriched", e.enriched);
                a.put(o);
            }
            store.edit().putString("list", a.toString()).putInt("version", 3).apply();
        } catch (Exception ignored) {
        }
    }

    /** Forgets everything (Settings could offer it). */
    void clear() {
        for (Entry e : new ArrayList<>(entries)) deleteArt(e);
        entries.clear();
        changed();
    }

    // MARK: - Cards

    /** The latest three for a tab: null = everything, "movie" or "tv". Only the ones with an app on the TV and a picture ready. */
    List<Recs.Pick> picks(String kind) {
        List<Recs.Pick> out = new ArrayList<>();
        Map<String, Boolean> have = new HashMap<>();
        for (Entry e : entries) if (!e.enriched) enrich(e);   // older ones that were saved before TMDB had looked them up
        for (Entry e : entries) {
            if (kind != null && !kind.equals(e.kind)) continue;
            if (e.art == 0 || !artFile(e).exists()) continue;
            Boolean ok = have.get(e.pkg);
            if (ok == null) {
                ok = installed(e.pkg);
                have.put(e.pkg, ok);
            }
            if (!ok) continue;
            Recs.Pick p = new Recs.Pick();
            p.id = e.key.hashCode();
            p.kind = e.kind;
            p.title = e.series != null ? e.series : e.title;
            p.left = left(e);
            p.detail = detail(e);
            p.meta = p.left + " \u00B7 " + p.detail;
            p.chip = e.appLabel;
            p.backdrop = "/w" + Integer.toHexString(e.key.hashCode()) + "-" + e.art + ".jpg";
            p.pkg = e.pkg;
            p.label = e.appLabel;
            p.detailed = true;
            p.local = artFile(e);
            p.progress = e.dur > 0 ? Math.max(0.02f, Math.min(1f, e.pos / (float) e.dur)) : -1;
            if (e.kind.equals("music")) {
                NowPlaying np = NowPlaying.get();
                p.music = true;
                p.live = musicPlaying && np.playing && np.pkg.equals(e.pkg);
                p.title = e.title;
                p.left = p.live ? Ui.tr("Tocando agora", "Playing now", "Sonando ahora") : Ui.tr("Você ouviu ", "You listened ", "Escuchaste ") + ago(e.at);
                p.detail = e.sub;
                p.meta = e.sub.isEmpty() ? p.left : p.left + " \u00B7 " + e.sub;
                if (e.dur <= 0 || e.pos >= e.dur - 3000) p.progress = -1;
            }
            out.add(p);
        }
        for (int i = 1; i < out.size(); i++) {   // what plays right now comes first
            if (out.get(i).live) out.add(0, out.remove(i));
        }
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    /** The descriptive pieces in order: "T2 E5 · Episode name · Serie · Acción", "Película · Acción", "Vídeo · Canal". */
    private static String detail(Entry e) {
        if (e.untitled || e.kind.equals("music")) return e.kind.equals("music") ? e.sub : "";
        List<String> parts = new ArrayList<>();
        if (!e.sub.isEmpty()) {
            parts.add(Ui.tr("Vídeo", "Video", "Video"));
            parts.add(e.sub);
        } else {
            if (e.season > 0) parts.add(Ui.tr("T", "S", "T") + e.season + " E" + e.number);
            if (e.episode != null && !e.episode.isEmpty()) parts.add(e.episode);
            if (e.enriched || e.kind.equals("tv") || e.season > 0) {
                parts.add(e.kind.equals("tv") ? Ui.tr("Série", "Series", "Serie") : Ui.tr("Filme", "Movie", "Película"));
            }
            if (!e.genres.isEmpty()) parts.add(e.genres);
        }
        StringBuilder s = new StringBuilder();
        for (String p : parts) s.append(s.length() == 0 ? "" : " \u00B7 ").append(p);
        return s.toString();
    }

    /** Time left ("Faltam 32 min"), "Continuar" if the duration is unknown, or where an untitled one stopped. */
    private static String left(Entry e) {
        if (e.untitled && e.dur <= 0) {
            long min = Math.max(1, Math.round(e.pos / 60000.0));
            return Ui.tr("Você parou no minuto " + min, "You stopped at minute " + min, "Vas por el minuto " + min);
        }
        if (e.dur > 0) {
            String t = duration(Math.max(0, e.dur - e.pos));
            return Ui.tr("Faltam " + t, t + " left", "Quedan " + t);
        }
        return Ui.tr("Continuar", "Continue", "Continuar");
    }

    static String duration(long ms) {
        long min = Math.max(1, Math.round(ms / 60000.0));
        return min >= 60 ? (min / 60) + " h" + (min % 60 == 0 ? "" : " " + (min % 60) + " min") : min + " min";
    }

    private boolean installed(String pkg) {
        PackageManager pm = context.getPackageManager();
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Genres, and season and episode of a show, from TMDB; once per title, in the background. */
    private void enrich(Entry e) {
        long now = System.currentTimeMillis();
        if (now - e.tried < 10 * 60_000L || !making.add("e:" + e.key)) return;
        e.tried = now;
        final String key = e.key, name = e.series != null ? e.series : e.title, episode = e.episode;
        final boolean show = e.series != null;
        io.execute(() -> {
            Recs.Info info = Recs.hasKey(context) ? Recs.get(context).lookup(name, show, episode) : null;
            final boolean noKey = !Recs.hasKey(context);
            main.post(() -> {
                making.remove("e:" + key);
                for (Entry x : entries) {
                    if (!x.key.equals(key)) continue;
                    if (info != null) {
                        x.enriched = true;
                        x.kind = info.kind == null ? x.kind : info.kind;
                        x.genres = android.text.TextUtils.join(", ", info.genres);
                        if (x.season == 0) {
                            x.season = info.season;
                            x.number = info.number;
                        }
                    } else if (noKey) {
                        x.enriched = true;
                    }
                    changed();
                }
            });
        });
    }

    // MARK: - Pictures

    private File dir() {
        File d = new File(context.getFilesDir(), "watched");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File artFile(Entry e) {
        return new File(dir(), Integer.toHexString(e.key.hashCode()) + ".jpg");
    }

    private void deleteArt(Entry e) {
        //noinspection ResultOfMethodCallIgnored
        artFile(e).delete();
    }

    /** Saves the app's own picture or, failing that, the title's photo from TMDB, or the app's logo on a dark card. */
    private void makeArt(Entry e, Bitmap given, String uri) {
        if (!making.add(e.key)) return;
        final Bitmap copy = given == null ? null : given.copy(Bitmap.Config.ARGB_8888, false);
        final File file = artFile(e);
        final String name = e.series != null ? e.series : e.title;
        final boolean realTitle = !e.untitled && !name.equals(e.appLabel);
        final String pkg = e.pkg, key = e.key;
        io.execute(() -> {
            boolean ok = false;
            try {
                Bitmap b = copy;
                if (b == null && uri != null) b = fetch(context, uri);
                if (b != null && b.getWidth() >= 160) ok = save(b, file);
                if (!ok && realTitle) ok = Recs.get(context).searchImage(name, file);
                if (!ok) ok = logoCard(pkg, file);
            } catch (Exception | OutOfMemoryError ex) {
                AppLog.w("watched picture: " + ex);
            }
            final boolean done = ok;
            main.post(() -> {
                making.remove(key);
                for (Entry x : entries) {
                    if (x.key.equals(key) && done) {
                        x.art = System.currentTimeMillis();
                        changed();
                    }
                }
            });
        });
    }

    /** Scaled to at most 1280 wide, as a JPEG. */
    private static boolean save(Bitmap b, File file) throws java.io.IOException {
        Bitmap out = b;
        if (b.getWidth() > 1280) out = Bitmap.createScaledBitmap(b, 1280, Math.max(1, Math.round(b.getHeight() * 1280f / b.getWidth())), true);
        try (FileOutputStream fo = new FileOutputStream(file)) {
            return out.compress(Bitmap.CompressFormat.JPEG, 90, fo);
        }
    }

    /** A picture from a URI an app gave (https or content://), or null. Call from a background thread. */
    static Bitmap fetch(Context context, String uri) {
        try {
            InputStream in;
            HttpURLConnection c = null;
            if (uri.startsWith("https://")) {
                c = (HttpURLConnection) new URL(uri).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                in = c.getInputStream();
            } else if (uri.startsWith("content://") || uri.startsWith("file://") || uri.startsWith("android.resource://")) {
                in = context.getContentResolver().openInputStream(android.net.Uri.parse(uri));
            } else {
                return null;
            }
            try {
                return in == null ? null : BitmapFactory.decodeStream(in);
            } finally {
                if (in != null) in.close();
                if (c != null) c.disconnect();
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** The app's logo (its TV banner or icon) centered, on a card filled with the app's own color. */
    private boolean logoCard(String pkg, File file) throws java.io.IOException {
        Bitmap out = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Bitmap logo = null;
        int base = 0xFF2B2536;
        try {
            PackageManager pm = context.getPackageManager();
            Drawable d = pm.getApplicationBanner(pkg);
            boolean banner = d != null;
            if (d == null) d = pm.getApplicationIcon(pkg);
            int lw = banner ? 520 : 300, lh = banner ? 292 : 300;
            logo = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, lw, lh);
            d.draw(new Canvas(logo));
            base = banner ? edgeColor(logo) : averageColor(logo, base);
        } catch (Exception ignored) {
        }
        // The whole card in the app's color (a banner brings its own, so it melts into the card), a little lighter above and darker below.
        p.setShader(new LinearGradient(0, 0, 0, 720, mix(base, 0xFFFFFFFF, 0.08f), mix(base, 0xFF000000, 0.16f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1280, 720, p);
        p.setShader(null);
        if (logo != null) {
            c.drawBitmap(logo, 640 - logo.getWidth() / 2f, 360 - logo.getHeight() / 2f, p);
            logo.recycle();
        }
        return save(out, file);
    }

    /** The color along the outer edge of a banner (what its background is). */
    private static int edgeColor(Bitmap b) {
        long r = 0, g = 0, bl = 0, n = 0;
        int w = b.getWidth(), h = b.getHeight();
        for (int x = 0; x < w; x += 4) {
            for (int y = 0; y < h; y += 4) {
                if (x > 6 && x < w - 8 && y > 6 && y < h - 8) continue;
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 200) continue;
                r += Color.red(px);
                g += Color.green(px);
                bl += Color.blue(px);
                n++;
            }
        }
        return n == 0 ? 0xFF2B2536 : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /** The average color of the opaque pixels of an icon. */
    private static int averageColor(Bitmap b, int fallback) {
        long r = 0, g = 0, bl = 0, n = 0;
        for (int x = 0; x < b.getWidth(); x += 6) {
            for (int y = 0; y < b.getHeight(); y += 6) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 200) continue;
                r += Color.red(px);
                g += Color.green(px);
                bl += Color.blue(px);
                n++;
            }
        }
        return n == 0 ? fallback : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    private static int mix(int from, int to, float t) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }
}
