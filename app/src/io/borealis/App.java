package io.borealis;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

/** Gives Prefs a context in every process, and notes when the screen wakes up (the TV was turned on). */
public class App extends Application {
    /** The screen came on and nobody has played the opening for it yet. */
    static volatile boolean wakePending;
    static volatile long wokeAt;
    /** Set by the home screen while it is in front: plays the opening right away when the screen wakes. */
    static volatile Runnable onWake;
    /** Set by the home screen while it is in front: puts the opening in place (waiting) the moment the screen goes off. */
    static volatile Runnable onSleep;
    /** Set by the home screen: drops the images it can load again when the system runs low on memory. */
    static volatile Runnable onTrim;

    private static IntentFilter screenFilter() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        return f;
    }

    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Diag.init(this);
        try {
            registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    Diag.event(Intent.ACTION_SCREEN_OFF.equals(i.getAction()) ? "screen off" : "screen on");
                    if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                        Runnable s = onSleep;
                        if (s != null) s.run();
                        return;
                    }
                    wakePending = true;
                    wokeAt = SystemClock.elapsedRealtime();
                    Runnable r = onWake;
                    if (r != null) r.run();
                }
            }, screenFilter());
        } catch (RuntimeException ignored) {
        }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Diag.event("low memory, level " + level + " " + Diag.memory());
            Runnable t = onTrim;
            if (t != null) t.run();
        }
    }
}
