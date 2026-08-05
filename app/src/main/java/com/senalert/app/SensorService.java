package com.senalert.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sen-Alert'in gerçek çalışma motoru.
 *
 * KRİTİK DÜZELTME (bu turda): PAUSED_GRAY durumuna geçildiğinde artık
 * MainActivity'ye yayın (broadcast) göndermeyi KESMİYORUZ. Eskiden
 * "if (currentState == PAUSED_GRAY) return;" en tepede olduğu için,
 * duraklama anında ekran son aldığı yayında donup kalıyordu - bildirim
 * çubuğu doğru güncelleniyordu (çünkü o direkt çağrılıyor) ama ekran
 * hiç haber almıyordu. Artık broadcastState() PAUSED_GRAY dahil her
 * zaman çağrılıyor, sadece dedektör mantığı (eşik kontrolü vb.) atlanıyor.
 */
public class SensorService extends Service implements SensorEventListener {

    private static final int NOTIF_ID = 1;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Vibrator vibrator;
    private CameraManager cameraManager;
    private String cameraId;
    private boolean torchOn = false;
    private MediaPlayer alarmPlayer;
    private SharedPreferences prefs;
    private NotificationManager notifManager;
    private PowerManager.WakeLock wakeLock;

    private enum State { CALIBRATING, GREEN, YELLOW, RED, PAUSED_GRAY }
    private State currentState = State.CALIBRATING;
    private State pendingState = null;
    private long pendingSinceMs = 0;

    private float lastX, lastY, lastZ;
    private boolean firstRead = true;
    private long calibrateStartMs = 0;

    private static final long CALIBRATION_MS = 2000;
    private static final long YELLOW_SUSTAIN_MS = 800;
    private static final long RED_SUSTAIN_MS = 500;
    private static final long RELEASE_SUSTAIN_MS = 500;
    private static final long REPEAT_INTERVAL_MS = 20000;

    private static final float BASE_YELLOW = 0.15f;
    private static final float BASE_RED    = 1.05f;
    private float thYellow = BASE_YELLOW;
    private float thRed    = BASE_RED;

    private static final float HAND_Z_DELTA = 1.0f;
    private static final long HAND_SUSTAIN_MS = 200;
    private long handPendingSinceMs = 0;

    private static final int SMOOTH_WINDOW = 6;
    private final float[] magBuffer = new float[SMOOTH_WINDOW];
    private int magIndex = 0;
    private static final float INSTANT_RED_OVERRIDE = 2.0f;

    private static final long GRAPH_PUSH_INTERVAL_MS = 60;
    private long lastGraphPushMs = 0;

    private static final long HEARTBEAT_INTERVAL_MS = 60000;
    private long lastHeartbeatMs = 0;

    private final Handler handler = new Handler();
    private boolean alarmActive = false;
    private boolean alarmMuted = false;

    private long lastNotifUpdateMs = 0;
    private static final long NOTIF_UPDATE_INTERVAL_MS = 1000;
    private int pendingScoreForNotif = 0;

    private final Runnable blinkRunnable = new Runnable() {
        @Override public void run() {
            if (!alarmActive || alarmMuted) return;
            if (prefs.getBoolean("alert_flash", true)) toggleTorch(!torchOn);
            handler.postDelayed(this, 400);
        }
    };

    private final Runnable stopAlarmRunnable = new Runnable() {
        @Override public void run() { stopAlarm(); }
    };

    private final Runnable repeatCheckRunnable = new Runnable() {
        @Override public void run() {
            if (currentState == State.YELLOW || currentState == State.RED) {
                triggerAlarm();
                handler.postDelayed(this, REPEAT_INTERVAL_MS);
            }
        }
    };

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_MUTE.equals(intent.getAction())) {
                muteAlarm();
            } else if (Constants.ACTION_RECALIBRATE.equals(intent.getAction())) {
                firstRead = true;
                magIndex = 0;
                goCalibrating();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        cameraId = getFirstCameraWithFlash();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        acquireWakeLock();

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("🟤", "KALİBRASYON", 0));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_MUTE);
        filter.addAction(Constants.ACTION_RECALIBRATE);
        registerReceiver(controlReceiver, filter);

        updateSensitivity();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);

        prefs.edit()
            .putBoolean("service_running", true)
            .putLong("last_heartbeat", System.currentTimeMillis())
            .apply();

        WatchdogReceiver.scheduleNext(this);

        goCalibrating();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
        stopAlarm();
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
        releaseWakeLock();
        prefs.edit().putBoolean("service_running", false).apply();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SenAlert::SensorWakeLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    private void updateSensitivity() {
        int sensitivity = prefs.getInt("sensitivity", 5);
        float factor = 1.0f - (sensitivity - 5) * 0.08f;
        factor = Math.max(0.35f, Math.min(1.8f, factor));
        thYellow = BASE_YELLOW * factor;
        thRed    = BASE_RED    * factor;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        updateSensitivity();

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        if (firstRead) {
            lastX = x; lastY = y; lastZ = z;
            firstRead = false;
            return;
        }

        float dx = x - lastX;
        float dy = y - lastY;
        float dz = Math.abs(z - lastZ);
        float dMag = (float) Math.sqrt(dx * dx + dy * dy);

        magBuffer[magIndex % SMOOTH_WINDOW] = dMag;
        magIndex++;
        int count = Math.min(magIndex, SMOOTH_WINDOW);
        float sum = 0f;
        for (int i = 0; i < count; i++) sum += magBuffer[i];
        float dXY = sum / count;

        lastX = x; lastY = y; lastZ = z;

        long now = SystemClock.elapsedRealtime();

        if (now - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatMs = now;
            prefs.edit().putLong("last_heartbeat", System.currentTimeMillis()).apply();
        }

        float frac = computeFrac(dXY);
        int score = Math.round(frac * 100);
        pendingScoreForNotif = score;

        // ---- ARTIK HER ZAMAN yayınlanır, PAUSED_GRAY dahil - ekran asla donmaz ----
        broadcastState(score, x, y, z, dXY, dz);

        if (currentState == State.PAUSED_GRAY) return; // sadece dedektör mantığı atlanır

        if (currentState == State.CALIBRATING) {
            if (now - calibrateStartMs >= CALIBRATION_MS) commitState(State.GREEN);
            maybeUpdateNotification();
            return;
        }

        if (dz >= HAND_Z_DELTA) {
            if (handPendingSinceMs == 0) handPendingSinceMs = now;
            if (now - handPendingSinceMs >= HAND_SUSTAIN_MS) {
                goGray();
                maybeUpdateNotification();
                return;
            }
        } else {
            handPendingSinceMs = 0;
        }

        if (dMag >= INSTANT_RED_OVERRIDE) {
            commitState(State.RED);
            maybeUpdateNotification();
            return;
        }

        State candidate = dXY >= thRed ? State.RED : (dXY >= thYellow ? State.YELLOW : State.GREEN);

        if (candidate == currentState) {
            pendingState = null;
            maybeUpdateNotification();
            return;
        }
        if (pendingState != candidate) {
            pendingState = candidate;
            pendingSinceMs = now;
            maybeUpdateNotification();
            return;
        }
        long requiredHold;
        if (candidate == State.RED) requiredHold = RED_SUSTAIN_MS;
        else if (candidate == State.YELLOW && candidate.ordinal() > currentState.ordinal()) requiredHold = YELLOW_SUSTAIN_MS;
        else requiredHold = RELEASE_SUSTAIN_MS;

        if (now - pendingSinceMs >= requiredHold) {
            commitState(candidate);
        }
        maybeUpdateNotification();
    }

    private void commitState(State s) {
        pendingState = null;
        switch (s) {
            case GREEN:  goGreen(); break;
            case YELLOW: goYellow(); break;
            case RED:    goRed(); break;
            default: break;
        }
    }

    private float computeFrac(float dXY) {
        if (dXY <= thYellow) {
            return (dXY / thYellow) * 0.30f;
        } else if (dXY <= thRed) {
            return 0.30f + (dXY - thYellow) / (thRed - thYellow) * 0.40f;
        } else {
            float over = Math.min((dXY - thRed) / thRed, 1f);
            return 0.70f + over * 0.30f;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ================= STATE =================

    private void goCalibrating() {
        currentState = State.CALIBRATING;
        calibrateStartMs = SystemClock.elapsedRealtime();
        handPendingSinceMs = 0;
        stopAlarm();
        handler.removeCallbacks(repeatCheckRunnable);
        updateNotification("🟤", "KALİBRASYON YAPILIYOR...", 0);
    }

    private void goGreen() {
        if (currentState == State.GREEN) return;
        currentState = State.GREEN;
        stopAlarm();
        handler.removeCallbacks(repeatCheckRunnable);
        updateNotification("🟢", "NORMAL", 0);
    }

    private void goYellow() {
        boolean already = (currentState == State.YELLOW);
        currentState = State.YELLOW;
        updateNotification("🟡", "SARSINTI ALGILANDI", 0);
        if (!already) {
            triggerAlarm();
            handler.removeCallbacks(repeatCheckRunnable);
            handler.postDelayed(repeatCheckRunnable, REPEAT_INTERVAL_MS);
        }
    }

    private void goRed() {
        boolean already = (currentState == State.RED);
        currentState = State.RED;
        updateNotification("🔴", "GÜÇLÜ SARSINTI", 0);
        if (!already) {
            saveLastAlertEvent();
            triggerAlarm();
            handler.removeCallbacks(repeatCheckRunnable);
            handler.postDelayed(repeatCheckRunnable, REPEAT_INTERVAL_MS);
        }
    }

    private void goGray() {
        currentState = State.PAUSED_GRAY;
        stopAlarm();
        handler.removeCallbacks(repeatCheckRunnable);
        updateNotification("⚪", "İZLEME BEKLEMEDE", 0);
    }

    private void saveLastAlertEvent() {
        String time = new SimpleDateFormat("HH:mm · dd.MM.yyyy", Locale.US).format(new Date());
        prefs.edit().putString("last_alert_time", time).apply();
    }

    // ================= UYARI =================

    private void triggerAlarm() {
        if (currentState != State.RED) saveLastAlertEvent();

        alarmActive = true;
        alarmMuted = false;

        int durationSec = prefs.getInt("alert_duration_sec", 5);
        long durationMs = durationSec * 1000L;

        if (prefs.getBoolean("alert_vibration", true) && vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(new long[]{0, 400, 200, 400, 200, 400}, 0);
        }
        if (prefs.getBoolean("alert_sound", true)) startLoopingAlarmSound();
        if (prefs.getBoolean("alert_flash", true)) handler.post(blinkRunnable);

        handler.removeCallbacks(stopAlarmRunnable);
        handler.postDelayed(stopAlarmRunnable, durationMs);
    }

    private void muteAlarm() {
        alarmMuted = true;
        stopAlarm();
    }

    private void stopAlarm() {
        alarmActive = false;
        alarmMuted = false;
        handler.removeCallbacks(blinkRunnable);
        handler.removeCallbacks(stopAlarmRunnable);
        if (vibrator != null) vibrator.cancel();
        stopLoopingAlarmSound();
        if (torchOn) toggleTorch(false);
    }

    private void startLoopingAlarmSound() {
        try {
            stopLoopingAlarmSound();
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            alarmPlayer = new MediaPlayer();
            alarmPlayer.setDataSource(this, uri);
            alarmPlayer.setLooping(true);
            alarmPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    if (alarmActive && !alarmMuted) mp.start();
                }
            });
            alarmPlayer.prepareAsync();
        } catch (Exception ignored) {}
    }

    private void stopLoopingAlarmSound() {
        if (alarmPlayer != null) {
            try { alarmPlayer.stop(); alarmPlayer.release(); } catch (Exception ignored) {}
            alarmPlayer = null;
        }
    }

    private void toggleTorch(boolean on) {
        if (cameraId == null || cameraManager == null) return;
        try {
            cameraManager.setTorchMode(cameraId, on);
            torchOn = on;
        } catch (CameraAccessException ignored) {}
    }

    private String getFirstCameraWithFlash() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean has = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (has != null && has) return id;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ================= BİLDİRİM (tek satır) =================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                Constants.CHANNEL_ID, "Sen-Alert İzleme", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Arka planda sarsıntı izleme durumu");
            notifManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String dot, String text, int score) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        String content = dot + " " + text + (score > 0 ? " · " + score + "/100" : "");

        return new Notification.Builder(this)
            .setChannelId(Constants.CHANNEL_ID)
            .setContentTitle("Sen-Alert")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private void updateNotification(String dot, String text, int score) {
        notifManager.notify(NOTIF_ID, buildNotification(dot, text, score));
    }

    private void maybeUpdateNotification() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifUpdateMs < NOTIF_UPDATE_INTERVAL_MS) return;
        lastNotifUpdateMs = now;
        if (currentState == State.CALIBRATING || currentState == State.PAUSED_GRAY) return;
        String dot = currentState == State.RED ? "🔴" : currentState == State.YELLOW ? "🟡" : "🟢";
        updateNotification(dot, stateLabel(), pendingScoreForNotif);
    }

    private String stateLabel() {
        switch (currentState) {
            case GREEN:  return "NORMAL";
            case YELLOW: return "SARSINTI ALGILANDI";
            case RED:    return "GÜÇLÜ SARSINTI";
            default:     return "";
        }
    }

    private void broadcastState(int score, float x, float y, float z, float dXY, float dZ) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastGraphPushMs < GRAPH_PUSH_INTERVAL_MS) return;
        lastGraphPushMs = now;

        Intent i = new Intent(Constants.ACTION_STATE);
        i.setPackage(getPackageName());
        i.putExtra(Constants.EXTRA_STATE, currentState.name());
        i.putExtra(Constants.EXTRA_SCORE, score);
        i.putExtra(Constants.EXTRA_X, x);
        i.putExtra(Constants.EXTRA_Y, y);
        i.putExtra(Constants.EXTRA_Z, z);
        i.putExtra(Constants.EXTRA_DXY, dXY);
        i.putExtra(Constants.EXTRA_DZ, dZ);
        sendBroadcast(i);
    }
}
