package me.smerrybeta.toolanapp.services;

import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import me.smerrybeta.toolanapp.R;
import me.smerrybeta.toolanapp.SPUtils;
import me.smerrybeta.toolanapp.SoundManager;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import fi.iki.elonen.NanoHTTPD;

/**
 * 本地 HTTP 服务
 * 作用�?
 * 1. 监听局域网请求
 * 2. 接收 JSON
 * 3. 转交给通知系统
 */
public class NotifyHttpServer extends NanoHTTPD {

    private final Context context;

    public NotifyHttpServer(Context context) {
        super(SPUtils.getPrefs().getInt("port", 1225));
        this.context = context;
    }

    @Override
    public void start() throws IOException {
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        System.out.println("Http 服务器已关闭");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String clientIp = session.getRemoteIpAddress();

        // 1️⃣ 拦公�?IP
        if (!this.isLanIp(clientIp)) {
            return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "application/json",
                    "{\"error\":\"forbidden\"}"
            );
        }

        // 2️⃣ Token 校验
        String auth = session.getHeaders().get("authorization");
        if (auth == null || !auth.equals("Bearer " + SPUtils.getPrefs().getString("API_TOKEN", "YOUR_SECRET_TOKEN"))) {
            return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    "{\"error\":\"unauthorized\"}"
            );
        }

        // 只处�?POST /notify
        if (Method.POST.equals(session.getMethod())
                &&
                "/notify".equals(session.getUri())) {

            try {
                // 解析 POST body
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);

                String json = body.get("postData");
                assert json != null;
                JSONObject obj = new JSONObject(json);

                // �?JSON 里取字段
                String title = obj.optString("title", "New message");
                String content = obj.optString("content", "");
                String importance = obj.optString("importance", "");

                // 直接发系统通知
                this.sendSystemNotification(title, content, importance);

                JSONObject resp = new JSONObject();
                resp.put("success", true);
                resp.put("msg", "received");

                return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        resp.toString()
                );

            } catch (Exception e) {
                e.printStackTrace();

                return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "error"
                );
            }
        }

        return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "404"
        );
    }

    /**
     * 发送系统通知
     */
    private void sendSystemNotification(String title, String content, String importance) {
        // Android 13+ 才需要检�?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
                Log.w("Notify", "Notification skipped");
                return;
            }
        }

        SoundManager.NotifyType[] values =
                SoundManager.NotifyType.values();

        SoundManager.NotifyType type = SoundManager.NotifyType.NORMAL;
        for (SoundManager.NotifyType value : values) {
            if (Objects.equals(value.getName(), importance)) {
                type = value;
            }
        }

        String channelId = type.getChannelId();

        Notification notification =
                new NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_stat_name)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setAutoCancel(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏可见
                        .setPriority(NotificationCompat.PRIORITY_HIGH) // 高优先级
                        .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE) // 启用音效和震�?
                        .build();

        NotificationManagerCompat.from(context)
                .notify((int) System.currentTimeMillis(), notification);
    }


    private boolean isLanIp(String ip) {
        return ip.startsWith("192.168.")
                || ip.startsWith("10.")
                || ip.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");
    }

    public static String getLocalIP(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return "WiFi disabled";
        int ipInt = wifiManager.getConnectionInfo().getIpAddress();
        if (ipInt == 0) return "未连�?WiFi";
        return String.format("%s.%s.%s.%s", (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
    }

    public int getPort() {
        return SPUtils.getPrefs().getInt("port", 1225);
    }
}
