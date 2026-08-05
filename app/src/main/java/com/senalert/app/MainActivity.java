package com.senalert.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    // ---------- UI ----------
    private TextView alertBar;
    private TextView sensorText;
    private TextView stateText;
    private TextView subText;
    private Button btnRecalibrate;
    private Button btnCapture;
    private Button btnMute;
    private View rootLayout;
    private ShakeOrbView orbView;
    private EkgGraphView ekgView;

    // ---------- SENSOR ----------
    private SensorManager sensorManager;
    private Sensor accelerometer;

    // ---------- VIBRATOR ----------
    private Vibrator vibrator;

    // ---------- KAMERA / FLAŞ (izin gerekmez, setTorchMode izinsizdir) ----------
    private CameraManager cameraManager;
    private String cameraId;
    private boolean torchOn = false;

    // ---------- SES ----------
    private MediaPlayer alarmPlayer; // kırmızıda döngülü çalar

    // ---------- SETTINGS ----------
    private SharedPreferences prefs;

    // ---------- STATE (3 kademe) ----------
    private enum State { CALIBRATING, GREEN, YELLOW, RED, PAUSED_GRAY }

    private State currentState = State.CALIBRATING;
    private State pendingState = null;
    private long pendingSinceMs = 0;

    private static final int COLOR_GREEN  = Color.parseColor("#35D07F");
    private static final int COLOR_YELLOW = Color.parseColor("#F5C518");
    private static final int COLOR_RED    = Color.parseColor("#FF4438");
    private static final int COLOR_GRAY   = Color.parseColor("#5E7472");
    private int currentColor = COLOR_GREEN;

    // ---------- SENSOR VALUES ----------
    private float lastX, lastY, lastZ;
    private boolean firstRead = true;

    private long calibrateStartMs = 0;

    // ---------- ZAMAN / SÜRE FİLTRESİ ----------
    private static final long CALIBRATION_MS = 2000;
    private static final long YELLOW_SUSTAIN_MS = 2000; // sarı: 2sn sürekli
    private static final long RED_SUSTAIN_MS = 500;      // kırmızı: 0.5sn sürekli
    private static final long RELEASE_SUSTAIN_MS = 500;  // geri düşüşte

    // ---------- EŞİKLER (3 kademe) ----------
    private static final float BASE_YELLOW = 0.15f;
    private static final float BASE_RED    = 0.45f;
    private float thYellow = BASE_YELLOW;
    private float thRed    = BASE_RED;

    private static final float HAND_Z_DELTA = 1.0f;

    private static final int SMOOTH_WINDOW = 6;
    private final float[] magBuffer = new float[SMOOTH_WINDOW];
    private int magIndex = 0;
    // Ani büyük darbe: süre beklemeden direkt kırmızı
    private static final float INSTANT_RED_OVERRIDE = 1.4f;

    // ---------- KIRMIZI SÜREKLİ ALARM ----------
    private final Handler redAlarmHandler = new Handler();
    private boolean redAlarmActive = false;
    private boolean redAlarmMuted = false;
    private final Runnable redBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!redAlarmActive || redAlarmMuted) return;
            if (prefs.getBoolean("red_flash", true)) toggleTorch(!torchOn);
            redAlarmHandler.postDelayed(this, 400);
        }
    };

    // ---------- LOG ----------
    private StringBuilder sensorLog = new StringBuilder();
    private static final int LOG_MAX_LINES = 500;
    private int logLineCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        alertBar        = findViewById(R.id.alertBar);
        sensorText      = findViewById(R.id.sensorText);
        stateText       = findViewById(R.id.stateText);
        subText         = findViewById(R.id.subText);
        btnRecalibrate  = findViewById(R.id.btnRecalibrate);
        btnCapture      = findViewById(R.id.btnCapture);
        btnMute         = findViewById(R.id.btnMute);
        rootLayout      = findViewById(R.id.rootLayout);
        orbView         = findViewById(R.id.orbView);
        ekgView         = findViewById(R.id.ekgView);

        vibrator      = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        cameraId = getFirstCameraWithFlash();

        prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        btnRecalibrate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnRecalibrate.setVisibility(View.GONE);
                firstRead = true;
                magIndex = 0;
                goCalibrating();
            }
        });

        btnCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureScreen();
                saveSensorLog();
            }
        });

        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                muteRedAlarm();
            }
        });

        goCalibrating();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSensitivity();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        stopRedAlarm();
    }

    private void updateSensitivity() {
        // 1-10 arası, renklerden bağımsız: sadece eşikleri kaydırır
        int sensitivity = prefs.getInt("sensitivity", 5);
        float factor = 1.0f - (sensitivity - 5) * 0.08f; // 1=kolay tetiklenmez, 10=çok hassas
        factor = Math.max(0.35f, Math.min(1.8f, factor));
        thYellow = BASE_YELLOW * factor;
        thRed    = BASE_RED    * factor;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
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

        sensorText.setText(String.format(
            Locale.US, "X: %.2f  Y: %.2f  Z: %.2f\nΔXY: %.2f  ΔZ: %.2f",
            x, y, z, dXY, dz
        ));
        appendLog(x, y, z, dXY, dz);

        float frac = computeFrac(dXY);
        ekgView.pushSample(frac);
        orbView.setLevel(frac, currentColor);

        long now = SystemClock.elapsedRealtime();

        if (currentState == State.CALIBRATING) {
            if (now - calibrateStartMs >= CALIBRATION_MS) commitState(State.GREEN, now);
            return;
        }

        if (currentState != State.GREEN && dz >= HAND_Z_DELTA) {
            goGray();
            return;
        }

        // Ani büyük darbe: süre beklemeden direkt kırmızı
        if (dMag >= INSTANT_RED_OVERRIDE) {
            commitState(State.RED, now);
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
            commitState(candidate, now);
        }
    }

    private void commitState(State s, long now) {
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
            return (dXY / thYellow) * 0.40f;
        } else if (dXY <= thRed) {
            return 0.40f + (dXY - thYellow) / (thRed - thYellow) * 0.35f;
        } else {
            float over = Math.min((dXY - thRed) / thRed, 1f);
            return 0.75f + over * 0.25f;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ================= STATE METHODS =================

    private void goCalibrating() {
        currentState = State.CALIBRATING;
        calibrateStartMs = SystemClock.elapsedRealtime();
        currentColor = COLOR_GRAY;
        stopRedAlarm();
        alertBar.setVisibility(View.VISIBLE);
        alertBar.setBackgroundColor(Color.GRAY);
        alertBar.setText("KALİBRASYON YAPILIYOR...");
        stateText.setText("KALİBRASYON");
        stateText.setTextColor(COLOR_GRAY);
        subText.setText("Telefonu sabit bırakın");
        btnRecalibrate.setVisibility(View.GONE);
    }

    private void goGreen() {
        if (currentState == State.GREEN) return;
        currentState = State.GREEN;
        currentColor = COLOR_GREEN;
        stopRedAlarm();
        alertBar.setVisibility(View.GONE);
        stateText.setText("SABİT");
        stateText.setTextColor(COLOR_GREEN);
        subText.setText("Normal seviyede");
        btnRecalibrate.setVisibility(View.GONE);
    }

    private void goYellow() {
        if (currentState == State.YELLOW) return;
        currentState = State.YELLOW;
        currentColor = COLOR_YELLOW;
        stopRedAlarm();
        alertBar.setVisibility(View.GONE);
        stateText.setText("SARSINTI ALGILANDI");
        stateText.setTextColor(COLOR_YELLOW);
        subText.setText("Belirgin hareket algılandı");
        triggerYellowFeedback();
    }

    private void goRed() {
        boolean alreadyRed = (currentState == State.RED);
        currentState = State.RED;
        currentColor = COLOR_RED;
        alertBar.setVisibility(View.GONE);
        stateText.setText("GÜÇLÜ SARSINTI");
        stateText.setTextColor(COLOR_RED);
        subText.setText("Güvenliğinizi kontrol edin");
        if (!alreadyRed) startRedAlarm();
    }

    private void goGray() {
        currentState = State.PAUSED_GRAY;
        currentColor = COLOR_GRAY;
        stopRedAlarm();
        alertBar.setVisibility(View.VISIBLE);
        alertBar.setBackgroundColor(Color.DKGRAY);
        alertBar.setText("CİHAZIN KONUMU DEĞİŞTİ\nİZLEME DURDURULDU");
        stateText.setText("İZLEME BEKLEMEDE");
        stateText.setTextColor(COLOR_GRAY);
        subText.setText("Yeniden başlatmak için kalibre edin");
        orbView.setLevel(0f, COLOR_GRAY);
        btnRecalibrate.setVisibility(View.VISIBLE);
    }

    // ================= BİLDİRİMLER =================

    private void triggerYellowFeedback() {
        if (prefs.getBoolean("yellow_vibration", true) && vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(new long[]{0, 150}, -1);
        }
        if (prefs.getBoolean("yellow_sound", true)) {
            playOnceSound();
        }
        if (prefs.getBoolean("yellow_flash", false)) {
            briefFlash();
        }
    }

    private void startRedAlarm() {
        redAlarmActive = true;
        redAlarmMuted = false;
        btnMute.setVisibility(View.VISIBLE);

        if (prefs.getBoolean("red_wake_screen", true)) {
            wakeScreen();
        }
        if (prefs.getBoolean("red_vibration", true) && vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 400, 150, 400, 150, 400};
            vibrator.vibrate(pattern, prefs.getBoolean("red_continuous", true) ? 0 : -1);
        }
        if (prefs.getBoolean("red_sound", true)) {
            startLoopingAlarmSound();
        }
        if (prefs.getBoolean("red_flash", true)) {
            redAlarmHandler.post(redBlinkRunnable);
        }
        // "Susturulana kadar devam et" kapalıysa birkaç saniye sonra otomatik durdur
        if (!prefs.getBoolean("red_continuous", true)) {
            redAlarmHandler.postDelayed(new Runnable() {
                @Override public void run() { stopRedAlarm(); }
            }, 5000);
        }
    }

    private void muteRedAlarm() {
        redAlarmMuted = true;
        if (vibrator != null) vibrator.cancel();
        stopLoopingAlarmSound();
        if (torchOn) toggleTorch(false);
        btnMute.setVisibility(View.GONE);
    }

    private void stopRedAlarm() {
        redAlarmActive = false;
        redAlarmMuted = false;
        redAlarmHandler.removeCallbacksAndMessages(null);
        if (vibrator != null) vibrator.cancel();
        stopLoopingAlarmSound();
        if (torchOn) toggleTorch(false);
        btnMute.setVisibility(View.GONE);
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
        redAlarmHandler.postDelayed(new Runnable() {
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
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (has != null && has) return id;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void wakeScreen() {
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
    }

    // ================= EKRAN GÖRÜNTÜSÜ =================

    private void captureScreen() {
        try {
            View view = rootLayout;
            view.setDrawingCacheEnabled(true);
            Bitmap bitmap = Bitmap.createBitmap(view.getDrawingCache());
            view.setDrawingCacheEnabled(false);

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "SenAlert");
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, "senalert_" + timestamp + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            Toast.makeText(this, "Ekran kaydedildi:\n" + file.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ekran görüntüsü alınamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ================= SENSOR LOG =================

    private void appendLog(float x, float y, float z, float dXY, float dz) {
        if (logLineCount >= LOG_MAX_LINES) return;
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        sensorLog.append(String.format(Locale.US,
            "%s | X:%.2f Y:%.2f Z:%.2f | dXY:%.2f dZ:%.2f | %s\n",
            time, x, y, z, dXY, dz, currentState.name()));
        logLineCount++;
    }

    private void saveSensorLog() {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "SenAlert");
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, "senalert_log_" + timestamp + ".txt");
            FileWriter fw = new FileWriter(file);
            fw.write("Sen-Alert Sensor Logu\n");
            fw.write("Tarih: " + new Date().toString() + "\n");
            fw.write("==============================\n");
            fw.write(sensorLog.toString());
            fw.close();

            Toast.makeText(this, "Log kaydedildi:\n" + file.getName(), Toast.LENGTH_SHORT).show();
            sensorLog = new StringBuilder();
            logLineCount = 0;
        } catch (Exception e) {
            Toast.makeText(this, "Log kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
