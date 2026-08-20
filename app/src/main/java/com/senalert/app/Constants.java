package com.senalert.app;

public class Constants {
    public static final String CHANNEL_ID   = "SEN_ALERT_CHANNEL";

    // Service -> Activity (gerçek durum güncellemesi - V1)
    public static final String ACTION_STATE = "com.senalert.app.STATE";
    public static final String EXTRA_STATE  = "state";
    public static final String EXTRA_SCORE  = "score";
    public static final String EXTRA_X      = "x";
    public static final String EXTRA_Y      = "y";
    public static final String EXTRA_Z      = "z";
    public static final String EXTRA_DXY    = "dxy";
    public static final String EXTRA_DZ     = "dz";

    // Activity -> Service (kontrol komutları)
    public static final String ACTION_MUTE        = "com.senalert.app.MUTE";
    public static final String ACTION_RECALIBRATE = "com.senalert.app.RECALIBRATE";
    public static final String ACTION_SAVE_CSV    = "com.senalert.app.SAVE_CSV";

    // Service -> Activity (V1.1 gölge motor karşılaştırma verisi - sadece Test Modu açıkken)
    public static final String ACTION_SHADOW        = "com.senalert.app.SHADOW";
    public static final String EXTRA_SXY            = "sxy";
    public static final String EXTRA_SXYZ           = "sxyz";
    public static final String EXTRA_STATE_XY       = "state_xy";
    public static final String EXTRA_STATE_XYZ      = "state_xyz";
    public static final String EXTRA_XY_YELLOW_MS   = "xy_yellow_ms";
    public static final String EXTRA_XY_RED_MS      = "xy_red_ms";
    public static final String EXTRA_XYZ_YELLOW_MS  = "xyz_yellow_ms";
    public static final String EXTRA_XYZ_RED_MS     = "xyz_red_ms";

    // Service -> Activity (CSV kaydetme sonucu)
    public static final String ACTION_CSV_SAVED   = "com.senalert.app.CSV_SAVED";
    public static final String EXTRA_CSV_FILENAME = "csv_filename";

    // Service -> Activity (V1.2 fiziksel kalibrasyon ilerlemesi)
    public static final String ACTION_CALIB_PROGRESS = "com.senalert.app.CALIB_PROGRESS";
    public static final String EXTRA_CALIB_PHASE     = "calib_phase";       // NOISE / TAPS / DONE / FAILED
    public static final String EXTRA_CALIB_TAPS_DONE = "calib_taps_done";   // 0-3
    public static final String EXTRA_CALIB_SECONDS_LEFT = "calib_seconds_left";
    public static final String EXTRA_CALIB_DEVICE_FACTOR = "calib_device_factor";
    public static final String EXTRA_CALIB_QUALITY   = "calib_quality";     // GOOD / LOW
}
