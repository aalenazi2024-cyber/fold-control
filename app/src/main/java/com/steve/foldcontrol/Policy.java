package com.steve.foldcontrol;

import android.app.admin.DevicePolicyManager;
import android.app.admin.SystemUpdatePolicy;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Everything that changes the phone goes through here. */
public final class Policy {
    private Policy() {}

    /** Never hidden, whatever the config says: hiding these would break the phone. */
    private static final String[] ALWAYS_PROTECTED = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.shell",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.samsung.android.knox.containercore",
            "com.sec.android.app.SecSetupWizard",
            "com.google.android.setupwizard",
    };

    public static DevicePolicyManager dpm(Context c) {
        return c.getSystemService(DevicePolicyManager.class);
    }

    public static boolean isOwner(Context c) {
        return dpm(c).isDeviceOwnerApp(c.getPackageName());
    }

    /** Runs once at the end of setup. Deliberately does not hide anything yet. */
    public static void onProvisioned(Context c) {
        if (Store.prefs(c).getBoolean("provisioned", false)) return;
        Store.prefs(c).edit().putBoolean("provisioned", true).apply();
        Store.log(c, "Provisioned as device owner: " + isOwner(c));
        UpdateJob.schedule(c);
    }

    /** System updates install automatically as soon as they are available. */
    public static void applyUpdatePolicy(Context c) {
        if (!isOwner(c)) return;
        try {
            SystemUpdatePolicy current = dpm(c).getSystemUpdatePolicy();
            if (current != null && current.getPolicyType() == SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC) return;
            dpm(c).setSystemUpdatePolicy(AdminReceiver.component(c),
                    SystemUpdatePolicy.createAutomaticInstallPolicy());
            Store.log(c, "System updates set to install automatically");
        } catch (Exception e) {
            Store.log(c, "Update policy failed: " + e.getMessage());
        }
    }

    /** Packages that must stay visible right now: system core plus whatever is currently a default app. */
    public static Set<String> protectedPackages(Context c) {
        Set<String> s = new HashSet<>();
        for (String p : ALWAYS_PROTECTED) s.add(p);
        s.add(c.getPackageName());
        PackageManager pm = c.getPackageManager();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) s.add(ri.activityInfo.packageName);
        } catch (Exception ignored) {}
        try {
            String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) s.add(ime.substring(0, ime.indexOf('/')));
        } catch (Exception ignored) {}
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(c);
            if (sms != null) s.add(sms);
        } catch (Exception ignored) {}
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            if (tm != null && tm.getDefaultDialerPackage() != null) s.add(tm.getDefaultDialerPackage());
        } catch (Exception ignored) {}
        return s;
    }

    /** Returns a short result message for the UI. */
    public static String setHidden(Context c, String pkg, boolean hidden) {
        if (!isOwner(c)) return "Not device owner, can't change apps";
        if (hidden && protectedPackages(c).contains(pkg)) return "Protected: " + pkg;
        try {
            boolean ok = dpm(c).setApplicationHidden(AdminReceiver.component(c), pkg, hidden);
            String msg = (hidden ? "Hid " : "Restored ") + pkg + (ok ? "" : " (no change)");
            Store.log(c, msg);
            return msg;
        } catch (Exception e) {
            String msg = "Failed on " + pkg + ": " + e.getMessage();
            Store.log(c, msg);
            return msg;
        }
    }

    public static boolean isHidden(Context c, String pkg) {
        try {
            return isOwner(c) && dpm(c).isApplicationHidden(AdminReceiver.component(c), pkg);
        } catch (Exception e) {
            return false;
        }
    }

    /** Hides everything on the config's list that is installed and not protected. */
    public static int applyHideList(Context c, JSONObject config) {
        if (!isOwner(c)) return 0;
        JSONArray list = config.optJSONArray("hide");
        if (list == null) return 0;
        Set<String> prot = protectedPackages(c);
        PackageManager pm = c.getPackageManager();
        int count = 0;
        for (int i = 0; i < list.length(); i++) {
            String pkg = list.optString(i, "");
            if (pkg.isEmpty() || prot.contains(pkg)) continue;
            try {
                pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // not on this phone
            }
            if (isHidden(c, pkg)) continue;
            setHidden(c, pkg, true);
            count++;
        }
        // Explicit restores, so an app can be brought back by editing config.json
        JSONArray show = config.optJSONArray("show");
        if (show != null) {
            for (int i = 0; i < show.length(); i++) {
                String pkg = show.optString(i, "");
                if (!pkg.isEmpty() && isHidden(c, pkg)) setHidden(c, pkg, false);
            }
        }
        return count;
    }

    /** Remote config if we have fetched one, otherwise the copy shipped inside the app. */
    public static JSONObject config(Context c) {
        String remote = Store.prefs(c).getString("remote_config", null);
        if (remote != null) {
            try { return new JSONObject(remote); } catch (Exception ignored) {}
        }
        try (InputStream in = c.getAssets().open("config.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
