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
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Vibrator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sen-Alert'in gerçek çalışma motoru. MainActivity ekranda olsa da olmasa da
 * sensörü dinler, durum makinesini işletir, bildirimleri (ses/titreşim/flaş) tetikler.
 * MainActivity sadece bu servisten gelen broadcast'leri dinleyip ekranda gösterir.
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
    // Sabit süreli alarm - sınırsız değil, panik yapmasın diye
    private static final long RED_ALARM_DURATION_MS = 4000;

    private static final float BASE_YELLOW = 0.15f;
    private static final float BASE_RED    = 0.90f;
    private float thYellow = BASE_YELLOW;
    private float thRed    = BASE_RED;
    private static final float HAND_Z_DELTA = 1.0f;
    private static final int SMOOTH_WINDOW = 6;
    private final float[] magBuffer = new float[SMOOTH_WINDOW];
    private int magIndex = 0;
    private static final float INSTANT_RED_OVERRIDE = 2.0f;

    private final Handler handler = new Handler();
    private boolean redAlarmActive = false;
    private boolean redAlarmMuted = false;

    private long lastNotifUpdateMs = 0;
    private static final long NOTIF_UPDATE_INTERVAL_MS = 1000;

    private final Runnable redBlinkRunnable = new Runnable() {
        @Override public void run() {
            if (!redAlarmActive || redAlarmMuted) return;
            if (prefs.getBoolean("red_flash", true)) toggleTorch(!torchOn);
            handler.postDelayed(this, 400);
        }
    };

    private final Runnable stopRedAlarmRunnable = new Runnable() {
        @Override public void run() { stopRedAlarm(); }
    };

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_MUTE.equals(intent.getAction())) {
                muteRedAlarm();
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

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("🟤", "KALİBRASYON"));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_MUTE);
        filter.addAction(Constants.ACTION_RECALIBRATE);
        registerReceiver(controlReceiver, filter);

        updateSensitivity();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);

        goCalibrating();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // sistem öldürürse yeniden başlat
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
        stopRedAlarm();
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void updateSensitivity() {
        int sensitivity = prefs.getInt("sensitivity", 5);
        float factor = 1.0f - (sensitivity - 5) * 0.08f;
        factor = Math.max(0.35f, Math.min(1.8f, factor));
        thYellow = BASE_YELLOW * factor;
        thRed    = BASE_RED    * factor;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        updateSensitivity(); // ayarlar ekranında değişmiş olabilir

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

        if (currentState == State.PAUSED_GRAY) return;

        float frac = computeFrac(dXY);
        int score = Math.round(frac * 100);

        broadcastState(score, x, y, z, dXY, dz);
        maybeUpdateNotification(score);

        long now = SystemClock.elapsedRealtime();

        if (currentState == State.CALIBRATING) {
            if (now - calibrateStartMs >= CALIBRATION_MS) commitState(State.GREEN);
            return;
        }

        if (currentState != State.GREEN && dz >= HAND_Z_DELTA) {
            goGray();
            return;
        }

        if (dMag >= INSTANT_RED_OVERRIDE) {
            commitState(State.RED);
            return;
        }

        State candidate = dXY >= thRed ? State.RED : (dXY >= thYellow ? State.YELLOW : State.GREEN);

        if (candidate == currentState) {
            pendingState = null;
            return;
        }
        if (pendingState != candidate) {
            pendingState = candidate;
            pendingSinceMs = now;
            return;
        }
        long requiredHold;
        if (candidate == State.RED) requiredHold = RED_SUSTAIN_MS;
        else if (candidate == State.YELLOW && candidate.ordinal() > currentState.ordinal()) requiredHold = YELLOW_SUSTAIN_MS;
        else requiredHold = RELEASE_SUSTAIN_MS;

        if (now - pendingSinceMs >= requiredHold) {
            commitState(candidate);
        }
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

    /** dXY -> 0..1 (EkgGraphView eşik çizgileriyle uyumlu) */
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
        stopRedAlarm();
        updateNotification("🟤", "KALİBRASYON YAPILIYOR...");
    }

    private void goGreen() {
        if (currentState == State.GREEN) return;
        currentState = State.GREEN;
        stopRedAlarm();
        updateNotification("🟢", "SABİT");
    }

    private void goYellow() {
        if (currentState == State.YELLOW) return;
        currentState = State.YELLOW;
        stopRedAlarm();
        updateNotification("🟡", "SARSINTI ALGILANDI");
        triggerYellowFeedback();
    }

    private void goRed() {
        boolean alreadyRed = (currentState == State.RED);
        currentState = State.RED;
        updateNotification("🔴", "GÜÇLÜ SARSINTI");
        if (!alreadyRed) {
            saveLastStrongEvent();
            startRedAlarm();
        }
    }

    private void goGray() {
        currentState = State.PAUSED_GRAY;
        stopRedAlarm();
        updateNotification("⚪", "İZLEME BEKLEMEDE");
    }

    private void saveLastStrongEvent() {
        String time = new SimpleDateFormat("HH:mm · dd.MM.yyyy", Locale.US).format(new Date());
        prefs.edit().putString("last_strong_time", time).apply();
    }

    // ================= BİLDİRİMLER (ses/titreşim/flaş) =================

    private void triggerYellowFeedback() {
        if (prefs.getBoolean("yellow_vibration", true) && vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(new long[]{0, 150}, -1);
        }
        if (prefs.getBoolean("yellow_sound", true)) playOnceSound();
        if (prefs.getBoolean("yellow_flash", false)) briefFlash();
    }

    private void startRedAlarm() {
        redAlarmActive = true;
        redAlarmMuted = false;

        if (prefs.getBoolean("red_vibration", true) && vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(new long[]{0, 400, 150, 400, 150, 400, 150, 400}, -1);
        }
        if (prefs.getBoolean("red_sound", true)) startLoopingAlarmSound();
        if (prefs.getBoolean("red_flash", true)) handler.post(redBlinkRunnable);

        // 4 saniye sonra otomatik dur - sınırsız çalmıyor
        handler.postDelayed(stopRedAlarmRunnable, RED_ALARM_DURATION_MS);
    }

    private void muteRedAlarm() {
        redAlarmMuted = true;
        stopRedAlarm();
    }

    private void stopRedAlarm() {
        redAlarmActive = false;
        redAlarmMuted = false;
        handler.removeCallbacks(redBlinkRunnable);
        handler.removeCallbacks(stopRedAlarmRunnable);
        if (vibrator != null) vibrator.cancel();
        stopLoopingAlarmSound();
        if (torchOn) toggleTorch(false);
    }

    private void playOnceSound() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(this, uri);
            if (r != null) r.play();
        } catch (Exception ignored) {}
    }

    private void startLoopingAlarmSound() {
        try {
            stopLoopingAlarmSound();
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            alarmPlayer = new MediaPlayer();
            alarmPlayer.setDataSource(this, uri);
            alarmPlayer.setLooping(true);
            alarmPlayer.prepare();
            alarmPlayer.start();
        } catch (Exception ignored) {}
    }

    private void stopLoopingAlarmSound() {
        if (alarmPlayer != null) {
            try { alarmPlayer.stop(); alarmPlayer.release(); } catch (Exception ignored) {}
            alarmPlayer = null;
        }
    }

    private void briefFlash() {
        toggleTorch(true);
        handler.postDelayed(new Runnable() {
            @Override public void run() { toggleTorch(false); }
        }, 250);
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

    // ================= BİLDİRİM (Sig-Fi Compass tarzı: ikon + renkli nokta + metin) =================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                Constants.CHANNEL_ID, "Sen-Alert İzleme", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Arka planda sarsıntı izleme durumu");
            notifManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String dot, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return new Notification.Builder(this)
            .setChannelId(Constants.CHANNEL_ID)
            .setContentTitle("Sen-Alert")
            .setContentText(dot + " " + text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private void updateNotification(String dot, String text) {
        notifManager.notify(NOTIF_ID, buildNotification(dot, text));
    }

    private void maybeUpdateNotification(int score) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifUpdateMs < NOTIF_UPDATE_INTERVAL_MS) return;
        lastNotifUpdateMs = now;
        if (currentState == State.CALIBRATING || currentState == State.PAUSED_GRAY) return;
        String dot = currentState == State.RED ? "🔴" : currentState == State.YELLOW ? "🟡" : "🟢";
        updateNotification(dot, stateLabel() + " · " + score + "/100");
    }

    private String stateLabel() {
        switch (currentState) {
            case GREEN:  return "SABİT";
            case YELLOW: return "SARSINTI ALGILANDI";
            case RED:    return "GÜÇLÜ SARSINTI";
            default:     return "";
        }
    }

    private void broadcastState(int score, float x, float y, float z, float dXY, float dZ) {
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
