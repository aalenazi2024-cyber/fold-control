package com.steve.shelf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts background downloading after install (Fold Control's KICK), reboot or update. */
public class KickReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Store.prefs(context).getBoolean("kicked_logged", false) == false) {
            Store.log(context, "Started by " + intent.getAction());
            Store.prefs(context).edit().putBoolean("kicked_logged", true).apply();
        }
        if (!Engine.isBusy()) DownloadJob.schedule(context, 0);
    }
}
