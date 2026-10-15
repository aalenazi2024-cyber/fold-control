package com.steve.triage;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible: opens the exact email/chat in its app (and copies the reply draft first, for "Reply"). */
public class OpenActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        long id = getIntent().getLongExtra("id", -1);
        String mode = getIntent().getStringExtra("mode");
        Store st = Store.get(this);
        Item it = st.get(id);
        if (it != null) {
            if ("reply".equals(mode) && it.hasReply()) {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText("Reply draft", it.reply));
                Toast.makeText(this, "Reply draft copied — paste it in " + it.app, Toast.LENGTH_LONG).show();
            }
            Alerts.cancel(this, id);
            if (!"opened".equals(it.status)) {
                st.setStatus(id, "opened");
                st.bump(it.senderKey(), true);
            }
            open(this, it);
        }
        finish();
    }

    static void open(Context c, Item it) {
        PendingIntent pi = Live.get(it.srcKey);
        if (pi != null) {
            try {
                ActivityOptions o = ActivityOptions.makeBasic();
                if (Build.VERSION.SDK_INT >= 34) {
                    o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                }
                pi.send(c, 0, null, null, null, null, o.toBundle());
                return;
            } catch (Exception ignored) {
                // fall through to opening the app itself
            }
        }
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(it.pkg);
        if (launch != null) c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
