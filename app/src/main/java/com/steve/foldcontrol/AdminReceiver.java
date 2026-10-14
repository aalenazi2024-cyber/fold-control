package com.steve.foldcontrol;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** The component that makes this app the device owner. */
public class AdminReceiver extends DeviceAdminReceiver {

    public static ComponentName component(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        Store.log(context, "Admin enabled");
    }

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        Policy.onProvisioned(context);
    }
}
