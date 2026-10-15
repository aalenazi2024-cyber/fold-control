package com.steve.doorcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home: every saved device with one-tap open buttons. Tap a name for the test bench. */
public class MainActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = Ui.dp(this, 16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        root.addView(Ui.text(this, "Door Control", 24));
        TextView hint = Ui.text(this, "Talks to the Hikvision stations directly on the home network. "
                + "Tap a device name for status, events, config and the test console.", 13);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        root.addView(hint);

        root.addView(Ui.button(this, "+ Add device", v ->
                Ui.editDevice(this, null, d -> { save(d); refresh(); }, null)));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void save(Device d) {
        try {
            DeviceStore.upsert(this, d);
        } catch (Exception e) {
            Ui.toast(this, "Save failed: " + e.getMessage());
        }
    }

    private void refresh() {
        list.removeAllViews();
        List<Device> devices = DeviceStore.load(this);
        if (devices.isEmpty()) {
            TextView t = Ui.text(this, "\nNo devices yet. Add each door station with its IP address "
                    + "and the admin password the installer set.", 15);
            list.addView(t);
            return;
        }
        for (Device d : devices) list.addView(card(d));
    }

    private LinearLayout card(Device d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));

        TextView title = Ui.text(this, d.label() + "  ›", 18);
        title.setOnClickListener(v -> startActivity(
                new Intent(this, DeviceActivity.class).putExtra("id", d.id)));
        title.setOnLongClickListener(v -> {
            Ui.editDevice(this, d, x -> { save(x); refresh(); }, () -> {
                try { DeviceStore.delete(this, d.id); } catch (Exception ignored) {}
                refresh();
            });
            return true;
        });
        card.addView(title);

        TextView result = Ui.text(this, d.host + (d.port == 80 ? "" : ":" + d.port) + "  ·  long-press name to edit", 12);
        card.addView(result);

        Button[] opens = new Button[d.doors];
        for (int i = 0; i < d.doors; i++) {
            int door = i + 1;
            opens[i] = Ui.button(this, d.doors == 1 ? "Open" : "Open " + door, v -> open(d, door, result));
        }
        LinearLayout row = Ui.row(this, opens);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row);
        return card;
    }

    private void open(Device d, int door, TextView result) {
        result.setText("Opening door " + door + "…");
        io.execute(() -> {
            String msg;
            try {
                HikClient.Response r = Isapi.door(new HikClient(d), door, Isapi.CMD_OPEN);
                msg = (r.ok() ? "✓ Door " + door + " opened" : "✗ Door " + door + ": " + Isapi.replyStatus(r))
                        + " in " + r.millis + " ms";
            } catch (Exception e) {
                msg = "✗ Can't reach " + d.host + ": " + e.getMessage();
            }
            String m = msg;
            runOnUiThread(() -> result.setText(m));
        });
    }
}
