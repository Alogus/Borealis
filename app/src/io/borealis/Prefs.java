package io.borealis;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Borealis > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    /** Like put, but it does not bump `version`: changes the home picks up by itself on resume (the shelf, the hidden apps). */
    private static void putQuiet(String key, String value) {
        sp().edit().putString(key, value).apply();
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "dark");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /** "auto" (24 h, or 12 h in English), "24" or "12". */
    static String timeFormat() {
        if (app == null) return "auto";
        String v = sp().getString("timeFormat", "auto");
        return v.equals("12") || v.equals("24") ? v : "auto";
    }

    static void setTimeFormat(String v) { put("timeFormat", v); }

    /** How the user wants to be greeted ("" = no name). */
    static String name() {
        return app == null ? "" : sp().getString("name", "").trim();
    }

    static void setName(String v) { put("name", v == null ? "" : v.trim()); }
    /** The profile avatar (an id from Avatars.IDS); empty until chosen. */
    static String avatar() { return app == null ? "" : sp().getString("avatar", ""); }
    static void setAvatar(String v) { put("avatar", v == null ? "" : v); }

    /** What the three cards of the home screen show: "recent" (continue watching) or "recs" (TMDB recommendations). */
    static String cardsMode() {
        if (app == null) return "recent";
        return sp().getString("cardsMode", "recent").equals("recs") ? "recs" : "recent";
    }

    static void setCardsMode(String v) { put("cardsMode", v); }

    /**
     * Wallpapers (home screen) are the dark set; screensavers are the earlier, brighter set plus Vesper (16:9, 1920x1080).
     * The first two screensavers are videos (their picture is a frame); the rest are a still moving slowly.
     */
    static final String[] WALLPAPER_FILES = {"wall6.jpg", "wall7.jpg", "wall8.jpg", "wall9.jpg", "wave.jpg", "wall2.jpg", "wall3.jpg", "wall4.jpg", "wall5.jpg", "wall10.jpg"};
    static final String[] WALLPAPER_NAMES = {"Vesper", "Lumen Nox", "Luna Nox", "Velum Nox", "Aurora Lily", "Lumen Peach", "Velum Night", "Pictura Peach", "Luna Melon", "Aurora Nox"};
    static final String[] SAVER_FILES = {"aurora.mp4", "aurora2.mp4", "wall3.jpg", "wall4.jpg", "wall5.jpg", "wall6.jpg"};
    /** The earlier, brighter wallpapers now live only as screensavers (same order as SAVER_FILES). */
    static final String[] SAVER_POSTERS = {"wave.jpg", "wall2.jpg", "wall3.jpg", "wall4.jpg", "wall5.jpg", "wall6.jpg"};
    static final String[] SAVER_NAMES = {"Aurora Lily", "Lumen Peach", "Velum Night", "Pictura Peach", "Luna Melon", "Vesper"};

    /** Vesper (first of the dark set) is the default. A new key: the old "wallpaper" number pointed into the old list. */
    static final int DEFAULT_WALLPAPER = 0;

    static int wallpaper() {
        if (app == null) return DEFAULT_WALLPAPER;
        int v = sp().getInt("homeWallpaper", DEFAULT_WALLPAPER);
        return v >= 0 && v < WALLPAPER_FILES.length ? v : DEFAULT_WALLPAPER;
    }

    static void setWallpaper(int v) {
        if (app == null) return;
        sp().edit().putInt("homeWallpaper", v).apply();
        version++;
    }

    /** Read with MODE_MULTI_PROCESS: the settings screen (process :home) writes it, the screensaver may run in the main process. */
    @SuppressWarnings("deprecation")
    static int screensaver() {
        if (app == null) return 0;
        int v = app.getSharedPreferences("settings", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS).getInt("screensaver", 0);
        return v >= 0 && v < SAVER_FILES.length ? v : 0;
    }

    @SuppressWarnings("deprecation")
    static void setScreensaver(int v) {
        if (app == null) return;
        app.getSharedPreferences("settings", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS).edit().putInt("screensaver", v).apply();
        version++;
    }

    // MARK: - City (from config.properties)

    /** The city the person chose on the TV (welcome or Settings) wins; without one, the build's config.properties gives it. */
    static String city() { return sp().getString("placeCity", Config.CITY).trim(); }
    static String latitude() { return sp().getString("placeLat", Config.LATITUDE); }
    static String longitude() { return sp().getString("placeLon", Config.LONGITUDE); }
    static boolean placeChosen() { return sp().getString("placeLat", null) != null; }
    static String placeCountry() { return sp().getString("placeCountry", ""); }
    static void setPlace(String country, String city, double lat, double lon) {
        sp().edit().putString("placeCountry", country).putString("placeCity", city)
                .putString("placeLat", String.valueOf(lat)).putString("placeLon", String.valueOf(lon)).apply();
        version++;
    }

    // MARK: - Icons and layout

    /** Columns of the apps grid (the shelf is its first row): 6 wide, 8 balanced (default), 10 compact. */
    static int iconCols() { int v = sp().getInt("iconCols", 8); return v == 6 || v == 10 ? v : 8; }
    static void setIconCols(int v) { sp().edit().putInt("iconCols", v == 6 || v == 10 ? v : 8).apply(); version++; }
    /** Shape of the app tiles: 0 rectangular (default), 1 square, 2 circular. */
    static int iconShape() { int v = sp().getInt("iconShape", 0); return v == 1 || v == 2 ? v : 0; }
    static void setIconShape(int v) { sp().edit().putInt("iconShape", v == 1 || v == 2 ? v : 0).apply(); version++; }

    // MARK: - Widgets

    /** The Borealis opening when the TV turns on (on by default). */
    static boolean splash() { return sp().getBoolean("splash", true); }
    static void setSplash(boolean on) { put("splash", on); }
    static boolean startupSound() { return sp().getBoolean("startupSound", true); }
    static void setStartupSound(boolean on) { put("startupSound", on); }
    /** The clicks when moving and pressing on the home screen (on by default). */
    static boolean clicks() { return clickLevel() > 0; }

    /** The clicks' volume in steps: 0 (off), 20, 40, 60, 80, 100. Aurora at 40 % for everybody; whoever had them off keeps them off. */
    static int clickLevel() {
        if (app == null) return 40;
        if (sp().contains("clickLevel")) return sp().getInt("clickLevel", 40);
        return sp().getBoolean("clicks", true) ? 40 : 0;
    }

    static void setClickLevel(int level) {
        sp().edit().putInt("clickLevel", Math.max(0, Math.min(100, level))).apply();
        version++;
    }

    static final String[] CLICK_SOUNDS = {"aurora", "cometa", "marte", "polaris", "pulsar"};

    static String clickSound() {
        String s = app == null ? "aurora" : sp().getString("clickSound", "aurora");
        for (String n : CLICK_SOUNDS) if (n.equals(s)) return s;
        return "aurora";
    }

    static void setClickSound(String name) { put("clickSound", name); }

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    // Update check (bookkeeping does not bump `version`: it must not redraw or restart the screens).
    static boolean updateAuto() { return sp().getBoolean("updateAuto", true); }
    static void setUpdateAuto(boolean on) { put("updateAuto", on); }
    static String updateFound() { return sp().getString("updateFound", ""); }
    static void setUpdateFound(String v) { sp().edit().putString("updateFound", v == null ? "" : v).apply(); }
    static String updateApkUrl() { return sp().getString("updateApk", ""); }
    static String updateShaUrl() { return sp().getString("updateSha", ""); }
    static void setUpdateApk(String apk, String sha) { sp().edit().putString("updateApk", apk).putString("updateSha", sha).apply(); }
    static long updateAt() { return sp().getLong("updateAt", 0); }
    static void setUpdateAt(long t) { sp().edit().putLong("updateAt", t).apply(); }
    /** "version:day" of the last time the notice was shown on the home screen (once a day per version). */
    static String updateShown() { return sp().getString("updateShown", ""); }
    static void setUpdateShown(String v) { sp().edit().putString("updateShown", v).apply(); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        putQuiet("shelf", sb.toString());   // the home re-reads the shelf when it resumes: no restart of the screen
    }

    // MARK: - Hidden apps (hidden from the shelf and from All Apps; Settings > Hidden apps brings them back)

    static List<String> hiddenApps() {
        List<String> out = new ArrayList<>();
        for (String p : sp().getString("hiddenApps", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setHiddenApps(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        putQuiet("hiddenApps", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) { return sp().getString("key" + keyCode, defaultApp(keyCode)); }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }
}
