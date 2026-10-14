package com.steve.foldcontrol;

import android.app.Activity;
import android.os.Bundle;

/** Last step of provisioning: set ourselves up, then hand back to the setup wizard. */
public class PolicyComplianceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Policy.onProvisioned(this);
        setResult(RESULT_OK);
        finish();
    }
}
