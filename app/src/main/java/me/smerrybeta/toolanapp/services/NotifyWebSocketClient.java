package me.smerrybeta.toolanapp.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import me.smerrybeta.toolanapp.MainActivity;
import me.smerrybeta.toolanapp.R;
import me.smerrybeta.toolanapp.SPUtils;
import me.smerrybeta.toolanapp.SoundManager;
import me.smerrybeta.toolanapp.utils.ServerEndpoint;
import me.smerrybeta.toolanapp.utils.crypto.A1Cipher;
import me.smerrybeta.toolanapp.utils.crypto.CryptoCipher;
import me.smerrybeta.toolanapp.utils.crypto.CryptoSettings;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

public class NotifyWebSocketClient {
    private static final String TAG = "NotifyWebSocket";
    private static final int BASE_RECONNECT_DELAY_MS = 3000;
    private static final int MAX_RECONNECT_DELAY_MS = 60000;
    private static final int UNLIMITED_RECONNECTS = 0;

    private final Context context;
    private final Handler reconnectHandler;
    // 每次 connect() 时按设置构建：加密开启时非空，消息收发按信封解密
    private CryptoCipher cipher;
    private WebSocketClient webSocketClient;
    private Runnable reconnectRunnable;
    private boolean isRunning = false;
    private boolean isConnecting = false;
    private boolean shouldReconnect = false;
    private int reconnectAttempts = 0;
    private ConnectionCallback connectionCallback;

    public interface ConnectionCallback {
        void onConnected();

        void onDisconnected(String reason);

        void onError(String error);
    }

    /** file.sync_offer 事件回调（电脑端勾选"同步到手机"后推送的文件邀请）。 */
    public interface SyncOfferCallback {
        void onOffer(org.json.JSONObject payload);
    }

    private SyncOfferCallback syncOfferCallback;

    public void setSyncOfferCallback(SyncOfferCallback callback) {
        this.syncOfferCallback = callback;
    }

    public NotifyWebSocketClient(Context context) {
        this.context = context.getApplicationContext();
        this.reconnectHandler = new Handler(Looper.getMainLooper());
    }

    public void setConnectionCallback(ConnectionCallback callback) {
        this.connectionCallback = callback;
    }

    public void connect() {
        if (isRunning || isConnecting) {
            return;
        }

        String wsUrl = ServerEndpoint.websocketUrlFromPrefs();
        if (wsUrl.isEmpty()) {
            notifyError("Server address not configured");
            return;
        }

        // 加密开启时通过查询参数协商（浏览器 WS 无法带自定义头，服务端统一
        // 识别 X-Toolan-Crypto 头或 ?crypto= 参数），服务端将下发 A1 信封帧
        cipher = CryptoSettings.cipherOrNull();
        if (cipher != null) {
            wsUrl += (wsUrl.contains("?") ? "&" : "?") + "crypto=" + cipher.version();
        }
        // 声明设备身份：服务端据此把 file.sync_offer 等定向事件只发给安卓客户端
        wsUrl += (wsUrl.contains("?") ? "&" : "?") + "device=android";

        shouldReconnect = true;
        isConnecting = true;
        removeReconnectCallback();

        try {
            URI uri = new URI(wsUrl);
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.i(TAG, "WebSocket connected: " + uri);
                    isRunning = true;
                    isConnecting = false;
                    reconnectAttempts = 0;
                    notifyConnected();
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.i(TAG, "WebSocket closed: " + reason + " (code: " + code + ")");
                    isRunning = false;
                    isConnecting = false;
                    notifyDisconnected(reason);
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error: " + ex.getMessage(), ex);
                    isRunning = false;
                    isConnecting = false;
                    notifyError(ex.getMessage());
                    scheduleReconnect();
                }
            };
            webSocketClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage(), e);
            isRunning = false;
            isConnecting = false;
            notifyError("Connect failed: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void handleMessage(String message) {
        try {
            // 加密通道：整条 JSON 事件被包在 A1 信封里，先解密。
            // 解密失败（密钥不符/被篡改）静默丢弃本条，不触发重连风暴。
            if (cipher != null && A1Cipher.looksEncrypted(message)) {
                try {
                    message = new String(cipher.decrypt(message), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    Log.w(TAG, "解密 WS 消息失败，已丢弃: " + e.getMessage());
                    return;
                }
            }

            JSONObject event = new JSONObject(message);
            String eventType = event.optString("type", "");
            // 文件同步邀请走独立回调（弹接受/拒绝 UI），不进通用系统通知白名单
            if ("file.sync_offer".equals(eventType)) {
                JSONObject payload = event.optJSONObject("payload");
                if (payload != null && syncOfferCallback != null) {
                    syncOfferCallback.onOffer(payload);
                }
                return;
            }
            if (!shouldNotify(eventType)) {
                return;
            }

            JSONObject payload = event.optJSONObject("payload");
            JSONObject source = payload != null ? payload : event;
            String title = source.optString("title", defaultTitle(eventType));
            String content = source.optString("msg", source.optString("content", ""));
            String importance = source.optString("importance", "normal");

            if (content.trim().isEmpty()) {
                return;
            }

            Log.d(TAG, "Notification event: " + eventType + " - " + title);
            sendSystemNotification(title, content, importance);
        } catch (JSONException e) {
            Log.e(TAG, "Message parse failed: " + e.getMessage());
        }
    }

    private boolean shouldNotify(String eventType) {
        return "message".equals(eventType)
                || "notification".equals(eventType)
                || "reminder.due".equals(eventType);
    }

    private String defaultTitle(String eventType) {
        if ("reminder.due".equals(eventType)) {
            return "Reminder";
        }
        return "New message";
    }

    private void sendSystemNotification(String title, String content, String importance) {
        SoundManager.NotifyType type = SoundManager.NotifyType.NORMAL;
        for (SoundManager.NotifyType value : SoundManager.NotifyType.values()) {
            if (Objects.equals(value.getName(), importance)
                    || Objects.equals(value.name().toLowerCase(Locale.US), importance.toLowerCase(Locale.US))) {
                type = value;
                break;
            }
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("navigate_to", "control_panel");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        Notification notification = new NotificationCompat.Builder(context, type.getChannelId())
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build();

        try {
            NotificationManagerCompat.from(context)
                    .notify((int) System.currentTimeMillis(), notification);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission missing: " + e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (!shouldReconnect || !SPUtils.getPrefs().getBoolean("notify", false)) {
            return;
        }

        removeReconnectCallback();
        reconnectAttempts++;
        int delay = Math.min(
                MAX_RECONNECT_DELAY_MS,
                BASE_RECONNECT_DELAY_MS * Math.min(reconnectAttempts, 20)
        );

        reconnectRunnable = () -> {
            if (!isRunning && !isConnecting && shouldReconnect) {
                connect();
            }
        };
        reconnectHandler.postDelayed(reconnectRunnable, delay);
        Log.i(TAG, "Reconnect scheduled in " + delay + "ms, attempt " + reconnectAttempts);
    }

    public void disconnect() {
        shouldReconnect = false;
        isRunning = false;
        isConnecting = false;
        reconnectAttempts = 0;
        removeReconnectCallback();

        if (webSocketClient != null) {
            try {
                webSocketClient.close();
            } catch (Exception e) {
                Log.e(TAG, "Close WebSocket failed: " + e.getMessage());
            }
            webSocketClient = null;
        }
    }

    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isOpen();
    }

    public void resetReconnectAttempts() {
        reconnectAttempts = 0;
    }

    public void forceReconnect() {
        disconnect();
        resetReconnectAttempts();
        shouldReconnect = true;
        reconnectHandler.postDelayed(this::connect, 300);
    }

    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    public int getMaxReconnectAttempts() {
        return UNLIMITED_RECONNECTS;
    }

    private void removeReconnectCallback() {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void notifyConnected() {
        if (connectionCallback != null) {
            new Handler(Looper.getMainLooper()).post(() -> connectionCallback.onConnected());
        }
    }

    private void notifyDisconnected(String reason) {
        if (connectionCallback != null) {
            new Handler(Looper.getMainLooper()).post(() -> connectionCallback.onDisconnected(reason));
        }
    }

    private void notifyError(String error) {
        if (connectionCallback != null) {
            new Handler(Looper.getMainLooper()).post(() -> connectionCallback.onError(error));
        }
    }

}
