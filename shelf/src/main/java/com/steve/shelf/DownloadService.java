package com.steve.shelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Downloads with a progress notification while the app is open or was just opened.
 * Android does not allow starting this from the background, so the job covers that case.
 */
public class DownloadService extends Service {
    static final String CHANNEL = "downloads";
    private static final int NOTE_ID = 11;
    private static final long RUN_MS = 5 * 3600 * 1000L;
    static final String EXTRA_ANY_NETWORK = "any_network";

    private Thread worker;
    private long lastNote;

    static boolean start(Context c, boolean anyNetwork) {
        try {
            c.startForegroundService(new Intent(c, DownloadService.class).putExtra(EXTRA_ANY_NETWORK, anyNetwork));
            return true;
        } catch (Exception e) {
            // Not allowed from the background on this Android version: the job takes over.
            return false;
        }
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification note(String text) {
        ensureChannel(this);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Shelf")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTE_ID, note("Getting ready…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean anyNetwork = intent != null && intent.getBooleanExtra(EXTRA_ANY_NETWORK, false);
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;

        worker = new Thread(() -> {
            PowerManager pm = getSystemService(PowerManager.class);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shelf:download");
            wl.acquire(RUN_MS + 60000);
            Engine.Result r = Engine.Result.IDLE;
            try {
                r = Engine.work(this, System.currentTimeMillis() + RUN_MS, anyNetwork, line -> {
                    long now = System.currentTimeMillis();
                    if (now - lastNote < 1500) return;
                    lastNote = now;
                    getSystemService(NotificationManager.class).notify(NOTE_ID, note(line));
                });
            } catch (Throwable t) {
                Store.log(this, "Service error: " + t);
            } finally {
                if (wl.isHeld()) wl.release();
            }
            if (r != Engine.Result.STOPPED) DownloadJob.schedule(this, 30000);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 caps data-sync services at 6 h a day; hand back to the job.
        Engine.cancelCurrent();
        DownloadJob.schedule(this, 30000);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (worker != null && worker.isAlive()) Engine.cancelCurrent();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
