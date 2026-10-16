package com.steve.shelf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Video cards with thumbnails, duration badge and a watch-progress bar. */
final class Cards {
    private Cards() {}

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- watch state

    static int position(Context c, String id) {
        return Store.prefs(c).getInt("pos_" + id, 0);
    }

    static boolean watched(Context c, String id) {
        return Store.prefs(c).getBoolean("w_" + id, false);
    }

    static void markWatched(Context c, String id) {
        Store.prefs(c).edit().putBoolean("w_" + id, true).remove("pos_" + id).apply();
    }

    /** 0..1 share watched, from saved position and known duration. */
    static float progress(Context c, Library.Item i) {
        if (watched(c, i.id)) return 1f;
        int pos = position(c, i.id);
        if (pos <= 0 || i.dur <= 0) return 0f;
        return Math.min(1f, pos / 1000f / i.dur);
    }

    // ---------------------------------------------------------------- thumbnails

    static void loadThumb(ImageView view, Library.Item item, int targetWidthPx) {
        String path = item.file == null || item.file.isEmpty() ? null : Library.thumb(item.file);
        view.setTag(path);
        if (path == null) return;
        Bitmap cached = CACHE.get(path);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        POOL.execute(() -> {
            File f = new File(path);
            if (!f.exists()) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetWidthPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeFile(path, o);
            if (bm == null) return;
            CACHE.put(path, bm);
            MAIN.post(() -> {
                if (path.equals(view.getTag())) view.setImageBitmap(bm);
            });
        });
    }

    // ---------------------------------------------------------------- views

    /** 16:9 thumbnail with duration badge and progress bar. */
    static FrameLayout thumb(Context c, Library.Item item, int widthPx, int accent) {
        int heightPx = widthPx * 9 / 16;
        FrameLayout f = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.withAlpha(accent, 140), Ui.SURFACE_2});
        float r = Ui.dp(c, 12);
        bg.setCornerRadius(r);
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setLayoutParams(new LinearLayout.LayoutParams(widthPx, heightPx));

        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(img, new FrameLayout.LayoutParams(widthPx, heightPx));
        loadThumb(img, item, widthPx);

        String d = Ui.duration(item.dur);
        if (!d.isEmpty()) {
            TextView badge = Ui.medium(c, d, 11);
            int p = Ui.dp(c, 5);
            badge.setPadding(p, Ui.dp(c, 2), p, Ui.dp(c, 2));
            badge.setBackground(Ui.rounded(0xCC000000, Ui.dp(c, 6)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, Ui.dp(c, 6), Ui.dp(c, 8));
            f.addView(badge, lp);
        }

        if (watched(c, item.id)) {
            TextView w = Ui.medium(c, "✓ Watched", 11);
            int p = Ui.dp(c, 6);
            w.setPadding(p, Ui.dp(c, 2), p, Ui.dp(c, 2));
            w.setBackground(Ui.rounded(0xCC000000, Ui.dp(c, 6)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            lp.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), 0, 0);
            f.addView(w, lp);
        }

        float prog = progress(c, item);
        if (prog > 0f && prog < 1f) {
            View track = new View(c);
            track.setBackgroundColor(0x66FFFFFF);
            f.addView(track, new FrameLayout.LayoutParams(widthPx, Ui.dp(c, 3), Gravity.BOTTOM));
            View bar = new View(c);
            bar.setBackgroundColor(accent);
            f.addView(bar, new FrameLayout.LayoutParams(Math.max(1, (int) (widthPx * prog)), Ui.dp(c, 3), Gravity.BOTTOM));
        }
        return f;
    }

    /** Card used in horizontal shelves: thumbnail with a two-line title under it. */
    static LinearLayout card(Context c, Library.Item item, int widthPx, int accent, View.OnClickListener click) {
        LinearLayout card = Ui.vertical(c);
        card.addView(thumb(c, item, widthPx, accent));
        TextView t = Ui.text(c, item.title, 13);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(t);
        card.setOnClickListener(click);
        return card;
    }
}
