package com.senalert.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    // MainActivity.java bu sabiti "SettingsActivity.PREFS" olarak kullanıyor
    public static final String PREFS = "SenAlertPrefs";

    private SharedPreferences prefs;
    private SeekBar seekSensitivity;
    private TextView tvSensitivityValue;
    private CheckBox cbYellowVib, cbOrangeVib, cbRedVib;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        seekSensitivity    = findViewById(R.id.seekSensitivity);
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue);
        cbYellowVib         = findViewById(R.id.cbYellowVib);
        cbOrangeVib          = findViewById(R.id.cbOrangeVib);
        cbRedVib             = findViewById(R.id.cbRedVib);

        // ---- Hassasiyet: 1-20 arası (MainActivity.updateSensitivity ile aynı ölçek) ----
        int savedSensitivity = prefs.getInt("sensitivity", 10);
        seekSensitivity.setMax(19); // 0..19 -> gösterimde +1
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

        // ---- Renk bazlı titreşim tercihleri (MainActivity.triggerFeedback bunları okuyor) ----
        cbYellowVib.setChecked(prefs.getBoolean("yellow_vibration", true));
        cbOrangeVib.setChecked(prefs.getBoolean("orange_vibration", true));
        cbRedVib.setChecked(prefs.getBoolean("red_vibration", true));

        cbYellowVib.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("yellow_vibration", checked).apply());
        cbOrangeVib.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("orange_vibration", checked).apply());
        cbRedVib.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("red_vibration", checked).apply());

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
    }
}
