package com.steve.shelf;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Preferences, a short activity log, and where files live. */
final class Store {
    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("shelf", Context.MODE_PRIVATE);
    }

    /** Root for downloaded media: app-specific storage, no permission needed. */
    static File mediaRoot(Context c) {
        File base = c.getApplicationContext().getExternalFilesDir(null);
        if (base == null) base = c.getApplicationContext().getFilesDir();
        File root = new File(base, "shelf");
        root.mkdirs();
        return root;
    }

    static File collectionDir(Context c, String collectionId) {
        File d = new File(mediaRoot(c), collectionId);
        d.mkdirs();
        return d;
    }

    static synchronized void log(Context c, String msg) {
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String prev = prefs(c).getString("log", "");
        String next = stamp + "  " + msg + "\n" + prev;
        if (next.length() > 12000) next = next.substring(0, 12000);
        prefs(c).edit().putString("log", next).apply();
    }

    static String log(Context c) {
        return prefs(c).getString("log", "");
    }
}
