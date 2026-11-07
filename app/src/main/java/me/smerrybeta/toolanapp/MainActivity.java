package me.smerrybeta.toolanapp;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import me.smerrybeta.toolanapp.utils.ToastUtils;
import me.smerrybeta.toolanapp.utils.NetworkScanner;
import me.smerrybeta.toolanapp.utils.ServerEndpoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import me.smerrybeta.toolanapp.databinding.ActivityMainBinding;
import me.smerrybeta.toolanapp.services.NotifyService;
import me.smerrybeta.toolanapp.services.SyncOfferManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;

/**
 * MainActivity：应用主 Activity
 * 功能�? * 1. 承载 Fragment（控制面板、设置等�? * 2. 管理通知服务连接
 * 3. 处理底部导航
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;
    private SoundManager soundManager;

    // 服务相关
    private NotifyService notifyService;
    private boolean serviceBound = false;

    // 自动扫描并重连(服务器 IP 变化时后台自愈),带冷却以控制开销
    private static final long AUTO_SCAN_COOLDOWN_MS = 30000L;
    private NetworkScanner autoScanner;
    private boolean autoConnectScanning = false;
    private long lastAutoScanTs = 0L;

    // "读取剪贴板 → 在控制面板打开"时暂存的地址,控制面板创建/显示时取走
    private String pendingWebUrl;

    // 是否处于前台(onStart/onStop 维护),供同步邀请选择弹框还是通知
    private boolean activityResumed = false;
    // 当前展示同步邀请的对话框(新邀请到达时重建刷新)
    private AlertDialog syncOfferDialog;

    /** 同步邀请的前台展示:文件清单（图片带缩略图） + 接受/拒绝（由 NotifyService 的 SyncOfferManager 主线程回调）。 */
    private final SyncOfferManager.SyncUiCallback syncUiCallback = (manager, files) -> {
        if (isFinishing() || isDestroyed()) {
            manager.setUiForeground(false);
            manager.fallbackToNotification();
            return;
        }
        if (syncOfferDialog != null && syncOfferDialog.isShowing()) {
            syncOfferDialog.dismiss();
        }
        syncOfferDialog = new AlertDialog.Builder(this)
                .setTitle("电脑推送了 " + files.size() + " 个文件")
                .setView(buildSyncOfferView(files))
                .setCancelable(false)
                .setNegativeButton("拒绝", (d, w) -> manager.rejectPending())
                .setPositiveButton("接受", (d, w) -> manager.acceptPending())
                .show();
    };

    /** 弹框内容：每行「缩略图（仅图片） + 文件名/大小」；缩略图异步取回后整框原地重建刷新。 */
    private View buildSyncOfferView(List<SyncOfferManager.OfferFile> files) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        list.setPadding(padding, dp(8), padding, 0);

        for (SyncOfferManager.OfferFile f : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 0, 0, dp(10));

            Bitmap thumbnail = f.getThumbnail();
            if (thumbnail != null) {
                ImageView image = new ImageView(this);
                int size = dp(76);
                image.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageBitmap(thumbnail);
                row.addView(image);
                LinearLayout.LayoutParams textParams =
                        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                textParams.leftMargin = dp(12);
                row.addView(buildSyncOfferText(f), textParams);
            } else {
                row.addView(buildSyncOfferText(f), new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            list.addView(row);
        }

        scroll.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView buildSyncOfferText(SyncOfferManager.OfferFile f) {
        TextView text = new TextView(this);
        String size = f.size > 0 ? "（" + SyncOfferManager.formatSize(f.size) + "）" : "";
        text.setText(f.name + size);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            NotifyService.LocalBinder binder = (NotifyService.LocalBinder) service;
            notifyService = binder.getService();
            serviceBound = true;

            if (notifyService.getSyncOfferManager() != null) {
                notifyService.getSyncOfferManager().setUiCallback(syncUiCallback);
                notifyService.getSyncOfferManager().setUiForeground(activityResumed);
            }

            notifyService.setStatusCallback(new NotifyService.ConnectionStatusCallback() {
                @Override
                public void onConnected() {
                    runOnUiThread(() -> {
                        ToastUtils.showSuccessToast(MainActivity.this, "Connected to server");
                    });
                }

                @Override
                public void onDisconnected(String reason) {
                    runOnUiThread(() -> {
                        tryAutoReconnectScan();
                        Log.d(TAG, "连接断开: " + reason);
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        tryAutoReconnectScan();
                        ToastUtils.showErrorToast(MainActivity.this, "连接错误: " + error);
                    });
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            notifyService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SPUtils.init(getBaseContext());

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 初始化声音管理器
        soundManager = new SoundManager(this);
        soundManager.createAllChannel();
        ensureNotificationPermission();

        // 设置导航
        setupNavigation();

        // 绑定服务
        bindNotifyService();

        // 检查是否需要自动扫描服务器
        checkAutoScan();
        
        // 处理通知点击跳转
        handleNotificationIntent(getIntent());
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleNotificationIntent(intent);
    }
    
    private void handleNotificationIntent(Intent intent) {
        if (intent != null && "control_panel".equals(intent.getStringExtra("navigate_to"))) {
            NavController navController = Navigation.findNavController(
                    this, R.id.nav_host_fragment_content_main);
            navController.navigate(R.id.ControlPanelFragment);
        }
    }

    private void setupNavigation() {
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment_content_main);

        appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.ControlPanelFragment, R.id.SettingsFragment)
                .build();

        // 设置底部导航
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav != null) {
            NavigationUI.setupWithNavController(bottomNav, navController);
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS
        );
    }

    private void bindNotifyService() {
        Intent intent = new Intent(this, NotifyService.class);
        
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        
        // 仅当之前已启用通知且服务未运行时，才启动服�?        // 注意：NotifyService.onCreate 中已经会检�?notify 设置并调�?enable()
        if (SPUtils.getPrefs().getBoolean("notify", false)) {
            // 只启动服务，不发�?ACTION_ENABLE
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private void checkAutoScan() {
        // 如果启用了自动扫描且没有配置服务器地址
        boolean autoScan = SPUtils.getPrefs().getBoolean("auto_scan", true);
        String serverUrl = SPUtils.getPrefs().getString("server_url", "");
        
        if (autoScan && serverUrl.isEmpty()) {
            Snackbar.make(binding.getRoot(),
                    "请先在设置中扫描或配置服务器地址", 
                    Snackbar.LENGTH_LONG)
                    .setAction("Settings", v -> {
                        NavController navController = Navigation.findNavController(
                                this, R.id.nav_host_fragment_content_main);
                        navController.navigate(R.id.SettingsFragment);
                    })
                    .show();
        }
    }

    /**
     * 服务器设置改变时调用
     */
    public void onServerSettingsChanged() {
        // 重新连接服务
        if (serviceBound && notifyService != null) {
            notifyService.reconnect();
        }

        // 若当前正显示控制面板,用新的服务器地址重新加载 WebView(换 IP 后即时生效)
        refreshControlPanelIfNeeded();
    }

    private void refreshControlPanelIfNeeded() {
        ControlPanelFragment panel = findControlPanelFragment();
        if (panel != null) {
            panel.reload();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityResumed = true;
        applySyncUiForeground();
    }

    @Override
    protected void onStop() {
        activityResumed = false;
        applySyncUiForeground();
        super.onStop();
    }

    private void applySyncUiForeground() {
        if (serviceBound && notifyService != null && notifyService.getSyncOfferManager() != null) {
            notifyService.getSyncOfferManager().setUiForeground(activityResumed);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            NavController navController = Navigation.findNavController(
                    this, R.id.nav_host_fragment_content_main);
            navController.navigate(R.id.SettingsFragment);
            return true;
        } else if (id == R.id.action_clipboard) {
            readClipboard();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 读取系统剪贴板并展示:内容像服务器地址时提供"一键设为服务器",
     * 像 http 网址时提供"在控制面板打开",其余仅展示内容。
     */
    private void readClipboard() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String text = null;
        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null) {
            ClipData.Item clipItem = clipboard.getPrimaryClip().getItemAt(0);
            CharSequence cs = clipItem != null ? clipItem.coerceToText(this) : null;
            text = cs != null ? cs.toString().trim() : null;
        }
        if (text == null || text.isEmpty()) {
            ToastUtils.showInfoToast(this, "剪贴板为空");
            return;
        }
        final String clipText = text;

        String lower = clipText.toLowerCase(Locale.US);
        boolean isUrl = lower.startsWith("http://") || lower.startsWith("https://");
        String[] hostPort = extractHostPort(clipText);

        String preview = clipText.length() > 500 ? clipText.substring(0, 500) + "…" : clipText;
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("剪贴板内容")
                .setMessage(preview)
                .setNegativeButton("关闭", null);
        if (hostPort != null) {
            dialog.setPositiveButton("设为服务器", (d, w) ->
                    applyServerHostPort(hostPort[0], hostPort[1]));
        }
        if (isUrl) {
            dialog.setNeutralButton("在控制面板打开", (d, w) ->
                    openInControlPanel(clipText));
        }
        dialog.show();
    }

    /**
     * 从文本中提取 host 与端口;不像地址(含空白/无域名点/端口非法)时返回 null。
     * 端口缺省时返回 -1,由调用方按默认端口处理。
     */
    private String[] extractHostPort(String text) {
        String s = text.trim();
        if (s.isEmpty() || s.contains(" ") || s.contains("\n")) {
            return null;
        }
        s = s.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^wss?://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        if (s.isEmpty() || s.indexOf('.') <= 0) {
            return null;
        }
        int colon = s.lastIndexOf(':');
        String host;
        int port;
        if (colon < 0) {
            host = s;
            port = -1;
        } else if (colon > 0 && colon < s.length() - 1 && s.indexOf(':') == colon) {
            host = s.substring(0, colon);
            try {
                port = Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (port < 1 || port > 65535) {
                return null;
            }
        } else {
            return null;
        }
        if (!host.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return new String[]{host, String.valueOf(port)};
    }

    /**
     * 把剪贴板里的地址保存为服务器并立即重连/刷新,与设置页保存走同一条路。
     */
    private void applyServerHostPort(String host, String portText) {
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            port = AppConfig.DEFAULT_SERVER_PORT;
        }
        if (port < 1) {
            port = AppConfig.DEFAULT_SERVER_PORT;
        }
        SPUtils.getPrefs().edit()
                .putString("server_url", host)
                .putInt("server_port", port)
                .apply();
        onServerSettingsChanged();
        ToastUtils.showSuccessToast(this, "服务器已切换: " + host + ":" + port);
    }

    /**
     * 切到控制面板并打开剪贴板里的网址;控制面板若已存在则直接加载,
     * 否则记入 pendingWebUrl 等其创建时加载。
     */
    private void openInControlPanel(String url) {
        pendingWebUrl = url;
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment_content_main);
        navController.navigate(R.id.ControlPanelFragment);
        ControlPanelFragment panel = findControlPanelFragment();
        if (panel != null) {
            panel.openUrl(url);
        }
    }

    /**
     * 控制面板取走待打开的网址(取后即清,避免下次误开)。
     */
    public String consumePendingWebUrl() {
        String url = pendingWebUrl;
        pendingWebUrl = null;
        return url;
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    @Override
    public void onBackPressed() {
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment_content_main);

        // 当前在控制面板时,先交给 WebView 分层后退
        // (关全屏视频/关弹层 → 网页历史后退),退不动了才走默认返回
        if (navController.getCurrentDestination() != null &&
            navController.getCurrentDestination().getId() == R.id.ControlPanelFragment) {
            ControlPanelFragment panel = findControlPanelFragment();
            if (panel != null && panel.canGoBack()) {
                panel.goBack();
                return;
            }
        }

        super.onBackPressed();
    }

    /**
     * 从导航宿主中查找当前的控制面板 Fragment(可能不存在)。
     */
    private ControlPanelFragment findControlPanelFragment() {
        Fragment navHost = getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_content_main);
        if (navHost == null) {
            return null;
        }
        for (Fragment child : navHost.getChildFragmentManager().getFragments()) {
            if (child instanceof ControlPanelFragment) {
                return (ControlPanelFragment) child;
            }
        }
        return null;
    }

    /**
     * 自动扫描并重连:仅当开关开启、通知启用、当前未连接且不在扫描中时,
     * 后台发起一次局域网扫描,找到后(优先同名设备)写入配置并重连。带冷却避免持续扫描。
     */
    private void tryAutoReconnectScan() {
        if (!SPUtils.getPrefs().getBoolean("auto_connect", false)) {
            return;
        }
        if (!SPUtils.getPrefs().getBoolean("notify", false)) {
            return;
        }
        if (autoConnectScanning) {
            return;
        }
        if (serviceBound && notifyService != null && notifyService.isConnected()) {
            return;
        }
        // 已配置为域名/URL 的无需靠局域网扫描定位
        String url = SPUtils.getPrefs().getString("server_url", "");
        if (!url.isEmpty() && ServerEndpoint.isDirectUrl(url)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAutoScanTs < AUTO_SCAN_COOLDOWN_MS) {
            return;
        }
        lastAutoScanTs = now;

        autoConnectScanning = true;
        if (autoScanner != null) {
            autoScanner.stop();
        }
        autoScanner = new NetworkScanner();
        final List<NetworkScanner.ServerInfo> found = Collections.synchronizedList(new ArrayList<>());
        autoScanner.scanLocalNetwork(AppConfig.LAN_SCAN_PORTS, new NetworkScanner.ScanCallback() {
            @Override
            public void onServerFound(String ip, int port, String info) {
                for (NetworkScanner.ServerInfo s : found) {
                    if (s.ip.equals(ip) && s.port == port) {
                        return;
                    }
                }
                found.add(new NetworkScanner.ServerInfo(ip, port, info));
            }

            @Override
            public void onScanComplete(List<NetworkScanner.ServerInfo> servers) {
                autoConnectScanning = false;
                runOnUiThread(() -> autoApplyFound(found));
            }

            @Override
            public void onScanCancelled() {
                autoConnectScanning = false;
            }

            @Override
            public void onProgress(int current, int total) {
            }
        });
    }

    /** 从扫描结果中择优选一台(优先上次配对过的同名设备),写入配置并触发重连/刷新 */
    private void autoApplyFound(List<NetworkScanner.ServerInfo> found) {
        if (found.isEmpty()) {
            ToastUtils.showWarningToast(this, "自动扫描未发现服务器");
            return;
        }
        String lastName = SPUtils.getPrefs().getString("server_name", "");
        NetworkScanner.ServerInfo best = found.get(0);
        if (!lastName.isEmpty()) {
            for (NetworkScanner.ServerInfo s : found) {
                if (lastName.equals(ServerEndpoint.serverNameOf(s.info))) {
                    best = s;
                    break;
                }
            }
        }
        SPUtils.getPrefs().edit()
                .putString("server_url", best.ip)
                .putInt("server_port", best.port)
                .putString("server_name", ServerEndpoint.serverNameOf(best.info))
                .apply();
        onServerSettingsChanged();
        ToastUtils.showSuccessToast(this, "已自动重连: " + best.ip + ":" + best.port);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (autoScanner != null) {
            autoScanner.stop();
        }
        if (serviceBound && notifyService != null && notifyService.getSyncOfferManager() != null) {
            notifyService.getSyncOfferManager().setUiCallback(null);
            notifyService.getSyncOfferManager().setUiForeground(false);
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
