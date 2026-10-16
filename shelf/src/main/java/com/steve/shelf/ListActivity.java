package com.steve.shelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** One category: big Play button, every ready video, then what's still coming. */
public class ListActivity extends Activity {
    private String col;
    private int accent;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.darkWindow(this);
        col = getIntent().getStringExtra("col");
        JSONObject cat = Catalog.load(this, false);
        JSONArray cols = Catalog.collections(cat);
        int idx = cols.length();
        for (int i = 0; i < cols.length(); i++) if (col.equals(cols.optJSONObject(i).optString("id"))) idx = i;
        accent = Ui.colorFor(idx);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vertical(this);
        int p = Ui.dp(this, 20);
        root.setPadding(p, Ui.dp(this, 16), p, Ui.dp(this, 32));
        sv.addView(root);
        setContentView(sv);

        TextView back = Ui.text(this, "‹  Back", 16);
        back.setTextColor(Ui.MUTED);
        back.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        String title = "saved".equals(col) ? "Saved links" : Catalog.title(cat, col);
        TextView t = Ui.bold(this, title, 28);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        root.addView(t);
        root.addView(Ui.muted(this, "Plays in order and continues to the next video automatically", 13));

        LinearLayout actions = Ui.horizontal(this);
        actions.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        actions.addView(Ui.pill(this, "▶  Play", accent, 0xFFFFFFFF, v -> play(null, false)));
        actions.addView(Ui.space(this, 10, 1));
        actions.addView(Ui.pill(this, "Continue", Ui.SURFACE_2, Ui.TEXT, v -> play(null, true)));
        root.addView(actions);

        list = Ui.vertical(this);
        root.addView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
    }

    /** Plays from a given item, from the start, or from the first unwatched one. */
    private void play(String itemId, boolean firstUnwatched) {
        List<Library.Item> ready = Library.inCollection(this, col, true);
        if (ready.isEmpty()) return;
        String start = itemId;
        if (start == null) {
            start = ready.get(0).id;
            if (firstUnwatched) {
                for (Library.Item i : ready) {
                    if (!Cards.watched(this, i.id)) {
                        start = i.id;
                        break;
                    }
                }
            }
        }
        startActivity(new Intent(this, PlayerActivity.class).putExtra("col", col).putExtra("item", start));
    }

    private void fill() {
        list.removeAllViews();
        List<Library.Item> ready = Library.inCollection(this, col, true);
        int thumbW = Ui.dp(this, 132);
        for (int i = 0; i < ready.size(); i++) {
            Library.Item item = ready.get(i);
            list.addView(row(item, i + 1, thumbW, true));
        }
        List<Library.Item> all = Library.inCollection(this, col, false);
        int waiting = 0;
        for (Library.Item item : all) if (!Library.DONE.equals(item.status)) waiting++;
        if (waiting > 0) {
            TextView h = Ui.bold(this, "Coming next · " + waiting, 15);
            h.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 6));
            list.addView(h);
            for (Library.Item item : all) {
                if (Library.DONE.equals(item.status)) continue;
                list.addView(row(item, 0, thumbW, false));
            }
        }
        if (ready.isEmpty() && waiting == 0) {
            list.addView(Ui.muted(this, "Nothing here yet.", 14));
        }
    }

    private LinearLayout row(Library.Item item, int number, int thumbW, boolean ready) {
        LinearLayout r = Ui.horizontal(this);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        if (ready) {
            FrameLayout th = Cards.thumb(this, item, thumbW, accent);
            r.addView(th);
        } else {
            View ph = new View(this);
            ph.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 12)));
            r.addView(ph, new LinearLayout.LayoutParams(thumbW, thumbW * 9 / 16));
        }
        LinearLayout texts = Ui.vertical(this);
        texts.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView t1 = Ui.text(this, item.title, 14);
        t1.setMaxLines(2);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        if (!ready) t1.setTextColor(Ui.MUTED);
        texts.addView(t1);
        String sub;
        if (ready) {
            int pos = Cards.position(this, item.id);
            sub = Cards.watched(this, item.id) ? "Watched"
                    : pos > 0 ? "Resume at " + Ui.duration(pos / 1000) : Ui.duration(item.dur);
        } else {
            sub = Library.FAILED.equals(item.status) ? "Couldn't download · hold to retry" : "Waiting to download";
        }
        TextView t2 = Ui.muted(this, sub, 12);
        t2.setPadding(0, Ui.dp(this, 4), 0, 0);
        texts.addView(t2);
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.setGravity(Gravity.CENTER_VERTICAL);

        if (ready) r.setOnClickListener(v -> play(item.id, false));
        r.setOnLongClickListener(v -> {
            String[] opts = ready
                    ? new String[]{Cards.watched(this, item.id) ? "Mark as unwatched" : "Mark as watched", "Delete download"}
                    : new String[]{Library.FAILED.equals(item.status) ? "Try again" : "Download first", "Remove"};
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(item.title)
                    .setItems(opts, (d, w) -> {
                        if (ready && w == 0) {
                            if (Cards.watched(this, item.id)) Store.prefs(this).edit().remove("w_" + item.id).apply();
                            else Cards.markWatched(this, item.id);
                        } else if (w == 1) {
                            Library.remove(this, item.id);
                        } else {
                            item.status = Library.QUEUED;
                            item.tries = 0;
                            item.order = -1; // jump the queue within this category
                            Library.update(this, item);
                            DownloadService.start(this, false);
                        }
                        fill();
                    })
                    .show();
            return true;
        });
        return r;
    }
}
