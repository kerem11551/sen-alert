package com.senalert.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

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
    private Button btnMute;
    private Button btnToggle;
    private ShakeOrbView orbView;
    private EkgGraphView ekgView;

    private SharedPreferences prefs;

    private static final int COLOR_GREEN  = Color.parseColor("#22E88A");
    private static final int COLOR_YELLOW = Color.parseColor("#FFD60A");
    private static final int COLOR_RED    = Color.parseColor("#FF3B30");
    private static final int COLOR_GRAY   = Color.parseColor("#8A9C9A");

    private String lastAppliedState = null;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(Constants.EXTRA_STATE);
            int score = intent.getIntExtra(Constants.EXTRA_SCORE, 0);
            float x = intent.getFloatExtra(Constants.EXTRA_X, 0);
            float y = intent.getFloatExtra(Constants.EXTRA_Y, 0);
            float z = intent.getFloatExtra(Constants.EXTRA_Z, 0);
            float dXY = intent.getFloatExtra(Constants.EXTRA_DXY, 0);
            float dZ = intent.getFloatExtra(Constants.EXTRA_DZ, 0);

            ekgView.pushSample(score / 100f);

            lastBroadcastText.setText("Son Broadcast: " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));

            sensorText.setText(String.format(
                Locale.US, "X: %.2f  Y: %.2f  Z: %.2f\nΔXY: %.2f  ΔZ: %.2f\nHassasiyet: %d",
                x, y, z, dXY, dZ, prefs.getInt("sensitivity", 5)
            ));

            if (!state.equals(lastAppliedState)) {
                lastAppliedState = state;
                applyState(state);
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

        prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);

        requestNotificationPermissionIfNeeded();

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
                btnRecalibrate.setVisibility(View.GONE);
                sendControl(Constants.ACTION_RECALIBRATE);
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

        orbView.setState(COLOR_GRAY, ShakeOrbView.LEVEL_NEUTRAL);
        updateLastAlertText();
        refreshToggleButtonFromState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(stateReceiver, new IntentFilter(Constants.ACTION_STATE));
        updateLastAlertText();
        refreshToggleButtonFromState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
    }

    private void onToggleClicked() {
        boolean running = prefs.getBoolean("service_running", false);
        if (running) {
            stopService(new Intent(this, SensorService.class));
            WatchdogReceiver.cancel(this); // kasıtlı durdurmada bekçi de iptal edilir
            lastAppliedState = null;
            orbView.setState(COLOR_GRAY, ShakeOrbView.LEVEL_NEUTRAL);
            stateText.setText("İZLEME DURDU");
            stateText.setTextColor(COLOR_GRAY);
            alertBar.setVisibility(View.GONE);
            btnMute.setVisibility(View.GONE);
            btnRecalibrate.setVisibility(View.GONE);
            btnToggle.setText("BAŞLAT");
        } else {
            requestNotificationPermissionIfNeeded();
            startMonitoringService();
            btnToggle.setText("DURDUR");
        }
    }

    private void refreshToggleButtonFromState() {
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
                btnRecalibrate.setVisibility(View.GONE);
                btnMute.setVisibility(View.GONE);
                break;
            case "GREEN":
                orbView.setState(COLOR_GREEN, ShakeOrbView.LEVEL_GREEN);
                alertBar.setVisibility(View.GONE);
                stateText.setText("SABİT");
                stateText.setTextColor(COLOR_GREEN);
                btnRecalibrate.setVisibility(View.GONE);
                btnMute.setVisibility(View.GONE);
                break;
            case "YELLOW":
                orbView.setState(COLOR_YELLOW, ShakeOrbView.LEVEL_YELLOW);
                alertBar.setVisibility(View.GONE);
                stateText.setText("SARSINTI ALGILANDI");
                stateText.setTextColor(COLOR_YELLOW);
                btnRecalibrate.setVisibility(View.GONE);
                btnMute.setVisibility(View.VISIBLE);
                updateLastAlertText();
                break;
            case "RED":
                orbView.setState(COLOR_RED, ShakeOrbView.LEVEL_RED);
                alertBar.setVisibility(View.GONE);
                stateText.setText("GÜÇLÜ SARSINTI ALGILANDI");
                stateText.setTextColor(COLOR_RED);
                btnRecalibrate.setVisibility(View.GONE);
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
                btnRecalibrate.setVisibility(View.VISIBLE);
                btnMute.setVisibility(View.GONE);
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
