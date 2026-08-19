package com.senalert.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
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
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Sen-Alert'in gerçek çalışma motoru.
 *
 * BU TURDA - TEK DEĞİŞKEN: Yumuşatma penceresi artık sabit ÖRNEK SAYISI
 * (6 örnek) değil, sabit GERÇEK SÜRE (150ms). Xiaomi (~17Hz) ve Huawei
 * (~50.5Hz) yan yana testlerinde 6 örnek farklı gerçek zaman aralıklarına
 * denk geliyordu, aynı fiziksel sarsıntıda iki cihazın filtresi farklı
 * davranıyordu. Diğer hiçbir parametreye dokunulmadı.
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
    private static final long YELLOW_SUSTAIN_MS = 400;
    private static final long RED_SUSTAIN_MS = 500;
    private static final long RELEASE_SUSTAIN_MS = 500;

    private static final float BASE_YELLOW = 0.15f;
    private static final float BASE_RED    = 0.85f;
    private float thYellow = BASE_YELLOW;
    private float thRed    = BASE_RED;

    private static final float HAND_Z_DELTA = 1.0f;
    private static final long HAND_SUSTAIN_MS = 200;
    private long handPendingSinceMs = 0;

    private static final float INSTANT_RED_OVERRIDE = 2.0f;

    private static final long SMOOTH_WINDOW_MS = 150;
    private static class Sample {
        final long tsMs; final float magXY; final float magXYZ;
        Sample(long tsMs, float magXY, float magXYZ) {
            this.tsMs = tsMs; this.magXY = magXY; this.magXYZ = magXYZ;
        }
    }
    private final ArrayDeque<Sample> smoothWindow = new ArrayDeque<>();

    private static final float WEIGHT_Z = 0.3f;

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

    private long lastEventTimestampNs = 0;
    private double avgSamplingHz = 0;

    private static final float ONSET_THRESHOLD = 0.05f;
    private static final long SHADOW_PUSH_INTERVAL_MS = 100;
    private static final int CSV_MAX_LINES = 3000;
    private long lastShadowPushMs = 0;

    private long episodeStartMs = 0;
    private boolean xyCrossedYellow, xyCrossedRed, xyzCrossedYellow, xyzCrossedRed;
    private long xyYellowMs = -1, xyRedMs = -1, xyzYellowMs = -1, xyzRedMs = -1;

    private final List<String> csvBuffer = new ArrayList<>();

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
                handler.postDelayed(this, getRepeatIntervalMs());
            }
        }
    };

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Constants.ACTION_MUTE.equals(action)) {
                muteAlarm();
            } else if (Constants.ACTION_RECALIBRATE.equals(action)) {
                firstRead = true;
                smoothWindow.clear();
                resetShadowTestData();
                goCalibrating();
            } else if (Constants.ACTION_SAVE_CSV.equals(action)) {
                saveTestCsv();
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

        createNotificationChannel();

        if (accelerometer == null) {
            startForeground(NOTIF_ID, buildNotification("Hareket sensörü bulunamadı", 0));
            prefs.edit().putBoolean("service_running", false).apply();
            stopSelf();
            return;
        }

        acquireWakeLock();
        startForeground(NOTIF_ID, buildNotification("KALİBRASYON", 0));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_MUTE);
        filter.addAction(Constants.ACTION_RECALIBRATE);
        filter.addAction(Constants.ACTION_SAVE_CSV);
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
        if (sensorManager != null && accelerometer != null) sensorManager.unregisterListener(this);
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

    private long getRepeatIntervalMs() {
        int seconds = prefs.getInt("alert_repeat_sec", 10);
        return seconds * 1000L;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        updateSensitivity();
        updateSamplingHz(event.timestamp);

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

        float dMagXY = (float) Math.sqrt(dx * dx + dy * dy);
        float dMagXYZ = (float) Math.sqrt(dx * dx + dy * dy + (WEIGHT_Z * dz) * (WEIGHT_Z * dz));

        lastX = x; lastY = y; lastZ = z;

        long now = SystemClock.elapsedRealtime();

        smoothWindow.addLast(new Sample(now, dMagXY, dMagXYZ));
        while (!smoothWindow.isEmpty() && now - smoothWindow.peekFirst().tsMs > SMOOTH_WINDOW_MS) {
            smoothWindow.pollFirst();
        }
        float sumXY = 0f, sumXYZ = 0f;
        for (Sample s : smoothWindow) { sumXY += s.magXY; sumXYZ += s.magXYZ; }
        int count = smoothWindow.size();
        float dXY_ref = sumXY / count;
        float dScore  = sumXYZ / count;

        if (now - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatMs = now;
            prefs.edit().putLong("last_heartbeat", System.currentTimeMillis()).apply();
        }

        float frac = computeFrac(dScore);
        int score = Math.round(frac * 100);
        pendingScoreForNotif = score;

        broadcastState(score, x, y, z, dScore, dz);

        if (prefs.getBoolean("test_mode_enabled", false)) {
            runShadowEngine(dXY_ref, dScore, dx, dy, dz, now);
        }

        if (dz >= HAND_Z_DELTA) {
            if (handPendingSinceMs == 0) handPendingSinceMs = now;
            if (now - handPendingSinceMs >= HAND_SUSTAIN_MS) {
                goGray();
            }
            maybeUpdateNotification();
            return;
        } else {
            handPendingSinceMs = 0;
        }

        if (alarmActive && prefs.getBoolean("alert_vibration", true)) {
            return;
        }

        if (currentState == State.PAUSED_GRAY) return;

        if (currentState == State.CALIBRATING) {
            if (now - calibrateStartMs >= CALIBRATION_MS) commitState(State.GREEN);
            maybeUpdateNotification();
            return;
        }

        if (dMagXYZ >= INSTANT_RED_OVERRIDE) {
            commitState(State.RED);
            maybeUpdateNotification();
            return;
        }

        State candidate = dScore >= thRed ? State.RED : (dScore >= thYellow ? State.YELLOW : State.GREEN);

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

    private void updateSamplingHz(long eventTimestampNs) {
        if (lastEventTimestampNs != 0) {
            long intervalNs = eventTimestampNs - lastEventTimestampNs;
            if (intervalNs > 0) {
                double hz = 1_000_000_000.0 / intervalNs;
                avgSamplingHz = (avgSamplingHz == 0) ? hz : (avgSamplingHz * 0.9 + hz * 0.1);
            }
        }
        lastEventTimestampNs = eventTimestampNs;
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

    private float computeFrac(float score) {
        if (score <= thYellow) {
            return (score / thYellow) * 0.30f;
        } else if (score <= thRed) {
            return 0.30f + (score - thYellow) / (thRed - thYellow) * 0.40f;
        } else {
            float over = Math.min((score - thRed) / thRed, 1f);
            return 0.70f + over * 0.30f;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void goCalibrating() {
        currentState = State.CALIBRATING;
        calibrateStartMs = SystemClock.elapsedRealtime();
        handPendingSinceMs = 0;
        stopAlarm();
        handler.removeCallbacks(repeatCheckRunnable);
        updateNotification("KALİBRASYON YAPILIYOR...", 0);
    }

    private void goGreen() {
        if (currentState == State.GREEN) return;
        currentState = State.GREEN;
        stopAlarm();
        handler.removeCallbacks(repeatCheckRunnable);
        updateNotification("NORMAL", 0);
    }

    private void goYellow() {
        boolean already = (currentState == State.YELLOW);
        currentState = State.YELLOW;
        updateNotification("SARSINTI ALGILANDI", 0);
        if (!already) {
            triggerAlarm();
            handler.removeCallbacks(repeatCheckRunnable);
            handler.postDelayed(repeatCheckRunnable, getRepeatIntervalMs());
        }
    }

    private void goRed() {
        boolean already = (currentState == State.RED);
        currentState = State.RED;
        updateNotification("GÜÇLÜ SARSINTI", 0);
        if (!already) {
            saveLastAlertEvent();
            triggerAlarm();
            handler.removeCallbacks(repeatCheckRunnable);
            handler.postDelayed(repeatCheckRunnable, getRepeatIntervalMs());
        }
    }

    private void goGray() {
        currentState = State.PAUSED_GRAY;
        stopAlarm();
        handler.removeCallbacks(repeatCheckRunnable);
        updateNotification("İZLEME BEKLEMEDE", 0);
    }

    private void saveLastAlertEvent() {
        String time = new SimpleDateFormat("HH:mm · dd.MM.yyyy", Locale.US).format(new Date());
        prefs.edit().putString("last_alert_time", time).apply();
    }

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

    private void resetShadowTestData() {
        csvBuffer.clear();
        episodeStartMs = 0;
        xyCrossedYellow = false; xyCrossedRed = false;
        xyzCrossedYellow = false; xyzCrossedRed = false;
        xyYellowMs = -1; xyRedMs = -1; xyzYellowMs = -1; xyzRedMs = -1;
    }

    private void runShadowEngine(float dXY_ref, float dScore, float dx, float dy, float dz, long now) {
        updateShadowLatency(dXY_ref, dScore, now);

        csvBuffer.add(String.format(Locale.US, "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s",
            System.currentTimeMillis(), dx, dy, dz, dXY_ref, dScore, simpleLabel(dXY_ref), simpleLabel(dScore)));
        if (csvBuffer.size() > CSV_MAX_LINES) csvBuffer.remove(0);

        if (now - lastShadowPushMs >= SHADOW_PUSH_INTERVAL_MS) {
            lastShadowPushMs = now;
            broadcastShadow(dXY_ref, dScore);
        }
    }

    private void updateShadowLatency(float dXY_ref, float dScore, long now) {
        boolean moving = dXY_ref >= ONSET_THRESHOLD || dScore >= ONSET_THRESHOLD;
        if (moving && episodeStartMs == 0) {
            episodeStartMs = now;
            xyCrossedYellow = false; xyCrossedRed = false;
            xyzCrossedYellow = false; xyzCrossedRed = false;
        }
        if (episodeStartMs != 0) {
            if (!xyCrossedYellow && dXY_ref >= thYellow) { xyCrossedYellow = true; xyYellowMs = now - episodeStartMs; }
            if (!xyCrossedRed && dXY_ref >= thRed) { xyCrossedRed = true; xyRedMs = now - episodeStartMs; }
            if (!xyzCrossedYellow && dScore >= thYellow) { xyzCrossedYellow = true; xyzYellowMs = now - episodeStartMs; }
            if (!xyzCrossedRed && dScore >= thRed) { xyzCrossedRed = true; xyzRedMs = now - episodeStartMs; }
            if (!moving) episodeStartMs = 0;
        }
    }

    private String simpleLabel(float mag) {
        if (mag >= thRed) return "KIRMIZI";
        if (mag >= thYellow) return "SARI";
        return "NORMAL";
    }

    private void broadcastShadow(float dXY_ref, float dScore) {
        Intent i = new Intent(Constants.ACTION_SHADOW);
        i.setPackage(getPackageName());
        i.putExtra(Constants.EXTRA_SXY, dXY_ref);
        i.putExtra(Constants.EXTRA_SXYZ, dScore);
        i.putExtra(Constants.EXTRA_STATE_XY, simpleLabel(dXY_ref));
        i.putExtra(Constants.EXTRA_STATE_XYZ, simpleLabel(dScore));
        i.putExtra(Constants.EXTRA_XY_YELLOW_MS, xyYellowMs);
        i.putExtra(Constants.EXTRA_XY_RED_MS, xyRedMs);
        i.putExtra(Constants.EXTRA_XYZ_YELLOW_MS, xyzYellowMs);
        i.putExtra(Constants.EXTRA_XYZ_RED_MS, xyzRedMs);
        sendBroadcast(i);
    }

    private void saveTestCsv() {
        try {
            StringBuilder sb = new StringBuilder();

            sb.append("# cihaz_model,").append(Build.MODEL).append("\n");
            sb.append("# uretici,").append(Build.MANUFACTURER).append("\n");
            sb.append("# android_surumu,").append(Build.VERSION.RELEASE).append("\n");
            sb.append("# sensor_adi,").append(accelerometer != null ? accelerometer.getName() : "-").append("\n");
            sb.append("# sensor_uretici,").append(accelerometer != null ? accelerometer.getVendor() : "-").append("\n");
            sb.append("# ortalama_ornekleme_hz,").append(String.format(Locale.US, "%.1f", avgSamplingHz)).append("\n");
            sb.append("# yumusatma_penceresi_ms,").append(SMOOTH_WINDOW_MS).append("\n");
            sb.append("# not,Sxy=referans(eski/salt-yatay) Sxyz=GERCEK(Z dahil w=0.3)\n");
            sb.append("\n");

            sb.append("timestamp,dx,dy,dz,Sxy,Sxyz,durum_xy,durum_xyz\n");
            for (String line : csvBuffer) sb.append(line).append("\n");
            sb.append("\n# Gecikme Sonuclari (son bolum)\n");
            sb.append("sari_esik_xy_ms,").append(xyYellowMs).append("\n");
            sb.append("sari_esik_xyz_ms,").append(xyzYellowMs).append("\n");
            sb.append("kirmizi_esik_xy_ms,").append(xyRedMs).append("\n");
            sb.append("kirmizi_esik_xyz_ms,").append(xyzRedMs).append("\n");
            sb.append("agirlik_w,").append(WEIGHT_Z).append("\n");
            sb.append("kirmizi_esigi,").append(BASE_RED).append("\n");

            String filename = "senalert_test_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
            boolean success = writeCsvFile(filename, sb.toString());

            Intent done = new Intent(Constants.ACTION_CSV_SAVED);
            done.setPackage(getPackageName());
            done.putExtra(Constants.EXTRA_CSV_FILENAME, success ? filename : "");
            sendBroadcast(done);
        } catch (Exception ignored) {}
    }

    private boolean writeCsvFile(String filename, String content) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SenAlert");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) return false;
                os.write(content.getBytes());
                os.close();
                return true;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SenAlert");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                FileWriter fw = new FileWriter(file);
                fw.write(content);
                fw.close();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                Constants.CHANNEL_ID, "Sen-Alert İzleme", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Arka planda sarsıntı izleme durumu");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notifManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, int score) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        String content = text + (score > 0 ? " · " + score + "/100" : "");

        return new Notification.Builder(this)
            .setChannelId(Constants.CHANNEL_ID)
            .setContentTitle("Sen-Alert")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build();
    }

    private void updateNotification(String text, int score) {
        notifManager.notify(NOTIF_ID, buildNotification(text, score));
    }

    private void maybeUpdateNotification() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifUpdateMs < NOTIF_UPDATE_INTERVAL_MS) return;
        lastNotifUpdateMs = now;
        if (currentState == State.CALIBRATING || currentState == State.PAUSED_GRAY) return;
        updateNotification(stateLabel(), pendingScoreForNotif);
    }

    private String stateLabel() {
        switch (currentState) {
            case GREEN:  return "NORMAL";
            case YELLOW: return "SARSINTI ALGILANDI";
            case RED:    return "GÜÇLÜ SARSINTI";
            default:     return "";
        }
    }

    private void broadcastState(int score, float x, float y, float z, float dScore, float dZ) {
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
        i.putExtra(Constants.EXTRA_DXY, dScore);
        i.putExtra(Constants.EXTRA_DZ, dZ);
        sendBroadcast(i);
    }
}
