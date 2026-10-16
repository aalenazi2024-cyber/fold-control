package com.steve.foldcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Receives the outcome of each install and records it. */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String key = intent.getStringExtra("key");
        long assetId = intent.getLongExtra("asset_id", -1);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Only happens if we are not device owner (e.g. testing on another phone)
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            if (key != null && !"self".equals(key)) {
                Store.prefs(context).edit().putLong("asset_" + key, assetId).apply();
            }
            Store.log(context, "Installed " + (pkg != null ? pkg : key));
            if (pkg != null && !"self".equals(key)) Sync.kick(context, pkg);
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Store.log(context, "Install failed for " + key + ": " + status + " " + msg);
        }
    }
}
