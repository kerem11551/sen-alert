package com.senalert.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    public static final String PREFS = "SenAlertPrefs";

    private SharedPreferences prefs;
    private SeekBar seekSensitivity;
    private TextView tvSensitivityValue;

    private CheckBox cbYellowSound, cbYellowVib, cbYellowFlash;
    private CheckBox cbRedSound, cbRedVib, cbRedFlash, cbRedWake;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        seekSensitivity    = findViewById(R.id.seekSensitivity);
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue);

        cbYellowSound = findViewById(R.id.cbYellowSound);
        cbYellowVib   = findViewById(R.id.cbYellowVib);
        cbYellowFlash = findViewById(R.id.cbYellowFlash);

        cbRedSound = findViewById(R.id.cbRedSound);
        cbRedVib   = findViewById(R.id.cbRedVib);
        cbRedFlash = findViewById(R.id.cbRedFlash);
        cbRedWake  = findViewById(R.id.cbRedWake);

        int savedSensitivity = prefs.getInt("sensitivity", 5);
        seekSensitivity.setMax(9);
        seekSensitivity.setProgress(savedSensitivity - 1);
        tvSensitivityValue.setText(String.valueOf(savedSensitivity));

        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 1;
                tvSensitivityValue.setText(String.valueOf(value));
                prefs.edit().putInt("sensitivity", value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        bindCheckbox(cbYellowSound, "yellow_sound", true);
        bindCheckbox(cbYellowVib,   "yellow_vibration", true);
        bindCheckbox(cbYellowFlash, "yellow_flash", false);

        bindCheckbox(cbRedSound, "red_sound", true);
        bindCheckbox(cbRedVib,   "red_vibration", true);
        bindCheckbox(cbRedFlash, "red_flash", true);
        bindCheckbox(cbRedWake,  "red_wake_screen", true);

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(SettingsActivity.this, "Ayarlar kaydedildi ✓", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    private void bindCheckbox(CheckBox cb, final String key, boolean defaultValue) {
        cb.setChecked(prefs.getBoolean(key, defaultValue));
        cb.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean(key, checked).apply());
    }
}
