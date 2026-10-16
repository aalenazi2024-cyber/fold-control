package com.steve.foldcontrol;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * One sync pass: fetch config from GitHub, keep the hide list applied,
 * update this app, and install or update the apps listed in config.json.
 * Always call off the main thread.
 */
public final class Sync {
    public static final String SELF_REPO = "aalenazi2024-cyber/fold-control";
    public static final String SELF_ASSET = "fold-control.apk";
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/" + SELF_REPO + "/main/config.json";

    private Sync() {}

    public static synchronized void run(Context c) {
        Context ctx = c.getApplicationContext();
        Store.prefs(ctx).edit().putLong("last_sync", System.currentTimeMillis()).apply();

        // 1. Config
        try {
            String body = get(CONFIG_URL + "?t=" + System.currentTimeMillis());
            new JSONObject(body); // validate before storing
            Store.prefs(ctx).edit().putString("remote_config", body).apply();
        } catch (Exception e) {
            Store.log(ctx, "Config fetch failed: " + e.getMessage());
        }
        JSONObject config = Policy.config(ctx);

        // 2. Keep Android installing system updates automatically
        Policy.applyUpdatePolicy(ctx);

        // 3. Hide list (only after the user has pressed "Clean phone" once)
        if (Store.prefs(ctx).getBoolean("cleanup_enabled", false)) {
            int n = Policy.applyHideList(ctx, config);
            if (n > 0) Store.log(ctx, "Hid " + n + " apps from the list");
        }

        // 4. Apps from config
        JSONArray apps = config.optJSONArray("apps");
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                if (a == null) continue;
                try {
                    updateApp(ctx, a.getString("repo"), a.optString("asset", ""), a.optString("match", ""));
                    // Apps that should keep working in the background without being opened first.
                    String kickPkg = a.optString("kick", "");
                    if (!kickPkg.isEmpty() && isInstalled(ctx, kickPkg)) kick(ctx, kickPkg);
                } catch (Exception e) {
                    Store.log(ctx, "App check failed for " + a.optString("repo") + ": " + e.getMessage());
                }
            }
        }

        // 5. Ourselves last, because a successful self-update restarts this app
        try {
            updateSelf(ctx);
        } catch (Exception e) {
            Store.log(ctx, "Self-update check failed: " + e.getMessage());
        }
    }

    private static void updateSelf(Context c) throws Exception {
        JSONObject rel = new JSONObject(get("https://api.github.com/repos/" + SELF_REPO + "/releases/latest"));
        String tag = rel.optString("tag_name", "");
        long remote;
        try {
            remote = Long.parseLong(tag.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return;
        }
        long local = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        if (remote <= local) return;
        JSONObject asset = findAsset(rel, SELF_ASSET);
        if (asset == null) return;
        Store.log(c, "Updating Fold Control to " + tag);
        install(c, asset.getString("browser_download_url"), "self", asset.getLong("id"));
    }

    private static void updateApp(Context c, String repo, String assetName, String match) throws Exception {
        JSONObject rel = new JSONObject(get("https://api.github.com/repos/" + repo + "/releases/latest"));
        JSONObject asset = match.isEmpty() ? findAsset(rel, assetName) : findAssetContaining(rel, match);
        if (asset == null) {
            Store.log(c, "No APK in latest release of " + repo);
            return;
        }
        long id = asset.getLong("id");
        if (Store.prefs(c).getLong("asset_" + repo, -1) == id) return;
        Store.log(c, "Installing " + repo + " " + rel.optString("tag_name"));
        install(c, asset.getString("browser_download_url"), repo, id);
    }

    private static JSONObject findAsset(JSONObject release, String name) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        JSONObject firstApk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name");
            if (!name.isEmpty() && n.equals(name)) return a;
            if (firstApk == null && n.endsWith(".apk")) firstApk = a;
        }
        return name.isEmpty() ? firstApk : null;
    }

    /** First .apk whose file name contains the given text, e.g. "arm64-v8a". */
    private static JSONObject findAssetContaining(JSONObject release, String part) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name");
            if (n.endsWith(".apk") && n.contains(part)) return a;
        }
        return null;
    }

    /** Silent install: allowed without a prompt because we are the device owner. */
    public static void install(Context c, String url, String key, long assetId) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int sessionId = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            HttpURLConnection conn = open(url);
            try (InputStream in = conn.getInputStream();
                 OutputStream out = session.openWrite("base.apk", 0, -1)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                conn.disconnect();
            }
            Intent result = new Intent(c, InstallResultReceiver.class)
                    .putExtra("key", key)
                    .putExtra("asset_id", assetId);
            PendingIntent pending = PendingIntent.getBroadcast(c, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        } catch (Exception e) {
            try { pi.abandonSession(sessionId); } catch (Exception ignored) {}
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "fold-control");
        conn.setRequestProperty("Accept", "application/vnd.github+json, */*");
        int code = conn.getResponseCode();
        if (code >= 400) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " for " + url);
        }
        return conn;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Wakes an app we installed. A freshly installed app stays "stopped" (no jobs, no
     * broadcasts) until it is opened once; an explicit broadcast with this flag starts it.
     */
    public static void kick(Context c, String pkg) {
        try {
            c.sendBroadcast(new Intent("com.steve.action.KICK")
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND));
        } catch (Exception e) {
            Store.log(c, "Kick failed for " + pkg + ": " + e.getMessage());
        }
    }

    public static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
