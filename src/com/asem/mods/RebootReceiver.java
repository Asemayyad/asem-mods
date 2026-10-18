package com.asem.mods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Locale;

/**
 * Reboot to recovery for the power menu button (ASEM Mods).
 * XOS SystemUI has no android.permission.REBOOT, so it asks this app, which has root.
 * Protected in the manifest by android.permission.STATUS_BAR_SERVICE (signature|recents):
 * only SystemUI / platform apps can send it, normal apps cannot.
 */
public class RebootReceiver extends BroadcastReceiver {
    static final String ACTION = "com.asem.mods.REBOOT_RECOVERY";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            int rc;
            try {
                // clean framework shutdown first; plain reboot only if svc is not available
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "svc power reboot recovery || reboot recovery"});
                rc = p.waitFor();
            } catch (Throwable t) { rc = -1; }
            if (rc != 0) {
                boolean ar = "ar".equals(Locale.getDefault().getLanguage());
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
                        ar ? "ASEM Mods يحتاج صلاحية الروت - اسمح له في Magisk" : "ASEM Mods needs root - allow it in Magisk",
                        Toast.LENGTH_LONG).show());
            }
            pr.finish();
        }).start();
    }
}
