
package com.senalert.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private TextView tvValues;
    private boolean isRunning = false;

    private BroadcastReceiver sensorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            float x = intent.getFloatExtra("x", 0);
            float y = intent.getFloatExtra("y", 0);
            float z = intent.getFloatExtra("z", 0);
            float dxy = intent.getFloatExtra("dxy", 0);
            float dz = intent.getFloatExtra("dz", 0);

            tvStatus.setText(status);
            tvValues.setText(String.format("X: %.2f  Y: %.2f  Z: %.2f\nΔXY: %.2f  ΔZ: %.2f", x, y, z, dxy, dz));

            if (status.equals("NO MOVEMENT")) {
                findViewById(R.id.mainLayout).setBackgroundColor(0xFF00FF00);
            } else if (status.equals("SLIGHT MOVEMENT")) {
                findViewById(R.id.mainLayout).setBackgroundColor(0xFFFFFF00);
            } else if (status.equals("MODERATE MOVEMENT")) {
                findViewById(R.id.mainLayout).setBackgroundColor(0xFFFF8C00);
            } else if (status.equals("STRONG MOVEMENT")) {
                findViewById(R.id.mainLayout).setBackgroundColor(0xFFFF0000);
            } else {
                findViewById(R.id.mainLayout).setBackgroundColor(0xFF888888);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvValues = findViewById(R.id.tvValues);

        findViewById(R.id.btnStart).setOnClickListener(v -> toggleService());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnSave).setOnClickListener(v -> saveLog());
    }

    private void toggleService() {
        Intent intent = new Intent(this, SensorService.class);
        if (!isRunning) {
            ContextCompat.startForegroundService(this, intent);
            isRunning = true;
        } else {
            stopService(intent);
            isRunning = false;
        }
    }

    private void saveLog() {
        // Log kaydetme - ileride eklenecek
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(sensorReceiver, new IntentFilter("SENSOR_UPDATE"),
            Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(sensorReceiver);
    }
}
