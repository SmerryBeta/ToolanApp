package me.smerrybeta.toolanapp.services;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import me.smerrybeta.toolanapp.NotifyNotificationFactory;
import me.smerrybeta.toolanapp.SPUtils;
import me.smerrybeta.toolanapp.SoundManager;

public class NotifyService extends Service {
    private static final String TAG = "NotifyService";
    private static final String WAKE_LOCK_TAG = "NotifyService:WakeLock";
    private static final int FOREGROUND_NOTIFICATION_ID = 1;

    public static final String ACTION_ENABLE = "notify_enable";
    public static final String ACTION_DISABLE = "notify_disable";
    public static final String ACTION_RESTART = "notify_restart";

    private final IBinder binder = new LocalBinder();
    private NotifyWebSocketClient webSocketClient;
    private SyncOfferManager syncOfferManager;
    private PowerManager.WakeLock wakeLock;
    private ConnectionStatusCallback statusCallback;

    public interface ConnectionStatusCallback {
        void onConnected();

        void onDisconnected(String reason);

        void onError(String error);
    }

    public class LocalBinder extends Binder {
        public NotifyService getService() {
            return NotifyService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SPUtils.init(getApplicationContext());
        Log.i(TAG, "NotifyService created");
        acquireWakeLock();
        new SoundManager(this).createAllChannel();

        Notification notification = NotifyNotificationFactory.create(this);
        startForeground(FOREGROUND_NOTIFICATION_ID, notification);

        syncOfferManager = new SyncOfferManager(getApplicationContext());
        webSocketClient = new NotifyWebSocketClient(getApplicationContext());
        webSocketClient.setSyncOfferCallback(payload -> syncOfferManager.onOffer(payload));
        webSocketClient.setConnectionCallback(new NotifyWebSocketClient.ConnectionCallback() {
            @Override
            public void onConnected() {
                if (statusCallback != null) {
                    statusCallback.onConnected();
                }
            }

            @Override
            public void onDisconnected(String reason) {
                if (statusCallback != null) {
                    statusCallback.onDisconnected(reason);
                }
            }

            @Override
            public void onError(String error) {
                if (statusCallback != null) {
                    statusCallback.onError(error);
                }
            }
        });

        if (SPUtils.getPrefs().getBoolean("notify", false)) {
            enable();
        }
    }

    public void setStatusCallback(ConnectionStatusCallback callback) {
        this.statusCallback = callback;
    }

    /** 同步邀请管理器（可能为 null：服务未完成创建时）。 */
    public SyncOfferManager getSyncOfferManager() {
        return syncOfferManager;
    }

    public void enable() {
        SPUtils.getPrefs().edit()
                .putBoolean("notify", true)
                .apply();

        if (webSocketClient != null && !webSocketClient.isConnected()) {
            webSocketClient.connect();
        }
    }

    public void disable() {
        SPUtils.getPrefs().edit()
                .putBoolean("notify", false)
                .apply();

        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
    }

    public void reconnect() {
        if (webSocketClient == null) {
            return;
        }
        webSocketClient.disconnect();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (SPUtils.getPrefs().getBoolean("notify", false)) {
                webSocketClient.connect();
            }
        }, 500);
    }

    public void forceReconnect() {
        if (webSocketClient != null) {
            webSocketClient.forceReconnect();
        }
    }

    public int getReconnectAttempts() {
        return webSocketClient != null ? webSocketClient.getReconnectAttempts() : 0;
    }

    public int getMaxReconnectAttempts() {
        return webSocketClient != null ? webSocketClient.getMaxReconnectAttempts() : 0;
    }

    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isConnected();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (SPUtils.getPrefs().getBoolean("notify", false)) {
            scheduleRestart();
        }
    }

    @Override
    public void onDestroy() {
        boolean shouldRestart = SPUtils.getPrefs().getBoolean("notify", false);
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
        releaseWakeLock();
        if (shouldRestart) {
            scheduleRestart();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        acquireWakeLock();

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_DISABLE.equals(action)) {
            disable();
            stopForeground(true);
            stopSelf();
        } else if (SyncOfferManager.ACTION_SYNC_ACCEPT.equals(action)) {
            // 通知栏"接受"按钮（getForegroundService 冷启动路径下 onCreate 已 startForeground）
            if (syncOfferManager != null) {
                syncOfferManager.acceptPending();
            }
        } else if (SyncOfferManager.ACTION_SYNC_REJECT.equals(action)) {
            if (syncOfferManager != null) {
                syncOfferManager.rejectPending();
            }
        } else if (ACTION_ENABLE.equals(action) || ACTION_RESTART.equals(action)) {
            enable();
        } else if (SPUtils.getPrefs().getBoolean("notify", false)) {
            enable();
        }

        return START_STICKY;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.acquire(10 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void scheduleRestart() {
        Intent restartIntent = new Intent(this, NotifyService.class);
        restartIntent.setAction(ACTION_RESTART);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getService(this, 0, restartIntent, flags);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        long triggerAt = SystemClock.elapsedRealtime() + 1000;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            } else {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Exact alarm denied, falling back to inexact restart alarm", e);
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
            );
        }
        Log.i(TAG, "NotifyService restart scheduled");
    }
}
