package me.smerrybeta.toolanapp.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import me.smerrybeta.toolanapp.AppConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkScanner {
    private static final String TAG = "NetworkScanner";
    private static final int CONNECT_TIMEOUT_MS = 1800;
    private static final int THREAD_POOL_SIZE = 50;

    private final ExecutorService executorService;
    private final Handler mainHandler;

    // 扫描状态:支持随时取消,且取消后可立即重新发起扫描(不关闭线程池)
    private volatile boolean scanning = false;
    private volatile boolean cancelRequested = false;
    private volatile long scanGeneration = 0L;

    public interface ScanCallback {
        void onServerFound(String ip, int port, String info);

        void onScanComplete(List<ServerInfo> servers);

        void onProgress(int current, int total);

        /** 扫描被用户主动取消时回调(区别于自然完成) */
        void onScanCancelled();
    }

    public static class ServerInfo {
        public String ip;
        public int port;
        public String info;

        public ServerInfo(String ip, int port, String info) {
            this.ip = ip;
            this.port = port;
            this.info = info;
        }
    }

    public NetworkScanner() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    public void scan(String subnet, int port, ScanCallback callback) {
        scan(subnet, new int[]{port}, callback);
    }

    public void scan(String subnet, int[] ports, ScanCallback callback) {
        cancelRequested = false;
        scanning = true;
        // 每次扫描递增代号,用于让上一次(已被取消的)扫描的在途任务自动失效
        final long gen = ++scanGeneration;
        List<ServerInfo> foundServers = java.util.Collections.synchronizedList(new ArrayList<>());
        final int totalHosts = 254 * ports.length;
        final int[] scannedCount = {0};

        Log.d(TAG, "Scanning subnet " + subnet + ".x");

        for (int port : ports) {
            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + "." + i;
                final int scanPort = port;
                executorService.execute(() -> {
                    if (cancelRequested || gen != scanGeneration) {
                        return;
                    }
                    try {
                        String info = getServerInfo(ip, scanPort);
                        if (info != null && !cancelRequested && gen == scanGeneration) {
                            ServerInfo serverInfo = new ServerInfo(ip, scanPort, info);
                            foundServers.add(serverInfo);
                            mainHandler.post(() -> callback.onServerFound(ip, scanPort, info));
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "Scan error " + ip + ":" + scanPort + ": " + e.getMessage());
                    } finally {
                        final int progress;
                        synchronized (scannedCount) {
                            progress = ++scannedCount[0];
                        }
                        if (!cancelRequested && gen == scanGeneration) {
                            mainHandler.post(() -> callback.onProgress(progress, totalHosts));
                        }
                    }
                });
            }
        }

        executorService.execute(() -> {
            try {
                int waitTime = 0;
                while (!cancelRequested && gen == scanGeneration
                        && scannedCount[0] < totalHosts && waitTime < 20000) {
                    Thread.sleep(500);
                    waitTime += 500;
                }
                if (cancelRequested || gen != scanGeneration) {
                    mainHandler.post(callback::onScanCancelled);
                } else {
                    scanning = false;
                    mainHandler.post(() -> callback.onScanComplete(new ArrayList<>(foundServers)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 当前是否正在扫描 */
    public boolean isScanning() {
        return scanning;
    }

    /** 取消当前扫描:让在途任务失效并停止,线程池保留以便再次扫描 */
    public void cancelScan() {
        cancelRequested = true;
        scanning = false;
        scanGeneration++;
    }

    public void scanLocalNetwork(int port, ScanCallback callback) {
        scanLocalNetwork(new int[]{port}, callback);
    }

    public void scanLocalNetwork(int[] ports, ScanCallback callback) {
        String localIp = getLocalIpAddress();
        if (localIp == null || localIp.equals("127.0.0.1")) {
            mainHandler.post(() -> callback.onScanComplete(new ArrayList<>()));
            return;
        }

        String subnet = localIp.substring(0, localIp.lastIndexOf('.'));
        scan(subnet, ports, callback);
    }

    private String getServerInfo(String ip, int port) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format(
                    Locale.US,
                    "http://%s:%d%s",
                    ip,
                    port,
                    AppConfig.SERVER_INFO_PATH
            ));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONNECT_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            String app = json.optString("app", json.optString("name", ""));
            String protocol = json.optString("protocol", "");
            String serverKind = json.optString("serverKind", "");
            if (!AppConfig.SERVER_PROTOCOL.equals(protocol)
                    && !AppConfig.SERVER_KIND.equals(serverKind)) {
                return null;
            }

            String version = json.optString("version", "");
            String wsPath = json.optString("wsPath", AppConfig.WEBSOCKET_PATH);
            int resolvedPort = json.optInt("port", port);
            StringBuilder info = new StringBuilder(app);
            if (info.length() == 0) {
                info.append("TOOLAN BackEnd");
            }
            if (!version.isEmpty()) {
                info.append(" ").append(version);
            }
            info.append(" (").append(ip).append(":").append(resolvedPort).append(", ws ").append(wsPath).append(")");
            return info.toString();
        } catch (Exception e) {
            Log.d(TAG, "Server info failed " + ip + ":" + port + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String getLocalIpAddress() {
        try {
            java.net.NetworkInterface wifiInterface = java.net.NetworkInterface.getByName("wlan0");
            if (wifiInterface == null) {
                wifiInterface = java.net.NetworkInterface.getByName("eth0");
            }
            if (wifiInterface != null) {
                java.util.Enumeration<java.net.InetAddress> addresses = wifiInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }

            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        String ip = address.getHostAddress();
                        if (ip == null) {
                            return null;
                        }
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                                ip.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Get local IP failed: " + e.getMessage());
        }
        return null;
    }

    public void stop() {
        cancelScan();
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }
}
