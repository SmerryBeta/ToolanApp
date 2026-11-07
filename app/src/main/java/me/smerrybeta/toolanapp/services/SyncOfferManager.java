package me.smerrybeta.toolanapp.services;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import me.smerrybeta.toolanapp.MainActivity;
import me.smerrybeta.toolanapp.R;
import me.smerrybeta.toolanapp.SoundManager;
import me.smerrybeta.toolanapp.utils.ServerEndpoint;
import me.smerrybeta.toolanapp.utils.WebFileUtils;

/**
 * 电脑端"同步到手机"邀请管理器。
 *
 * 流程：ws 收到 file.sync_offer → 聚合进 pending → 前台弹确认框 / 后台发带
 * "接受"按钮的通知 → 用户接受后顺序流式下载到公共「下载」目录（通知栏显示进度）。
 * 多个邀请合并展示（接受=全部下载）；展示期间新邀请到达时原地刷新。
 */
public class SyncOfferManager {

    public static final String ACTION_SYNC_ACCEPT = "sync_offer_accept";
    public static final String ACTION_SYNC_REJECT = "sync_offer_reject";
    public static final int OFFER_NOTIFICATION_ID = 910001;
    public static final int PROGRESS_NOTIFICATION_ID = 910002;
    public static final int DONE_NOTIFICATION_ID = 910003;

    /** 一条待接收文件（来自 file.sync_offer payload.files[i]）。 */
    public static class OfferFile {
        public final String name;
        public final long size;
        public final String url; // 服务端相对路径，下载前拼本机配置的服务器地址
        public final String thumbUrl; // 图片缩略图相对路径，非图片为空
        final AtomicBoolean thumbnailRequested = new AtomicBoolean(false);
        volatile Bitmap thumbnail;

        OfferFile(String name, long size, String url, String thumbUrl) {
            this.name = name;
            this.size = size;
            this.url = url;
            this.thumbUrl = thumbUrl;
        }

        public Bitmap getThumbnail() {
            return thumbnail;
        }
    }

    /** 前台展示回调：MainActivity 注册，主线程调用。 */
    public interface SyncUiCallback {
        void showSyncOffer(SyncOfferManager manager, List<OfferFile> files);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadIO = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailIO = Executors.newSingleThreadExecutor();
    private final List<OfferFile> pending = new ArrayList<>();
    private final AtomicBoolean presenting = new AtomicBoolean(false);
    private volatile SyncUiCallback uiCallback;
    private volatile boolean uiForeground = false;
    private volatile int lastProgressPercent = -1;

    public SyncOfferManager(Context appContext) {
        this.context = appContext.getApplicationContext();
    }

    public void setUiCallback(SyncUiCallback callback) {
        uiCallback = callback;
    }

    public void setUiForeground(boolean foreground) {
        uiForeground = foreground;
    }

    /** ws 工作线程进入：解析 payload 后切主线程聚合展示。 */
    public void onOffer(JSONObject payload) {
        if (payload == null) {
            return;
        }
        List<OfferFile> files = new ArrayList<>();
        try {
            JSONArray arr = payload.optJSONArray("files");
            if (arr == null) {
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject f = arr.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                String name = f.optString("name", "");
                String url = f.optString("url", "");
                if (name.isEmpty() || url.isEmpty()) {
                    continue;
                }
                files.add(new OfferFile(name, f.optLong("size", 0), url, f.optString("thumbUrl", "")));
            }
        } catch (Exception ignored) {
        }
        if (files.isEmpty()) {
            return;
        }
        main.post(() -> {
            pending.addAll(files);
            presenting.set(true);
            present();
            loadThumbnails(files);
        });
    }

    /** 缩略图在 IO 线程取回后原地刷新弹框/通知；失败静默保持文件清单展示。 */
    private void loadThumbnails(List<OfferFile> files) {
        thumbnailIO.execute(() -> {
            String baseUrl = ServerEndpoint.httpBaseUrlFromPrefs();
            for (OfferFile f : files) {
                if (f.thumbUrl == null || f.thumbUrl.isEmpty()
                        || !f.thumbnailRequested.compareAndSet(false, true)) {
                    continue;
                }
                try {
                    String url = ServerEndpoint.appendPath(baseUrl, f.thumbUrl);
                    if (url.isEmpty()) {
                        continue;
                    }
                    Bitmap bitmap = WebFileUtils.fetchBitmap(url);
                    main.post(() -> {
                        f.thumbnail = bitmap;
                        if (presenting.get() && !pending.isEmpty()) {
                            present();
                        }
                    });
                } catch (Exception ignored) {
                    // 预览失败不影响接受/拒绝；原文件仍在接受后正常下载
                }
            }
        });
    }

    private void present() {
        if (pending.isEmpty()) {
            presenting.set(false);
            cancelOfferNotification();
            return;
        }
        if (uiForeground && uiCallback != null) {
            uiCallback.showSyncOffer(this, new ArrayList<>(pending));
        } else {
            showOfferNotification();
        }
    }

    /** 用户点"接受"（对话框或通知按钮）：下载全部待接收文件。 */
    public void acceptPending() {
        main.post(() -> {
            presenting.set(false);
            cancelOfferNotification();
            if (pending.isEmpty()) {
                return;
            }
            List<OfferFile> batch = new ArrayList<>(pending);
            pending.clear();
            downloadIO.execute(() -> runDownloads(batch));
        });
    }

    /** 用户点"拒绝"或滑掉通知：丢弃全部待接收文件。 */
    public void rejectPending() {
        main.post(() -> {
            presenting.set(false);
            pending.clear();
            cancelOfferNotification();
        });
    }

    /** Activity 无法弹框（正在销毁）时转走通知路径。 */
    public void fallbackToNotification() {
        main.post(this::showOfferNotification);
    }

    private void runDownloads(List<OfferFile> batch) {
        String baseUrl = ServerEndpoint.httpBaseUrlFromPrefs();
        int ok = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            OfferFile f = batch.get(i);
            final int fileIndex = i;
            lastProgressPercent = -1;
            notifyProgress(fileIndex + 1, batch.size(), f.name, 0, f.size);
            try {
                String url = ServerEndpoint.appendPath(baseUrl, f.url);
                if (url.isEmpty()) {
                    throw new IOException("服务器地址未配置");
                }
                WebFileUtils.streamToDownloads(context, url, f.name, null, f.size,
                        (done, total) -> notifyProgress(fileIndex + 1, batch.size(), f.name, done, total));
                ok++;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                failures.add(f.name + ": " + msg);
            }
        }
        notifyDone(ok, failures);
    }

    private void notifyProgress(int index, int count, String name, long done, long totalBytes) {
        int percent = -1;
        if (totalBytes > 0) {
            percent = (int) Math.min(100, done * 100 / totalBytes);
        }
        if (percent >= 0 && percent == lastProgressPercent) {
            return;
        }
        lastProgressPercent = percent;

        Notification notification = new NotificationCompat.Builder(
                context, SoundManager.NotifyType.SYNC.getChannelId())
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("正在接收 " + index + "/" + count)
                .setContentText(name + (percent >= 0 ? "（" + percent + "%）" : ""))
                .setProgress(100, Math.max(percent, 0), percent < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
        postNotification(PROGRESS_NOTIFICATION_ID, notification);
    }

    private void notifyDone(int ok, List<String> failures) {
        NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID);

        String title = failures.isEmpty()
                ? "已接收 " + ok + " 个文件"
                : "接收完成：成功 " + ok + "，失败 " + failures.size();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, SoundManager.NotifyType.NORMAL.getChannelId())
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText("已保存到「下载」目录")
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(viewDownloadsIntent());
        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String f : failures) {
                sb.append(f).append('\n');
            }
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(sb.toString().trim()));
        }
        postNotification(DONE_NOTIFICATION_ID, builder.build());
    }

    private void showOfferNotification() {
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        int shown = Math.min(pending.size(), 8);
        for (int i = 0; i < shown; i++) {
            OfferFile f = pending.get(i);
            body.append(f.name);
            if (f.size > 0) {
                body.append("（").append(formatSize(f.size)).append("）");
            }
            body.append('\n');
        }
        if (pending.size() > shown) {
            body.append("… 共 ").append(pending.size()).append(" 个文件");
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, SoundManager.NotifyType.NORMAL.getChannelId())
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle("电脑推送了 " + pending.size() + " 个文件")
                .setContentText("点击「接受」下载到手机")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openAppIntent())
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .addAction(0, "拒绝", servicePendingIntent(ACTION_SYNC_REJECT, 2))
                .addAction(0, "接受", servicePendingIntent(ACTION_SYNC_ACCEPT, 1))
                .setDeleteIntent(servicePendingIntent(ACTION_SYNC_REJECT, 4));

        OfferFile preview = null;
        for (OfferFile f : pending) {
            if (f.thumbnail != null) {
                preview = f;
                break;
            }
        }
        if (preview != null) {
            // 悬浮/横幅状态展示右侧大图标，展开后展示大图预览
            builder.setLargeIcon(preview.thumbnail)
                    .setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(preview.thumbnail)
                            .setBigContentTitle("电脑推送了 " + pending.size() + " 个文件")
                            .setSummaryText(pending.size() == 1 ? preview.name : "共 " + pending.size() + " 个文件"));
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(body.toString().trim()));
        }
        postNotification(OFFER_NOTIFICATION_ID, builder.build());
    }

    private void cancelOfferNotification() {
        NotificationManagerCompat.from(context).cancel(OFFER_NOTIFICATION_ID);
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(context, NotifyService.class).setAction(action);
        // getForegroundService：点通知按钮时服务可能已被杀（冷启动路径），
        // NotifyService.onCreate 无条件 startForeground，满足 FGS 5 秒时限
        return PendingIntent.getForegroundService(context, requestCode, intent, piFlags());
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 5, intent, piFlags());
    }

    private PendingIntent viewDownloadsIntent() {
        return PendingIntent.getActivity(context, 3,
                new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS), piFlags());
    }

    private static int piFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void postNotification(int id, Notification notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification);
        } catch (SecurityException e) {
            android.util.Log.w("SyncOffer", "通知权限缺失: " + e.getMessage());
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
