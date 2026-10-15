package com.steve.triage;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sees each notification from the apps you chose (Outlook and Teams by default), judges it, and:
 *   important → replaces it with a summary alert (Snooze / Reply / Done)
 *   FYI       → removes it and adds a line to the silent digest
 *   noise     → presses the app's own "Mark as read" if it offers one, removes it, adds to the digest
 *   hidden    → leaves it untouched (when the app/company policy hides the content)
 */
public class Listener extends NotificationListenerService {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Map<String, Boolean> SEEN = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 500;
        }
    };

    @Override
    public void onListenerConnected() {
        Alerts.channels(this);
        io.execute(() -> Store.get(this).prune());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (getPackageName().equals(sbn.getPackageName())) return;
        Prefs p = new Prefs(this);
        if (!p.gated(sbn.getPackageName())) return;
        Incoming in = Incoming.from(this, sbn);
        if (in == null) return;
        String sig = sbn.getKey() + "|" + (in.title + "\u0000" + in.text).hashCode();
        synchronized (SEEN) {
            if (SEEN.put(sig, Boolean.TRUE) != null) return;
        }
        Notification n = sbn.getNotification();
        if (n.contentIntent != null) Live.put(sbn.getKey(), n.contentIntent);
        io.execute(() -> handle(sbn, in, p));
    }

    private void handle(StatusBarNotification sbn, Incoming in, Prefs p) {
        Judge.Decision d;
        try {
            d = Judge.decide(this, in);
        } catch (Throwable t) {
            return; // never swallow a notification we failed to judge
        }
        if (d.v == Judge.V.PASS) return;

        Item it = new Item();
        it.pkg = in.pkg;
        it.app = in.app;
        it.sender = in.sender;
        it.title = in.title.equals(in.sender) ? firstLine(in.text) : in.title;
        it.body = in.text;
        it.summary = d.summary;
        it.reason = d.reason;
        it.reply = d.reply == null ? "" : d.reply;
        it.verdict = d.v.name().toLowerCase(Locale.ROOT);
        it.status = d.v == Judge.V.IMPORTANT ? "shown" : d.v == Judge.V.FYI ? "fyi" : "quiet";
        it.created = in.postTime > 0 ? in.postTime : System.currentTimeMillis();
        it.srcKey = in.key;

        switch (d.v) {
            case IMPORTANT:
                Store.get(this).insert(it);
                quietly(sbn);
                Alerts.showImportant(this, it, false);
                break;
            case FYI:
                if (!p.on(Prefs.DIGEST)) {
                    it.status = "seen";
                    Store.get(this).insert(it);
                    return; // leave the app's own notification alone
                }
                Store.get(this).insert(it);
                quietly(sbn);
                Alerts.digest(this);
                break;
            case NOISE:
                Store.get(this).insert(it);
                if (p.on(Prefs.MARK_READ)) pressMarkRead(sbn.getNotification());
                quietly(sbn);
                Alerts.digest(this);
                break;
            default:
                break;
        }
    }

    /** Removes the app's notification, and its group header if nothing else is left under it. */
    private void quietly(StatusBarNotification sbn) {
        try {
            cancelNotification(sbn.getKey());
        } catch (Exception ignored) {
        }
        String group = sbn.getNotification().getGroup();
        if (group == null) return;
        try {
            List<String> headers = new ArrayList<>();
            boolean othersLeft = false;
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification a : active) {
                if (!a.getPackageName().equals(sbn.getPackageName()) || !group.equals(a.getNotification().getGroup())) continue;
                if ((a.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) headers.add(a.getKey());
                else if (!a.getKey().equals(sbn.getKey())) othersLeft = true;
            }
            if (!othersLeft) for (String k : headers) cancelNotification(k);
        } catch (Exception ignored) {
        }
    }

    /** If the app puts a "Mark as read" button on its notification, press it. */
    private static void pressMarkRead(Notification n) {
        if (n.actions == null) return;
        for (Notification.Action a : n.actions) {
            String t = a.title == null ? "" : a.title.toString().toLowerCase(Locale.ROOT);
            if (t.contains("mark as read") || t.contains("mark read") || t.equals("read") || t.contains("كمقروء")) {
                try {
                    a.actionIntent.send();
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    private static String firstLine(String s) {
        for (String l : s.split("\\n")) if (!l.trim().isEmpty()) return Judge.cut(l, 140);
        return "";
    }
}
