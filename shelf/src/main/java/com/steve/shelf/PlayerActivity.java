package com.steve.shelf;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.List;

/**
 * Plays a collection in order. Video plays here, full screen, and moves to the next item
 * automatically. Audio collections hand off to AudioService so they keep playing with the
 * screen off; this screen then shows simple controls.
 */
public class PlayerActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String col;
    private int index;
    private List<Library.Item> items;
    private VideoView video;
    private TextView title;

    // audio mode
    private TextView audioTitle, audioTime;
    private SeekBar seek;
    private TextView playPause;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        col = getIntent().getStringExtra("col");
        index = getIntent().getIntExtra("index", 0);
        items = Library.inCollection(this, col, true);
        if (items.isEmpty()) {
            finish();
            return;
        }
        if (Catalog.isAudio(Catalog.load(this, false), col)) {
            buildAudio();
            if (!getIntent().getBooleanExtra("attach", false)) AudioService.play(this, col, index);
        } else {
            buildVideo();
            playVideo(true);
        }
    }

    // ---------------------------------------------------------------- video

    private void buildVideo() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        f.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        title = Ui.text(this, "", 14);
        title.setTextColor(Color.WHITE);
        title.setShadowLayer(4, 0, 0, Color.BLACK);
        int p = Ui.dp(this, 16);
        title.setPadding(p, p, p, p);
        f.addView(title, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setContentView(f);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        mc.setPrevNextListeners(v -> step(1), v -> step(-1));
        video.setMediaController(mc);
        video.setOnCompletionListener(mp -> {
            Store.prefs(this).edit().remove("pos_" + items.get(index).id).apply();
            if (index + 1 < items.size()) step(1);
            else finish();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            if (index + 1 < items.size()) step(1);
            return true;
        });
        video.setOnClickListener(v -> title.setVisibility(title.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    private void playVideo(boolean resume) {
        Library.Item item = items.get(index);
        title.setText((index + 1) + "/" + items.size() + "  ·  " + item.title);
        title.setVisibility(View.VISIBLE);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> title.setVisibility(View.GONE), 4000);
        video.setVideoPath(item.file);
        int pos = resume ? Store.prefs(this).getInt("pos_" + item.id, 0) : 0;
        video.setOnPreparedListener(mp -> {
            if (pos > 0) video.seekTo(pos);
            video.start();
        });
        Store.prefs(this).edit().putString("last_played", item.id).apply();
    }

    private void step(int delta) {
        saveVideoPosition();
        int next = index + delta;
        if (next < 0 || next >= items.size()) return;
        index = next;
        playVideo(true);
    }

    private void saveVideoPosition() {
        if (video == null) return;
        int pos = video.getCurrentPosition();
        if (pos > 3000) Store.prefs(this).edit().putInt("pos_" + items.get(index).id, pos).apply();
    }

    @Override
    protected void onPause() {
        saveVideoPosition();
        if (video != null) video.pause();
        handler.removeCallbacks(audioTick);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (audioTitle != null) handler.post(audioTick);
    }

    // ---------------------------------------------------------------- audio

    private void buildAudio() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF101418);
        int p = Ui.dp(this, 28);
        root.setPadding(p, p, p, p);

        TextView colTitle = Ui.text(this, Catalog.title(Catalog.load(this, false), col), 14);
        colTitle.setTextColor(0xAAFFFFFF);
        colTitle.setGravity(Gravity.CENTER);
        root.addView(colTitle);

        audioTitle = Ui.bold(this, "", 24);
        audioTitle.setTextColor(Color.WHITE);
        audioTitle.setGravity(Gravity.CENTER);
        audioTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 24));
        root.addView(audioTitle);

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                AudioService svc = AudioService.instance;
                if (user && svc != null) svc.seekTo(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(seek);
        audioTime = Ui.text(this, "", 13);
        audioTime.setTextColor(0xAAFFFFFF);
        audioTime.setGravity(Gravity.CENTER);
        root.addView(audioTime);

        TextView prev = control("⏮", v -> AudioService.send(this, AudioService.ACTION_PREV));
        playPause = control("⏸", v -> AudioService.send(this, AudioService.ACTION_TOGGLE));
        TextView next = control("⏭", v -> AudioService.send(this, AudioService.ACTION_NEXT));
        LinearLayout row = Ui.row(this, prev, playPause, next);
        row.setPadding(0, Ui.dp(this, 24), 0, 0);
        root.addView(row);
        setContentView(root);
    }

    private TextView control(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 40);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        return t;
    }

    private final Runnable audioTick = new Runnable() {
        @Override
        public void run() {
            AudioService svc = AudioService.instance;
            if (svc != null && audioTitle != null) {
                Library.Item cur = svc.current();
                if (cur != null) audioTitle.setText(cur.title);
                int d = svc.duration(), p = svc.position();
                seek.setMax(Math.max(d, 1));
                seek.setProgress(p);
                audioTime.setText(Ui.duration(p / 1000) + " / " + Ui.duration(d / 1000));
                playPause.setText(svc.isPlaying() ? "⏸" : "▶");
            }
            handler.postDelayed(this, 500);
        }
    };
}
