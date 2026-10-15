package com.steve.triage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms don't survive a reboot or an app update: put the snoozes back. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        long soon = System.currentTimeMillis() + 15_000;
        for (Item it : Store.get(c).byStatus(200, "snoozed")) {
            SnoozeActivity.schedule(c, it.id, Math.max(it.snoozeUntil, soon));
        }
    }
}
