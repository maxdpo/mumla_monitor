package it.iw2dpo.mumlamonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** IW2DPO - All'accensione (o dopo un aggiornamento) riavvia il watchdog se era attivo. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return;
        }
        if (WatchdogService.prefs(ctx).getBoolean(WatchdogService.KEY_ENABLED, true)) {
            Log.i(WatchdogService.TAG, "Avvio automatico del watchdog: " + a);
            WatchdogService.start(ctx);
        }
    }
}
