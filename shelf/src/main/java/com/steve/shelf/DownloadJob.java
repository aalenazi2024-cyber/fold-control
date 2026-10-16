package com.steve.shelf;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Background downloading without the app being open. Each run works for ~8 minutes
 * (Android's job limit is 10), then chains the next run. Two job ids alternate so
 * scheduling the next run never cancels the one that is finishing.
 */
public class DownloadJob extends JobService {
    private static final int JOB_A = 701;
    private static final int JOB_B = 702;
    private static final long RUN_MS = 8 * 60 * 1000L;

    private volatile Thread worker;

    static void schedule(Context c, long delayMs) {
        schedule(c, delayMs, JOB_A);
    }

    private static void schedule(Context c, long delayMs, int id) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(id, new ComponentName(c, DownloadJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setMinimumLatency(delayMs)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final int myId = params.getJobId();
        final int nextId = myId == JOB_A ? JOB_B : JOB_A;
        worker = new Thread(() -> {
            Engine.Result r = Engine.Result.IDLE;
            try {
                r = Engine.work(this, System.currentTimeMillis() + RUN_MS, false, null);
            } catch (Throwable t) {
                Store.log(this, "Job error: " + t);
            }
            switch (r) {
                case MORE:
                    schedule(this, 3000, nextId);
                    break;
                case BUSY:
                    // The foreground service is downloading; look again later.
                    schedule(this, 10 * 60 * 1000L, nextId);
                    break;
                case IDLE:
                case NO_WIFI:
                    // Pick up refreshed news and anything newly added.
                    schedule(this, 45 * 60 * 1000L, nextId);
                    break;
                case FULL:
                    schedule(this, 3 * 3600 * 1000L, nextId);
                    break;
                case STOPPED:
                default:
                    break;
            }
            jobFinished(params, false);
        });
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Android is ending this run early; stop yt-dlp and try again soon.
        Engine.cancelCurrent();
        return true;
    }
}
