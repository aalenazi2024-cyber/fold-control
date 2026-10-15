package com.steve.triage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import java.util.Arrays;
import java.util.List;

/**
 * The only notifications you see:
 *   "Needs you" — loud, with summary, Snooze / Reply / Done; swipe away = ignore.
 *   "Filtered"  — one silent, collapsed digest of everything that was held back.
 */
final class Alerts {
    static final String CH_NOW = "needs_you";
    static final String CH_QUIET = "filtered";
    private static final String TAG = "item";
    private static final String TAG_DIGEST = "digest";
    private static final int FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

    private Alerts() {}

    static void channels(Context c) {
        NotificationChannel now = new NotificationChannel(CH_NOW, "Needs you", NotificationManager.IMPORTANCE_HIGH);
        now.setDescription("Emails and mentions that need you, with a summary");
        now.enableVibration(true);
        NotificationChannel quiet = new NotificationChannel(CH_QUIET, "Filtered (silent)", NotificationManager.IMPORTANCE_MIN);
        quiet.setDescription("Silent digest of what Triage held back");
        quiet.setShowBadge(false);
        nm(c).createNotificationChannels(Arrays.asList(now, quiet));
    }

    static void showImportant(Context c, Item it, boolean reminder) {
        channels(c);
        int id = (int) it.id;
        PendingIntent open = PendingIntent.getActivity(c, rc(id, 0), new Intent(c, OpenActivity.class)
                .setAction("open:" + id).putExtra("id", it.id).putExtra("mode", "open")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS);
        PendingIntent snooze = PendingIntent.getActivity(c, rc(id, 1), new Intent(c, SnoozeActivity.class)
                .setAction("snooze:" + id).putExtra("id", it.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY), FLAGS);
        PendingIntent done = broadcast(c, rc(id, 2), ActionReceiver.DONE, it.id);
        PendingIntent ignore = broadcast(c, rc(id, 3), ActionReceiver.IGNORE, it.id);

        StringBuilder big = new StringBuilder(it.summary);
        if (!it.title.isEmpty() && !it.summary.contains(it.title)) big.append("\n\n").append(Judge.cut(it.title, 120));
        if (it.hasReply()) big.append("\n\n✍️ Reply drafted — tap Reply to copy it and open the thread");
        if (!it.reason.isEmpty()) big.append("\nWhy: ").append(it.reason);

        Notification.Builder b = new Notification.Builder(c, CH_NOW)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle((reminder ? "⏰ " : "") + it.sender + " · " + it.app)
                .setContentText(it.summary)
                .setStyle(new Notification.BigTextStyle().bigText(big))
                .setContentIntent(open)
                .setDeleteIntent(ignore)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EMAIL)
                .setWhen(it.created)
                .setShowWhen(true)
                .addAction(action(c, "Snooze", snooze));
        if (it.hasReply()) {
            PendingIntent reply = PendingIntent.getActivity(c, rc(id, 4), new Intent(c, OpenActivity.class)
                    .setAction("reply:" + id).putExtra("id", it.id).putExtra("mode", "reply")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS);
            b.addAction(action(c, "Reply", reply));
        } else {
            b.addAction(action(c, "Open", open));
        }
        b.addAction(action(c, "Done", done));
        nm(c).notify(TAG, id, b.build());
    }

    static void cancel(Context c, long id) {
        nm(c).cancel(TAG, (int) id);
    }

    /** One silent, collapsed line for everything held back. Updated in place, never buzzes. */
    static void digest(Context c) {
        channels(c);
        List<Item> held = Store.get(c).byStatus(60, "fyi", "quiet");
        if (held.isEmpty()) {
            nm(c).cancel(TAG_DIGEST, 1);
            return;
        }
        int fyi = 0, quiet = 0;
        for (Item i : held) {
            if ("fyi".equals(i.status)) fyi++;
            else quiet++;
        }
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (int i = 0; i < Math.min(6, held.size()); i++) {
            Item it = held.get(i);
            style.addLine(("fyi".equals(it.status) ? "• " : "· ") + it.sender + ": " + Judge.cut(it.summary, 70));
        }
        if (held.size() > 6) style.setSummaryText("+" + (held.size() - 6) + " more in Triage");
        PendingIntent open = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), FLAGS);
        PendingIntent clear = PendingIntent.getBroadcast(c, 3, new Intent(c, ActionReceiver.class)
                .setAction(ActionReceiver.CLEAR), FLAGS);
        Notification n = new Notification.Builder(c, CH_QUIET)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(fyi + " FYI · " + quiet + " filtered")
                .setContentText(held.get(0).sender + ": " + Judge.cut(held.get(0).summary, 60))
                .setStyle(style)
                .setContentIntent(open)
                .setDeleteIntent(clear)
                .setOnlyAlertOnce(true)
                .build();
        nm(c).notify(TAG_DIGEST, 1, n);
    }

    private static PendingIntent broadcast(Context c, int rc, String action, long id) {
        return PendingIntent.getBroadcast(c, rc, new Intent(c, ActionReceiver.class)
                .setAction(action).putExtra("id", id), FLAGS);
    }

    private static Notification.Action action(Context c, String title, PendingIntent pi) {
        return new Notification.Action.Builder(
                Icon.createWithResource(c, android.R.drawable.ic_menu_send), title, pi).build();
    }

    private static int rc(int id, int slot) {
        return id * 8 + slot + 16;
    }

    private static NotificationManager nm(Context c) {
        return c.getSystemService(NotificationManager.class);
    }
}
