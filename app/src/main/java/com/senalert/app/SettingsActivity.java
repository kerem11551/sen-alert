package com.senalert.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    public static final String PREFS = "SenAlertPrefs";

    private SharedPreferences prefs;
    private SeekBar seekSensitivity;
    private TextView tvSensitivityValue;

    private CheckBox cbSound, cbVib, cbFlash;
    private RadioGroup durationGroup;
    private Switch switchTestMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        seekSensitivity    = findViewById(R.id.seekSensitivity);
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue);
        cbSound  = findViewById(R.id.cbSound);
        cbVib    = findViewById(R.id.cbVib);
        cbFlash  = findViewById(R.id.cbFlash);
        durationGroup = findViewById(R.id.durationGroup);
        switchTestMode = findViewById(R.id.switchTestMode);

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

        bindCheckbox(cbSound, "alert_sound", true);
        bindCheckbox(cbVib,   "alert_vibration", true);
        bindCheckbox(cbFlash, "alert_flash", true);

        int savedDuration = prefs.getInt("alert_duration_sec", 5);
        int checkedId = savedDuration == 3 ? R.id.dur3 : savedDuration == 10 ? R.id.dur10 : R.id.dur5;
        durationGroup.check(checkedId);
        durationGroup.setOnCheckedChangeListener((group, checkedIdNow) -> {
            int seconds = checkedIdNow == R.id.dur3 ? 3 : checkedIdNow == R.id.dur10 ? 10 : 5;
            prefs.edit().putInt("alert_duration_sec", seconds).apply();
        });

        switchTestMode.setChecked(prefs.getBoolean("test_mode_enabled", false));
        switchTestMode.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("test_mode_enabled", checked).apply());

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
