package com.senalert.app;

public class Constants {
    public static final String CHANNEL_ID   = "SEN_ALERT_CHANNEL";

    // Service -> Activity (durum güncellemesi)
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
    public static final String ACTION_RECALIBRATE  = "com.senalert.app.RECALIBRATE";
}
