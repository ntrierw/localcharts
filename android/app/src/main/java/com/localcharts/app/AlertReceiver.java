package com.localcharts.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wakes up for each scheduled alert check, and re-arms the schedule after a reboot or an app update. */
public class AlertReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Alerts.ACTION_CHECK.equals(a) && !Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(a)) return;
        Context c = context.getApplicationContext();
        if (!Alerts.enabled(c)) return;
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                Alerts.runScheduledCheck(c);
            } finally {
                pr.finish();
            }
        }, "alerts-check").start();
    }
}
