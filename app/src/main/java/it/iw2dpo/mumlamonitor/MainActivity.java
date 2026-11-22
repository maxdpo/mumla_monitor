package it.iw2dpo.mumlamonitor;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * IW2DPO - Schermata di configurazione: concede i permessi necessari e
 * avvia/ferma il controllo di Mumla.
 */
public class MainActivity extends Activity {

    private TextView status;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);

        Button overlay = findViewById(R.id.btn_overlay);
        overlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));

        // Modalita' di controllo (22-11-2026-2)
        RadioGroup modes = findViewById(R.id.mode_group);
        int mode = WatchdogService.prefs(this).getInt(WatchdogService.KEY_MODE,
                WatchdogService.DEFAULT_MODE);
        modes.check(mode == WatchdogService.MODE_PROCESS ? R.id.mode_process
                : mode == WatchdogService.MODE_FOREGROUND ? R.id.mode_foreground
                : R.id.mode_window);
        modes.setOnCheckedChangeListener((group, id) -> {
            int m = id == R.id.mode_process ? WatchdogService.MODE_PROCESS
                    : id == R.id.mode_foreground ? WatchdogService.MODE_FOREGROUND
                    : WatchdogService.MODE_WINDOW;
            WatchdogService.prefs(this).edit().putInt(WatchdogService.KEY_MODE, m).apply();
            refresh();
        });

        Button battery = findViewById(R.id.btn_battery);
        battery.setOnClickListener(v -> requestIgnoreBattery());

        findViewById(R.id.btn_start).setOnClickListener(v -> {
            WatchdogService.start(this);
            refresh();
        });
        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            WatchdogService.stop(this);
            refresh();
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // Prima apertura: se tutto e' gia' a posto, parte subito
        if (WatchdogService.prefs(this).getBoolean(WatchdogService.KEY_ENABLED, true)) {
            WatchdogService.start(this);
        }
    }

    private void requestIgnoreBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } else {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(WatchdogService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, f);
        }
        refresh();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(statusReceiver);
        super.onPause();
    }

    private void refresh() {
        SharedPreferences p = WatchdogService.prefs(this);
        boolean enabled = p.getBoolean(WatchdogService.KEY_ENABLED, true);
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean batteryOk = ((PowerManager) getSystemService(POWER_SERVICE))
                .isIgnoringBatteryOptimizations(getPackageName());

        String s = "Controllo: " + (enabled ? "ATTIVO (ogni 60 s)" : "FERMO") + "\n\n"
                + (overlayOk ? "✔" : "✘") + " Mostra sopra altre app\n"
                + (batteryOk ? "✔" : "✘") + " Escluso da ottimizzazione batteria\n\n"
                + "Ultimo controllo:\n" + p.getString(WatchdogService.KEY_LAST_CHECK, "-") + "\n\n"
                + "Riaperture di Mumla: " + p.getInt(WatchdogService.KEY_RESTARTS, 0);
        status.setText(s);
    }
}
