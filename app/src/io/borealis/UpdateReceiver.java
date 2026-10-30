package io.borealis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Android's installer reports back here: when it needs the person's OK it gives us its confirmation screen to open. */
public class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION = "io.borealis.UPDATE_STATUS";

    @Override public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (RuntimeException e) {
                    AppLog.w("update confirmation could not open: " + e.getMessage());
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            AppLog.w("update install status " + status + ": " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
