package io.borealis;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "Is there a newer Borealis?" Asks GitHub (the project's latest release) at most once a day, and only if the person
 * left the check on. It never downloads or installs anything: it only says that a new version exists and how to get it
 * (the Downloader code). GitHub sees the TV's IP address, like any website would; see PRIVACY.md.
 */
final class Update {
    private Update() {}

    static final String API = "https://api.github.com/repos/Alogus/Borealis/releases/latest";
    /** The AFTVnews Downloader code that always downloads the latest release. */
    static final String CODE = "3357424";
    static final int UP_TO_DATE = 0, FOUND = 1, FAILED = 2;
    private static final long EVERY_MS = 24L * 3600 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    interface Result { void done(int status); }

    static String installed(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** The newer version found by the last check ("" when there is none or it was installed since). */
    static String newer(Context c) {
        String found = Prefs.updateFound();
        return !found.isEmpty() && isNewer(found, installed(c)) ? found : "";
    }

    /** `force`: ignore the once-a-day limit and the on/off setting (the "Check now" row). `done` runs on the main thread. */
    static void check(Context c, boolean force, Result done) {
        final Context app = c.getApplicationContext();
        if (!force && (!Prefs.updateAuto() || System.currentTimeMillis() - Prefs.updateAt() < EVERY_MS)) return;
        if (running) return;
        running = true;
        new Thread(() -> {
            int status;
            try {
                String tag = new JSONObject(get(API)).optString("tag_name", "");
                String v = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                Prefs.setUpdateAt(System.currentTimeMillis());
                if (!v.isEmpty() && isNewer(v, installed(app))) {
                    Prefs.setUpdateFound(v);
                    status = FOUND;
                } else {
                    Prefs.setUpdateFound("");
                    status = UP_TO_DATE;
                }
            } catch (Exception e) {
                AppLog.w("update check failed: " + e.getMessage());
                status = FAILED;
            } finally {
                running = false;
            }
            final int s = status;
            if (done != null) MAIN.post(() -> done.done(s));
        }, "update-check").start();
    }

    /** "3.39.0" > "3.38.0": numbers compared part by part. */
    static boolean isNewer(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** What to tell the person. */
    static String message(String version) {
        return Ui.tr("Nova versão do Borealis: " + version + ". Para atualizar, abra o Downloader e digite o código " + CODE + ".",
                "New Borealis version: " + version + ". To update, open Downloader and type the code " + CODE + ".",
                "Nueva versión de Borealis: " + version + ". Para actualizar, abre Downloader y escribe el código " + CODE + ".");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "Borealis");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
