package com.steve.shelf;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.List;
import java.util.Locale;

/**
 * Plays one category in order. When a video ends it shows "Up next" for 5 seconds and
 * continues to the next video in the same category; previous/next step through that
 * category only. Audio categories hand off to AudioService so they keep playing with the
 * screen off.
 */
public class PlayerActivity extends Activity {
    private static final int UP_NEXT_SECONDS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String col;
    private String colTitle;
    private int accent;
    private int index;
    private List<Library.Item> items;

    // video
    private VideoView video;
    private FrameLayout overlay;
    private TextView title, subtitle, playBtn, timeNow, timeTotal, upNextHint;
    private SeekBar seek;
    private boolean userSeeking;
    private LinearLayout upNextPanel;
    private TextView upNextTitle, upNextCount;
    private int countdown;

    // audio
    private TextView audioTitle, audioTime, audioPlay;
    private SeekBar audioSeek;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        col = getIntent().getStringExtra("col");
        items = Library.inCollection(this, col, true);
        if (items.isEmpty()) {
            finish();
            return;
        }
        index = indexOf(getIntent().getStringExtra("item"), getIntent().getIntExtra("index", 0));
        org.json.JSONObject cat = Catalog.load(this, false);
        colTitle = "saved".equals(col) ? "Saved links" : Catalog.title(cat, col);
        org.json.JSONArray cols = Catalog.collections(cat);
        int ci = cols.length();
        for (int i = 0; i < cols.length(); i++) if (col.equals(cols.optJSONObject(i).optString("id"))) ci = i;
        accent = Ui.colorFor(ci);

        if (Catalog.isAudio(cat, col)) {
            buildAudio();
            if (!getIntent().getBooleanExtra("attach", false)) AudioService.play(this, col, index);
        } else {
            buildVideo();
            playCurrent(true);
        }
    }

    private int indexOf(String id, int fallback) {
        if (id != null) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
        }
        return Math.max(0, Math.min(fallback, items.size() - 1));
    }

    /** Picks up videos that finished downloading while we were watching. */
    private void reloadKeepingCurrent() {
        String cur = items.get(index).id;
        List<Library.Item> fresh = Library.inCollection(this, col, true);
        if (fresh.isEmpty()) return;
        items = fresh;
        index = indexOf(cur, index);
    }

    // ================================================================ video

    private void buildVideo() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Tap anywhere toggles the controls.
        View tapCatcher = new View(this);
        tapCatcher.setOnClickListener(v -> toggleControls());
        root.addView(tapCatcher, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.setOnClickListener(v -> toggleControls());

        // Top: gradient, back, title, position in category
        LinearLayout top = Ui.horizontal(this);
        top.setGravity(Gravity.TOP);
        int p = Ui.dp(this, 16);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 28), p, Ui.dp(this, 40));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        TextView back = Ui.text(this, "‹", 34);
        back.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 14), Ui.dp(this, 6));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout titles = Ui.vertical(this);
        title = Ui.medium(this, "", 16);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = Ui.muted(this, "", 12);
        titles.addView(title);
        titles.addView(subtitle);
        titles.setPadding(0, Ui.dp(this, 8), 0, 0);
        top.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        overlay.addView(top, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Center: previous, back 10 s, play/pause, forward 10 s, next
        LinearLayout center = Ui.horizontal(this);
        center.setGravity(Gravity.CENTER);
        center.addView(roundControl("⏮", 26, 52, v -> step(-1)));
        center.addView(Ui.space(this, 18, 1));
        center.addView(roundControl("−10", 15, 48, v -> seekBy(-10000)));
        center.addView(Ui.space(this, 18, 1));
        playBtn = roundControl("⏸", 32, 72, v -> togglePlay());
        playBtn.setBackground(Ui.rounded(accent, Ui.dp(this, 36)));
        center.addView(playBtn);
        center.addView(Ui.space(this, 18, 1));
        center.addView(roundControl("+10", 15, 48, v -> seekBy(10000)));
        center.addView(Ui.space(this, 18, 1));
        center.addView(roundControl("⏭", 26, 52, v -> step(1)));
        overlay.addView(center, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Bottom: up-next hint, seek bar with times
        LinearLayout bottom = Ui.vertical(this);
        bottom.setPadding(p, Ui.dp(this, 40), p, Ui.dp(this, 24));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        upNextHint = Ui.muted(this, "", 12);
        upNextHint.setMaxLines(1);
        upNextHint.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(upNextHint);
        LinearLayout bar = Ui.horizontal(this);
        timeNow = Ui.text(this, "0:00", 12);
        timeTotal = Ui.text(this, "0:00", 12);
        seek = new SeekBar(this);
        seek.getProgressDrawable().setTint(accent);
        seek.getThumb().setTint(accent);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (fromUser) timeNow.setText(Ui.duration(v / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
                handler.removeCallbacks(hideControls);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                video.seekTo(s.getProgress());
                scheduleHide();
            }
        });
        bar.addView(timeNow);
        bar.addView(seek, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(timeTotal);
        bottom.addView(bar);
        overlay.addView(bottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Up-next card (shown when a video ends)
        upNextPanel = Ui.vertical(this);
        int q = Ui.dp(this, 18);
        upNextPanel.setPadding(q, q, q, q);
        upNextPanel.setBackground(Ui.rounded(0xEE15191F, Ui.dp(this, 18)));
        upNextCount = Ui.muted(this, "", 13);
        upNextTitle = Ui.bold(this, "", 17);
        upNextTitle.setMaxLines(2);
        upNextTitle.setEllipsize(TextUtils.TruncateAt.END);
        upNextTitle.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        upNextPanel.addView(upNextCount);
        upNextPanel.addView(upNextTitle);
        LinearLayout btns = Ui.horizontal(this);
        btns.addView(Ui.pill(this, "▶  Play now", accent, Color.WHITE, v -> playNextNow()));
        btns.addView(Ui.space(this, 10, 1));
        btns.addView(Ui.pill(this, "Cancel", Ui.SURFACE_2, Ui.TEXT, v -> cancelUpNext()));
        upNextPanel.addView(btns);
        upNextPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(Ui.dp(this, 320), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(upNextPanel, ulp);

        setContentView(root);
        immersive();

        video.setOnCompletionListener(mp -> onFinished());
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Can't play this one, skipping", Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> step(1), 800);
            return true;
        });
    }

    private TextView roundControl(String s, float size, int boxDp, View.OnClickListener l) {
        TextView t = Ui.text(this, s, size);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rounded(0x66000000, Ui.dp(this, boxDp / 2)));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, boxDp), Ui.dp(this, boxDp)));
        t.setOnClickListener(v -> {
            l.onClick(v);
            scheduleHide();
        });
        return t;
    }

    private void immersive() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void playCurrent(boolean resume) {
        cancelUpNext();
        Library.Item item = items.get(index);
        title.setText(item.title);
        subtitle.setText(String.format(Locale.US, "%s  ·  %d of %d", colTitle, index + 1, items.size()));
        upNextHint.setText(index + 1 < items.size() ? "Up next: " + items.get(index + 1).title : "Last video in " + colTitle);
        video.setVideoPath(item.file);
        int pos = resume && !Cards.watched(this, item.id) ? Cards.position(this, item.id) : 0;
        video.setOnPreparedListener(mp -> {
            seek.setMax(Math.max(1, video.getDuration()));
            timeTotal.setText(Ui.duration(video.getDuration() / 1000));
            if (pos > 0) video.seekTo(pos);
            video.start();
            playBtn.setText("⏸");
        });
        Store.prefs(this).edit().putString("last_played", item.id).apply();
        showControls();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        private int n;

        @Override
        public void run() {
            if (video != null && !userSeeking) {
                int pos = video.getCurrentPosition();
                seek.setProgress(pos);
                timeNow.setText(Ui.duration(pos / 1000));
                if (++n % 10 == 0) savePosition();
            }
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable hideControls = () -> {
        if (video != null && video.isPlaying() && !userSeeking) overlay.setVisibility(View.GONE);
    };

    private void showControls() {
        overlay.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 3500);
    }

    private void toggleControls() {
        if (overlay.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(hideControls);
            overlay.setVisibility(View.GONE);
        } else {
            showControls();
        }
    }

    private void togglePlay() {
        if (video.isPlaying()) {
            video.pause();
            savePosition();
            playBtn.setText("▶");
            handler.removeCallbacks(hideControls);
        } else {
            video.start();
            playBtn.setText("⏸");
        }
    }

    private void seekBy(int ms) {
        int to = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + ms));
        video.seekTo(to);
    }

    /** Previous / next within this category only. "Previous" restarts if we're past 5 s. */
    private void step(int delta) {
        savePosition();
        reloadKeepingCurrent();
        if (delta < 0 && video.getCurrentPosition() > 5000) {
            video.seekTo(0);
            return;
        }
        int next = index + delta;
        if (next < 0) next = 0;
        if (next >= items.size()) {
            Toast.makeText(this, "That's the last one in " + colTitle, Toast.LENGTH_SHORT).show();
            return;
        }
        index = next;
        playCurrent(true);
    }

    private void onFinished() {
        Cards.markWatched(this, items.get(index).id);
        reloadKeepingCurrent();
        if (index + 1 >= items.size()) {
            upNextCount.setText("You've finished");
            upNextTitle.setText(colTitle);
            upNextPanel.setVisibility(View.VISIBLE);
            overlay.setVisibility(View.VISIBLE);
            return;
        }
        upNextTitle.setText(items.get(index + 1).title);
        countdown = UP_NEXT_SECONDS;
        upNextCount.setText("Up next in " + countdown);
        upNextPanel.setVisibility(View.VISIBLE);
        handler.postDelayed(countdownTick, 1000);
    }

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            countdown--;
            if (countdown <= 0) {
                playNextNow();
            } else {
                upNextCount.setText("Up next in " + countdown);
                handler.postDelayed(this, 1000);
            }
        }
    };

    private void playNextNow() {
        handler.removeCallbacks(countdownTick);
        upNextPanel.setVisibility(View.GONE);
        if (index + 1 < items.size()) {
            index++;
            playCurrent(true);
        } else {
            finish();
        }
    }

    private void cancelUpNext() {
        handler.removeCallbacks(countdownTick);
        if (upNextPanel != null) upNextPanel.setVisibility(View.GONE);
    }

    private void savePosition() {
        if (video == null || items.isEmpty()) return;
        int pos = video.getCurrentPosition();
        Library.Item item = items.get(index);
        if (pos > 3000 && !Cards.watched(this, item.id)) {
            Store.prefs(this).edit().putInt("pos_" + item.id, pos).apply();
        }
    }

    @Override
    protected void onPause() {
        savePosition();
        if (video != null) {
            video.pause();
            if (playBtn != null) playBtn.setText("▶");
        }
        handler.removeCallbacks(audioTick);
        handler.removeCallbacks(countdownTick);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (audioTitle != null) handler.post(audioTick);
        if (video != null) {
            immersive();
            showControls();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ================================================================ audio

    private void buildAudio() {
        Ui.darkWindow(this);
        LinearLayout root = Ui.vertical(this);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.withAlpha(accent, 90), Ui.BG, Ui.BG}));
        int p = Ui.dp(this, 28);
        root.setPadding(p, Ui.dp(this, 20), p, p);

        TextView back = Ui.text(this, "‹  " + colTitle, 16);
        back.setTextColor(Ui.MUTED);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView art = Ui.bold(this, "﴾ ﴿", 64);
        art.setGravity(Gravity.CENTER);
        art.setTextColor(accent);
        art.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 28)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 220));
        alp.setMargins(0, Ui.dp(this, 48), 0, Ui.dp(this, 32));
        root.addView(art, alp);

        audioTitle = Ui.bold(this, "", 24);
        audioTitle.setGravity(Gravity.CENTER);
        root.addView(audioTitle);

        audioSeek = new SeekBar(this);
        audioSeek.getProgressDrawable().setTint(accent);
        audioSeek.getThumb().setTint(accent);
        audioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                AudioService svc = AudioService.instance;
                if (user && svc != null) svc.seekTo(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, Ui.dp(this, 28), 0, 0);
        root.addView(audioSeek, slp);
        audioTime = Ui.muted(this, "", 13);
        audioTime.setGravity(Gravity.CENTER);
        root.addView(audioTime);

        LinearLayout controls = Ui.horizontal(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, Ui.dp(this, 24), 0, 0);
        controls.addView(audioControl("⏮", 56, Ui.SURFACE, v -> AudioService.send(this, AudioService.ACTION_PREV)));
        controls.addView(Ui.space(this, 24, 1));
        audioPlay = audioControl("⏸", 76, accent, v -> AudioService.send(this, AudioService.ACTION_TOGGLE));
        controls.addView(audioPlay);
        controls.addView(Ui.space(this, 24, 1));
        controls.addView(audioControl("⏭", 56, Ui.SURFACE, v -> AudioService.send(this, AudioService.ACTION_NEXT)));
        root.addView(controls);

        TextView note = Ui.muted(this, "Keeps playing with the screen off and continues to the next one", 12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 28), 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private TextView audioControl(String s, int boxDp, int bg, View.OnClickListener l) {
        TextView t = Ui.text(this, s, boxDp > 60 ? 32 : 24);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rounded(bg, Ui.dp(this, boxDp / 2)));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, boxDp), Ui.dp(this, boxDp)));
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
                audioSeek.setMax(Math.max(d, 1));
                audioSeek.setProgress(p);
                audioTime.setText(Ui.duration(p / 1000) + "  /  " + Ui.duration(d / 1000));
                audioPlay.setText(svc.isPlaying() ? "⏸" : "▶");
            }
            handler.postDelayed(this, 500);
        }
    };
}
