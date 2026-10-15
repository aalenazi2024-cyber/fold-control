package com.steve.triage;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Home: setup checks, what needs you, what's snoozed, and what was filtered (long-press to correct). */
public class MainActivity extends Activity {
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = Ui.dp(this, 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        root.addView(Ui.text(this, "Triage", 24));
        TextView hint = Ui.text(this, "Only what needs you makes a sound, with a summary. Swipe = ignore. "
                + "Snooze = remind me later. Everything else waits silently here.", 13);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        root.addView(hint);
        root.addView(Ui.row(this,
                Ui.button(this, "Settings", v -> startActivity(new Intent(this, SettingsActivity.class))),
                Ui.button(this, "Test alert", v -> testAlert())));

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        body.removeAllViews();
        setupChecks();
        Store st = Store.get(this);
        section("Needs you", st.byStatus(15, "shown"), "Nothing waiting for you.", false);
        section("Snoozed", st.byStatus(20, "snoozed"), "Nothing snoozed.", false);
        section("Filtered — long-press anything that should have alerted you",
                st.byStatus(40, "fyi", "quiet", "seen"), "Nothing filtered yet.", true);
    }

    private void setupChecks() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean access = nm.isNotificationListenerAccessGranted(new ComponentName(this, Listener.class));
        boolean post = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean exact = getSystemService(AlarmManager.class).canScheduleExactAlarms();
        if (access && post && exact) return;
        body.addView(Ui.header(this, "Finish setup"));
        if (!access) {
            body.addView(Ui.button(this, "1. Allow notification access (required)", v -> {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                new ComponentName(this, Listener.class).flattenToString());
                try {
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                }
            }));
        }
        if (!post) {
            body.addView(Ui.button(this, "2. Allow Triage to alert you", v ->
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1)));
        }
        if (!exact) {
            body.addView(Ui.button(this, "3. Allow exact snooze times", v ->
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())))));
        }
    }

    private void section(String title, List<Item> items, String empty, boolean filtered) {
        body.addView(Ui.header(this, title + (items.isEmpty() ? "" : " (" + items.size() + ")")));
        if (items.isEmpty()) {
            body.addView(Ui.text(this, empty, 14));
            return;
        }
        for (Item it : items) {
            String when = "snoozed".equals(it.status)
                    ? "until " + DateUtils.formatDateTime(this, it.snoozeUntil, DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY)
                    : DateUtils.getRelativeTimeSpanString(it.created).toString();
            TextView t = Ui.text(this, it.sender + " · " + it.app + " · " + when + "\n" + it.summary
                    + (filtered && !it.reason.isEmpty() ? "\n(" + it.reason + ")" : ""), 14);
            int p = Ui.dp(this, 8);
            t.setPadding(0, p, 0, p);
            t.setOnClickListener(v -> startActivity(new Intent(this, OpenActivity.class)
                    .putExtra("id", it.id).putExtra("mode", "open")));
            if (filtered) t.setOnLongClickListener(v -> {
                correct(it);
                return true;
            });
            body.addView(t);
        }
    }

    /** Teach Triage: this sender always alerts you. */
    private void correct(Item it) {
        new AlertDialog.Builder(this)
                .setTitle(it.sender)
                .setMessage("Always alert me when " + it.sender + " writes?")
                .setPositiveButton("Always alert", (d, w) -> {
                    new Prefs(this).addVip(it.sender);
                    for (int i = 0; i < 3; i++) Store.get(this).bump(it.senderKey(), true);
                    Toast.makeText(this, it.sender + " added to VIP", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void testAlert() {
        Item it = new Item();
        it.pkg = Prefs.OUTLOOK;
        it.app = "Outlook";
        it.sender = "Test sender";
        it.title = "Q4 board pack — approval needed";
        it.body = "Please approve the Q4 board pack by Thursday so we can circulate it.";
        it.summary = "Board pack needs your approval by Thursday";
        it.reason = "test";
        it.reply = "Approved. Circulate Thursday morning.";
        it.verdict = "important";
        it.status = "shown";
        it.created = System.currentTimeMillis();
        Store.get(this).insert(it);
        Alerts.showImportant(this, it, false);
        refresh();
    }
}
