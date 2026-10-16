package com.steve.shelf;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * What to download. The live copy is shelf/catalog.json on GitHub, so the list can be
 * changed without a new app build. A copy is bundled in the APK as a fallback.
 */
final class Catalog {
    static final String URL_LIVE =
            "https://raw.githubusercontent.com/aalenazi2024-cyber/fold-control/main/shelf/catalog.json";

    private Catalog() {}

    /** Fetches the live catalog (at most every 20 min), else the cached or bundled copy. */
    static JSONObject load(Context c, boolean allowNetwork) {
        long last = Store.prefs(c).getLong("catalog_fetched", 0);
        if (allowNetwork && System.currentTimeMillis() - last > 20 * 60 * 1000L) {
            try {
                String body = Net.getString(URL_LIVE + "?t=" + System.currentTimeMillis());
                new JSONObject(body);
                Store.prefs(c).edit()
                        .putString("catalog", body)
                        .putLong("catalog_fetched", System.currentTimeMillis())
                        .apply();
            } catch (Exception e) {
                Store.log(c, "Catalog fetch failed: " + e.getMessage());
            }
        }
        String cached = Store.prefs(c).getString("catalog", null);
        if (cached != null) {
            try {
                return new JSONObject(cached);
            } catch (Exception ignored) {
            }
        }
        try (InputStream in = c.getAssets().open("catalog.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Epoch millis after which automatic downloading stops; 0 = never. */
    static long stopAt(JSONObject cat) {
        String s = cat.optString("stopAt", "");
        if (s.isEmpty()) return 0;
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    static JSONArray collections(JSONObject cat) {
        JSONArray a = cat.optJSONArray("collections");
        return a != null ? a : new JSONArray();
    }

    static JSONObject collection(JSONObject cat, String id) {
        JSONArray a = collections(cat);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    static boolean isAudio(JSONObject cat, String collectionId) {
        JSONObject col = collection(cat, collectionId);
        return col != null && "audio".equals(col.optString("type"));
    }

    static String title(JSONObject cat, String collectionId) {
        JSONObject col = collection(cat, collectionId);
        return col != null ? col.optString("title", collectionId) : collectionId;
    }

    /** Minimal HTTP helper. */
    static final class Net {
        private Net() {}

        static String getString(String url) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "shelf");
            try {
                int code = conn.getResponseCode();
                if (code >= 400) throw new Exception("HTTP " + code);
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                conn.disconnect();
            }
        }
    }
}
