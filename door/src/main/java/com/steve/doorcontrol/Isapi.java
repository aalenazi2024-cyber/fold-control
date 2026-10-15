package com.steve.doorcontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The handful of Hikvision ISAPI calls the app uses, plus readable summaries of their answers. */
public final class Isapi {
    private Isapi() {}

    public static final String CMD_OPEN = "open";
    public static final String CMD_CLOSE = "close";
    public static final String CMD_ALWAYS_OPEN = "alwaysOpen";
    public static final String CMD_ALWAYS_CLOSE = "alwaysClose";

    /** Endpoints read by "Config readout". Unsupported ones just come back 4xx, which is itself useful. */
    public static final String[] CONFIG_PATHS = {
            "/ISAPI/System/deviceInfo",
            "/ISAPI/System/time",
            "/ISAPI/AccessControl/AcsWorkStatus?format=json",
            "/ISAPI/AccessControl/Door/param/1",
            "/ISAPI/AccessControl/Door/param/2",
            "/ISAPI/AccessControl/Door/param/1/capabilities",
            "/ISAPI/AccessControl/AcsCfg?format=json",
            "/ISAPI/AccessControl/capabilities",
            "/ISAPI/VideoIntercom/capabilities",
            "/ISAPI/VideoIntercom/deviceId",
            "/ISAPI/VideoIntercom/relatedDeviceAddress",
            "/ISAPI/System/IO/outputs",
            "/ISAPI/System/Network/interfaces",
    };

    public static HikClient.Response door(HikClient c, int door, String cmd) throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<RemoteControlDoor version=\"2.0\" xmlns=\"http://www.isapi.org/ver20/XMLSchema\">"
                + "<cmd>" + cmd + "</cmd></RemoteControlDoor>";
        return c.request("PUT", "/ISAPI/AccessControl/RemoteControl/door/" + door, body, "application/xml");
    }

    public static HikClient.Response workStatus(HikClient c) throws Exception {
        return c.get("/ISAPI/AccessControl/AcsWorkStatus?format=json");
    }

    public static HikClient.Response snapshot(HikClient c) throws Exception {
        return c.get("/ISAPI/Streaming/channels/101/picture");
    }

    /** One line: lock, door mode and door-contact state per door, with raw values. */
    public static String summarizeStatus(String json) {
        try {
            JSONObject s = new JSONObject(json).optJSONObject("AcsWorkStatus");
            if (s == null) return "no AcsWorkStatus in reply";
            return "lock " + list(s.optJSONArray("doorLockStatus"), LOCK)
                    + " | mode " + list(s.optJSONArray("doorStatus"), MODE)
                    + " | contact " + list(s.optJSONArray("magneticStatus"), CONTACT);
        } catch (Exception e) {
            return "unreadable status: " + e.getMessage();
        }
    }

    private static final String[] LOCK = {"locked", "unlocked", "short-circuit", "open-circuit", "fault"};
    private static final String[] MODE = {"?", "sleep", "held open", "held locked", "normal"};
    private static final String[] CONTACT = {"closed", "open", "short-circuit", "open-circuit", "fault"};

    private static String list(JSONArray a, String[] names) {
        if (a == null) return "n/a";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length(); i++) {
            int v = a.optInt(i, -1);
            if (i > 0) sb.append(", ");
            sb.append(v >= 0 && v < names.length ? names[v] : "?").append('(').append(v).append(')');
        }
        return sb.append(']').toString();
    }

    /** Picks out ISAPI's statusCode / subStatusCode / statusString from an XML or JSON reply. */
    public static String replyStatus(HikClient.Response r) {
        String t = r.text();
        String sub = between(t, "<subStatusCode>", "</subStatusCode>");
        String str = between(t, "<statusString>", "</statusString>");
        if (sub == null && t.trim().startsWith("{")) {
            try {
                JSONObject o = new JSONObject(t);
                sub = o.optString("subStatusCode", null);
                str = o.optString("statusString", null);
            } catch (Exception ignored) {
            }
        }
        StringBuilder sb = new StringBuilder("HTTP ").append(r.code);
        if (str != null) sb.append(" ").append(str);
        if (sub != null) sb.append(" (").append(sub).append(")");
        if (r.code == 401) sb.append(" — wrong user/password (device locks out after repeated failures)");
        return sb.toString();
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        int j = s.indexOf(b, i + a.length());
        return j < 0 ? null : s.substring(i + a.length(), j).trim();
    }

    /** Access-control events from the last {@code hours} hours, newest first. */
    public static String events(HikClient c, int hours) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        long now = System.currentTimeMillis();
        JSONObject cond = new JSONObject()
                .put("searchID", UUID.randomUUID().toString())
                .put("searchResultPosition", 0)
                .put("maxResults", 30)
                .put("major", 0)
                .put("minor", 0)
                .put("startTime", f.format(new Date(now - hours * 3600_000L)))
                .put("endTime", f.format(new Date(now)));
        String body = new JSONObject().put("AcsEventCond", cond).toString();
        HikClient.Response r = c.request("POST", "/ISAPI/AccessControl/AcsEvent?format=json", body, "application/json");
        if (!r.ok()) return "Event search: " + replyStatus(r) + "\n" + r.text();

        JSONObject ev = new JSONObject(r.text()).optJSONObject("AcsEvent");
        JSONArray info = ev == null ? null : ev.optJSONArray("InfoList");
        if (info == null || info.length() == 0) return "No access events in the last " + hours + " h.";

        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < info.length(); i++) rows.add(info.getJSONObject(i));
        Collections.sort(rows, (a, b) -> b.optString("time").compareTo(a.optString("time")));

        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" events (of ").append(ev.optInt("totalMatches", rows.size()))
                .append(") in the last ").append(hours).append(" h, newest first:\n");
        for (JSONObject e : rows) {
            String time = e.optString("time");
            if (time.length() >= 19) time = time.substring(5, 19).replace('T', ' ');
            int major = e.optInt("major"), minor = e.optInt("minor");
            sb.append(time).append("  ").append(eventName(major, minor))
                    .append("  [").append(major).append('/').append(minor).append(']');
            if (e.has("doorNo")) sb.append("  door ").append(e.optInt("doorNo"));
            String who = e.optString("name", "");
            if (!who.isEmpty()) sb.append("  ").append(who);
            String mode = e.optString("currentVerifyMode", "");
            if (!mode.isEmpty() && !mode.equals("invalid")) sb.append("  via ").append(mode);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Common Hikvision event codes; anything else shows as its raw major/minor. */
    private static String eventName(int major, int minor) {
        if (major == 3) {
            switch (minor) {
                case 1024: return "Remote open";
                case 1025: return "Remote close";
                case 1026: return "Remote hold open";
                case 1027: return "Remote hold locked";
                case 112: return "Remote login";
                case 113: return "Remote logout";
            }
        }
        if (major == 5) {
            switch (minor) {
                case 21: return "Lock released";
                case 22: return "Lock engaged";
                case 23: return "Door opened";
                case 24: return "Door closed";
                case 25: return "Door forced open";
                case 26: return "Door left open too long";
                case 75: return "Face verified";
                case 76: return "Face not recognised";
                case 104: return "Face + card verified";
            }
        }
        if (major == 2) return "Exception";
        if (major == 1) return "Alarm";
        return "Event";
    }
}
