package me.smerrybeta.toolanapp.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import me.smerrybeta.toolanapp.SPUtils;

/**
 * 开机自启广播接收器
 * 用于在设备重启后自动启动通知服务
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "设备启动完成，检查是否需要启动通知服务");

            // 初始�?SPUtils
            SPUtils.init(context);

            // 检查用户是否开启了通知功能
            if (SPUtils.getPrefs().getBoolean("notify", false)) {
                Log.i(TAG, "用户已开启通知功能，启�?NotifyService");
                startNotifyService(context);
            } else {
                Log.i(TAG, "Log message");
            }
        }
    }

    private void startNotifyService(Context context) {
        Intent serviceIntent = new Intent(context, NotifyService.class);
        serviceIntent.setAction(NotifyService.ACTION_ENABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
