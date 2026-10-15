package com.steve.doorcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;

/** Small helpers for building plain-view screens in code. */
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

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }

    static void confirm(Activity a, String title, String msg, Runnable yes) {
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> yes.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Add/edit dialog. Calls {@code onSave} with the edited device. */
    static void editDevice(Activity a, Device existing, Consumer<Device> onSave, Runnable onDelete) {
        Device d = existing != null ? existing : new Device();
        int pad = dp(a, 20);
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, dp(a, 8), pad, 0);

        EditText name = field(a, form, "Name (e.g. Main gate)", d.name, InputType.TYPE_CLASS_TEXT);
        EditText host = field(a, form, "IP address (e.g. 192.168.1.64)", d.host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = field(a, form, "HTTP port", String.valueOf(d.port), InputType.TYPE_CLASS_NUMBER);
        EditText user = field(a, form, "User", d.user, InputType.TYPE_CLASS_TEXT);
        EditText pw = field(a, form, "Password", d.password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText doors = field(a, form, "Lock relays (1 or 2)", String.valueOf(d.doors), InputType.TYPE_CLASS_NUMBER);

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(existing == null ? "Add device" : "Edit device")
                .setView(form)
                .setPositiveButton("Save", (dlg, w) -> {
                    d.name = name.getText().toString().trim();
                    d.host = host.getText().toString().trim();
                    d.port = parse(port.getText().toString(), 80);
                    d.user = user.getText().toString().trim();
                    d.password = pw.getText().toString();
                    d.doors = Math.max(1, Math.min(4, parse(doors.getText().toString(), 2)));
                    if (d.host.isEmpty()) {
                        toast(a, "IP address is required");
                        return;
                    }
                    onSave.accept(d);
                })
                .setNegativeButton("Cancel", null);
        if (existing != null && onDelete != null) {
            b.setNeutralButton("Delete", (dlg, w) -> confirm(a, "Delete " + existing.label() + "?", "", onDelete));
        }
        b.show();
    }

    private static EditText field(Context c, LinearLayout parent, String hint, String value, int type) {
        TextView label = text(c, hint, 12);
        label.setPadding(0, dp(c, 8), 0, 0);
        parent.addView(label);
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setInputType(type);
        if (value != null) e.setText(value);
        parent.addView(e);
        return e;
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
