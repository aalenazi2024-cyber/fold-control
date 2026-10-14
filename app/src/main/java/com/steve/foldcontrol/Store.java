package com.steve.foldcontrol;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small persistent state plus a rolling activity log shown in the app. */
public final class Store {
    private static final int MAX_LOG_LINES = 120;

    private Store() {}

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    public static synchronized void log(Context c, String msg) {
        android.util.Log.i("FoldControl", msg);
        String ts = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date());
        SharedPreferences p = prefs(c);
        String existing = p.getString("log", "");
        String all = ts + "  " + msg + "\n" + existing;
        String[] lines = all.split("\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_LOG_LINES; i++) sb.append(lines[i]).append('\n');
            all = sb.toString();
        }
        p.edit().putString("log", all).apply();
    }

    public static String readLog(Context c) {
        String s = prefs(c).getString("log", "");
        return s.isEmpty() ? "(nothing yet)" : s;
    }
}
