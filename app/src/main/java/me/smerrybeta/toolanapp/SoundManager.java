package me.smerrybeta.toolanapp;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

import androidx.core.app.NotificationCompat;

public class SoundManager {

    private static final String CH_NORMAL = "notify_normal";

    private static final String CH_IMPORTANT = "notify_important";

    private static final String CH_URGENT = "notify_urgent";

    private static final String CH_SILENT = "notify_silent";

    /** 同步下载的进度/完成通知：低优先级无声，避免下载中反复打扰。 */
    private static final String CH_SYNC = "sync_progress";

    private final Context context;

    /**
     * 提示音类�?
     */
    public enum NotifyType {
        NORMAL(CH_NORMAL, R.raw.notify_normal, NotificationManager.IMPORTANCE_DEFAULT, "普通通知"),
        IMPORTANT(CH_IMPORTANT, R.raw.notify_heavy, NotificationManager.IMPORTANCE_HIGH, "重要通知"),
        URGENT(CH_URGENT, R.raw.notify_alarm, NotificationManager.IMPORTANCE_HIGH, "紧急通知"),
        SYNC(CH_SYNC, 0, NotificationManager.IMPORTANCE_LOW, "同步下载"),
        SILENT(CH_SILENT, 0, NotificationManager.IMPORTANCE_LOW, "静默通知");

        private final String channelId;

        private final int soundRes;

        private final int importance;

        private final String name;

        NotifyType(String channelId, int soundRes, int importance, String name) {
            this.channelId = channelId;
            this.soundRes = soundRes;
            this.importance = importance;
            this.name = name;
        }

        public String getChannelId() {
            return channelId;
        }

        public int getSoundRes() {
            return soundRes;
        }

        public int getImportance() {
            return importance;
        }

        public String getName() {
            return name;
        }
    }

    public SoundManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void createAllChannel() {
        for (NotifyType value : NotifyType.values()) {
            createSoundChannel(
                    value.getChannelId(),
                    value.getName(),
                    value.getImportance(),
                    value.getSoundRes()
            );
        }
    }

    private void createSoundChannel(
            String id,
            String name,
            int importance,
            int soundRes
    ) {
        NotificationChannel ch =
                new NotificationChannel(id, name, importance);

        // 启用锁屏显示（使�?VISIBILITY_PUBLIC 确保锁屏可见�?
        ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // 启用震动（同步下载渠道不震，进度通知反复更新会打扰）
        if (!CH_SYNC.equals(id)) {
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 250, 200, 250});
        }
        
        if (soundRes != 0) {
            Uri sound = Uri.parse(
                    ContentResolver.SCHEME_ANDROID_RESOURCE + "://"
                            + context.getPackageName() + "/raw/" + soundRes
            );

            AudioAttributes attrs = new AudioAttributes.
                    Builder().
                    setUsage(AudioAttributes.USAGE_NOTIFICATION).
                    build();

            ch.setSound(sound, attrs);
        } else {
            ch.setSound(null, null); // 静默
        }

        context.getSystemService(NotificationManager.class)
                .createNotificationChannel(ch);
    }
}

