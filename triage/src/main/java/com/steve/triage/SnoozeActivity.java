package com.steve.triage;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** "Remind me in…" picker shown over whatever you're doing. */
public class SnoozeActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        long id = getIntent().getLongExtra("id", -1);
        Item it = Store.get(this).get(id);
        if (it == null) {
            finish();
            return;
        }
        long now = System.currentTimeMillis();
        List<String> labels = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        add(labels, times, "5 minutes", now + 5 * 60_000L);
        add(labels, times, "30 minutes", now + 30 * 60_000L);
        add(labels, times, "1 hour", now + 60 * 60_000L);
        add(labels, times, "2 hours", now + 2 * 3600_000L);
        add(labels, times, "3 hours", now + 3 * 3600_000L);
        ZoneId z = ZoneId.systemDefault();
        LocalDateTime t = LocalDateTime.now(z);
        if (t.toLocalTime().isBefore(LocalTime.of(19, 30))) {
            add(labels, times, "This evening (8 pm)", t.toLocalDate().atTime(20, 0).atZone(z).toInstant().toEpochMilli());
        }
        add(labels, times, "Tomorrow 9 am", t.toLocalDate().plusDays(1).atTime(9, 0).atZone(z).toInstant().toEpochMilli());

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remind me: " + Judge.cut(it.sender + " — " + it.summary, 80))
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    long at = times.get(w);
                    Store.get(this).snooze(id, at);
                    schedule(this, id, at);
                    Alerts.cancel(this, id);
                    String when = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), z)
                            .format(DateTimeFormatter.ofPattern("EEE HH:mm"));
                    Toast.makeText(this, "Snoozed until " + when, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private static void add(List<String> l, List<Long> t, String label, long at) {
        l.add(label);
        t.add(at);
    }

    static void schedule(Context c, long id, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, (int) (id * 8 + 7), new Intent(c, ActionReceiver.class)
                .setAction(ActionReceiver.WAKE).putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }
}
