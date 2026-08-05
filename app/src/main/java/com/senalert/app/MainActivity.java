package com.senalert.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.View;
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
    private View rootLayout;
    private ShakeOrbView orbView;
    private EkgGraphView ekgView;

    // ---------- SENSOR ----------
    private SensorManager sensorManager;
    private Sensor accelerometer;

    // ---------- VIBRATOR ----------
    private Vibrator vibrator;

    // ---------- SETTINGS ----------
    private SharedPreferences prefs;

    // ---------- STATE ----------
    private enum State {
        CALIBRATING,
        GREEN,
        YELLOW,
        ORANGE,
        RED,
        PAUSED_GRAY
    }

    private State currentState = State.CALIBRATING;

    // ---------- RENKLER (mockup ile birebir) ----------
    private static final int COLOR_GREEN  = Color.parseColor("#35D07F");
    private static final int COLOR_YELLOW = Color.parseColor("#F5C518");
    private static final int COLOR_ORANGE = Color.parseColor("#FFA500");
    private static final int COLOR_RED    = Color.parseColor("#FF4438");
    private static final int COLOR_GRAY   = Color.parseColor("#5E7472");
    private int currentColor = COLOR_GREEN;

    // ---------- SENSOR VALUES ----------
    private float lastX, lastY, lastZ;
    private boolean firstRead = true;

    // ---------- TIME ----------
    private long calibrateStartMs = 0;
    private long lastStateChangeMs = 0;

    // ---------- CONSTANTS ----------
    private static final long CALIBRATION_MS = 2000;
    private static final long STATE_HOLD_MS = 500;

    private static final float BASE_YELLOW = 0.15f;
    private static final float BASE_ORANGE = 0.35f;
    private static final float BASE_RED    = 0.65f;

    private float thYellow = BASE_YELLOW;
    private float thOrange = BASE_ORANGE;
    private float thRed    = BASE_RED;

    private static final float HAND_Z_DELTA = 1.0f;

    private static final int SMOOTH_WINDOW = 6;
    private final float[] magBuffer = new float[SMOOTH_WINDOW];
    private int magIndex = 0;
    private static final float INSTANT_RED_OVERRIDE = 1.4f;

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
        rootLayout      = findViewById(R.id.rootLayout);
        orbView         = findViewById(R.id.orbView);
        ekgView         = findViewById(R.id.ekgView);

        vibrator      = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

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
    }

    private void updateSensitivity() {
        int sensitivity = prefs.getInt("sensitivity", 10);
        float factor = 1.0f - (sensitivity - 10) * 0.03f;
        factor = Math.max(0.4f, Math.min(1.6f, factor));
        thYellow = BASE_YELLOW * factor;
        thOrange = BASE_ORANGE * factor;
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
            Locale.US,
            "X: %.2f  Y: %.2f  Z: %.2f\nΔXY: %.2f  ΔZ: %.2f",
            x, y, z, dXY, dz
        ));

        appendLog(x, y, z, dXY, dz);

        // ---- GÖRSEL: EKG grafiği ve top her örnekte güncellenir ----
        float frac = computeFrac(dXY);
        ekgView.pushSample(frac);
        orbView.setLevel(frac, currentColor);

        long now = SystemClock.elapsedRealtime();

        if (currentState == State.CALIBRATING) {
            if (now - calibrateStartMs >= CALIBRATION_MS) goGreen();
            return;
        }

        if (currentState != State.GREEN && dz >= HAND_Z_DELTA) {
            goGray();
            return;
        }

        if (dMag >= INSTANT_RED_OVERRIDE) {
            goRed();
            return;
        }

        if (now - lastStateChangeMs < STATE_HOLD_MS) return;

        if      (dXY >= thRed)    goRed();
        else if (dXY >= thOrange) goOrange();
        else if (dXY >= thYellow) goYellow();
        else                      goGreen();
    }

    /** dXY değerini eşiklere göre 0..1 aralığına eşler (EkgGraphView'daki çizgilerle uyumlu) */
    private float computeFrac(float dXY) {
        if (dXY <= thYellow) {
            return (dXY / thYellow) * 0.33f;
        } else if (dXY <= thOrange) {
            return 0.33f + (dXY - thYellow) / (thOrange - thYellow) * 0.27f;
        } else if (dXY <= thRed) {
            return 0.60f + (dXY - thOrange) / (thRed - thOrange) * 0.25f;
        } else {
            float over = Math.min((dXY - thRed) / thRed, 1f);
            return 0.85f + over * 0.15f;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ================= STATE METHODS =================

    private void goCalibrating() {
        currentState = State.CALIBRATING;
        calibrateStartMs = SystemClock.elapsedRealtime();
        currentColor = COLOR_GRAY;
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
        lastStateChangeMs = SystemClock.elapsedRealtime();
        alertBar.setVisibility(View.GONE);
        stateText.setText("SABİT");
        stateText.setTextColor(COLOR_GREEN);
        subText.setText("Sarsıntı yok");
        btnRecalibrate.setVisibility(View.GONE);
    }

    private void goYellow() {
        if (currentState == State.YELLOW) return;
        currentState = State.YELLOW;
        currentColor = COLOR_YELLOW;
        lastStateChangeMs = SystemClock.elapsedRealtime();
        alertBar.setVisibility(View.GONE);
        stateText.setText("HAFİF SARSINTI");
        stateText.setTextColor(COLOR_YELLOW);
        subText.setText("Küçük bir hareket algılandı");
        triggerFeedback("yellow");
    }

    private void goOrange() {
        if (currentState == State.ORANGE) return;
        currentState = State.ORANGE;
        currentColor = COLOR_ORANGE;
        lastStateChangeMs = SystemClock.elapsedRealtime();
        alertBar.setVisibility(View.GONE);
        stateText.setText("ORTA SARSINTI");
        stateText.setTextColor(COLOR_ORANGE);
        subText.setText("Belirgin titreşim var");
        triggerFeedback("orange");
    }

    private void goRed() {
        if (currentState == State.RED) return;
        currentState = State.RED;
        currentColor = COLOR_RED;
        lastStateChangeMs = SystemClock.elapsedRealtime();
        alertBar.setVisibility(View.GONE);
        stateText.setText("ŞİDDETLİ SARSINTI!");
        stateText.setTextColor(COLOR_RED);
        subText.setText("Güçlü hareket algılandı");
        triggerFeedback("red");
    }

    private void goGray() {
        currentState = State.PAUSED_GRAY;
        currentColor = COLOR_GRAY;
        alertBar.setVisibility(View.VISIBLE);
        alertBar.setBackgroundColor(Color.DKGRAY);
        alertBar.setText("CİHAZ ELİNİZE ALINDI\nÖLÇÜM DURDURULDU");
        stateText.setText("DURAKLATILDI");
        stateText.setTextColor(COLOR_GRAY);
        subText.setText("Devam etmek için kalibre edin");
        orbView.setLevel(0f, COLOR_GRAY);
        btnRecalibrate.setVisibility(View.VISIBLE);
    }

    // ================= VİBRASYON =================

    private void triggerFeedback(String level) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        boolean vibOn = prefs.getBoolean(level + "_vibration", true);
        if (!vibOn) return;

        long[] pattern;
        switch (level) {
            case "yellow": pattern = new long[]{0, 100}; break;
            case "orange": pattern = new long[]{0, 200, 100, 200}; break;
            case "red":    pattern = new long[]{0, 400, 100, 400, 100, 400}; break;
            default:       return;
        }
        vibrator.vibrate(pattern, -1);
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
