package com.steve.triage;

import android.content.Context;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides what a notification deserves, using context:
 *   - your VIP list and your name (Teams @mentions)
 *   - urgent vs noise words (English and Arabic)
 *   - what YOU did with this sender before (opened vs swiped away)
 *   - Teams: 1:1 vs group chat
 *   - optionally a local model for judgement, a summary and a reply draft
 * Hard rules (VIP, mention, urgent) are never downgraded by the model.
 */
final class Judge {
    enum V { IMPORTANT, FYI, NOISE, PASS }

    static final class Decision {
        V v;
        String summary = "", reason = "", reply = "";
    }

    static Decision decide(Context c, Incoming in) {
        Prefs p = new Prefs(c);
        Decision d = new Decision();
        if (in.hidden()) {
            d.v = V.PASS;
            d.reason = "content hidden by the app";
            return d;
        }

        String hay = in.haystack();
        String name = p.get(Prefs.NAME).trim().toLowerCase();
        String vip = firstHit(in.who(), p.list(Prefs.VIP));
        String urgent = firstHit(hay, p.list(Prefs.URGENT));
        String noise = firstHit(hay, p.list(Prefs.NOISE));
        boolean mention = in.isTeams() && (hay.contains("mentioned you") || hay.contains("mentioned your")
                || (!name.isEmpty() && hay.contains("@" + name)));
        int[] st = Store.get(c).stats(in.pkg + ":" + in.sender.toLowerCase().trim());

        V rule = null;
        boolean hard = false;
        String reason = "";
        if (vip != null) {
            rule = V.IMPORTANT; hard = true; reason = "VIP: " + vip;
        } else if (mention) {
            rule = V.IMPORTANT; hard = true; reason = "you were @mentioned in Teams";
        } else if (urgent != null && noise == null) {
            rule = V.IMPORTANT; hard = true; reason = "urgent: \"" + urgent + "\"";
        } else if (noise != null && urgent == null) {
            rule = V.NOISE; reason = "looks like bulk mail (\"" + noise + "\")";
        } else if (in.isTeams() && in.group) {
            rule = V.FYI; reason = "Teams group chat, no mention";
        }
        if (!hard) {
            if (st[1] >= 3 && st[0] == 0) {
                rule = V.NOISE; reason = "you swiped this sender away " + st[1] + "x";
            } else if (st[0] >= 3 && st[0] >= 2 * st[1]) {
                rule = V.IMPORTANT; reason = "you usually open this sender";
            }
        }

        String ctx = "Signals: " + (vip != null ? "VIP sender. " : "") + (mention ? "He was @mentioned. " : "")
                + (urgent != null ? "Urgent word '" + urgent + "'. " : "") + (noise != null ? "Bulk word '" + noise + "'. " : "")
                + "His history with this sender: opened " + st[0] + "x, swiped away " + st[1] + "x. "
                + (in.isTeams() ? (in.group ? "Teams group chat." : "Teams 1:1 chat.") : "");
        Llm.Result r = Llm.judge(p, in, ctx);
        if (r != null) {
            d.summary = r.summary;
            d.reply = r.reply;
            if (!hard && r.v != null) {
                d.v = r.v;
                d.reason = r.reason.isEmpty() ? reason : r.reason;
            }
        }
        if (d.v == null) {
            d.v = rule != null ? rule : V.FYI;
            d.reason = reason.isEmpty() ? "no strong signal" : reason;
        }
        if (d.summary == null || d.summary.trim().isEmpty()) d.summary = summarize(in);
        return d;
    }

    /** No-model summary: subject plus the first sentence of the preview. */
    static String summarize(Incoming in) {
        String[] lines = in.text.split("\\n");
        String first = "", rest = "";
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            if (first.isEmpty()) first = t;
            else { rest = t; break; }
        }
        if (in.isTeams()) return cut(in.text.replace('\n', ' '), 160);
        String s = cut(first, 90);
        if (!rest.isEmpty()) {
            int dot = rest.indexOf(". ");
            s += " — " + cut(dot > 20 ? rest.substring(0, dot + 1) : rest, 110);
        }
        return s;
    }

    static String firstHit(String hay, List<String> words) {
        for (String w : words) {
            if (w.matches("[a-z0-9 .'-]+")) {
                if (Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(w) + "($|[^a-z0-9])").matcher(hay).find()) return w;
            } else if (hay.contains(w)) {
                return w;
            }
        }
        return null;
    }

    static String cut(String s, int n) {
        s = s.trim();
        return s.length() <= n ? s : s.substring(0, n - 1).trim() + "…";
    }
}
