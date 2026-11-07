package me.smerrybeta.toolanapp.utils;

import me.smerrybeta.toolanapp.AppConfig;
import me.smerrybeta.toolanapp.SPUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class ServerEndpoint {
    private ServerEndpoint() {
    }

    public static String httpBaseUrlFromPrefs() {
        String serverUrl = SPUtils.getPrefs().getString("server_url", "");
        int serverPort = SPUtils.getPrefs().getInt("server_port", AppConfig.DEFAULT_SERVER_PORT);
        return httpBaseUrl(serverUrl, serverPort);
    }

    public static String websocketUrlFromPrefs() {
        return websocketUrl(httpBaseUrlFromPrefs());
    }

    public static String uploadUrlFromPrefs() {
        return appendPath(httpBaseUrlFromPrefs(), "/upload");
    }

    public static String serverInfoUrlFromPrefs() {
        return appendPath(httpBaseUrlFromPrefs(), AppConfig.SERVER_INFO_PATH);
    }

    public static boolean isDirectUrl(String value) {
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("ws://")
                || lower.startsWith("wss://");
    }

    /**
     * 从扫描到的服务器 info 文本中提取设备身份名称(去掉末尾括号里的 ip:port 部分)。
     * 用于服务器 IP 变化后仍能识别出“同一台”服务器,供自动重连时优先匹配。
     */
    public static String serverNameOf(String info) {
        if (info == null) {
            return "";
        }
        int idx = info.indexOf(" (");
        return idx > 0 ? info.substring(0, idx).trim() : info.trim();
    }

    public static String httpBaseUrl(String serverUrl, int serverPort) {
        String value = serverUrl == null ? "" : serverUrl.trim();
        if (value.isEmpty()) {
            return "";
        }

        if (isDirectUrl(value)) {
            String httpUrl = value
                    .replaceFirst("(?i)^ws://", "http://")
                    .replaceFirst("(?i)^wss://", "https://");
            return trimTrailingSlash(httpUrl);
        }

        String host = normalizeHost(value);
        if (host.isEmpty()) {
            return "";
        }
        if (hasPort(host)) {
            return "http://" + host;
        }
        return String.format(Locale.US, "http://%s:%d", host, serverPort);
    }

    public static String websocketUrl(String httpBaseUrl) {
        String base = httpBaseUrl == null ? "" : httpBaseUrl.trim();
        if (base.isEmpty()) {
            return "";
        }
        String wsBase = base
                .replaceFirst("(?i)^https://", "wss://")
                .replaceFirst("(?i)^http://", "ws://");
        return appendPath(wsBase, AppConfig.WEBSOCKET_PATH);
    }

    public static String appendPath(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return "";
        }
        String base = trimTrailingSlash(baseUrl.trim());
        String suffix = path == null ? "" : path.trim();
        if (suffix.isEmpty()) {
            return base;
        }
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String normalizeHost(String host) {
        String result = host.trim();
        result = result.replaceFirst("(?i)^https?://", "");
        result = result.replaceFirst("(?i)^wss?://", "");
        int slashIndex = result.indexOf('/');
        if (slashIndex >= 0) {
            result = result.substring(0, slashIndex);
        }
        return result;
    }

    private static boolean hasPort(String host) {
        try {
            URI uri = new URI("http://" + host);
            return uri.getPort() > 0;
        } catch (URISyntaxException e) {
            int colonIndex = host.lastIndexOf(':');
            return colonIndex > 0 && colonIndex < host.length() - 1;
        }
    }
}
