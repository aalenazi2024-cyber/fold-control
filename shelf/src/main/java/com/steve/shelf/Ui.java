package com.steve.shelf;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Plain-view helpers (same style as the other apps in this repo). */
final class Ui {
    private Ui() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float size) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        return t;
    }

    static TextView bold(Context c, String s, float size) {
        TextView t = text(c, s, size);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    static Button button(Context c, String s, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    static LinearLayout row(Context c, View... views) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    /** A rounded tile with a title and a subtitle, used for collections. */
    static LinearLayout tile(Context c, String title, String sub, int color, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.BOTTOM);
        int p = dp(c, 14);
        t.setPadding(p, p, p, p);
        t.setMinimumHeight(dp(c, 104));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(c, 18));
        t.setBackground(bg);
        TextView a = bold(c, title, 17);
        a.setTextColor(Color.WHITE);
        TextView b = text(c, sub, 13);
        b.setTextColor(0xDDFFFFFF);
        t.addView(a);
        t.addView(b);
        t.setOnClickListener(l);
        return t;
    }

    static String duration(long secs) {
        if (secs <= 0) return "";
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1e9);
    }

    static final int[] COLORS = {
            0xFF1F6FEB, 0xFF8957E5, 0xFFD2691E, 0xFF2DA44E, 0xFFCF222E, 0xFF0E8A7E, 0xFFB08800, 0xFF6E7781, 0xFF3B5BDB
    };
}
