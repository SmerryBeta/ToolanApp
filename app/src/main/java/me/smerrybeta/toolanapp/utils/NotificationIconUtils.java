package me.smerrybeta.toolanapp.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.drawable.DrawableCompat;

import me.smerrybeta.toolanapp.R;

public class NotificationIconUtils {
    
    /**
     * �?JPG 图片转换为适合作为通知图标�?Drawable
     * @param context 上下�?     * @return 适合作为通知图标�?Drawable
     */
    public static Drawable getNotificationIconFromJpg(Context context) {
        try {
            // 读取 JPG 图片
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo);
            
            int size = (int) (24 * context.getResources().getDisplayMetrics().density);
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, size, size, true);
            
            // 转换�?Drawable
            Drawable drawable = new BitmapDrawable(context.getResources(), scaledBitmap);
            
            // 设置为白色（通知栏图标通常显示为白色）
            DrawableCompat.setTint(drawable, 0xFFFFFFFF);
            
            return drawable;
        } catch (Exception e) {
            return context.getDrawable(R.drawable.ic_stat_name);
        }
    }
}
