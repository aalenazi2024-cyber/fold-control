package com.steve.triage;

import java.util.Locale;

/** One notification Triage has judged. */
final class Item {
    long id;
    String pkg = "", app = "", sender = "", title = "", body = "";
    String summary = "", reason = "", reply = "", verdict = "", status = "", srcKey = "";
    long created, snoozeUntil;

    String senderKey() {
        return pkg + ":" + sender.toLowerCase(Locale.ROOT).trim();
    }

    boolean hasReply() {
        return reply != null && !reply.trim().isEmpty();
    }
}
