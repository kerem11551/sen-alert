
package com.senalert.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class SensorService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "SenAlertChannel";
    private SensorManager sensorManager;
    private Sensor accelerometer;

    private float lastX, lastY, lastZ;
    private boolean firstReading = true;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification("CALIBRATING..."));

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        if (firstReading) {
            lastX = x; lastY = y; lastZ = z;
            firstReading = false;
            return;
        }

        float dxy = (float) Math.sqrt(Math.pow(x - lastX, 2) + Math.pow(y - lastY, 2));
        float dz = Math.abs(z - lastZ);

        lastX = x; lastY = y; lastZ = z;

        String status;
        if (dxy < 0.1f && dz < 0.1f) {
            status = "NO MOVEMENT";
        } else if (dxy < 0.5f && dz < 0.5f) {
            status = "SLIGHT MOVEMENT";
        } else if (dxy < 2.0f && dz < 2.0f) {
            status = "MODERATE MOVEMENT";
        } else {
            status = "STRONG MOVEMENT";
        }

        Intent broadcast = new Intent("SENSOR_UPDATE");
        broadcast.putExtra("status", status);
        broadcast.putExtra("x", x);
        broadcast.putExtra("y", y);
        broadcast.putExtra("z", z);
        broadcast.putExtra("dxy", dxy);
        broadcast.putExtra("dz", dz);
        sendBroadcast(broadcast);

        updateNotification(status);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Sen-Alert", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sen-Alert")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .build();
    }

    private void updateNotification(String status) {
        getSystemService(NotificationManager.class)
            .notify(1, buildNotification(status));
    }
}
