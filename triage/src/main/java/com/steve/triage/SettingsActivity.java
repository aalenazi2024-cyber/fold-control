package com.steve.triage;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Toast;

import java.util.concurrent.Executors;

/** Who matters, which words matter, which apps to filter, and the optional local model. */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs p = new Prefs(this);
        int pad = Ui.dp(this, 16);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(pad, pad, pad, pad);
        f.setFitsSystemWindows(true);

        f.addView(Ui.text(this, "Settings", 24));
        Switch enabled = Ui.toggle(this, f, "Triage is on", p.on(Prefs.ENABLED));
        Switch digest = Ui.toggle(this, f, "Hold FYI quietly in the digest (instead of letting it ring)", p.on(Prefs.DIGEST));
        Switch markRead = Ui.toggle(this, f, "Press \"Mark as read\" on noise when the app offers it", p.on(Prefs.MARK_READ));
        Switch all = Ui.toggle(this, f, "Filter ALL apps, not just the list below", p.on(Prefs.GATE_ALL));

        f.addView(Ui.header(this, "Who and what matters"));
        EditText name = Ui.field(this, f, "Your name (for Teams @mentions)", p.get(Prefs.NAME), false);
        EditText vip = Ui.field(this, f, "VIP people — always alert (names as they appear, comma separated)", p.get(Prefs.VIP), true);
        EditText urgent = Ui.field(this, f, "Urgent words — always alert", p.get(Prefs.URGENT), true);
        EditText noise = Ui.field(this, f, "Noise words — hold back", p.get(Prefs.NOISE), true);
        EditText apps = Ui.field(this, f, "Apps to filter (package names)", p.get(Prefs.APPS), true);

        f.addView(Ui.header(this, "Local model (optional)"));
        f.addView(Ui.text(this, "Better judgement, real summaries and reply drafts. Runs on this phone "
                + "(llama.cpp server in Termux: http://127.0.0.1:8080/v1) or on your own machine over Tailscale. "
                + "Leave blank to use rules only. Nothing goes to any cloud.", 12));
        EditText url = Ui.field(this, f, "Model URL", p.get(Prefs.LLM_URL), false);
        EditText model = Ui.field(this, f, "Model name", p.get(Prefs.LLM_MODEL), false);
        EditText profile = Ui.field(this, f, "About you (the model reads this)", p.get(Prefs.PROFILE), true);

        f.addView(Ui.row(this,
                Ui.button(this, "Save", v -> {
                    p.set(Prefs.ENABLED, enabled.isChecked());
                    p.set(Prefs.DIGEST, digest.isChecked());
                    p.set(Prefs.MARK_READ, markRead.isChecked());
                    p.set(Prefs.GATE_ALL, all.isChecked());
                    p.put(Prefs.NAME, name.getText().toString().trim());
                    p.put(Prefs.VIP, vip.getText().toString().trim());
                    p.put(Prefs.URGENT, urgent.getText().toString().trim());
                    p.put(Prefs.NOISE, noise.getText().toString().trim());
                    p.put(Prefs.APPS, apps.getText().toString().trim());
                    p.put(Prefs.LLM_URL, url.getText().toString().trim());
                    p.put(Prefs.LLM_MODEL, model.getText().toString().trim());
                    p.put(Prefs.PROFILE, profile.getText().toString().trim());
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                    finish();
                }),
                Ui.button(this, "Test model", v -> {
                    p.put(Prefs.LLM_URL, url.getText().toString().trim());
                    p.put(Prefs.LLM_MODEL, model.getText().toString().trim());
                    Toast.makeText(this, "Asking the model…", Toast.LENGTH_SHORT).show();
                    Executors.newSingleThreadExecutor().execute(() -> {
                        String msg;
                        try {
                            msg = "✓ " + Judge.cut(Llm.test(p), 120);
                        } catch (Exception e) {
                            msg = "✗ " + e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                        String m = msg;
                        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
                    });
                })));

        ScrollView s = new ScrollView(this);
        s.addView(f);
        setContentView(s);
    }
}
