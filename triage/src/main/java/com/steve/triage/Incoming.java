package com.steve.triage;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import java.util.Locale;
import java.util.regex.Pattern;

/** What a notification says: who, about what, in which app. */
final class Incoming {
    private static final Pattern GENERIC = Pattern.compile(
            "^(new (message|email|mail|notification)s?|you have (a )?new .*|\\d+ new (messages|emails|notifications)|"
                    + "you've got mail|رسالة جديدة|لديك رسالة جديدة)$", Pattern.CASE_INSENSITIVE);

    String pkg = "", app = "", sender = "", title = "", text = "", conversation = "", key = "";
    boolean group;
    long postTime;

    static Incoming from(Context c, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || !sbn.isClearable()) return null;
        Bundle e = n.extras;
        if (e == null) return null;

        Incoming in = new Incoming();
        in.pkg = sbn.getPackageName();
        in.key = sbn.getKey();
        in.postTime = sbn.getPostTime();
        in.title = str(e.getCharSequence(Notification.EXTRA_TITLE));
        in.conversation = str(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        in.group = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        String big = str(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String txt = str(e.getCharSequence(Notification.EXTRA_TEXT));
        String sub = str(e.getCharSequence(Notification.EXTRA_SUB_TEXT));

        // Chat apps (Teams) use MessagingStyle: read the last few messages with their senders.
        String sender = in.title;
        StringBuilder msgs = new StringBuilder();
        Parcelable[] arr = e.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
        if (arr != null) {
            for (int i = Math.max(0, arr.length - 4); i < arr.length; i++) {
                if (!(arr[i] instanceof Bundle)) continue;
                Bundle b = (Bundle) arr[i];
                String t = str(b.getCharSequence("text"));
                String s = str(b.getCharSequence("sender"));
                if (s.isEmpty()) {
                    Person p = b.getParcelable("sender_person", Person.class);
                    if (p != null && p.getName() != null) s = p.getName().toString();
                }
                if (t.isEmpty()) continue;
                if (!s.isEmpty()) {
                    msgs.append(s).append(": ");
                    sender = s;
                }
                msgs.append(t).append('\n');
            }
        }
        in.sender = sender.isEmpty() ? in.title : sender;
        String body = msgs.length() > 0 ? msgs.toString().trim() : (!big.isEmpty() ? big : txt);
        if (!sub.isEmpty() && !body.contains(sub)) body = body + "\n(" + sub + ")";
        in.text = body.length() > 2500 ? body.substring(0, 2500) : body;
        in.app = label(c, in.pkg);
        return in;
    }

    /** True when the app hides the content (e.g. a company policy shows only "New message"). */
    boolean hidden() {
        String t = text.trim();
        return t.isEmpty() || GENERIC.matcher(t).matches();
    }

    String haystack() {
        return (sender + "\n" + title + "\n" + conversation + "\n" + text).toLowerCase(Locale.ROOT);
    }

    String who() {
        return (sender + " " + title).toLowerCase(Locale.ROOT);
    }

    boolean isTeams() {
        return pkg.startsWith(Prefs.TEAMS);
    }

    private static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
