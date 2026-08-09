package com.senalert.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    // ---------- UI ----------
    private TextView alertBar;
    private TextView sensorText;
    private TextView stateText;
    private TextView btnInfoToggle;
    private TextView lastAlertText;
    private TextView lastBroadcastText;
    private View detailsPanel;
    private Button btnRecalibrate;
    private TextView btnMute;
    private Button btnToggle;
    private ShakeOrbView orbView;
    private EkgGraphView ekgView;

    // ---------- KURULUM KONTROLÜ ----------
    private TextView btnSetupToggle;
    private View setupPanel;
    private TextView setupNotifStatus;
    private TextView setupBatteryStatus;
    private Button btnFixNotif;
    private Button btnFixBattery;
    private Button btnFixAutostart;

    // ---------- V1.1 KARŞILAŞTIRMA PANELİ ----------
    private View comparisonPanel;
    private TextView testModeOffNotice;
    private TextView cmpSxy, cmpSxyState, cmpSxyz, cmpSxyzState;
    private TextView cmpYellowXy, cmpYellowXyz, cmpRedXy, cmpRedXyz;
    private Button btnSaveCsv;

    private SharedPreferences prefs;
    private boolean hasAccelerometer = true;

    private static final int COLOR_GREEN  = Color.parseColor("#22E88A");
    private static final int COLOR_YELLOW = Color.parseColor("#FFD60A");
    private static final int COLOR_RED    = Color.parseColor("#FF3B30");
    private static final int COLOR_GRAY   = Color.parseColor("#8A9C9A");

    private String lastAppliedState = null;

    private final BroadcastReceiver mainReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (Constants.ACTION_STATE.equals(action)) {
                String state = intent.getStringExtra(Constants.EXTRA_STATE);
                int score = intent.getIntExtra(Constants.EXTRA_SCORE, 0);
                float x = intent.getFloatExtra(Constants.EXTRA_X, 0);
                float y = intent.getFloatExtra(Constants.EXTRA_Y, 0);
                float z = intent.getFloatExtra(Constants.EXTRA_Z, 0);
                float dXY = intent.getFloatExtra(Constants.EXTRA_DXY, 0);
                float dZ = intent.getFloatExtra(Constants.EXTRA_DZ, 0);

                if (!"PAUSED_GRAY".equals(state)) {
                    ekgView.pushSample(score / 100f);
                }

                lastBroadcastText.setText("Son Veri Güncellemesi: " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));

                sensorText.setText(String.format(
                    Locale.US, "X: %.2f  Y: %.2f  Z: %.2f\nΔXY: %.2f  ΔZ: %.2f\nHassasiyet: %d",
                    x, y, z, dXY, dZ, prefs.getInt("sensitivity", 5)
                ));

                if (!state.equals(lastAppliedState)) {
                    lastAppliedState = state;
                    applyState(state);
                }

            } else if (Constants.ACTION_SHADOW.equals(action)) {
                float sxy = intent.getFloatExtra(Constants.EXTRA_SXY, 0);
                float sxyz = intent.getFloatExtra(Constants.EXTRA_SXYZ, 0);
                String stateXy = intent.getStringExtra(Constants.EXTRA_STATE_XY);
                String stateXyz = intent.getStringExtra(Constants.EXTRA_STATE_XYZ);
                long xyYellow = intent.getLongExtra(Constants.EXTRA_XY_YELLOW_MS, -1);
                long xyRed = intent.getLongExtra(Constants.EXTRA_XY_RED_MS, -1);
                long xyzYellow = intent.getLongExtra(Constants.EXTRA_XYZ_YELLOW_MS, -1);
                long xyzRed = intent.getLongExtra(Constants.EXTRA_XYZ_RED_MS, -1);

                cmpSxy.setText(String.format(Locale.US, "%.2f", sxy));
                cmpSxyState.setText(stateXy);
                cmpSxyz.setText(String.format(Locale.US, "%.2f", sxyz));
                cmpSxyzState.setText(stateXyz);
                cmpYellowXy.setText(xyYellow >= 0 ? xyYellow + " ms" : "—");
                cmpYellowXyz.setText(xyzYellow >= 0 ? xyzYellow + " ms" : "—");
                cmpRedXy.setText(xyRed >= 0 ? xyRed + " ms" : "—");
                cmpRedXyz.setText(xyzRed >= 0 ? xyzRed + " ms" : "—");

            } else if (Constants.ACTION_CSV_SAVED.equals(action)) {
                String filename = intent.getStringExtra(Constants.EXTRA_CSV_FILENAME);
                if (filename != null && !filename.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Kaydedildi: İndirilenler/SenAlert/" + filename, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Kayıt başarısız oldu", Toast.LENGTH_LONG).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        alertBar           = findViewById(R.id.alertBar);
        sensorText          = findViewById(R.id.sensorText);
        stateText           = findViewById(R.id.stateText);
        btnInfoToggle       = findViewById(R.id.btnInfoToggle);
        lastAlertText       = findViewById(R.id.lastAlertText);
        lastBroadcastText   = findViewById(R.id.lastBroadcastText);
        detailsPanel         = findViewById(R.id.detailsPanel);
        btnRecalibrate       = findViewById(R.id.btnRecalibrate);
        btnMute              = findViewById(R.id.btnMute);
        btnToggle            = findViewById(R.id.btnToggle);
        orbView              = findViewById(R.id.orbView);
        ekgView              = findViewById(R.id.ekgView);

        btnSetupToggle       = findViewById(R.id.btnSetupToggle);
        setupPanel            = findViewById(R.id.setupPanel);
        setupNotifStatus      = findViewById(R.id.setupNotifStatus);
        setupBatteryStatus    = findViewById(R.id.setupBatteryStatus);
        btnFixNotif           = findViewById(R.id.btnFixNotif);
        btnFixBattery         = findViewById(R.id.btnFixBattery);
        btnFixAutostart       = findViewById(R.id.btnFixAutostart);

        comparisonPanel      = findViewById(R.id.comparisonPanel);
        testModeOffNotice    = findViewById(R.id.testModeOffNotice);
        cmpSxy               = findViewById(R.id.cmpSxy);
        cmpSxyState          = findViewById(R.id.cmpSxyState);
        cmpSxyz              = findViewById(R.id.cmpSxyz);
        cmpSxyzState         = findViewById(R.id.cmpSxyzState);
        cmpYellowXy          = findViewById(R.id.cmpYellowXy);
        cmpYellowXyz         = findViewById(R.id.cmpYellowXyz);
        cmpRedXy             = findViewById(R.id.cmpRedXy);
        cmpRedXyz            = findViewById(R.id.cmpRedXyz);
        btnSaveCsv           = findViewById(R.id.btnSaveCsv);

        prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);

        checkAccelerometer();
        requestNotificationPermissionIfNeeded();

        // Yeniden Kalibre Et artık her zaman görünür/erişilebilir - sadece
        // İZLEME BEKLEMEDE durumuna özgü değil, kullanıcı istediği an manuel
        // kalibrasyon başlatabilsin diye (özellikle test/kalibrasyon amaçlı).
        btnRecalibrate.setVisibility(View.VISIBLE);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onToggleClicked(); }
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        btnRecalibrate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendControl(Constants.ACTION_RECALIBRATE);
                Toast.makeText(MainActivity.this, "Yeniden kalibre ediliyor...", Toast.LENGTH_SHORT).show();
            }
        });

        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendControl(Constants.ACTION_MUTE);
                btnMute.setVisibility(View.INVISIBLE);
            }
        });

        btnInfoToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = detailsPanel.getVisibility() != View.VISIBLE;
                detailsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                btnInfoToggle.setText(show ? "ℹ️ Teknik Bilgiyi Gizle" : "ℹ️ Teknik Bilgi");
            }
        });

        btnSetupToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = setupPanel.getVisibility() != View.VISIBLE;
                setupPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                btnSetupToggle.setText(show ? "🛠️ Kurulum Kontrolünü Gizle" : "🛠️ Kurulum Kontrolü");
            }
        });

        btnFixNotif.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestNotificationPermissionIfNeeded(); }
        });

        btnFixBattery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestIgnoreBatteryOptimizations(); }
        });

        btnFixAutostart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAutostartSettings(); }
        });

        btnSaveCsv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendControl(Constants.ACTION_SAVE_CSV); }
        });

        orbView.setState(COLOR_GRAY, ShakeOrbView.LEVEL_NEUTRAL);
        updateLastAlertText();
        refreshToggleButtonFromState();
        refreshSetupStatus();
        refreshTestModeUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_STATE);
        filter.addAction(Constants.ACTION_SHADOW);
        filter.addAction(Constants.ACTION_CSV_SAVED);
        registerReceiver(mainReceiver, filter);
        updateLastAlertText();
        refreshToggleButtonFromState();
        refreshSetupStatus();
        refreshTestModeUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(mainReceiver); } catch (Exception ignored) {}
    }

    // ================= V1.1 TEST MODU UI =================

    private void refreshTestModeUi() {
        boolean testMode = prefs.getBoolean("test_mode_enabled", false);
        comparisonPanel.setVisibility(testMode ? View.VISIBLE : View.GONE);
        testModeOffNotice.setVisibility(testMode ? View.GONE : View.VISIBLE);
    }

    // ================= İVMEÖLÇER KONTROLÜ =================

    private void checkAccelerometer() {
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        hasAccelerometer = sm != null && sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
        if (!hasAccelerometer) {
            btnToggle.setEnabled(false);
            btnToggle.setText("SENSÖR YOK");
            btnToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_GRAY));
            stateText.setText("DESTEKLENMİYOR");
            stateText.setTextColor(COLOR_RED);
            alertBar.setVisibility(View.VISIBLE);
            alertBar.setBackgroundColor(Color.DKGRAY);
            alertBar.setText("Bu cihazda gerekli hareket sensörü bulunmadığından\nSen-Alert çalıştırılamıyor.");
        }
    }

    // ================= KURULUM KONTROLÜ =================

    private void refreshSetupStatus() {
        boolean notifGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        setupNotifStatus.setText(notifGranted ? "✅ Bildirim İzni: Verildi" : "❌ Bildirim İzni: Verilmedi");
        setupNotifStatus.setTextColor(notifGranted ? COLOR_GREEN : COLOR_RED);
        btnFixNotif.setVisibility(notifGranted ? View.GONE : View.VISIBLE);

        boolean batteryOk = isIgnoringBatteryOptimizations();
        setupBatteryStatus.setText(batteryOk ? "✅ Pil Optimizasyonu: Kısıtlama Yok" : "❌ Pil Optimizasyonu: Kısıtlı");
        setupBatteryStatus.setTextColor(batteryOk ? COLOR_GREEN : COLOR_RED);
        btnFixBattery.setVisibility(batteryOk ? View.GONE : View.VISIBLE);
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void openAutostartSettings() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        Intent intent = new Intent();
        try {
            if (manufacturer.contains("xiaomi")) {
                intent.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                intent.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            } else if (manufacturer.contains("oppo")) {
                intent.setComponent(new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            } else if (manufacturer.contains("vivo")) {
                intent.setComponent(new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            } else if (manufacturer.contains("samsung")) {
                intent.setComponent(new ComponentName("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"));
            } else {
                openAppSettings();
                return;
            }
            startActivity(intent);
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    // ================= BAŞLAT / DURDUR =================

    private void onToggleClicked() {
        if (!hasAccelerometer) return;
        boolean running = prefs.getBoolean("service_running", false);
        if (running) {
            stopService(new Intent(this, SensorService.class));
            WatchdogReceiver.cancel(this);
            lastAppliedState = null;
            orbView.setState(COLOR_GRAY, ShakeOrbView.LEVEL_NEUTRAL);
            stateText.setText("İZLEME DURDU");
            stateText.setTextColor(COLOR_GRAY);
            alertBar.setVisibility(View.GONE);
            btnMute.setVisibility(View.INVISIBLE);
            btnToggle.setText("BAŞLAT");
        } else {
            requestNotificationPermissionIfNeeded();
            startMonitoringService();
            btnToggle.setText("DURDUR");
        }
    }

    private void refreshToggleButtonFromState() {
        if (!hasAccelerometer) return;
        boolean running = prefs.getBoolean("service_running", false);
        btnToggle.setText(running ? "DURDUR" : "BAŞLAT");
        if (!running) {
            stateText.setText("İZLEME DURDU");
            stateText.setTextColor(COLOR_GRAY);
        }
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

    private void applyState(String state) {
        switch (state) {
            case "CALIBRATING":
                orbView.setState(COLOR_GRAY, ShakeOrbView.LEVEL_NEUTRAL);
                alertBar.setVisibility(View.VISIBLE);
                alertBar.setBackgroundColor(Color.GRAY);
                alertBar.setText("KALİBRASYON YAPILIYOR...");
                stateText.setText("KALİBRASYON");
                stateText.setTextColor(COLOR_GRAY);
                btnMute.setVisibility(View.INVISIBLE);
                break;
            case "GREEN":
                orbView.setState(COLOR_GREEN, ShakeOrbView.LEVEL_GREEN);
                alertBar.setVisibility(View.GONE);
                stateText.setText("NORMAL");
                stateText.setTextColor(COLOR_GREEN);
                btnMute.setVisibility(View.INVISIBLE);
                break;
            case "YELLOW":
                orbView.setState(COLOR_YELLOW, ShakeOrbView.LEVEL_YELLOW);
                alertBar.setVisibility(View.GONE);
                stateText.setText("SARSINTI ALGILANDI");
                stateText.setTextColor(COLOR_YELLOW);
                btnMute.setVisibility(View.VISIBLE);
                updateLastAlertText();
                break;
            case "RED":
                orbView.setState(COLOR_RED, ShakeOrbView.LEVEL_RED);
                alertBar.setVisibility(View.GONE);
                stateText.setText("GÜÇLÜ SARSINTI");
                stateText.setTextColor(COLOR_RED);
                btnMute.setVisibility(View.VISIBLE);
                updateLastAlertText();
                break;
            case "PAUSED_GRAY":
                orbView.setState(COLOR_GRAY, ShakeOrbView.LEVEL_NEUTRAL);
                alertBar.setVisibility(View.VISIBLE);
                alertBar.setBackgroundColor(Color.DKGRAY);
                alertBar.setText("CİHAZIN KONUMU DEĞİŞTİ\nİZLEME DURDURULDU");
                stateText.setText("İZLEME BEKLEMEDE");
                stateText.setTextColor(COLOR_GRAY);
                btnMute.setVisibility(View.INVISIBLE);
                break;
        }
    }

    private void updateLastAlertText() {
        String last = prefs.getString("last_alert_time", null);
        lastAlertText.setText(last != null
            ? "Son Uyarı: 🕒 " + last
            : "Son Uyarı: —");
    }
}
