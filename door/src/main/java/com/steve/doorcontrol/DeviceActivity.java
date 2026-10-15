package com.steve.doorcontrol;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test bench for one device: lock commands with a live timeline of what the lock
 * actually did, status, event log, config readout, snapshot and a raw ISAPI console.
 */
public class DeviceActivity extends Activity {

    private static final long WATCH_MS = 12_000;
    private static final long POLL_MS = 400;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Device device;
    private HikClient client;
    private int door = 1;

    private TextView out;
    private ScrollView scroll;
    private ImageView snapshot;
    private LinearLayout console;
    private Spinner method;
    private EditText path, body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        device = DeviceStore.find(this, getIntent().getStringExtra("id"));
        if (device == null) {
            finish();
            return;
        }
        client = new HikClient(device);
        int pad = Ui.dp(this, 16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.text(this, device.label(), 22));
        root.addView(Ui.text(this, device.user + " @ " + device.host + (device.port == 80 ? "" : ":" + device.port), 12));

        if (device.doors > 1) {
            RadioGroup g = new RadioGroup(this);
            g.setOrientation(RadioGroup.HORIZONTAL);
            for (int i = 1; i <= device.doors; i++) {
                RadioButton rb = new RadioButton(this);
                rb.setText("Door " + i);
                rb.setId(View.generateViewId());
                int n = i;
                rb.setOnCheckedChangeListener((b, on) -> { if (on) door = n; });
                g.addView(rb);
                if (i == 1) rb.setChecked(true);
            }
            root.addView(g);
        }

        root.addView(Ui.row(this,
                Ui.button(this, "OPEN", v -> lockCommand(Isapi.CMD_OPEN)),
                Ui.button(this, "Close", v -> lockCommand(Isapi.CMD_CLOSE))));
        root.addView(Ui.row(this,
                Ui.button(this, "Hold open", v -> Ui.confirm(this, "Hold door " + door + " open?",
                        "It stays unlocked until you press Close.", () -> lockCommand(Isapi.CMD_ALWAYS_OPEN))),
                Ui.button(this, "Hold locked", v -> Ui.confirm(this, "Hold door " + door + " locked?",
                        "Face, app and indoor-monitor unlocks will be refused until you press Close.",
                        () -> lockCommand(Isapi.CMD_ALWAYS_CLOSE)))));
        root.addView(Ui.row(this,
                Ui.button(this, "Status", v -> run(this::status)),
                Ui.button(this, "Events", v -> run(() -> say(Isapi.events(client, 24)))),
                Ui.button(this, "Config", v -> run(this::configReadout))));
        root.addView(Ui.row(this,
                Ui.button(this, "Snapshot", v -> run(this::takeSnapshot)),
                Ui.button(this, "Console", v -> console.setVisibility(
                        console.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)),
                Ui.button(this, "Share log", v -> share())));
        root.addView(Ui.row(this,
                Ui.button(this, "Copy log", v -> copy()),
                Ui.button(this, "Clear", v -> { out.setText(""); snapshot.setVisibility(View.GONE); })));

        console = buildConsole();
        console.setVisibility(View.GONE);
        root.addView(console);

        snapshot = new ImageView(this);
        snapshot.setAdjustViewBounds(true);
        snapshot.setVisibility(View.GONE);
        root.addView(snapshot);

        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextIsSelectable(true);
        out.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(out);

        scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);

        say("Ready. Pick a door and press OPEN — the app sends the unlock, then watches the "
                + "lock for " + (WATCH_MS / 1000) + " s and logs every change.");
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---- actions --------------------------------------------------------------------------

    private interface Job { void run() throws Exception; }

    private void run(Job job) {
        io.execute(() -> {
            try {
                job.run();
            } catch (Exception e) {
                say("✗ " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\n  (Is the phone on the villa Wi-Fi, and is " + device.host + " right?)");
            }
        });
    }

    /** Baseline status → command → poll status, logging each change with its timing. */
    private void lockCommand(String cmd) {
        int d = door;
        run(() -> {
            say("\n── " + stamp() + "  " + cmd + " door " + d + " ──");
            HikClient.Response before = Isapi.workStatus(client);
            boolean canWatch = before.ok();
            String last = canWatch ? Isapi.summarizeStatus(before.text()) : null;
            say(canWatch ? "before   " + last
                    : "status not available (" + Isapi.replyStatus(before) + "), sending command only");

            long t0 = System.currentTimeMillis();
            HikClient.Response r = Isapi.door(client, d, cmd);
            say("command  " + Isapi.replyStatus(r) + " in " + r.millis + " ms"
                    + (r.attempts > 1 ? " (incl. login handshake)" : ""));
            if (!r.ok()) {
                say(r.text().trim());
                return;
            }
            if (!canWatch) return;

            int changes = 0;
            while (System.currentTimeMillis() - t0 < WATCH_MS) {
                Thread.sleep(POLL_MS);
                HikClient.Response s = Isapi.workStatus(client);
                String now = s.ok() ? Isapi.summarizeStatus(s.text()) : "status " + Isapi.replyStatus(s);
                if (!now.equals(last)) {
                    changes++;
                    say(String.format(Locale.US, "+%5.1fs  %s", (System.currentTimeMillis() - t0) / 1000.0, now));
                    last = now;
                }
            }
            say(changes == 0
                    ? "no state change seen in " + (WATCH_MS / 1000) + " s — the device accepted the command "
                    + "but reports no lock movement (check relay wiring / lock type / door number)"
                    : "watch ended after " + (WATCH_MS / 1000) + " s, " + changes + " change(s)");
        });
    }

    private void status() throws Exception {
        HikClient.Response r = Isapi.workStatus(client);
        say("\n── " + stamp() + "  status ──");
        say(r.ok() ? Isapi.summarizeStatus(r.text()) + "\n" + r.text().trim() : Isapi.replyStatus(r));
    }

    private void configReadout() throws Exception {
        say("\n── " + stamp() + "  config readout ──");
        for (String p : Isapi.CONFIG_PATHS) {
            HikClient.Response r = client.get(p);
            say("\n## GET " + p + "  →  " + r.code + " (" + r.millis + " ms)");
            String t = r.text().trim();
            if (t.length() > 4000) t = t.substring(0, 4000) + "\n… (" + r.bytes.length + " bytes, truncated)";
            if (r.ok() || r.code != 404) say(t);
        }
        say("\n(end of readout — use Share log to send it for review)");
    }

    private void takeSnapshot() throws Exception {
        HikClient.Response r = Isapi.snapshot(client);
        if (!r.ok()) {
            say("snapshot: " + Isapi.replyStatus(r));
            return;
        }
        Bitmap bmp = BitmapFactory.decodeByteArray(r.bytes, 0, r.bytes.length);
        runOnUiThread(() -> {
            snapshot.setImageBitmap(bmp);
            snapshot.setVisibility(View.VISIBLE);
        });
        say("snapshot " + r.bytes.length / 1024 + " KB in " + r.millis + " ms");
    }

    // ---- raw console ----------------------------------------------------------------------

    private LinearLayout buildConsole() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(0, Ui.dp(this, 8), 0, 0);

        method = new Spinner(this);
        method.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"GET", "PUT", "POST", "DELETE"}));

        path = new EditText(this);
        path.setSingleLine(true);
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        path.setText("/ISAPI/AccessControl/Door/param/1");

        body = new EditText(this);
        body.setHint("Body (for PUT/POST). Tip: GET first, edit the XML here, then PUT it back.");
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(12);
        body.setMinLines(4);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        Button send = Ui.button(this, "Send", v -> sendRaw());
        Button load = Ui.button(this, "GET into body", v -> loadIntoBody());

        c.addView(Ui.row(this, method, load));
        c.addView(path);
        c.addView(body);
        c.addView(send);
        return c;
    }

    private void loadIntoBody() {
        String p = path.getText().toString().trim();
        run(() -> {
            HikClient.Response r = client.get(p);
            String t = r.text();
            runOnUiThread(() -> {
                body.setText(t);
                method.setSelection(1); // PUT, ready to write back
            });
            say("\nGET " + p + " → " + r.code + " loaded into body; edit and Send as PUT");
        });
    }

    private void sendRaw() {
        String m = (String) method.getSelectedItem();
        String p = path.getText().toString().trim();
        String b = body.getText().toString();
        boolean hasBody = (m.equals("PUT") || m.equals("POST")) && !b.trim().isEmpty();
        String type = b.trim().startsWith("{") ? "application/json" : "application/xml";
        Runnable go = () -> run(() -> {
            HikClient.Response r = client.request(m, p, hasBody ? b : null, type);
            say("\n── " + stamp() + "  " + m + " " + p + " ──\n" + Isapi.replyStatus(r)
                    + " in " + r.millis + " ms\n" + r.text().trim());
        });
        if (m.equals("GET")) go.run();
        else Ui.confirm(this, m + " to the device?", p + "\n\nThis changes the device's settings.", go);
    }

    // ---- output ---------------------------------------------------------------------------

    private void say(String s) {
        runOnUiThread(() -> {
            out.append(s);
            out.append("\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private String logText() {
        return "Door Control log — " + device.label() + " (" + device.host + ")\n" + out.getText();
    }

    private void copy() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Door Control log", logText()));
        Ui.toast(this, "Log copied");
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Door Control log — " + device.label())
                .putExtra(Intent.EXTRA_TEXT, logText());
        startActivity(Intent.createChooser(send, "Share log"));
    }
}
