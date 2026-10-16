package com.steve.shelf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Battery-optimization exemption. Without it Android cuts an idle app off the internet
 * (downloads fail with "No address associated with hostname") and won't let it start its
 * download service from the background.
 */
final class Power {
    private Power() {}

    static boolean exempt(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static void askExemption(Activity a) {
        if (exempt(a)) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {
        }
    }
}
