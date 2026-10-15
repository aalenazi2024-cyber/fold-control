package com.steve.doorcontrol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal ISAPI client for one Hikvision device: plain HTTP on the LAN with
 * Digest authentication (what Hikvision firmware requires). The challenge is
 * cached so repeat calls go out in one round trip. Always call off the main thread.
 */
public final class HikClient {

    public static final class Response {
        public final int code;
        public final byte[] bytes;
        public final String contentType;
        public final long millis;
        public final int attempts;

        Response(int code, byte[] bytes, String contentType, long millis, int attempts) {
            this.code = code;
            this.bytes = bytes;
            this.contentType = contentType;
            this.millis = millis;
            this.attempts = attempts;
        }

        public boolean ok() { return code >= 200 && code < 300; }

        public String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    private static final class Raw {
        int code;
        byte[] bytes;
        String contentType;
        String challenge;
    }

    private static final Pattern PARAM = Pattern.compile("(\\w+)\\s*=\\s*(\"([^\"]*)\"|[^,\\s]+)");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Device device;
    private String realm, nonce, opaque, qop, algorithm;
    private int nc;

    public HikClient(Device device) { this.device = device; }

    public Response get(String path) throws Exception { return request("GET", path, null, null); }

    public synchronized Response request(String method, String path, String body, String contentType) throws Exception {
        long t0 = System.nanoTime();
        int attempts = 0;
        for (int i = 0; i < 2; i++) {
            attempts++;
            String auth = nonce == null ? null : authHeader(method, path);
            Raw raw = send(method, path, body, contentType, auth);
            boolean digest = raw.challenge != null && raw.challenge.regionMatches(true, 0, "Digest", 0, 6);
            if (raw.code == 401 && digest && i == 0) {
                parseChallenge(raw.challenge);
                continue;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new Response(raw.code, raw.bytes, raw.contentType, ms, attempts);
        }
        throw new IllegalStateException("unreachable");
    }

    private Raw send(String method, String path, String body, String contentType, String auth) throws Exception {
        String base = "http://" + device.host + (device.port == 80 ? "" : ":" + device.port);
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", "door-control");
            if (auth != null) c.setRequestProperty("Authorization", auth);
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType != null ? contentType : "application/xml");
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            Raw r = new Raw();
            r.code = c.getResponseCode();
            r.contentType = c.getContentType();
            r.challenge = digestChallenge(c.getHeaderFields());
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.bytes = in == null ? new byte[0] : readAll(in);
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** Picks the Digest challenge when the device offers several WWW-Authenticate headers. */
    private static String digestChallenge(Map<String, List<String>> headers) {
        String any = null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("WWW-Authenticate")) continue;
            for (String v : e.getValue()) {
                if (v == null) continue;
                if (v.regionMatches(true, 0, "Digest", 0, 6)) return v;
                any = v;
            }
        }
        return any;
    }

    private void parseChallenge(String header) {
        realm = nonce = opaque = qop = algorithm = null;
        nc = 0;
        Matcher m = PARAM.matcher(header.substring(6));
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.US);
            String val = m.group(3) != null ? m.group(3) : m.group(2);
            switch (key) {
                case "realm": realm = val; break;
                case "nonce": nonce = val; break;
                case "opaque": opaque = val; break;
                case "qop": qop = val; break;
                case "algorithm": algorithm = val; break;
                default: break;
            }
        }
    }

    private String authHeader(String method, String uri) throws Exception {
        String alg = algorithm == null ? "MD5" : algorithm;
        boolean sess = alg.toLowerCase(Locale.US).endsWith("-sess");
        String hashName = alg.toUpperCase(Locale.US).startsWith("SHA-256") ? "SHA-256" : "MD5";
        boolean useQop = qop != null && qop.toLowerCase(Locale.US).contains("auth");

        nc++;
        String ncs = String.format(Locale.US, "%08x", nc);
        byte[] rnd = new byte[8];
        RANDOM.nextBytes(rnd);
        String cnonce = hex(rnd);

        String ha1 = hash(hashName, device.user + ":" + realm + ":" + device.password);
        if (sess) ha1 = hash(hashName, ha1 + ":" + nonce + ":" + cnonce);
        String ha2 = hash(hashName, method + ":" + uri);
        String response = useQop
                ? hash(hashName, ha1 + ":" + nonce + ":" + ncs + ":" + cnonce + ":auth:" + ha2)
                : hash(hashName, ha1 + ":" + nonce + ":" + ha2);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(device.user).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");
        sb.append("response=\"").append(response).append("\"");
        if (algorithm != null) sb.append(", algorithm=").append(algorithm);
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append("\"");
        if (useQop) sb.append(", qop=auth, nc=").append(ncs).append(", cnonce=\"").append(cnonce).append("\"");
        return sb.toString();
    }

    private static String hash(String alg, String s) throws Exception {
        return hex(MessageDigest.getInstance(alg).digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
