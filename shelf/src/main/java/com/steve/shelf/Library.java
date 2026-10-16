package com.steve.shelf;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Every item Shelf knows about: queued, downloaded or failed.
 * Stored as one JSON file; all access goes through this class's lock.
 */
final class Library {
    static final String QUEUED = "queued";
    static final String DONE = "done";
    static final String FAILED = "failed";

    static final class Item {
        String id, url, title, col, status, file;
        long dur, size, added;
        int tries, order;

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id).put("url", url).put("title", title).put("col", col)
                    .put("status", status).put("file", file == null ? "" : file)
                    .put("dur", dur).put("size", size).put("added", added)
                    .put("tries", tries).put("order", order);
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.id = o.optString("id");
            i.url = o.optString("url");
            i.title = o.optString("title");
            i.col = o.optString("col");
            i.status = o.optString("status", QUEUED);
            i.file = o.optString("file", "");
            i.dur = o.optLong("dur");
            i.size = o.optLong("size");
            i.added = o.optLong("added");
            i.tries = o.optInt("tries");
            i.order = o.optInt("order");
            return i;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Item> cache;

    private Library() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "library.json");
    }

    private static List<Item> loadLocked(Context c) {
        if (cache != null) return cache;
        List<Item> list = new ArrayList<>();
        File f = file(c);
        if (f.exists()) {
            try {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) list.add(Item.fromJson(a.getJSONObject(i)));
            } catch (Exception ignored) {
            }
        }
        cache = list;
        return list;
    }

    private static void saveLocked(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (Item i : cache) a.put(i.toJson());
            File tmp = new File(file(c).getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(file(c));
        } catch (Exception ignored) {
        }
    }

    static List<Item> all(Context c) {
        synchronized (LOCK) {
            return new ArrayList<>(loadLocked(c));
        }
    }

    static List<Item> inCollection(Context c, String col, boolean doneOnly) {
        List<Item> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Item i : loadLocked(c)) {
                if (!i.col.equals(col)) continue;
                if (doneOnly && !DONE.equals(i.status)) continue;
                out.add(i);
            }
        }
        out.sort((a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : Long.compare(a.added, b.added));
        return out;
    }

    /** Adds an item unless one with the same id exists. Returns true if added. */
    static boolean add(Context c, String id, String url, String title, String col, long dur, int order) {
        synchronized (LOCK) {
            List<Item> list = loadLocked(c);
            for (Item i : list) if (i.id.equals(id)) return false;
            Item i = new Item();
            i.id = id;
            i.url = url;
            i.title = title == null || title.isEmpty() ? url : title;
            i.col = col;
            i.status = QUEUED;
            i.dur = dur;
            i.added = System.currentTimeMillis();
            i.order = order;
            list.add(i);
            saveLocked(c);
            return true;
        }
    }

    static void update(Context c, Item changed) {
        synchronized (LOCK) {
            List<Item> list = loadLocked(c);
            for (int k = 0; k < list.size(); k++) {
                if (list.get(k).id.equals(changed.id)) {
                    list.set(k, changed);
                    saveLocked(c);
                    return;
                }
            }
        }
    }

    static void remove(Context c, String id) {
        synchronized (LOCK) {
            List<Item> list = loadLocked(c);
            for (int k = 0; k < list.size(); k++) {
                Item i = list.get(k);
                if (i.id.equals(id)) {
                    if (i.file != null && !i.file.isEmpty()) {
                        new File(i.file).delete();
                        new File(thumb(i.file)).delete();
                    }
                    list.remove(k);
                    saveLocked(c);
                    return;
                }
            }
        }
    }

    static int count(Context c, String col, String status) {
        int n = 0;
        synchronized (LOCK) {
            for (Item i : loadLocked(c)) {
                if (col != null && !i.col.equals(col)) continue;
                if (status.equals(i.status)) n++;
            }
        }
        return n;
    }

    static long bytesUsed(Context c) {
        long n = 0;
        synchronized (LOCK) {
            for (Item i : loadLocked(c)) if (DONE.equals(i.status)) n += i.size;
        }
        return n;
    }

    /** Thumbnail path next to a media file (written by yt-dlp as .jpg). */
    static String thumb(String mediaPath) {
        int dot = mediaPath.lastIndexOf('.');
        return (dot > 0 ? mediaPath.substring(0, dot) : mediaPath) + ".jpg";
    }
}
