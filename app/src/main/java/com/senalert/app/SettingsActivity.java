package com.senalert.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private SeekBar seekSensitivity;
    private TextView tvSensitivityValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("SenAlertPrefs", MODE_PRIVATE);

        seekSensitivity = findViewById(R.id.seekSensitivity);
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue);

        int savedSensitivity = prefs.getInt("sensitivity", 5);
        seekSensitivity.setProgress(savedSensitivity);
        tvSensitivityValue.setText(String.valueOf(savedSensitivity));

        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSensitivityValue.setText(String.valueOf(progress));
                prefs.edit().putInt("sensitivity", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }
}
