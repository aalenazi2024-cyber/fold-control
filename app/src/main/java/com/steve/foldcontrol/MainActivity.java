package com.steve.foldcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Control screen: status, sync, clean-up, and a list to hide or restore any app. */
public class MainActivity extends Activity {

    private static final class AppRow {
        final String pkg;
        final String label;
        boolean hidden;
        AppRow(String pkg, String label, boolean hidden) {
            this.pkg = pkg; this.label = label; this.hidden = hidden;
        }
    }

    private TextView status;
    private EditText filter;
    private ArrayAdapter<AppRow> adapter;
    private final List<AppRow> all = new ArrayList<>();
    private final List<AppRow> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("Fold Control");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, dp(4), 0, dp(12));
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Sync now", v -> syncNow()), weight());
        buttons.addView(button("Clean phone", v -> confirmClean()), weight());
        buttons.addView(button("Log", v -> showLog()), weight());
        root.addView(buttons);

        filter = new EditText(this);
        filter.setHint("Search apps (tap one to hide or restore)");
        filter.setSingleLine(true);
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { applyFilter(); }
        });
        root.addView(filter);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<AppRow>(this, android.R.layout.simple_list_item_2, android.R.id.text1, shown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                AppRow r = getItem(position);
                ((TextView) v.findViewById(android.R.id.text1))
                        .setText((r.hidden ? "[hidden]  " : "") + r.label);
                TextView t2 = v.findViewById(android.R.id.text2);
                t2.setText(r.pkg);
                v.setAlpha(r.hidden ? 0.5f : 1f);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> toggle(shown.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Policy.isOwner(this)) UpdateJob.schedule(this);
        refreshStatus();
        loadApps();
    }

    private void refreshStatus() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        long last = Store.prefs(this).getLong("last_sync", 0);
        String lastText = last == 0 ? "never" :
                DateUtils.getRelativeTimeSpanString(last).toString();
        boolean clean = Store.prefs(this).getBoolean("cleanup_enabled", false);
        status.setText((Policy.isOwner(this) ? "Device owner: YES" : "Device owner: NO (limited mode)")
                + "\nVersion " + version + "  ·  Last sync: " + lastText
                + "\nAuto clean-up: " + (clean ? "on" : "off"));
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> infos = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES);
            List<AppRow> rows = new ArrayList<>();
            for (ApplicationInfo ai : infos) {
                String label;
                try { label = ai.loadLabel(pm).toString(); } catch (Exception e) { label = ai.packageName; }
                rows.add(new AppRow(ai.packageName, label, Policy.isHidden(this, ai.packageName)));
            }
            Collections.sort(rows, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                all.clear();
                all.addAll(rows);
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        String q = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (AppRow r : all) {
            if (q.isEmpty() || r.label.toLowerCase(Locale.ROOT).contains(q)
                    || r.pkg.toLowerCase(Locale.ROOT).contains(q)) shown.add(r);
        }
        adapter.notifyDataSetChanged();
    }

    private void toggle(AppRow r) {
        if (!Policy.isOwner(this)) {
            toast("Not device owner, can't change apps");
            return;
        }
        Set<String> prot = Policy.protectedPackages(this);
        if (!r.hidden && prot.contains(r.pkg)) {
            toast("Protected (needed by the system or set as a default app)");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(r.hidden ? "Restore app?" : "Hide app?")
                .setMessage(r.label + "\n" + r.pkg)
                .setPositiveButton(r.hidden ? "Restore" : "Hide", (d, w) -> {
                    toast(Policy.setHidden(this, r.pkg, !r.hidden));
                    r.hidden = Policy.isHidden(this, r.pkg);
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClean() {
        new AlertDialog.Builder(this)
                .setTitle("Clean phone")
                .setMessage("Hide the Samsung and pre-installed apps on the list in config.json, "
                        + "and keep them hidden from now on. Nothing is deleted: you can restore "
                        + "any app from this screen.")
                .setPositiveButton("Clean", (d, w) -> {
                    Store.prefs(this).edit().putBoolean("cleanup_enabled", true).apply();
                    syncNow();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void syncNow() {
        toast("Syncing…");
        new Thread(() -> {
            try {
                Sync.run(this);
            } catch (Exception e) {
                Store.log(this, "Sync error: " + e.getMessage());
            }
            runOnUiThread(() -> {
                toast("Sync finished");
                refreshStatus();
                loadApps();
            });
        }).start();
    }

    private void showLog() {
        TextView tv = new TextView(this);
        tv.setText(Store.readLog(this));
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Activity log").setView(sv)
                .setPositiveButton("Close", null).show();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
