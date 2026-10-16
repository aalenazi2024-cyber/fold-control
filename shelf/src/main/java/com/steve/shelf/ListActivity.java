package com.steve.shelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/** One collection: play all, or pick an item. Long-press to delete. */
public class ListActivity extends Activity {
    private String col;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        col = getIntent().getStringExtra("col");
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 24), p, p);
        sv.addView(root);
        setContentView(sv);

        JSONObject cat = Catalog.load(this, false);
        String title = "saved".equals(col) ? "Saved links" : Catalog.title(cat, col);
        root.addView(Ui.bold(this, title, 24));
        root.addView(Ui.button(this, "▶  Play all", v -> play(0)));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
    }

    private void play(int index) {
        if (Library.inCollection(this, col, true).isEmpty()) return;
        startActivity(new Intent(this, PlayerActivity.class).putExtra("col", col).putExtra("index", index));
    }

    private void fill() {
        list.removeAllViews();
        List<Library.Item> ready = Library.inCollection(this, col, true);
        for (int i = 0; i < ready.size(); i++) {
            Library.Item item = ready.get(i);
            final int idx = i;
            list.addView(rowFor(item, true, v -> play(idx)));
        }
        List<Library.Item> all = Library.inCollection(this, col, false);
        int waiting = 0;
        for (Library.Item item : all) if (!Library.DONE.equals(item.status)) waiting++;
        if (waiting > 0) {
            TextView h = Ui.bold(this, "Coming (" + waiting + ")", 15);
            h.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
            list.addView(h);
            for (Library.Item item : all) {
                if (Library.DONE.equals(item.status)) continue;
                list.addView(rowFor(item, false, null));
            }
        }
    }

    private LinearLayout rowFor(Library.Item item, boolean ready, android.view.View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 8);
        r.setPadding(0, pad, 0, pad);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (ready && item.file != null) {
            File t = new File(Library.thumb(item.file));
            if (t.exists()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap bm = BitmapFactory.decodeFile(t.getAbsolutePath(), o);
                if (bm != null) img.setImageBitmap(bm);
            }
        }
        if (img.getDrawable() == null) img.setImageResource(ready ? android.R.drawable.ic_media_play : android.R.drawable.stat_sys_download);
        r.addView(img, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 54)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView t1 = Ui.text(this, item.title, 15);
        t1.setMaxLines(2);
        String sub = Ui.duration(item.dur);
        if (!ready) sub = (Library.FAILED.equals(item.status) ? "Couldn't download" : "Waiting") + (sub.isEmpty() ? "" : " · " + sub);
        int pos = Store.prefs(this).getInt("pos_" + item.id, 0);
        if (ready && pos > 0) sub += (sub.isEmpty() ? "" : " · ") + "resume at " + Ui.duration(pos / 1000);
        texts.addView(t1);
        texts.addView(Ui.text(this, sub, 12));
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (click != null) r.setOnClickListener(click);
        r.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(item.title)
                    .setItems(new String[]{ready ? "Delete download" : "Remove from queue",
                            Library.FAILED.equals(item.status) ? "Try again" : "Cancel"}, (d, w) -> {
                        if (w == 0) {
                            Library.remove(this, item.id);
                        } else if (Library.FAILED.equals(item.status)) {
                            item.status = Library.QUEUED;
                            item.tries = 0;
                            Library.update(this, item);
                        }
                        fill();
                    })
                    .show();
            return true;
        });
        return r;
    }
}
