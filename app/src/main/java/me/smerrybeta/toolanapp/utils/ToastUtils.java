package me.smerrybeta.toolanapp.utils;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import me.smerrybeta.toolanapp.R;

public class ToastUtils {
    
    public static void showSuccessToast(Context context, String message) {
        showCustomToast(context, message, R.drawable.ic_check_circle);
    }
    
    public static void showErrorToast(Context context, String message) {
        showCustomToast(context, message, R.drawable.ic_error);
    }
    
    public static void showWarningToast(Context context, String message) {
        showCustomToast(context, message, R.drawable.ic_warning);
    }
    
    public static void showInfoToast(Context context, String message) {
        showCustomToast(context, message, R.drawable.ic_info);
    }
    
    private static void showCustomToast(Context context, String message, int iconResId) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View layout = inflater.inflate(R.layout.custom_toast, null);
        
        ImageView icon = layout.findViewById(R.id.toast_icon);
        TextView text = layout.findViewById(R.id.toast_text);
        
        text.setText(message);
        icon.setImageResource(iconResId);
        
        Toast toast = new Toast(context);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setView(layout);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }
}
