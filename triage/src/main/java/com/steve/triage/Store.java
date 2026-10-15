package com.steve.triage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local database: judged notifications and what you did with each sender
 * (opened vs ignored), which is how Triage learns.
 */
final class Store extends SQLiteOpenHelper {
    private static Store inst;

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private Store(Context c) {
        super(c, "triage.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, app TEXT, sender TEXT, "
                + "title TEXT, body TEXT, summary TEXT, reason TEXT, reply TEXT, verdict TEXT, status TEXT, "
                + "created INTEGER, snooze_until INTEGER DEFAULT 0, src_key TEXT)");
        db.execSQL("CREATE INDEX ix_items_status ON items(status, created)");
        db.execSQL("CREATE TABLE senders(k TEXT PRIMARY KEY, opened INTEGER DEFAULT 0, ignored INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS items");
        db.execSQL("DROP TABLE IF EXISTS senders");
        onCreate(db);
    }

    synchronized long insert(Item it) {
        ContentValues v = new ContentValues();
        v.put("pkg", it.pkg);
        v.put("app", it.app);
        v.put("sender", it.sender);
        v.put("title", it.title);
        v.put("body", it.body);
        v.put("summary", it.summary);
        v.put("reason", it.reason);
        v.put("reply", it.reply);
        v.put("verdict", it.verdict);
        v.put("status", it.status);
        v.put("created", it.created);
        v.put("snooze_until", it.snoozeUntil);
        v.put("src_key", it.srcKey);
        it.id = getWritableDatabase().insert("items", null, v);
        return it.id;
    }

    synchronized Item get(long id) {
        List<Item> l = query("id=?", new String[]{String.valueOf(id)}, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    synchronized void setStatus(long id, String status) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        getWritableDatabase().update("items", v, "id=?", new String[]{String.valueOf(id)});
    }

    synchronized void snooze(long id, long until) {
        ContentValues v = new ContentValues();
        v.put("status", "snoozed");
        v.put("snooze_until", until);
        getWritableDatabase().update("items", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** Clears the silent digest: FYI and filtered items become "seen". */
    synchronized void clearDigest() {
        getWritableDatabase().execSQL("UPDATE items SET status='seen' WHERE status IN ('fyi','quiet')");
    }

    synchronized List<Item> byStatus(int limit, String... statuses) {
        StringBuilder q = new StringBuilder("status IN (");
        for (int i = 0; i < statuses.length; i++) q.append(i == 0 ? "?" : ",?");
        q.append(")");
        return query(q.toString(), statuses, limit);
    }

    synchronized void bump(String key, boolean opened) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO senders(k) VALUES(?)", new Object[]{key});
        db.execSQL(opened ? "UPDATE senders SET opened=opened+1 WHERE k=?"
                : "UPDATE senders SET ignored=ignored+1 WHERE k=?", new Object[]{key});
    }

    /** {opened, ignored} for a sender. */
    synchronized int[] stats(String key) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT opened, ignored FROM senders WHERE k=?", new String[]{key})) {
            if (c.moveToFirst()) return new int[]{c.getInt(0), c.getInt(1)};
        }
        return new int[]{0, 0};
    }

    synchronized void prune() {
        long cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000;
        getWritableDatabase().execSQL("DELETE FROM items WHERE created < ? AND status <> 'snoozed'", new Object[]{cutoff});
    }

    private List<Item> query(String where, String[] args, int limit) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("items", null, where, args, null, null,
                "created DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(c.getColumnIndexOrThrow("id"));
                it.pkg = s(c, "pkg");
                it.app = s(c, "app");
                it.sender = s(c, "sender");
                it.title = s(c, "title");
                it.body = s(c, "body");
                it.summary = s(c, "summary");
                it.reason = s(c, "reason");
                it.reply = s(c, "reply");
                it.verdict = s(c, "verdict");
                it.status = s(c, "status");
                it.srcKey = s(c, "src_key");
                it.created = c.getLong(c.getColumnIndexOrThrow("created"));
                it.snoozeUntil = c.getLong(c.getColumnIndexOrThrow("snooze_until"));
                out.add(it);
            }
        }
        return out;
    }

    private static String s(Cursor c, String col) {
        String v = c.getString(c.getColumnIndexOrThrow(col));
        return v == null ? "" : v;
    }
}
