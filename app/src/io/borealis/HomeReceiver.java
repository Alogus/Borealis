package io.borealis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, or this app was updated. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Borealis mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            // Start over on the new version: the opening plays and the person lands on the home screen.
            try {
                Intent home = new Intent(context, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(HomeActivity.EXTRA_UPDATED, true);
                context.startActivity(home);
            } catch (RuntimeException e) {
                AppLog.w("could not reopen after update: " + e.getMessage());
            }
        }
    }
}
