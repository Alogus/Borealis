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
     * Wallpapers and screensavers are the same set, in the same order (16:9, 1920x1080): Latin word + a palette color.
     * The first two screensavers are videos (the wallpaper is a frame); the rest are the wallpaper moving slowly.
     */
    static final String[] WALLPAPER_FILES = {"wave.jpg", "wall2.jpg", "wall3.jpg", "wall4.jpg", "wall5.jpg"};
    static final String[] WALLPAPER_NAMES = {"Aurora Lily", "Lumen Peach", "Velum Night", "Pictura Peach", "Luna Melon"};
    static final String[] SAVER_FILES = {"aurora.mp4", "aurora2.mp4", "wall3.jpg", "wall4.jpg", "wall5.jpg"};
    static final String[] SAVER_POSTERS = WALLPAPER_FILES;
    static final String[] SAVER_NAMES = WALLPAPER_NAMES;

    static int wallpaper() {
        if (app == null) return 0;
        int v = sp().getInt("wallpaper", 0);
        return v >= 0 && v < WALLPAPER_FILES.length ? v : 0;
    }

    static void setWallpaper(int v) {
        if (app == null) return;
        sp().edit().putInt("wallpaper", v).apply();
        version++;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
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

    static String city() { return Config.CITY.trim(); }
    static String latitude() { return Config.LATITUDE; }
    static String longitude() { return Config.LONGITUDE; }

    // MARK: - Widgets and coins

    /** The Borealis opening when the TV turns on (on by default). */
    static boolean splash() { return sp().getBoolean("splash", true); }
    static void setSplash(boolean on) { put("splash", on); }
    static boolean startupSound() { return sp().getBoolean("startupSound", true); }
    static void setStartupSound(boolean on) { put("startupSound", on); }

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", false); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

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
        put("shelf", sb.toString());
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
