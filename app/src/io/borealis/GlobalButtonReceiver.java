package io.borealis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * The remote's app buttons on TVs that use Google's "global button" mechanism: the TV maker's key service sends
 * com.google.global_button.ACTION_LAUNCH_APP and Google's home screen opens the app. With Google's home off (Free My
 * TV mode), nobody listened; this opens the app instead. Registered in the manifest and also at runtime (whichever the
 * system delivers to); a short guard keeps one press from opening the app twice.
 */
public final class GlobalButtonReceiver extends BroadcastReceiver {
    static final String ACTION = "com.google.global_button.ACTION_LAUNCH_APP";
    private static long lastAt;
    private final String via;

    /** The manifest needs a no-argument constructor. */
    public GlobalButtonReceiver() {
        this("manifest");
    }

    GlobalButtonReceiver(String via) {
        this.via = via;
    }

    /** Listens while this process lives (covers the case where manifest receivers don't get the broadcast). */
    static void register(Context app) {
        try {
            app.registerReceiver(new GlobalButtonReceiver("runtime"), new IntentFilter(ACTION));
        } catch (RuntimeException e) {
            AppLog.w("global button receiver: " + e.getMessage());
        }
    }

    @Override public void onReceive(Context c, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        StringBuilder sb = new StringBuilder("global button (" + via + "):");
        Bundle extras = intent.getExtras();
        String pkg = null;
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                sb.append(' ').append(key).append('=').append(value);
                if (pkg == null && value instanceof String && ((String) value).contains(".")
                        && HomeApps.launchIntent(c.getPackageManager(), (String) value) != null) pkg = (String) value;
            }
        }
        AppLog.w(sb.toString());
        long now = SystemClock.uptimeMillis();
        if (pkg == null || now - lastAt < 1500) return;
        lastAt = now;
        Intent launch = HomeApps.launchIntent(c.getPackageManager(), pkg);
        if (launch == null) return;
        if ("com.netflix.ninja".equals(pkg)) launch.setAction("com.netflix.action.NETFLIX_KEY_START");
        AppLog.w("global button -> " + pkg);
        c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }
}
