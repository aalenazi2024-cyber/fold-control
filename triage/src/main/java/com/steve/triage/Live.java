package com.steve.triage;

import android.app.PendingIntent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Holds the original app's "open this message" intent, so our alert opens the exact email or chat. */
final class Live {
    private static final Map<String, PendingIntent> OPEN = new LinkedHashMap<String, PendingIntent>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PendingIntent> e) {
            return size() > 400;
        }
    };

    private Live() {}

    static synchronized void put(String key, PendingIntent pi) {
        if (key != null && pi != null) OPEN.put(key, pi);
    }

    static synchronized PendingIntent get(String key) {
        return key == null ? null : OPEN.get(key);
    }
}
