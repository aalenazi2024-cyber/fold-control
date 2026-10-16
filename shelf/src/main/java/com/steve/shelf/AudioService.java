package com.steve.shelf;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays an audio collection (Quran, music) in order and keeps going with the screen off.
 * Controlled from its notification and from PlayerActivity.
 */
public class AudioService extends Service {
    static final String ACTION_PLAY = "play";
    static final String ACTION_TOGGLE = "toggle";
    static final String ACTION_NEXT = "next";
    static final String ACTION_PREV = "prev";
    static final String ACTION_STOP = "stop";
    private static final int NOTE_ID = 12;

    static volatile AudioService instance;

    private MediaPlayer player;
    private final List<Library.Item> queue = new ArrayList<>();
    private int index;
    String collection;

    static void play(Context c, String collection, int index) {
        c.startForegroundService(new Intent(c, AudioService.class)
                .setAction(ACTION_PLAY).putExtra("col", collection).putExtra("index", index));
    }

    static void send(Context c, String action) {
        c.startService(new Intent(c, AudioService.class).setAction(action));
    }

    Library.Item current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    int position() {
        try {
            return player != null ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    int duration() {
        try {
            return player != null ? player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    void seekTo(int ms) {
        try {
            if (player != null) player.seekTo(ms);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_TOGGLE;
        switch (action) {
            case ACTION_PLAY:
                collection = intent.getStringExtra("col");
                queue.clear();
                queue.addAll(Library.inCollection(this, collection, true));
                index = intent.getIntExtra("index", 0);
                foreground();
                startCurrent(true);
                break;
            case ACTION_TOGGLE:
                if (player == null) break;
                if (player.isPlaying()) {
                    player.pause();
                    savePosition();
                } else {
                    player.start();
                }
                foreground();
                break;
            case ACTION_NEXT:
                move(1);
                break;
            case ACTION_PREV:
                if (position() > 5000) seekTo(0);
                else move(-1);
                break;
            case ACTION_STOP:
                savePosition();
                release();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    private void move(int delta) {
        savePosition();
        int next = index + delta;
        if (next < 0 || next >= queue.size()) return;
        index = next;
        startCurrent(false);
        foreground();
    }

    private void startCurrent(boolean resume) {
        release();
        Library.Item item = current();
        if (item == null) return;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(item.file);
            player.setOnCompletionListener(mp -> {
                Store.prefs(this).edit().remove("pos_" + item.id).apply();
                if (index + 1 < queue.size()) move(1);
                else foreground();
            });
            player.prepare();
            int pos = resume ? Store.prefs(this).getInt("pos_" + item.id, 0) : 0;
            if (pos > 0) player.seekTo(pos);
            player.start();
            Store.prefs(this).edit().putString("last_played", item.id).apply();
        } catch (Exception e) {
            Store.log(this, "Can't play " + item.title + ": " + e.getMessage());
        }
    }

    private void savePosition() {
        Library.Item item = current();
        if (item != null && player != null) {
            Store.prefs(this).edit().putInt("pos_" + item.id, position()).apply();
        }
    }

    private void release() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code,
                new Intent(this, AudioService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE);
    }

    private void foreground() {
        DownloadService.ensureChannel(this);
        Library.Item item = current();
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, PlayerActivity.class)
                        .putExtra("col", collection).putExtra("index", index).putExtra("attach", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean playing = isPlaying();
        Notification n = new Notification.Builder(this, DownloadService.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(item != null ? item.title : "Shelf")
                .setContentText(Catalog.title(Catalog.load(this, false), collection == null ? "" : collection))
                .setContentIntent(open)
                .setOngoing(playing)
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .addAction(new Notification.Action.Builder(null, "Previous", action(ACTION_PREV, 10)).build())
                .addAction(new Notification.Action.Builder(null, playing ? "Pause" : "Play", action(ACTION_TOGGLE, 11)).build())
                .addAction(new Notification.Action.Builder(null, "Next", action(ACTION_NEXT, 12)).build())
                .setDeleteIntent(action(ACTION_STOP, 13))
                .build();
        try {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        savePosition();
        release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
