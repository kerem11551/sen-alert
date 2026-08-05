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
    private Button btnRecalibrate;
    private Button btnCapture;
    private View rootLayout;

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

    // ---------- SENSOR VALUES ----------
    private float lastX, lastY, lastZ;
    private boolean firstRead = true;

    // ---------- TIME ----------
    private long calibrateStartMs = 0;
    private long lastStateChangeMs = 0;

    // ---------- CONSTANTS ----------
    private static final long CALIBRATION_MS = 2000;
    private static final long STATE_HOLD_MS = 500;

    // Hassasiyet eşikleri (büyüklük, m/s^2 cinsinden) - Settings'deki sensitivity % değerine göre ölçeklenir
    private static final float BASE_YELLOW = 0.15f;
    private static final float BASE_ORANGE = 0.35f;
    private static final float BASE_RED    = 0.65f;

    // Dinamik eşikler (sensitivity'ye göre güncellenir)
    private float thYellow = BASE_YELLOW;
    private float thOrange = BASE_ORANGE;
    private float thRed    = BASE_RED;

    // Ele alma tespiti (ΔZ)
    private static final float HAND_Z_DELTA = 1.0f;

    // ---- YENİ: yumuşatma penceresi (RMS benzeri hareketli ortalama) ----
    // Gerçek testte (sert elle sallama) dXY ~1.02 çıktı; STATE_HOLD_MS zaten
    // 500ms'lik bir soğuma sağlıyordu, bunu gerçek "sürelilik" filtresine çeviriyoruz.
    private static final int SMOOTH_WINDOW = 6; // ~SENSOR_DELAY_UI'de yaklaşık 300-400ms
    private final float[] magBuffer = new float[SMOOTH_WINDOW];
    private int magIndex = 0;
    // Çok büyük ani darbede (örn. masaya sert vurma) soğuma süresini beklemeden tetikle
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
        btnRecalibrate  = findViewById(R.id.btnRecalibrate);
        btnCapture      = findViewById(R.id.btnCapture);
        rootLayout      = findViewById(R.id.rootLayout);

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

    // Settings'deki sensitivity % değerine göre eşikleri güncelle
    private void updateSensitivity() {
        int sensitivity = prefs.getInt("sensitivity", 10); // 1-20 arası
        float factor = 1.0f - (sensitivity - 10) * 0.03f; // ±30% aralık
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

        // ---- DEĞİŞTİ: ortalama yerine gerçek vektör büyüklüğü ----
        float dMag = (float) Math.sqrt(dx * dx + dy * dy);

        // ---- YENİ: yumuşatılmış (hareketli ortalama) değer ----
        magBuffer[magIndex % SMOOTH_WINDOW] = dMag;
        magIndex++;
        int count = Math.min(magIndex, SMOOTH_WINDOW);
        float sum = 0f;
        for (int i = 0; i < count; i++) sum += magBuffer[i];
        float dXY = sum / count;

        lastX = x; lastY = y; lastZ = z;

        // GRİ: sadece butonu bekle
        if (currentState == State.PAUSED_GRAY) return;

        sensorText.setText(String.format(
            Locale.US,
            "X: %.2f  Y: %.2f  Z: %.2f\nΔXY: %.2f  ΔZ: %.2f",
            x, y, z, dXY, dz
        ));

        appendLog(x, y, z, dXY, dz);

        long now = SystemClock.elapsedRealtime();

        // KALİBRASYON
        if (currentState == State.CALIBRATING) {
            if (now - calibrateStartMs >= CALIBRATION_MS) goGreen();
            return;
        }

        // ELE ALINDI → GRİ
        if (currentState != State.GREEN && dz >= HAND_Z_DELTA) {
            goGray();
            return;
        }

        // ---- YENİ: çok büyük ani darbe, soğuma süresini beklemeden kırmızıya geç ----
        if (dMag >= INSTANT_RED_OVERRIDE) {
            goRed();
            return;
        }

        // RENK ZIPLAMASINI ÖNLE (soğuma süresi)
        if (now - lastStateChangeMs < STATE_HOLD_MS) return;

        // RENK GEÇİŞLERİ
        if      (dXY >= thRed)    goRed();
        else if (dXY >= thOrange) goOrange();
        else if (dXY >= thYellow) goYellow();
        else                      goGreen();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ================= STATE METHODS =================

    private void goCalibrating() {
        currentState = State.CALIBRATING;
        calibrateStartMs = SystemClock.elapsedRealtime();
        alertBar.setBackgroundColor(Color.GRAY);
        alertBar.setText("KALİBRASYON YAPILIYOR...");
        btnRecalibrate.setVisibility(View.GONE);
    }

    private void goGreen() {
        if (currentState == State.GREEN) return;
        currentState = State.GREEN;
        lastStateChangeMs = SystemClock.elapsedRealtime();
        alertBar.setBackgroundColor(Color.GREEN);
        alertBar.setText("SARSINTI ALGILANMADI");
        btnRecalibrate.setVisibility(View.GONE);
    }

    private void goYellow() {
        if (currentState == State.YELLOW) return;
        currentState = State.YELLOW;
        lastStateChangeMs = SystemClock.elapsedRealtime();
        alertBar.setBackgroundColor(Color.YELLOW);
        alertBar.setText("HAFİF SARSINTI");
        triggerFeedback("yellow");
    }

    private void goOrange() {
        if (currentState == State.ORANGE) return;
        currentState = State.ORANGE;
        lastStateChangeMs = SystemClock.elapsedRealtime();
        alertBar.setBackgroundColor(Color.parseColor("#FFA500"));
        alertBar.setText("ORTA SARSINTI");
        triggerFeedback("orange");
    }

    private void goRed() {
        if (currentState == State.RED) return;
        currentState = State.RED;
        lastStateChangeMs = SystemClock.elapsedRealtime();
        alertBar.setBackgroundColor(Color.RED);
        alertBar.setText("ŞİDDETLİ SARSINTI!");
        triggerFeedback("red");
    }

    private void goGray() {
        currentState = State.PAUSED_GRAY;
        alertBar.setBackgroundColor(Color.DKGRAY);
        alertBar.setText("CİHAZ ELİNİZE ALINDI\nÖLÇÜM DURDURULDU");
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
