package com.steve.shelf;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Home: collections as tiles, download status, and adding links. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView status;
    private TextView stats;
    private GridLayout grid;
    private int gridCols;

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
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        build();
        handleShare(getIntent());
        // One tap: lets downloads keep their internet connection while the phone sleeps.
        Power.askExemption(this);
        // Opening the app is the one moment Android lets us run a foreground download.
        new Thread(() -> {
            JSONObject cat = Catalog.load(this, true);
            long stop = Catalog.stopAt(cat);
            if (stop == 0 || System.currentTimeMillis() < stop) {
                DownloadService.start(this, false);
            }
            runOnUiThread(this::fillGrid);
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
        fillGrid();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 28), p, p);
        sv.addView(root);
        setContentView(sv);

        root.addView(Ui.bold(this, "Shelf", 28));
        stats = Ui.text(this, "", 14);
        root.addView(stats);
        status = Ui.text(this, "", 13);
        status.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        status.setMaxLines(2);
        root.addView(status);

        root.addView(Ui.row(this,
                Ui.button(this, "Download now", v -> {
                    boolean anyNet = Engine.isMetered(this);
                    DownloadService.start(this, anyNet);
                    Toast.makeText(this, anyNet ? "Downloading on mobile data" : "Downloading", Toast.LENGTH_SHORT).show();
                }),
                Ui.button(this, "Add link", v -> addLinkDialog()),
                Ui.button(this, "Log", v -> showLog())));

        grid = new GridLayout(this);
        grid.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(grid);
    }

    private void fillGrid() {
        if (grid == null) return;
        grid.removeAllViews();
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        gridCols = widthDp > 600 ? 3 : 2;
        grid.setColumnCount(gridCols);
        JSONObject cat = Catalog.load(this, false);
        JSONArray cols = Catalog.collections(cat);
        int k = 0;
        for (int i = 0; i <= cols.length(); i++) {
            String id, title;
            if (i < cols.length()) {
                JSONObject col = cols.optJSONObject(i);
                id = col.optString("id");
                title = col.optString("title", id);
            } else {
                id = "saved";
                title = "Saved links";
                if (Library.inCollection(this, id, false).isEmpty()) continue;
            }
            int ready = Library.count(this, id, Library.DONE);
            int coming = Library.count(this, id, Library.QUEUED);
            String sub = ready + " ready" + (coming > 0 ? " · " + coming + " coming" : "");
            final String colId = id;
            LinearLayout tile = Ui.tile(this, title, sub, Ui.COLORS[k % Ui.COLORS.length],
                    v -> startActivity(new Intent(this, ListActivity.class).putExtra("col", colId)));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            int m = Ui.dp(this, 5);
            lp.setMargins(m, m, m, m);
            grid.addView(tile, lp);
            k++;
        }
    }

    private void updateStatus() {
        JSONObject cat = Catalog.load(this, false);
        int done = Library.count(this, null, Library.DONE);
        int queued = Library.count(this, null, Library.QUEUED);
        long stop = Catalog.stopAt(cat);
        String s = done + " ready · " + queued + " in queue · " + Ui.gb(Library.bytesUsed(this));
        if (stop > 0) {
            String when = DateFormat.getTimeFormat(this).format(new Date(stop));
            s += System.currentTimeMillis() < stop ? " · auto-download until " + when : " · auto-download ended";
        }
        stats.setText(s);
        String st = Store.prefs(this).getString("status", "");
        status.setText((Engine.isBusy() ? "● " : "") + st);
        // Counts on tiles change as items finish.
        long last = Store.prefs(this).getLong("ui_grid", 0);
        if (System.currentTimeMillis() - last > 10000) {
            Store.prefs(this).edit().putLong("ui_grid", System.currentTimeMillis()).apply();
            fillGrid();
        }
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        String url = firstUrl(text);
        if (url == null) {
            Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show();
            return;
        }
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        chooseCollection(url, subject);
    }

    private static String firstUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        return m.find() ? m.group() : null;
    }

    private void addLinkDialog() {
        EditText e = new EditText(this);
        e.setHint("YouTube or TikTok link, video or playlist");
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
                    if (!Engine.isBusy()) DownloadService.start(this, false);
                    fillGrid();
                })
                .show();
    }

    /** Playlists and profiles are stored as extra sources so the engine expands them. */
    private void addPlaylist(String url, String col) {
        try {
            String key = "user_sources";
            JSONArray arr = new JSONArray(Store.prefs(this).getString(key, "[]"));
            arr.put(new JSONObject().put("col", col).put("url", url).put("max", 50));
            Store.prefs(this).edit().putString(key, arr.toString()).apply();
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
        new AlertDialog.Builder(this).setTitle("Activity").setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Shelf log", Store.log(this)));
                })
                .show();
    }
}
