package com.steve.triage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** All settings. Everything stays on the phone. */
final class Prefs {
    static final String OUTLOOK = "com.microsoft.office.outlook";
    static final String TEAMS = "com.microsoft.teams";

    static final String ENABLED = "enabled";
    static final String GATE_ALL = "gate_all";
    static final String DIGEST = "digest";
    static final String MARK_READ = "mark_read";
    static final String NAME = "name";
    static final String VIP = "vip";
    static final String URGENT = "urgent";
    static final String NOISE = "noise";
    static final String APPS = "apps";
    static final String LLM_URL = "llm_url";
    static final String LLM_MODEL = "llm_model";
    static final String PROFILE = "profile";

    private final SharedPreferences p;

    Prefs(Context c) {
        p = c.getSharedPreferences("triage", Context.MODE_PRIVATE);
    }

    String get(String k) {
        return p.getString(k, def(k));
    }

    boolean on(String k) {
        return p.getBoolean(k, defOn(k));
    }

    void put(String k, String v) {
        p.edit().putString(k, v).apply();
    }

    void set(String k, boolean v) {
        p.edit().putBoolean(k, v).apply();
    }

    /** Comma- or line-separated list, lower-cased. */
    List<String> list(String k) {
        List<String> out = new ArrayList<>();
        for (String s : get(k).split("[,\\n]")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    boolean gated(String pkg) {
        return on(ENABLED) && (on(GATE_ALL) || list(APPS).contains(pkg.toLowerCase(Locale.ROOT)));
    }

    void addVip(String name) {
        String cur = get(VIP).trim();
        put(VIP, cur.isEmpty() ? name : cur + ", " + name);
    }

    private static String def(String k) {
        switch (k) {
            case NAME:
                return "Steve";
            case URGENT:
                return "urgent, asap, immediately, deadline, approve, approval, sign, signature, "
                        + "sama, regulator, central bank, board, breach, incident, outage, fraud, "
                        + "legal, lawsuit, payment failed, عاجل, ضروري, موافقة, توقيع, ساما";
            case NOISE:
                return "unsubscribe, newsletter, webinar, % off, sale, offer, promo, promotion, discount, "
                        + "digest, no-reply, noreply, register now, limited time, free trial, "
                        + "weekly update, عرض, خصم, تخفيضات";
            case APPS:
                return OUTLOOK + ", " + TEAMS;
            case LLM_MODEL:
                return "qwen3";
            case PROFILE:
                return "Founder & CEO of Barq, a SAMA-licensed Saudi fintech e-wallet. Sits on several boards. "
                        + "Always matters: SAMA/regulators, board, investors, banking and card-scheme partners, "
                        + "legal, auditors, exec team decisions, security or outage incidents, family. "
                        + "Noise: vendor pitches, event and webinar marketing, newsletters, automated mail.";
            default:
                return "";
        }
    }

    private static boolean defOn(String k) {
        switch (k) {
            case ENABLED:
            case DIGEST:
            case MARK_READ:
                return true;
            default:
                return false;
        }
    }
}
