package com.senalert.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

/**
 * Sig-Fi Compass'ta kullandığımız yöntemle aynı: MIUI/Xiaomi gibi agresif
 * pil yönetimi olan cihazlarda foreground service yine de öldürülebiliyor.
 * Bu alıcı periyodik olarak (10 dakikada bir) kontrol eder: kullanıcı
 * izlemeyi BAŞLAT ile açık bıraktıysa ama servis gerçekten ölmüşse
 * (heartbeat bayatlamışsa), servisi yeniden başlatır.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final String ACTION_WATCHDOG = "com.senalert.app.WATCHDOG_CHECK";
    private static final long CHECK_INTERVAL_MS = 10 * 60 * 1000;   // 10 dakikada bir kontrol
    private static final long HEARTBEAT_STALE_MS = 3 * 60 * 1000;   // 3 dakikadır sinyal yoksa "ölü" say

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
        boolean shouldBeRunning = prefs.getBoolean("service_running", false);

        if (shouldBeRunning) {
            long lastHeartbeat = prefs.getLong("last_heartbeat", 0);
            long staleFor = System.currentTimeMillis() - lastHeartbeat;

            if (staleFor > HEARTBEAT_STALE_MS) {
                Intent serviceIntent = new Intent(context, SensorService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
            scheduleNext(context);
        }
        // shouldBeRunning false ise (kullanıcı DURDUR'a bastıysa) yeni alarm kurulmaz, döngü kendiliğinden biter.
    }

    /** Servis onCreate()'te bir sonraki kontrolü kurar. */
    public static void scheduleNext(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(context);
        long triggerAt = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS;

        // Tam saniyesinde çalması şart değil - izin gerektirmeyen "inexact" alarm yeterli ve daha güvenli.
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
    }

    /** Kullanıcı DURDUR'a bastığında çağrılır - watchdog döngüsünü tamamen durdurur. */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(buildPendingIntent(context));
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, WatchdogReceiver.class);
        intent.setAction(ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }
}
