package me.smerrybeta.toolanapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;

public final class NotifyNotificationFactory {
    private static final String CHANNEL_ID = "notify_service_min";
    private static final String CHANNEL_NAME = "TOOLAN background service";
    private static final int IMPORTANCE = NotificationManager.IMPORTANCE_MIN;

    private NotifyNotificationFactory() {
    }

    public static Notification create(Context context) {
        createChannelIfNeeded(context);

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("TOOLAN")
                .setContentText("Listening for server notifications")
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private static void createChannelIfNeeded(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, IMPORTANCE);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(channel);
    }
}
