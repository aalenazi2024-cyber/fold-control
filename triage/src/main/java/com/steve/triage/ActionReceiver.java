package com.steve.triage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Done, swipe-to-ignore, snooze wake-up, and clearing the digest. */
public class ActionReceiver extends BroadcastReceiver {
    static final String DONE = "com.steve.triage.DONE";
    static final String IGNORE = "com.steve.triage.IGNORE";
    static final String WAKE = "com.steve.triage.WAKE";
    static final String CLEAR = "com.steve.triage.CLEAR";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        long id = i.getLongExtra("id", -1);
        Store st = Store.get(c);
        Item it = id > 0 ? st.get(id) : null;
        switch (a) {
            case DONE:
                Alerts.cancel(c, id);
                if (it != null) {
                    st.setStatus(id, "done");
                    st.bump(it.senderKey(), true);
                }
                break;
            case IGNORE: // swiped away
                if (it != null && "shown".equals(it.status)) {
                    st.setStatus(id, "ignored");
                    st.bump(it.senderKey(), false);
                }
                break;
            case WAKE: // snooze is over
                if (it != null && "snoozed".equals(it.status)) {
                    st.setStatus(id, "shown");
                    it.status = "shown";
                    Alerts.showImportant(c, it, true);
                }
                break;
            case CLEAR:
                st.clearDigest();
                break;
            default:
                break;
        }
    }
}
