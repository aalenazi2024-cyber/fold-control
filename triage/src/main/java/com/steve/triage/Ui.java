package com.steve.triage;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Small helpers for building plain-view screens in code (same style as Door Control). */
final class Ui {
    private Ui() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Button button(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
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

    static TextView text(Context c, String s, float size) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        return t;
    }

    static TextView header(Context c, String s) {
        TextView t = text(c, s, 17);
        t.setPadding(0, dp(c, 18), 0, dp(c, 4));
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    static EditText field(Context c, LinearLayout parent, String label, String value, boolean multi) {
        TextView l = text(c, label, 12);
        l.setPadding(0, dp(c, 10), 0, 0);
        parent.addView(l);
        EditText e = new EditText(c);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(2);
            e.setMaxLines(6);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
        e.setText(value);
        parent.addView(e);
        return e;
    }

    static Switch toggle(Context c, LinearLayout parent, String label, boolean on) {
        Switch s = new Switch(c);
        s.setText(label);
        s.setChecked(on);
        s.setPadding(0, dp(c, 8), 0, dp(c, 8));
        parent.addView(s);
        return s;
    }
}
