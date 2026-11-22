package it.iw2dpo.mumlamonitor;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * IW2DPO - Servizio in primo piano che ogni 60 secondi manda un "ping" a Mumla.
 * Mumla risponde solo se il suo processo e' vivo (receiver registrato a runtime da
 * it.iw2dpo.PingProvider dentro Mumla). Se nessuno risponde, Mumla viene riaperta
 * in primo piano.
 */
public class WatchdogService extends Service {

    static final String TAG = "MumlaMonitor";

    /** Action del ping: deve coincidere con quella in Mumla (it.iw2dpo.PingProvider). */
    static final String ACTION_PING = "it.iw2dpo.mumla.PING";

    /** Pacchetti di Mumla, in ordine di preferenza (flavor goog/foss, beta, donation). */
    static final String[] MUMLA_PACKAGES = {
            "se.lublin.mumla", "se.lublin.mumla.beta", "se.lublin.mumla.donation"
    };

    static final long INTERVAL_MS = 60_000L;

    static final String PREFS = "monitor";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_LAST_CHECK = "last_check";
    static final String KEY_RESTARTS = "restarts";
    static final String KEY_MODE = "mode";

    // --- Modalita' di controllo (22-11-2026-2) ---
    /** Riapre Mumla solo se il processo e' morto (chiusa forzatamente o crash). */
    static final int MODE_PROCESS = 0;
    /** Anche se Mumla non ha schermate aperte (tolta dalle recenti, servizio ancora vivo). */
    static final int MODE_WINDOW = 1;
    /** Anche se un'altra app (o la home) e' in primo piano: Mumla sempre a schermo. */
    static final int MODE_FOREGROUND = 2;
    static final int DEFAULT_MODE = MODE_WINDOW;

    /** In MODE_FOREGROUND: controlli consecutivi con Mumla non visibile prima di riportarla
     *  a schermo (2 = tra 1 e 2 minuti), per lasciare il tempo di usare un'altra app al volo. */
    static final int FOREGROUND_GRACE_CHECKS = 2;

    /** Chiavi della risposta al ping: devono coincidere con it.iw2dpo.PingProvider in Mumla. */
    static final String EXTRA_ACTIVITIES = "activities";
    static final String EXTRA_VISIBLE = "visible";
    /** Extra nell'intent di avvio: Mumla e' stata aperta dal watchdog. */
    static final String EXTRA_FROM_WATCHDOG = "it.iw2dpo.FROM_WATCHDOG";

    /** Inviato all'Activity per aggiornare lo stato a video. */
    static final String ACTION_STATUS = "it.iw2dpo.mumlamonitor.STATUS";

    private static final String CHANNEL_ID = "watchdog";
    private static final int NOTIF_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    /** Controlli consecutivi in cui Mumla non era in primo piano. */
    private int notVisibleCount = 0;

    private final Runnable checkTask = new Runnable() {
        @Override public void run() {
            checkMumla();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    // ---------------------------------------------------------------- avvio / stop

    static void start(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, true).apply();
        ctx.startForegroundService(new Intent(ctx, WatchdogService.class));
    }

    static void stop(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, false).apply();
        ctx.stopService(new Intent(ctx, WatchdogService.class));
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification("Controllo Mumla attivo (ogni 60 s)"));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        // Wakelock parziale: il timer continua anche a schermo spento
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MumlaMonitor:watchdog");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(checkTask);
        // primo controllo dopo 5 s (es. dopo il boot lasciamo partire il sistema)
        handler.postDelayed(checkTask, 5_000L);
        return START_STICKY; // se Android uccide il servizio, lo ricrea
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(checkTask);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- logica del controllo

    private void checkMumla() {
        final String pkg = findMumlaPackage();
        if (pkg == null) {
            updateStatus("Mumla non installata!");
            return;
        }
        Intent ping = new Intent(ACTION_PING).setPackage(pkg);
        // Broadcast ordinato: se Mumla e' viva il suo receiver mette RESULT_OK,
        // altrimenti arriva il valore iniziale RESULT_CANCELED.
        sendOrderedBroadcast(ping, null, new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (getResultCode() != Activity.RESULT_OK) {
                    notVisibleCount = 0;
                    Log.w(TAG, "Mumla non risponde: la riapro");
                    launchMumla(pkg, "Mumla era chiusa: riaperta");
                    return;
                }
                handleAlive(pkg, getResultExtras(false));
            }
        }, handler, Activity.RESULT_CANCELED, null, null);
    }

    /** Mumla e' viva: controlla, secondo la modalita', se ha schermate aperte / visibili. */
    private void handleAlive(String pkg, Bundle extras) {
        int mode = prefs(this).getInt(KEY_MODE, DEFAULT_MODE);
        if (mode == MODE_PROCESS) {
            updateStatus("Mumla attiva");
            return;
        }
        if (extras == null || !extras.containsKey(EXTRA_ACTIVITIES)) {
            // Mumla vecchia, senza le informazioni sulle schermate: solo controllo processo
            updateStatus("Mumla attiva (versione senza stato schermate)");
            return;
        }
        int activities = extras.getInt(EXTRA_ACTIVITIES, 0);
        int visible = extras.getInt(EXTRA_VISIBLE, 0);

        if (activities == 0) {
            notVisibleCount = 0;
            Log.w(TAG, "Mumla viva ma senza schermate: la riporto a schermo");
            launchMumla(pkg, "Mumla tolta dalle recenti: riportata a schermo");
            return;
        }
        if (visible > 0) {
            notVisibleCount = 0;
            updateStatus("Mumla attiva e in primo piano");
            return;
        }
        // Schermata aperta ma non visibile: un'altra app o la home e' in primo piano
        if (mode != MODE_FOREGROUND) {
            updateStatus("Mumla attiva (in background)");
            return;
        }
        if (!isScreenUsable()) {
            // schermo spento o bloccato: le schermate risultano sempre "non visibili"
            notVisibleCount = 0;
            updateStatus("Mumla attiva (schermo spento/bloccato)");
            return;
        }
        notVisibleCount++;
        if (notVisibleCount >= FOREGROUND_GRACE_CHECKS) {
            notVisibleCount = 0;
            launchMumla(pkg, "Un'altra app era in primo piano: Mumla riportata a schermo");
        } else {
            updateStatus("Mumla in background (" + notVisibleCount + "/"
                    + FOREGROUND_GRACE_CHECKS + "), al prossimo controllo torna a schermo");
        }
    }

    /**
     * 22-11-2026-3: a schermo spento Android crea la schermata di Mumla ma non la porta
     * in primo piano finche' la radio non si sveglia (e l'autoconnect di Mumla aspetta).
     * Accendiamo lo schermo per 10 s: la schermata si apre subito e Mumla si connette,
     * poi lo schermo si rispegne da solo col timeout normale.
     */
    @SuppressWarnings("deprecation")
    private void wakeScreenIfNeeded() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isInteractive()) return;
        Log.i(TAG, "Schermo spento: lo accendo per aprire Mumla");
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                "MumlaMonitor:wakeScreen");
        wl.acquire(10_000L);
    }

    /** true se lo schermo e' acceso e sbloccato. */
    private boolean isScreenUsable() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return pm.isInteractive() && (km == null || !km.isKeyguardLocked());
    }

    private String findMumlaPackage() {
        PackageManager pm = getPackageManager();
        for (String p : MUMLA_PACKAGES) {
            if (pm.getLaunchIntentForPackage(p) != null) return p;
        }
        return null;
    }

    private void launchMumla(String pkg, String okMessage) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        // Se Mumla ha gia' una schermata in background la riporta davanti, altrimenti la crea
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        if (!Settings.canDrawOverlays(this)) {
            // Senza il permesso Android 10+ blocca l'avvio dal background:
            // lo segnaliamo in notifica (toccandola si apre Mumla).
            updateStatus("Mumla CHIUSA - manca permesso 'Mostra sopra altre app'");
            return;
        }
        // 22-11-2026-3: Mumla sa che la apre il watchdog -> accende lo schermo e
        // si mostra anche sopra la schermata di blocco (vedi it.iw2dpo.PingProvider)
        launch.putExtra(EXTRA_FROM_WATCHDOG, true);
        wakeScreenIfNeeded();
        try {
            startActivity(launch);
            SharedPreferences p = prefs(this);
            p.edit().putInt(KEY_RESTARTS, p.getInt(KEY_RESTARTS, 0) + 1).apply();
            updateStatus(okMessage);
        } catch (Exception e) {
            Log.e(TAG, "Avvio Mumla fallito", e);
            updateStatus("Errore riapertura Mumla: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- notifica / stato

    private void updateStatus(String text) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date());
        String line = time + " - " + text;
        Log.i(TAG, line);
        prefs(this).edit().putString(KEY_LAST_CHECK, line).apply();
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(line));
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Watchdog Mumla",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("MumlaMonitor")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
