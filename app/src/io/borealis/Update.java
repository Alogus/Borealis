package io.borealis;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "Is there a newer Borealis?" Asks GitHub (the project's latest release) at most once a day, and only if the person
 * left the check on. Nothing is downloaded until the person says "Install": then the APK is fetched from the release,
 * checked against its published SHA-256 and handed to Android's own installer, which asks one more confirmation (and
 * only accepts it if it is signed with the same key). GitHub sees the TV's IP address, like any website would; see PRIVACY.md.
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
                String json = get(API);
                String tag = new JSONObject(json).optString("tag_name", "");
                String v = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                Prefs.setUpdateAt(System.currentTimeMillis());
                if (!v.isEmpty() && isNewer(v, installed(app))) {
                    String apk = "", sha = "";
                    org.json.JSONArray assets = new JSONObject(json).optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if ("Borealis.apk".equals(a.optString("name"))) apk = a.optString("browser_download_url");
                        if ("Borealis.apk.sha256".equals(a.optString("name"))) sha = a.optString("browser_download_url");
                    }
                    Prefs.setUpdateApk(apk, sha);
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

    /** What to tell the person (on the home screen). */
    static String message(String version) {
        return Ui.tr("Nova versão do Borealis: " + version + ". Para instalar: Configurações → Sistema → Atualizações.",
                "New Borealis version: " + version + ". To install it: Settings → System → Updates.",
                "Nueva versión de Borealis: " + version + ". Para instalarla: Configuración → Sistema → Actualizaciones.");
    }

    /** Android asks, once, to let Borealis install apps. */
    static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static void askPermission(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
        if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    interface Done { void done(boolean ok, String error); }

    /** Downloads the new version, checks it and hands it to Android's installer. `done` runs on the main thread (ok = the installer is open). */
    static void install(Context c, Done done) {
        final Context app = c.getApplicationContext();
        final String apkUrl = Prefs.updateApkUrl(), shaUrl = Prefs.updateShaUrl();
        if (running) return;
        running = true;
        new Thread(() -> {
            String error = null;
            try {
                if (apkUrl.isEmpty()) throw new Exception("no installer in the release");
                File dir = new File(app.getCacheDir(), "update");
                dir.mkdirs();
                File apk = new File(dir, "Borealis.apk");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                HttpURLConnection h = (HttpURLConnection) new URL(apkUrl).openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(20000);
                h.setRequestProperty("User-Agent", "Borealis");
                try {
                    if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
                    try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                            md.update(buffer, 0, n);
                        }
                    }
                } finally {
                    h.disconnect();
                }
                if (!shaUrl.isEmpty()) {   // the published checksum must match
                    String want = get(shaUrl).trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
                    StringBuilder got = new StringBuilder();
                    for (byte b : md.digest()) got.append(String.format("%02x", b));
                    if (!want.equals(got.toString())) {
                        apk.delete();
                        throw new Exception("checksum does not match");
                    }
                }
                commit(app, apk);
            } catch (Exception e) {
                AppLog.w("update install failed: " + e.getMessage());
                error = e.getMessage() == null ? "error" : e.getMessage();
            } finally {
                running = false;
            }
            final String err = error;
            MAIN.post(() -> done.done(err == null, err));
        }, "update-install").start();
    }

    private static void commit(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("Borealis.apk", 0, apk.length())) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(UpdateReceiver.ACTION).setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
        }
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
