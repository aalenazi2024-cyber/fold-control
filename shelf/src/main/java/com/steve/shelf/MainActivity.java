package com.steve.shelf;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Home: Continue watching, then one shelf of thumbnails per category. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView statusLine, statusDetail;
    private View statusDot;
    private int lastDoneCount = -1;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.darkWindow(this);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        build();
        handleShare(getIntent());
        // One tap: lets downloads keep their internet connection while the phone sleeps.
        Power.askExemption(this);
        // Opening the app is the one moment Android always lets us run a foreground download.
        new Thread(() -> {
            JSONObject cat = Catalog.load(this, true);
            long stop = Catalog.stopAt(cat);
            if (stop == 0 || System.currentTimeMillis() < stop) DownloadService.start(this, false);
            runOnUiThread(this::fill);
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout root = Ui.vertical(this);
        root.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 32));
        sv.addView(root);
        setContentView(sv);

        // Header
        LinearLayout header = Ui.horizontal(this);
        header.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 12), 0);
        TextView title = Ui.bold(this, "Shelf", 30);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(iconButton("+", v -> addLinkDialog()));
        header.addView(iconButton("≡", v -> showLog()));
        root.addView(header);

        // Status card
        LinearLayout status = Ui.horizontal(this);
        int p = Ui.dp(this, 14);
        status.setPadding(p, p, p, p);
        status.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 16)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 4));
        statusDot = new View(this);
        statusDot.setBackground(Ui.rounded(Ui.MUTED, Ui.dp(this, 5)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dlp.setMargins(0, 0, Ui.dp(this, 12), 0);
        status.addView(statusDot, dlp);
        LinearLayout texts = Ui.vertical(this);
        statusLine = Ui.medium(this, "", 14);
        statusDetail = Ui.muted(this, "", 12);
        statusDetail.setMaxLines(1);
        statusDetail.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(statusLine);
        texts.addView(statusDetail);
        status.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        status.addView(Ui.pill(this, "Download", Ui.ACCENT, 0xFFFFFFFF, v -> {
            boolean anyNet = Engine.isMetered(this);
            DownloadService.start(this, anyNet);
            Toast.makeText(this, anyNet ? "Downloading on mobile data" : "Downloading", Toast.LENGTH_SHORT).show();
        }));
        root.addView(status, slp);

        content = Ui.vertical(this);
        root.addView(content);
    }

    private TextView iconButton(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 24);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 20)));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        lp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private int cardWidth() {
        float wDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        // Folded: ~1.7 cards visible; open: ~3.3.
        float cardDp = wDp > 600 ? (wDp - 40) / 3.3f : (wDp - 40) / 1.7f;
        return Ui.dp(this, (int) Math.min(cardDp, 300));
    }

    private void fill() {
        if (content == null) return;
        content.removeAllViews();
        JSONObject cat = Catalog.load(this, false);
        int w = cardWidth();

        // Continue watching: started but not finished, newest first.
        List<Library.Item> cont = new ArrayList<>();
        for (Library.Item i : Library.all(this)) {
            if (!Library.DONE.equals(i.status)) continue;
            if (Cards.position(this, i.id) > 0 && !Cards.watched(this, i.id)) cont.add(i);
        }
        String last = Store.prefs(this).getString("last_played", "");
        cont.sort((a, b) -> a.id.equals(last) ? -1 : b.id.equals(last) ? 1 : 0);
        if (!cont.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Library.Item i : cont) ids.add(i.id);
            addShelf("Continue watching", null, cont, w, Ui.ACCENT, (item, idx) -> openPlayer(item.col, item.id));
        }

        JSONArray cols = Catalog.collections(cat);
        int colorIdx = 0;
        for (int i = 0; i <= cols.length(); i++) {
            String id, title;
            if (i < cols.length()) {
                id = cols.optJSONObject(i).optString("id");
                title = cols.optJSONObject(i).optString("title", id);
            } else {
                id = "saved";
                title = "Saved links";
            }
            List<Library.Item> ready = Library.inCollection(this, id, true);
            int coming = Library.count(this, id, Library.QUEUED);
            if (ready.isEmpty() && coming == 0) continue;
            int accent = Ui.colorFor(colorIdx++);
            String sub = ready.size() + " ready" + (coming > 0 ? " · " + coming + " coming" : "");
            final String colId = id;
            addShelf(title, sub, ready, w, accent, (item, idx) -> openPlayer(colId, item.id));
            // Header tap opens the full list.
            View head = content.getChildAt(content.getChildCount() - 2);
            head.setOnClickListener(v -> startActivity(new Intent(this, ListActivity.class).putExtra("col", colId)));
        }

        if (content.getChildCount() == 0) {
            TextView empty = Ui.muted(this, "Getting your videos ready. They appear here as each one finishes downloading.", 14);
            empty.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), 0);
            content.addView(empty);
        }
    }

    interface ItemClick {
        void onClick(Library.Item item, int index);
    }

    /** Adds a header row and a horizontal strip of cards. */
    private void addShelf(String title, String sub, List<Library.Item> items, int w, int accent, ItemClick click) {
        LinearLayout head = Ui.horizontal(this);
        head.setPadding(Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 10));
        View bar = new View(this);
        bar.setBackground(Ui.rounded(accent, Ui.dp(this, 2)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(this, 4), Ui.dp(this, 20));
        blp.setMargins(0, 0, Ui.dp(this, 10), 0);
        head.addView(bar, blp);
        head.addView(Ui.bold(this, title, 18), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (sub != null) {
            TextView s = Ui.muted(this, sub + "  ›", 12);
            head.addView(s);
        }
        content.addView(head);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = Ui.horizontal(this);
        strip.setGravity(Gravity.TOP);
        strip.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        if (items.isEmpty()) {
            TextView t = Ui.muted(this, "Downloading…", 13);
            t.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, Ui.dp(this, 8));
            strip.addView(t);
        }
        for (int k = 0; k < items.size(); k++) {
            Library.Item item = items.get(k);
            final int idx = k;
            LinearLayout card = Cards.card(this, item, w, accent, v -> click.onClick(item, idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 8), 0);
            strip.addView(card, lp);
        }
        hs.addView(strip);
        content.addView(hs);
    }

    private void openPlayer(String col, String itemId) {
        startActivity(new Intent(this, PlayerActivity.class).putExtra("col", col).putExtra("item", itemId));
    }

    // ---------------------------------------------------------------- status

    private void updateStatus() {
        JSONObject cat = Catalog.load(this, false);
        int done = Library.count(this, null, Library.DONE);
        int queued = Library.count(this, null, Library.QUEUED);
        boolean busy = Engine.isBusy();
        long stop = Catalog.stopAt(cat);
        String line = done + " ready · " + Ui.gb(Library.bytesUsed(this));
        if (queued > 0) line += " · " + queued + " coming";
        statusLine.setText(line);
        String st = Store.prefs(this).getString("status", "");
        String detail = busy ? st : (st.isEmpty() ? "Idle" : st);
        if (stop > 0 && System.currentTimeMillis() < stop) {
            detail += "  ·  until " + DateFormat.getTimeFormat(this).format(new Date(stop));
        }
        statusDetail.setText(detail);
        statusDot.setBackground(Ui.rounded(busy ? 0xFF22C55E : Ui.MUTED, Ui.dp(this, 5)));
        if (done != lastDoneCount) {
            lastDoneCount = done;
            fill();
        }
    }

    // ---------------------------------------------------------------- adding links

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String url = firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT));
        if (url == null) {
            Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show();
            return;
        }
        chooseCollection(url, intent.getStringExtra(Intent.EXTRA_SUBJECT));
    }

    private static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        return m.find() ? m.group() : null;
    }

    private void addLinkDialog() {
        EditText e = new EditText(this);
        e.setHint("YouTube or TikTok link, video or playlist");
        int p = Ui.dp(this, 20);
        e.setPadding(p, p, p, p);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add link")
                .setView(e)
                .setPositiveButton("Next", (d, w) -> {
                    String url = firstUrl(e.getText().toString());
                    if (url != null) chooseCollection(url, null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseCollection(String url, String title) {
        JSONArray cols = Catalog.collections(Catalog.load(this, false));
        String[] names = new String[cols.length() + 1];
        String[] ids = new String[cols.length() + 1];
        for (int i = 0; i < cols.length(); i++) {
            names[i] = cols.optJSONObject(i).optString("title");
            ids[i] = cols.optJSONObject(i).optString("id");
        }
        names[cols.length()] = "Saved links";
        ids[cols.length()] = "saved";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Save to…")
                .setItems(names, (d, which) -> {
                    boolean playlist = url.contains("list=") || url.contains("/playlist") || url.matches(".*tiktok\\.com/@[^/]+/?$");
                    if (playlist) {
                        addPlaylist(url, ids[which]);
                    } else {
                        Library.add(this, "s_" + Integer.toHexString(url.hashCode()), url,
                                title != null ? title : url, ids[which], 0, (int) (System.currentTimeMillis() / 1000 % 100000));
                        Toast.makeText(this, "Added to " + names[which], Toast.LENGTH_SHORT).show();
                    }
                    DownloadService.start(this, false);
                    fill();
                })
                .show();
    }

    /** Playlists and profiles are stored as extra sources so the engine expands them. */
    private void addPlaylist(String url, String col) {
        try {
            JSONArray arr = new JSONArray(Store.prefs(this).getString("user_sources", "[]"));
            arr.put(new JSONObject().put("col", col).put("url", url).put("max", 50));
            Store.prefs(this).edit().putString("user_sources", arr.toString()).apply();
            Toast.makeText(this, "Playlist added", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private void showLog() {
        TextView t = Ui.text(this, Store.log(this), 12);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Activity").setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Shelf log", Store.log(this)));
                })
                .show();
    }
}
