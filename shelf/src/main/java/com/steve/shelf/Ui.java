package com.steve.shelf;

import android.app.Activity;
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

/** Shared look: dark theme, colors, type and small view builders. */
final class Ui {
    private Ui() {}

    // Palette
    static final int BG = 0xFF0B0D10;
    static final int SURFACE = 0xFF15191F;
    static final int SURFACE_2 = 0xFF1E242C;
    static final int TEXT = 0xFFF2F4F7;
    static final int MUTED = 0xFF98A2B3;
    static final int ACCENT = 0xFF3B82F6;
    static final int[] COLORS = {
            0xFF3B82F6, 0xFF8B5CF6, 0xFFF59E0B, 0xFF10B981, 0xFFEF4444, 0xFF14B8A6, 0xFFEAB308, 0xFFEC4899, 0xFF6366F1
    };

    static final Typeface SANS = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface SANS_MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface SANS_BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static void darkWindow(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setBackgroundColor(BG);
    }

    static TextView text(Context c, String s, float size) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(TEXT);
        t.setTypeface(SANS);
        return t;
    }

    static TextView muted(Context c, String s, float size) {
        TextView t = text(c, s, size);
        t.setTextColor(MUTED);
        return t;
    }

    static TextView bold(Context c, String s, float size) {
        TextView t = text(c, s, size);
        t.setTypeface(SANS_BOLD);
        return t;
    }

    static TextView medium(Context c, String s, float size) {
        TextView t = text(c, s, size);
        t.setTypeface(SANS_MED);
        return t;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Pill-shaped button. */
    static TextView pill(Context c, String s, int bg, int fg, View.OnClickListener l) {
        TextView t = medium(c, s, 14);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 16), v = dp(c, 10);
        t.setPadding(h, v, h, v);
        t.setBackground(rounded(bg, dp(c, 22)));
        t.setOnClickListener(l);
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

    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static View space(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp)));
        return v;
    }

    static int colorFor(int index) {
        return COLORS[Math.abs(index) % COLORS.length];
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static String duration(long secs) {
        if (secs <= 0) return "";
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1e9);
    }
}
