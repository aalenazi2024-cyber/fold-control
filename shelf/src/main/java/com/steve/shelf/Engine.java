package com.steve.shelf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.StatFs;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;

/**
 * Turns the catalog into a queue (searches, channels, playlists, Quran list) and downloads
 * the queue with yt-dlp, one item at a time, rotating between collections so every
 * collection fills up evenly. Shared by the background job and the foreground service.
 */
final class Engine {
    enum Result { MORE, IDLE, STOPPED, BUSY, NO_WIFI, NO_NET, FULL }

    interface Listener {
        void onStatus(String line);
    }

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static volatile String currentProcess;
    private static volatile boolean stopRequested;
    private static boolean ready;

    private static final String DEFAULT_VIDEO_FORMAT =
            "bv*[height<=720][vcodec^=avc1]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/b[height<=720]/b";
    private static final String DEFAULT_AUDIO_FORMAT = "ba[ext=m4a]/ba/b";

    private Engine() {}

    static boolean isBusy() {
        return BUSY.get();
    }

    /** Asks the running work loop to stop (used when the foreground service takes over). */
    static void requestStop() {
        stopRequested = true;
        cancelCurrent();
    }

    /** Stops the yt-dlp process that is running right now, if any. */
    static void cancelCurrent() {
        String id = currentProcess;
        if (id != null) {
            try {
                YoutubeDL.getInstance().destroyProcessById(id);
            } catch (Exception ignored) {
            }
        }
    }

    /** Works until the deadline or until there is nothing left. Never call on the main thread. */
    static Result work(Context ctx, long deadline, boolean allowMetered, Listener l) {
        if (!BUSY.compareAndSet(false, true)) return Result.BUSY;
        stopRequested = false;
        try {
            return workLocked(ctx.getApplicationContext(), deadline, allowMetered, l);
        } finally {
            BUSY.set(false);
            currentProcess = null;
        }
    }

    private static Result workLocked(Context c, long deadline, boolean allowMetered, Listener l) {
        JSONObject cat = Catalog.load(c, true);
        long stopAt = Catalog.stopAt(cat);
        if (stopAt > 0 && System.currentTimeMillis() > stopAt && !allowMetered) {
            say(c, l, "Stopped: past the download window");
            return Result.STOPPED;
        }
        if (!allowMetered && isMetered(c)) {
            say(c, l, "Waiting for Wi-Fi");
            return Result.NO_WIFI;
        }
        if (!canReachInternet()) {
            say(c, l, "No internet right now, waiting");
            return Result.NO_NET;
        }
        if (!ensureReady(c, l)) return Result.IDLE;
        requeueNetworkFailures(c);

        // 1. Plan: expand every source into queue items (re-planned on its refresh interval).
        JSONArray cols = Catalog.collections(cat);
        for (int i = 0; i < cols.length(); i++) {
            JSONObject col = cols.optJSONObject(i);
            if (col == null) continue;
            JSONArray sources = col.optJSONArray("sources");
            if (sources == null) continue;
            for (int k = 0; k < sources.length(); k++) {
                if (System.currentTimeMillis() > deadline || stopRequested) return Result.MORE;
                JSONObject src = sources.optJSONObject(k);
                if (src == null) continue;
                plan(c, cat, col, src, k, l);
            }
        }
        // Playlists and profiles added from the app.
        try {
            JSONArray user = new JSONArray(Store.prefs(c).getString("user_sources", "[]"));
            for (int k = 0; k < user.length(); k++) {
                if (System.currentTimeMillis() > deadline) return Result.MORE;
                JSONObject src = user.getJSONObject(k);
                plan(c, cat, new JSONObject().put("id", src.optString("col", "saved")), src, 90 + k, l);
            }
        } catch (Exception ignored) {
        }

        // 2. Download, rotating between collections.
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            if (stopAt > 0 && System.currentTimeMillis() > stopAt && !allowMetered) {
                say(c, l, "Stopped: past the download window");
                return Result.STOPPED;
            }
            if (!allowMetered && isMetered(c)) {
                say(c, l, "Waiting for Wi-Fi");
                return Result.NO_WIFI;
            }
            String full = storageProblem(c, cat);
            if (full != null) {
                say(c, l, full);
                return Result.FULL;
            }
            Library.Item next = pickNext(c, cat);
            if (next == null) {
                say(c, l, "All caught up");
                return Result.IDLE;
            }
            if (!download(c, cat, next, l)) {
                say(c, l, "Lost internet, waiting");
                return Result.NO_NET;
            }
        }
        return Result.MORE;
    }

    // ---------------------------------------------------------------- setup

    private static boolean ensureReady(Context c, Listener l) {
        if (ready) return true;
        try {
            say(c, l, "Starting downloader");
            YoutubeDL.getInstance().init(c);
            FFmpeg.getInstance().init(c);
            ready = true;
        } catch (Exception e) {
            say(c, l, "Downloader failed to start: " + e.getMessage());
            return false;
        }
        long last = Store.prefs(c).getLong("ytdlp_updated", 0);
        if (System.currentTimeMillis() - last > 12 * 3600 * 1000L) {
            say(c, l, "Updating yt-dlp");
            if (updateYtDlp(c)) {
                Store.prefs(c).edit().putLong("ytdlp_updated", System.currentTimeMillis()).apply();
                say(c, l, "yt-dlp is up to date");
            }
        }
        return true;
    }

    /**
     * Calls YoutubeDL.updateYoutubeDL(context, channel) by reflection, because the channel
     * argument's shape differs between library versions. YouTube changes often, so a fresh
     * yt-dlp matters more than anything else here.
     */
    private static boolean updateYtDlp(Context c) {
        try {
            Object ytdl = YoutubeDL.getInstance();
            for (Method m : ytdl.getClass().getMethods()) {
                if (!m.getName().equals("updateYoutubeDL")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1) {
                    m.invoke(ytdl, c);
                    return true;
                }
                if (p.length >= 2) {
                    Object channel = stableChannel(p[1]);
                    Object[] args = new Object[p.length];
                    args[0] = c;
                    args[1] = channel;
                    m.invoke(ytdl, args);
                    return true;
                }
            }
        } catch (Exception e) {
            Store.log(c, "yt-dlp update failed: " + e);
        }
        return false;
    }

    private static Object stableChannel(Class<?> type) throws Exception {
        for (String name : new String[]{"_STABLE", "STABLE", "Stable"}) {
            try {
                Field f = type.getField(name);
                return f.get(null);
            } catch (NoSuchFieldException ignored) {
            }
            for (Class<?> inner : type.getDeclaredClasses()) {
                if (inner.getSimpleName().equalsIgnoreCase(name.replace("_", ""))) {
                    try {
                        return inner.getField("INSTANCE").get(null);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            }
        }
        // Kotlin sealed class with a companion holding the channels.
        try {
            Object companion = type.getField("Companion").get(null);
            for (Method m : companion.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().toLowerCase(Locale.US).contains("stable")) {
                    return m.invoke(companion);
                }
            }
        } catch (Exception ignored) {
        }
        if (type.isEnum()) return type.getEnumConstants()[0];
        return null;
    }

    // ---------------------------------------------------------------- planning

    private static void plan(Context c, JSONObject cat, JSONObject col, JSONObject src, int srcIndex, Listener l) {
        String colId = col.optString("id");
        String key = "planned_" + colId + "_" + src.toString().hashCode();
        long last = Store.prefs(c).getLong(key, 0);
        double refreshH = src.optDouble("refreshHours", col.optDouble("refreshHours", 0));
        if (last > 0 && (refreshH <= 0 || System.currentTimeMillis() - last < refreshH * 3600 * 1000L)) return;

        int base = srcIndex * 1000;
        try {
            if (src.has("mp3quran")) {
                planQuran(c, colId, src.getString("mp3quran"), base, l);
            } else if (src.has("urls")) {
                JSONArray urls = src.getJSONArray("urls");
                for (int i = 0; i < urls.length(); i++) {
                    String u = urls.getString(i);
                    Library.add(c, "u_" + Integer.toHexString(u.hashCode()), u, u, colId, 0, base + i);
                }
            } else {
                String target = searchUrl(src.optString("q", src.optString("url", "")));
                if (target.isEmpty()) return;
                say(c, l, "Finding: " + label(target));
                planYtDlp(c, cat, colId, src, target, base);
            }
            Store.prefs(c).edit().putLong(key, System.currentTimeMillis()).apply();
        } catch (Exception e) {
            if (isNetworkError(e)) return; // try again next round, nothing learned
            Store.log(c, "Plan failed for " + label(src.toString()) + ": " + shortError(e));
            // Retry this source next round, but not in a tight loop.
            Store.prefs(c).edit().putLong(key, System.currentTimeMillis() - (long) (Math.max(refreshH, 1) * 3600 * 1000L) + 30 * 60 * 1000L).apply();
        }
    }

    private static void planYtDlp(Context c, JSONObject cat, String colId, JSONObject src, String target, int base) throws Exception {
        int max = src.optInt("max", 8);
        long minDur = src.optLong("min", 0);
        long maxDur = src.optLong("maxDur", src.optLong("maxdur", 0));

        YoutubeDLRequest req = new YoutubeDLRequest(target);
        req.addOption("--flat-playlist");
        req.addOption("-J");
        req.addOption("--playlist-end", String.valueOf(Math.max(max * 2, max + 4)));
        req.addOption("--socket-timeout", "30");
        addExtraArgs(req, cat);
        String out = run(req).getOut();
        JSONObject root = new JSONObject(out.substring(out.indexOf('{')));
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) {
            // A single video URL.
            addEntry(c, colId, root, base, target);
            return;
        }
        int added = 0;
        for (int i = 0; i < entries.length() && added < max; i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            long dur = (long) e.optDouble("duration", 0);
            if (dur > 0 && minDur > 0 && dur < minDur) continue;
            if (dur > 0 && maxDur > 0 && dur > maxDur) continue;
            String liveStatus = e.optString("live_status", "");
            if (liveStatus.equals("is_live") || liveStatus.equals("is_upcoming")) continue;
            if (addEntry(c, colId, e, base + i, null)) added++;
        }
    }

    private static boolean addEntry(Context c, String colId, JSONObject e, int order, String fallbackUrl) {
        String id = e.optString("id", "");
        String url = e.optString("webpage_url", e.optString("url", ""));
        String ie = e.optString("ie_key", e.optString("extractor_key", "")).toLowerCase(Locale.US);
        if (!url.startsWith("http")) {
            if (ie.contains("youtube") && !id.isEmpty()) url = "https://www.youtube.com/watch?v=" + id;
            else if (fallbackUrl != null) url = fallbackUrl;
            else return false;
        }
        String prefix = url.contains("tiktok.com") ? "tt_" : url.contains("youtu") ? "yt_" : "x_";
        if (id.isEmpty()) id = Integer.toHexString(url.hashCode());
        String title = e.optString("title", url);
        return Library.add(c, prefix + id, url, title, colId, (long) e.optDouble("duration", 0), order);
    }

    /** Full recitation from mp3quran.net's public API, matched by the reciter's Arabic name. */
    private static void planQuran(Context c, String colId, String reciter, int base, Listener l) throws Exception {
        say(c, l, "Finding Quran recitation: " + reciter);
        JSONObject root = new JSONObject(Catalog.Net.getString("https://mp3quran.net/api/v3/reciters?language=ar"));
        JSONArray reciters = root.getJSONArray("reciters");
        String[] parts = reciter.trim().split("\\s+");
        for (int i = 0; i < reciters.length(); i++) {
            JSONObject r = reciters.getJSONObject(i);
            String name = r.optString("name");
            boolean all = true;
            for (String p : parts) if (!name.contains(p)) all = false;
            if (!all) continue;
            JSONArray moshaf = r.optJSONArray("moshaf");
            if (moshaf == null || moshaf.length() == 0) continue;
            // Prefer the most complete recitation.
            JSONObject best = moshaf.getJSONObject(0);
            for (int k = 1; k < moshaf.length(); k++) {
                JSONObject m = moshaf.getJSONObject(k);
                if (m.optInt("surah_total") > best.optInt("surah_total")) best = m;
            }
            String server = best.getString("server");
            if (!server.endsWith("/")) server += "/";
            for (String s : best.getString("surah_list").split(",")) {
                int n;
                try {
                    n = Integer.parseInt(s.trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                String num = String.format(Locale.US, "%03d", n);
                String title = n + ". سورة " + Surahs.name(n);
                Library.add(c, "q_" + r.optInt("id") + "_" + num, server + num + ".mp3", title, colId, 0, base + n);
            }
            Store.log(c, "Quran: " + name + " (" + best.optString("name") + ")");
            return;
        }
        throw new Exception("reciter not found: " + reciter);
    }

    // ---------------------------------------------------------------- downloading

    private static Library.Item pickNext(Context c, JSONObject cat) {
        JSONArray cols = Catalog.collections(cat);
        int n = cols.length() + 1;
        int rr = Store.prefs(c).getInt("rr", 0);
        for (int step = 0; step < n; step++) {
            int idx = (rr + step) % n;
            // Slot n-1 is the "saved" collection for links you share in.
            String colId = idx < cols.length() ? cols.optJSONObject(idx).optString("id") : "saved";
            for (Library.Item i : Library.inCollection(c, colId, false)) {
                if (Library.QUEUED.equals(i.status)) {
                    Store.prefs(c).edit().putInt("rr", idx + 1).apply();
                    return i;
                }
            }
        }
        return null;
    }

    /** Returns false if the network dropped (the item stays queued, no try is used up). */
    private static boolean download(Context c, JSONObject cat, Library.Item item, Listener l) {
        boolean audio = Catalog.isAudio(cat, item.col);
        boolean direct = item.url.toLowerCase(Locale.US).matches("^https?://[^?]+\\.(mp3|m4a|mp4)(\\?.*)?$");
        File dir = Store.collectionDir(c, item.col);
        say(c, l, "Downloading: " + item.title);

        YoutubeDLRequest req = new YoutubeDLRequest(item.url);
        req.addOption("--no-playlist");
        req.addOption("-o", new File(dir, item.id + ".%(ext)s").getAbsolutePath());
        req.addOption("--no-mtime");
        req.addOption("--retries", "5");
        req.addOption("--fragment-retries", "5");
        req.addOption("--socket-timeout", "30");
        req.addOption("--concurrent-fragments", "4");
        if (!direct) {
            if (audio) {
                req.addOption("-f", cat.optString("audioFormat", DEFAULT_AUDIO_FORMAT));
            } else {
                req.addOption("-f", cat.optString("videoFormat", DEFAULT_VIDEO_FORMAT));
                req.addOption("--merge-output-format", "mp4");
            }
            req.addOption("--write-thumbnail");
            req.addOption("--convert-thumbnails", "jpg");
            addExtraArgs(req, cat);
        }

        final String title = item.title;
        try {
            String pid = "dl_" + item.id;
            currentProcess = pid;
            YoutubeDL.getInstance().execute(req, pid, (Float progress, Long eta, String line) -> {
                if (l != null && progress != null && progress > 0) {
                    l.onStatus(String.format(Locale.US, "%.0f%%  %s", progress, title));
                }
                return Unit.INSTANCE;
            });
            File media = findMedia(dir, item.id);
            if (media == null) throw new Exception("finished but no file found");
            item.file = media.getAbsolutePath();
            item.size = media.length();
            item.status = Library.DONE;
            Library.update(c, item);
            Store.log(c, "Saved: " + item.title);
            return true;
        } catch (Exception e) {
            if (stopRequested) return true;
            if (isNetworkError(e) || !canReachInternet()) return false;
            item.tries++;
            item.status = item.tries >= 3 ? Library.FAILED : Library.QUEUED;
            Library.update(c, item);
            Store.log(c, "Failed (" + item.tries + "): " + item.title + " — " + shortError(e));
            return true;
        } finally {
            currentProcess = null;
        }
    }

    private static String shortError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        int cut = msg.lastIndexOf("ERROR:");
        if (cut >= 0) msg = msg.substring(cut);
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }

    static boolean isNetworkError(Exception e) {
        String m = String.valueOf(e.getMessage()) + " " + e;
        return m.contains("Errno 7") || m.contains("No address associated") || m.contains("Unable to resolve host")
                || m.contains("Network is unreachable") || m.contains("Errno 101") || m.contains("Errno 3]")
                || m.contains("Temporary failure in name resolution") || m.contains("UnknownHostException");
    }

    /** DNS check: Android blocks idle background apps from the network and lookups fail. */
    static boolean canReachInternet() {
        try {
            return java.net.InetAddress.getByName("www.youtube.com") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Items marked failed while the network was blocked deserve another go (once per version). */
    private static void requeueNetworkFailures(Context c) {
        String flag = "requeued_v2";
        if (Store.prefs(c).getBoolean(flag, false)) return;
        for (Library.Item i : Library.all(c)) {
            if (Library.FAILED.equals(i.status)) {
                i.status = Library.QUEUED;
                i.tries = 0;
                Library.update(c, i);
            }
        }
        Store.prefs(c).edit().putBoolean(flag, true).apply();
        Store.log(c, "Retrying videos that failed because of the network");
    }

    /** yt-dlp's "ytsearchdate" isn't in this build; use YouTube's own newest-first search page. */
    private static String searchUrl(String target) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^ytsearchdate(\\d*):(.+)$").matcher(target);
        if (!m.matches()) return target;
        try {
            return "https://www.youtube.com/results?search_query="
                    + java.net.URLEncoder.encode(m.group(2), "UTF-8") + "&sp=CAI%253D";
        } catch (Exception e) {
            return target;
        }
    }

    private static File findMedia(File dir, String id) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith(id + ".")) continue;
            String lower = n.toLowerCase(Locale.US);
            if (lower.endsWith(".part") || lower.endsWith(".ytdl") || lower.endsWith(".jpg")
                    || lower.endsWith(".webp") || lower.endsWith(".png") || lower.endsWith(".json")
                    || lower.contains(".temp") || lower.matches(".*\\.f\\d+\\..*")) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    private static YoutubeDLResponse run(YoutubeDLRequest req) throws Exception {
        String pid = "plan_" + System.nanoTime();
        currentProcess = pid;
        try {
            return YoutubeDL.getInstance().execute(req, pid, null);
        } finally {
            currentProcess = null;
        }
    }

    /** Extra yt-dlp arguments from the catalog, e.g. extractor-args, so fixes need no rebuild. */
    private static void addExtraArgs(YoutubeDLRequest req, JSONObject cat) {
        JSONArray extra = cat.optJSONArray("ytdlpArgs");
        if (extra == null) return;
        for (int i = 0; i < extra.length(); i++) {
            JSONArray pair = extra.optJSONArray(i);
            if (pair == null || pair.length() == 0) continue;
            if (pair.length() == 1) req.addOption(pair.optString(0));
            else req.addOption(pair.optString(0), pair.optString(1));
        }
    }

    // ---------------------------------------------------------------- limits

    private static String storageProblem(Context c, JSONObject cat) {
        double maxGb = cat.optDouble("maxGB", 40);
        double minFreeGb = cat.optDouble("minFreeGB", 15);
        long used = Library.bytesUsed(c);
        if (used > maxGb * 1e9) return String.format(Locale.US, "Stopped: reached %.0f GB limit", maxGb);
        StatFs fs = new StatFs(Store.mediaRoot(c).getAbsolutePath());
        if (fs.getAvailableBytes() < minFreeGb * 1e9) {
            return String.format(Locale.US, "Stopped: keeping %.0f GB free on the phone", minFreeGb);
        }
        return null;
    }

    static boolean isMetered(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return true;
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private static void say(Context c, Listener l, String s) {
        Store.prefs(c).edit().putString("status", s).apply();
        if (l != null) l.onStatus(s);
    }

    private static String label(String s) {
        int colon = s.indexOf(':');
        String t = s.startsWith("ytsearch") && colon > 0 ? s.substring(colon + 1) : s;
        return t.length() > 70 ? t.substring(0, 70) + "…" : t;
    }

    static List<Library.Item> queued(Context c) {
        List<Library.Item> all = Library.all(c);
        all.removeIf(i -> !Library.QUEUED.equals(i.status));
        return all;
    }
}
