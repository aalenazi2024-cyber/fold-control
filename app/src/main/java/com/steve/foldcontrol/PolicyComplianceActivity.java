package com.steve.foldcontrol;

import android.app.Activity;
import android.os.Bundle;

/** Last step of provisioning: set ourselves up, then hand back to the setup wizard. */
public class PolicyComplianceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Policy.onProvisioned(this);
        } catch (Throwable t) {
            // Never let our own setup break the phone's setup; the app retries later.
            android.util.Log.e("FoldControl", "onProvisioned failed", t);
        }
        setResult(RESULT_OK);
        finish();
    }
}
