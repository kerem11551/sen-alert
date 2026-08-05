package com.senalert.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

public class MainActivity extends Activity {

    // ---------- UI ----------
    private TextView alertBar;
    private TextView sensorText;
    private TextView stateText;
    private TextView subText;
    private TextView levelNumberText;
    private TextView btnInfoToggle;
    private TextView lastStrongText;
    private View detailsPanel;
    private Button btnRecalibrate;
    private Button btnCapture;
    private Button btnMute;
    private View rootLayout;
    private ShakeOrbView orbView;
    private EkgGraphView ekgView;

    private SharedPreferences prefs;

    private static final int COLOR_GREEN  = Color.parseColor("#22E88A");
    private static final int COLOR_YELLOW = Color.parseColor("#FFD60A");
    private static final int COLOR_RED    = Color.parseColor("#FF3B30");
    private static final int COLOR_GRAY   = Color.parseColor("#8A9C9A");

    // Son alınan değerler - "OLAYI KAYDET" butonunda kullanılır
    private float lastX, lastY, lastZ, lastDXY, lastDZ;
    private StringBuilder sensorLog = new StringBuilder();
    private static final int LOG_MAX_LINES = 500;
    private int logLineCount = 0;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(Constants.EXTRA_STATE);
            int score = intent.getIntExtra(Constants.EXTRA_SCORE, 0);
            lastX = intent.getFloatExtra(Constants.EXTRA_X, 0);
            lastY = intent.getFloatExtra(Constants.EXTRA_Y, 0);
            lastZ = intent.getFloatExtra(Constants.EXTRA_Z, 0);
            lastDXY = intent.getFloatExtra(Constants.EXTRA_DXY, 0);
            lastDZ = intent.getFloatExtra(Constants.EXTRA_DZ, 0);
            applyState(state, score);
            appendLog();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        alertBar        = findViewById(R.id.alertBar);
        sensorText      = findViewById(R.id.sensorText);
        stateText       = findViewById(R.id.stateText);
        subText         = findViewById(R.id.subText);
        levelNumberText = findViewById(R.id.levelNumberText);
        btnInfoToggle   = findViewById(R.id.btnInfoToggle);
        lastStrongText  = findViewById(R.id.lastStrongText);
        detailsPanel    = findViewById(R.id.detailsPanel);
        btnRecalibrate  = findViewById(R.id.btnRecalibrate);
        btnCapture      = findViewById(R.id.btnCapture);
        btnMute         = findViewById(R.id.btnMute);
        rootLayout      = findViewById(R.id.rootLayout);
        orbView         = findViewById(R.id.orbView);
        ekgView         = findViewById(R.id.ekgView);

        prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);

        requestNotificationPermissionIfNeeded();
        startMonitoringService();

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        btnRecalibrate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                btnRecalibrate.setVisibility(View.GONE);
                sendControl(Constants.ACTION_RECALIBRATE);
            }
        });

        btnCapture.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                captureScreen();
                saveSensorLog();
            }
        });

        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendControl(Constants.ACTION_MUTE);
                btnMute.setVisibility(View.GONE);
            }
        });

        btnInfoToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = detailsPanel.getVisibility() != View.VISIBLE;
                detailsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                btnInfoToggle.setText(show ? "ℹ️ Teknik Bilgiyi Gizle" : "ℹ️ Teknik Bilgi");
            }
        });

        updateLastStrongText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(Constants.ACTION_STATE);
        registerReceiver(stateReceiver, filter);
        updateLastStrongText();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        // NOT: servisi burada durdurmuyoruz - arka planda çalışmaya devam etmesi tam olarak amacımız
    }

    private void startMonitoringService() {
        Intent serviceIntent = new Intent(this, SensorService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void sendControl(String action) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void applyState(String state, int score) {
        float frac = score / 100f;
        ekgView.pushSample(frac);
        levelNumberText.setText(String.valueOf(score));

        sensorText.setText(String.format(
            Locale.US, "X: %.2f  Y: %.2f  Z: %.2f\nΔXY: %.2f  ΔZ: %.2f\nHassasiyet: %d",
            lastX, lastY, lastZ, lastDXY, lastDZ, prefs.getInt("sensitivity", 5)
        ));

        switch (state) {
            case "CALIBRATING":
                orbView.setLevel(0f, COLOR_GRAY);
                alertBar.setVisibility(View.VISIBLE);
                alertBar.setBackgroundColor(Color.GRAY);
                alertBar.setText("KALİBRASYON YAPILIYOR...");
                stateText.setText("KALİBRASYON");
                stateText.setTextColor(COLOR_GRAY);
                subText.setText("Telefonu sabit bırakın");
                btnRecalibrate.setVisibility(View.GONE);
                btnMute.setVisibility(View.GONE);
                break;
            case "GREEN":
                orbView.setLevel(frac, COLOR_GREEN);
                alertBar.setVisibility(View.GONE);
                stateText.setText("SABİT");
                stateText.setTextColor(COLOR_GREEN);
                subText.setText("Normal seviyede");
                btnRecalibrate.setVisibility(View.GONE);
                btnMute.setVisibility(View.GONE);
                break;
            case "YELLOW":
                orbView.setLevel(frac, COLOR_YELLOW);
                alertBar.setVisibility(View.GONE);
                stateText.setText("SARSINTI ALGILANDI");
                stateText.setTextColor(COLOR_YELLOW);
                subText.setText("Belirgin hareket algılandı");
                btnRecalibrate.setVisibility(View.GONE);
                btnMute.setVisibility(View.GONE);
                break;
            case "RED":
                orbView.setLevel(frac, COLOR_RED);
                alertBar.setVisibility(View.GONE);
                stateText.setText("GÜÇLÜ SARSINTI");
                stateText.setTextColor(COLOR_RED);
                subText.setText("Güvenliğinizi kontrol edin");
                btnRecalibrate.setVisibility(View.GONE);
                btnMute.setVisibility(View.VISIBLE);
                updateLastStrongText();
                break;
            case "PAUSED_GRAY":
                orbView.setLevel(0f, COLOR_GRAY);
                alertBar.setVisibility(View.VISIBLE);
                alertBar.setBackgroundColor(Color.DKGRAY);
                alertBar.setText("CİHAZIN KONUMU DEĞİŞTİ\nİZLEME DURDURULDU");
                stateText.setText("İZLEME BEKLEMEDE");
                stateText.setTextColor(COLOR_GRAY);
                subText.setText("Yeniden başlatmak için kalibre edin");
                btnRecalibrate.setVisibility(View.VISIBLE);
                btnMute.setVisibility(View.GONE);
                break;
        }
    }

    private void updateLastStrongText() {
        String last = prefs.getString("last_strong_time", null);
        lastStrongText.setText(last != null
            ? "Son Güçlü Sarsıntı: " + last
            : "Son Güçlü Sarsıntı: —");
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

            Toast.makeText(this, "Olay kaydedildi:\n" + file.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ekran görüntüsü alınamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void appendLog() {
        if (logLineCount >= LOG_MAX_LINES) return;
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        sensorLog.append(String.format(Locale.US,
            "%s | X:%.2f Y:%.2f Z:%.2f | dXY:%.2f dZ:%.2f\n",
            time, lastX, lastY, lastZ, lastDXY, lastDZ));
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

            sensorLog = new StringBuilder();
            logLineCount = 0;
        } catch (Exception e) {
            Toast.makeText(this, "Log kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
