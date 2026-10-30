package io.borealis;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A small black box for the :home process: a text file with the app's life (start, screen on and off, crashes) and, when the
 * main thread stops answering for a few seconds, the exact place where it is stuck. Nothing leaves the TV.
 * Read it with: adb pull /sdcard/Android/data/io.borealis/files/borealis.log
 */
final class Diag {
    private Diag() {}

    private static final long MAX_BYTES = 256 * 1024, STALL_MS = 4000, BEAT_MS = 1500;
    private static Context app;
    private static File file;
    private static boolean monitoring;
    private static volatile long beat;

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        File dir = app.getExternalFilesDir(null);
        file = new File(dir != null ? dir : app.getFilesDir(), "borealis.log");
        if (file.length() > MAX_BYTES) {
            File old = new File(file.getPath() + ".old");
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(old);
        }
    }

    /** One line: time, process, text. */
    static synchronized void event(String text) {
        if (file == null) return;
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " [" + SystemClock.elapsedRealtime() / 1000 + "s] " + text + "\n");
        } catch (IOException ignored) {
        }
    }

    static String memory() {
        Runtime rt = Runtime.getRuntime();
        String s = "heap " + (rt.totalMemory() - rt.freeMemory()) / 1048576 + "/" + rt.maxMemory() / 1048576 + "MB";
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s += ", system free " + mi.availMem / 1048576 + "/" + mi.totalMem / 1048576 + "MB" + (mi.lowMemory ? " LOW" : "");
        } catch (RuntimeException ignored) {
        }
        return s;
    }

    static void crash(Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        event("CRASH in " + t.getName() + ": " + sw + " | " + memory());
    }

    /** Checks every second and a half that the main thread answers; if it doesn't for 4 s, writes what it is doing. */
    static synchronized void monitor() {
        if (monitoring || file == null) return;
        monitoring = true;
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable ping = () -> beat = SystemClock.uptimeMillis();
        beat = SystemClock.uptimeMillis();
        Thread t = new Thread(() -> {
            boolean reported = false;
            while (true) {
                main.post(ping);
                try {
                    Thread.sleep(BEAT_MS);
                } catch (InterruptedException e) {
                    return;
                }
                long late = SystemClock.uptimeMillis() - beat;
                if (late > STALL_MS) {
                    if (!reported) {
                        reported = true;
                        StringBuilder sb = new StringBuilder("MAIN THREAD STUCK for " + late + " ms, " + memory() + "\n");
                        for (StackTraceElement el : Looper.getMainLooper().getThread().getStackTrace()) sb.append("    at ").append(el).append('\n');
                        event(sb.toString());
                    }
                } else if (reported) {
                    reported = false;
                    event("main thread answers again");
                }
            }
        }, "borealis-diag");
        t.setDaemon(true);
        t.start();
    }
}
