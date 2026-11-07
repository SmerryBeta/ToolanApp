package me.smerrybeta.toolanapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import me.smerrybeta.toolanapp.databinding.FragmentControlPanelBinding;
import me.smerrybeta.toolanapp.utils.ServerEndpoint;
import me.smerrybeta.toolanapp.utils.ToastUtils;
import me.smerrybeta.toolanapp.utils.WebFileUtils;
import me.smerrybeta.toolanapp.utils.crypto.CipherFactory;
import me.smerrybeta.toolanapp.utils.crypto.CryptoCipher;
import me.smerrybeta.toolanapp.utils.crypto.CryptoSettings;

import java.nio.charset.StandardCharsets;

/**
 * 控制面板 Fragment
 * 使用 WebView 嵌入脚本的网页控制界�?
 */
public class ControlPanelFragment extends Fragment {

    private FragmentControlPanelBinding binding;
    private String serverUrl;
    private int serverPort;

    // WebView 内 <input type="file"> 上传时暂存的回调
    private ValueCallback<Uri[]> pendingUploadCallback;

    // 右边缘滑动手势:起始区宽度 / 触发后退的最小水平位移 / 起始区判定
    private static final float EDGE_SWIPE_START_BAND_DP = 24f;
    private static final float EDGE_SWIPE_MIN_DISTANCE_DP = 56f;
    private float edgeSwipeStartX = -1f;
    private float edgeSwipeStartY = 0f;
    private boolean edgeSwipeFired = false;

    /**
     * 注入网页的剪贴板补丁:http 局域网页不是"安全上下文",浏览器标准的
     * navigator.clipboard 在其中不存在,网页的"读取剪贴板"会报不支持。
     * 这里把原生桥 ToolanClipboard 包装成标准 Clipboard API,网页代码无需修改。
     * 幂等:同一页面重复注入只生效一次。
     */
    private static final String CLIPBOARD_PATCH_JS =
            "(function(){"
                    + "if(window.__toolanClipboardPatched)return;"
                    + "window.__toolanClipboardPatched=true;"
                    + "var impl={"
                    + " readText:function(){return new Promise(function(resolve){"
                    + "  try{resolve(window.ToolanClipboard.readText()||'');}"
                    + "  catch(e){resolve('');}});},"
                    + " writeText:function(t){return new Promise(function(resolve,reject){"
                    + "  try{window.ToolanClipboard.writeText(t==null?'':String(t));resolve();}"
                    + "  catch(e){reject(e);}});}};"
                    + "try{Object.defineProperty(navigator,'clipboard',"
                    + " {value:impl,configurable:true,writable:true});}"
                    + "catch(e){navigator.clipboard=impl;}"
                    + "})();";

    // 系统文件选择器回调(在 Fragment 创建前注册)
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::handleFileChooserResult);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentControlPanelBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 获取服务器地址
        loadServerSettings();

        // 配置 WebView
        WebSettings settings = binding.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        // 禁用缩放控制（包括双击放大）
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        // 禁用双击缩放
        settings.setUseWideViewPort(true);

        // 注册原生剪贴板桥:网页 navigator.clipboard 的读写在原生侧代为执行
        binding.webView.addJavascriptInterface(
                new NativeClipboardBridge(requireContext().getApplicationContext()),
                "ToolanClipboard");

        // 注册原生加密桥:网页 JS 检测到 window.ToolanCrypto 即走加密通道
        //（http 局域网页无 WebCrypto,加解密全部由 Java 代劳,密钥不出 App）
        binding.webView.addJavascriptInterface(new NativeCryptoBridge(), "ToolanCrypto");

        // 注册原生分享桥:网页检测到 window.ToolanShare 才显示分享按钮(仅 App 内可用)
        binding.webView.addJavascriptInterface(new NativeShareBridge(), "ToolanShare");

        binding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 尽量赶在网页脚本运行前注入剪贴板补丁
                view.evaluateJavascript(CLIPBOARD_PATCH_JS, null);
                binding.progressBar.setVisibility(ProgressBar.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完再补一次,兜底早期注入没赶上的情况
                view.evaluateJavascript(CLIPBOARD_PATCH_JS, null);
                binding.progressBar.setVisibility(ProgressBar.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                binding.progressBar.setVisibility(ProgressBar.GONE);
                Toast.makeText(requireContext(),
                        "加载失败: " + description, Toast.LENGTH_SHORT).show();
            }
        });

        binding.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // 加载到 20% 时也注入一次剪贴板补丁,确保早于网页的能力检测
                if (newProgress >= 20) {
                    view.evaluateJavascript(CLIPBOARD_PATCH_JS, null);
                }
                binding.progressBar.setProgress(newProgress);
            }

            /**
             * 网页里的 <input type="file"> 上传按钮点击时触发,
             * 调起系统文件选择器,选完后再把结果回传给 WebView。
             */
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                // 若上一次选择尚未结束,先以 null 收尾,避免回调泄漏导致下次无法弹出
                if (pendingUploadCallback != null) {
                    pendingUploadCallback.onReceiveValue(null);
                }
                pendingUploadCallback = callback;
                try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent());
                } catch (Exception e) {
                    pendingUploadCallback = null;
                    callback.onReceiveValue(null);
                    if (isAdded()) {
                        ToastUtils.showErrorToast(requireContext(), "无法打开文件选择器");
                    }
                }
                return true;
            }
        });

        // 右边缘向屏幕内滑动:网页后退一步
        setupEdgeBackSwipe();

        // "读取剪贴板 → 在控制面板打开"跳转过来时,优先打开剪贴板里的地址
        if (getActivity() instanceof MainActivity) {
            String pendingUrl = ((MainActivity) getActivity()).consumePendingWebUrl();
            if (pendingUrl != null) {
                binding.webView.loadUrl(pendingUrl);
                return;
            }
        }

        // 加载页面
        loadWebPage();
    }

    /**
     * 右边缘滑动后退:
     * 只监听不拦截(onTouch 返回 false),WebView 的滚动和点击不受影响;
     * 当手指从屏幕最右侧窄条内按下、并明显向屏幕内(左)滑时,让网页后退一步。
     */
    private void setupEdgeBackSwipe() {
        float density = getResources().getDisplayMetrics().density;
        float startBandPx = EDGE_SWIPE_START_BAND_DP * density;
        float minDistancePx = EDGE_SWIPE_MIN_DISTANCE_DP * density;

        binding.webView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    edgeSwipeStartX = event.getX() >= v.getWidth() - startBandPx
                            ? event.getX() : -1f;
                    edgeSwipeStartY = event.getY();
                    edgeSwipeFired = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!edgeSwipeFired && edgeSwipeStartX >= 0) {
                        float dx = event.getX() - edgeSwipeStartX;
                        float dy = Math.abs(event.getY() - edgeSwipeStartY);
                        if (dx <= -minDistancePx && Math.abs(dx) > dy * 1.2f) {
                            edgeSwipeFired = true;
                            goBack();
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    edgeSwipeStartX = -1f;
                    edgeSwipeFired = false;
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void loadServerSettings() {
        serverUrl = SPUtils.getPrefs().getString("server_url", "");
        serverPort = SPUtils.getPrefs().getInt("server_port", AppConfig.DEFAULT_SERVER_PORT);
    }

    private void loadWebPage() {
        if (serverUrl.isEmpty()) {
            // 显示提示信息
            binding.webView.loadData(
                    "<html><body style='text-align:center;padding:50px;'>" +
                            "<h2>未配置服务器</h2>" +
                            "<p>请先在设置中配置服务器地址</p>" +
                            "</body></html>",
                    "text/html", "UTF-8"
            );
            return;
        }

        binding.webView.loadUrl(ServerEndpoint.httpBaseUrl(serverUrl, serverPort));
        // 网页中触发下载时,由 App 直连下载并保存到"下载"目录,
        // 不再走系统 DownloadManager,避免"是否等待 WiFi"等拦截弹窗。
        // 内容是安装包(含 .zip 后缀的 APK)时,下载完直接拉起系统安装器。
        binding.webView.setDownloadListener(
                (url, userAgent, contentDisposition, mimeType, contentLength) ->
                        startWebDownload(url));
    }

    /**
     * 直连下载网页请求的文件并保存到"下载"目录;加密模式下响应为 A1L 流,
     * 由 WebFileUtils 解密。DownloadListener 与加密桥 download() 共用此入口。
     */
    private void startWebDownload(String url) {
        if (!isAdded()) {
            return;
        }
        Context context = requireContext().getApplicationContext();
        ToastUtils.showInfoToast(requireContext(), "开始下载…");
        WebFileUtils.downloadToDownloads(context, url, (success, message, uri) -> {
            if (!isAdded()) {
                return;
            }
            if (!success) {
                ToastUtils.showErrorToast(requireContext(), message);
                return;
            }
            ToastUtils.showSuccessToast(requireContext(), message);
            if (uri != null) {
                launchApkInstaller(uri);
            }
        });
    }

    /**
     * 拉起系统安装器安装刚下载的 APK。
     * Android 8+ 需要"安装未知应用"授权,未授权时自动跳到对应设置页,授权后重试即可。
     */
    private void launchApkInstaller(Uri apkUri) {
        Context context = requireContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            ToastUtils.showWarningToast(context, "请先在设置中允许本应用\"安装未知应用\",然后重新下载");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                } catch (Exception ignored) {
                    ToastUtils.showErrorToast(context, "无法打开权限设置");
                }
            }
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception e) {
            ToastUtils.showErrorToast(context, "无法打开安装器");
        }
    }

    /**
     * 处理系统文件选择器的返回结果,把选中的文件回传给 WebView。
     */
    private void handleFileChooserResult(ActivityResult result) {
        if (pendingUploadCallback == null) {
            return;
        }
        Uri[] uris = null;
        Intent data = result.getData();
        if (result.getResultCode() == Activity.RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        // 必须且只能调用一次,否则 WebView 的下一次上传会被卡住
        pendingUploadCallback.onReceiveValue(uris);
        pendingUploadCallback = null;
    }

    /**
     * 重新加载页面（当服务器设置改变时调用�?
     */
    public void reload() {
        loadServerSettings();
        loadWebPage();
    }

    /**
     * 在 WebView 中打开指定地址(读取剪贴板入口用),并清掉 MainActivity 暂存的待打开地址。
     */
    public void openUrl(String url) {
        if (url != null && binding != null) {
            binding.webView.loadUrl(url);
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).consumePendingWebUrl();
        }
    }

    /**
     * 检�?WebView 是否可以返回(仅指网页历史;goBack() 还会先处理网页内的弹层/视频)
     */
    public boolean canGoBack() {
        return binding != null && binding.webView.canGoBack();
    }

    /**
     * 分层后退:
     * 1. 网页有全屏元素(如视频全屏) → 退出全屏
     * 2. 其余交给网页历史后退 —— 网页自身用 hash 路由记录了
     *    标签页/文件夹/预览层/模态,goBack 会触发其 popstate 逐层恢复
     */
    public void goBack() {
        if (binding == null) {
            return;
        }
        binding.webView.evaluateJavascript(WEB_FULLSCREEN_BACK_JS, result -> {
            if (!isAdded() || binding == null) {
                return;
            }
            if (!"\"fullscreen\"".equals(result)) {
                goBackInHistory();
            }
        });
    }

    private void goBackInHistory() {
        if (binding != null && binding.webView.canGoBack()) {
            binding.webView.goBack();
        }
    }

    // 检测并退出网页内全屏;返回 'fullscreen' 表示已处理,'none' 交由历史后退
    private static final String WEB_FULLSCREEN_BACK_JS =
            "(function(){"
                    + "if(document.fullscreenElement){"
                    + "if(document.exitFullscreen)document.exitFullscreen();"
                    + "return 'fullscreen';}"
                    + "return 'none';})()";

    /**
     * 暴露给网页 JS 的原生剪贴板桥(window.ToolanClipboard)。
     * 方法在 WebView 的 JS 线程回调,ClipboardManager 本身线程安全。
     */
    private static class NativeClipboardBridge {
        private final Context context;

        NativeClipboardBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public String readText() {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) {
                return "";
            }
            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            CharSequence text = item != null ? item.coerceToText(context) : null;
            return text != null ? text.toString() : "";
        }

        @JavascriptInterface
        public void writeText(String text) {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "web", text != null ? text : ""));
        }
    }

    /**
     * 暴露给网页 JS 的原生加密桥（window.ToolanCrypto）。
     *
     * <p>http 局域网页不是安全上下文,没有 WebCrypto,页面 JS 无法自行做 AES。
     * 加密开启时由本桥代为加解密（密钥留在 App 内,不进 WebView JS 环境）;
     * 加密关闭时 enabled() 返回 false,页面自动走明文。方法在 WebView 的 JS
     * 线程回调,加解密耗时仅首次密钥派生（约百毫秒,结果缓存于加密器实例）。</p>
     */
    private class NativeCryptoBridge {

        private CryptoCipher cachedCipher;
        private String cachedKey;

        private synchronized CryptoCipher currentCipher() {
            if (!CryptoSettings.enabled()) {
                return null;
            }
            String key = CryptoSettings.key();
            if (key == null || key.trim().isEmpty()) {
                return null;
            }
            key = key.trim();
            if (cachedCipher != null && key.equals(cachedKey)) {
                return cachedCipher;
            }
            try {
                cachedCipher = CipherFactory.create(CipherFactory.VERSION_A1, key);
                cachedKey = key;
                return cachedCipher;
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public boolean enabled() {
            return currentCipher() != null;
        }

        @JavascriptInterface
        public String version() {
            CryptoCipher cipher = currentCipher();
            return cipher == null ? "" : cipher.version();
        }

        /** 加密文本（通常是 JSON 字符串）→ A1 信封;失败返回 null。 */
        @JavascriptInterface
        public String a1Encrypt(String text) {
            CryptoCipher cipher = currentCipher();
            if (cipher == null || text == null) {
                return null;
            }
            try {
                return cipher.encrypt(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return null;
            }
        }

        /** 解密 A1 信封 → 文本;失败返回 null。 */
        @JavascriptInterface
        public String a1Decrypt(String envelope) {
            CryptoCipher cipher = currentCipher();
            if (cipher == null || envelope == null) {
                return null;
            }
            try {
                return new String(cipher.decrypt(envelope), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        /** 加密二进制（base64 输入）→ A1 信封;失败返回 null。供 JS 分块上传用。 */
        @JavascriptInterface
        public String a1EncryptB64(String base64) {
            CryptoCipher cipher = currentCipher();
            if (cipher == null || base64 == null) {
                return null;
            }
            try {
                byte[] plain = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
                return cipher.encrypt(plain);
            } catch (Exception e) {
                return null;
            }
        }

        /** 解密 A1 信封 → 二进制（base64 输出）;失败返回 null。供 JS 流式解密用。 */
        @JavascriptInterface
        public String a1DecryptB64(String envelope) {
            CryptoCipher cipher = currentCipher();
            if (cipher == null || envelope == null) {
                return null;
            }
            try {
                byte[] plain = cipher.decrypt(envelope);
                return android.util.Base64.encodeToString(plain, android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 原生下载加密流并保存到"下载"目录（规避 blob: URL 在 WebView
         * 系统下载器中无法处理的问题）;是 APK 时直接拉起安装器。
         */
        @JavascriptInterface
        public void download(final String url) {
            if (url == null || url.isEmpty()) {
                return;
            }
            // JS 线程 → 主线程确认 Fragment 存活,再进下载流程
            binding.webView.post(() -> startWebDownload(url));
        }
    }

    /**
     * 暴露给网页 JS 的原生分享桥(window.ToolanShare):把仓库文件经系统分享面板
     * 发给微信/QQ 等其它应用。分享类型按文件扩展名决定(图片即图片流程)。
     */
    private class NativeShareBridge {

        /** @param url 仓库内相对地址(可含 ?token=);加密响应由 openConnection 自动协商。 */
        @JavascriptInterface
        public void shareFile(final String url, final String fileName) {
            if (url == null || url.isEmpty()) {
                return;
            }
            // JS 线程 → 主线程确认 Fragment 存活,再进分享流程
            binding.webView.post(() -> startWebShare(url, fileName));
        }
    }

    /**
     * 直连下载文件到分享缓存并调起系统分享面板;加密模式下响应为 A1L 流,
     * 由 WebFileUtils 解密后按原始字节分享。
     */
    private void startWebShare(String relativeUrl, String fileName) {
        if (!isAdded()) {
            return;
        }
        String url = ServerEndpoint.appendPath(ServerEndpoint.httpBaseUrlFromPrefs(), relativeUrl);
        if (url.isEmpty()) {
            ToastUtils.showErrorToast(requireContext(), "服务器地址未配置");
            return;
        }
        ToastUtils.showInfoToast(requireContext(), "正在准备分享…");
        WebFileUtils.prepareSharedFile(requireContext().getApplicationContext(), url, fileName,
                (success, message, uri) -> {
                    if (!isAdded()) {
                        return;
                    }
                    if (!success) {
                        ToastUtils.showErrorToast(requireContext(), message);
                        return;
                    }
                    launchShareChooser(uri, message);
                });
    }

    /** 按文件类型调起系统分享面板;图片以 image/* 进入微信/QQ 的发图流程。 */
    private void launchShareChooser(Uri uri, String fileName) {
        if (!isAdded()) {
            return;
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(WebFileUtils.mimeForName(fileName));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            // ClipData 与 FLAG 双保险授权,部分应用的 intent 复制路径只读 ClipData
            send.setClipData(ClipData.newRawUri(fileName, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享 " + fileName));
        } catch (Exception e) {
            ToastUtils.showErrorToast(requireContext(), "无法打开分享面板");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) {
            binding.webView.stopLoading();
            binding.webView.destroy();
        }
        binding = null;
    }
}
