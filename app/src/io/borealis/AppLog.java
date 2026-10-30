package io.borealis;

import android.util.Log;

/** The app's log (adb logcat -s Borealis). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("Borealis", message);
        Diag.event(message);
    }
}
