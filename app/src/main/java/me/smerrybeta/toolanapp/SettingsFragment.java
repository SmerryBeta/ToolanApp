package me.smerrybeta.toolanapp;

import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import me.smerrybeta.toolanapp.databinding.FragmentSettingsBinding;
import me.smerrybeta.toolanapp.services.NotifyService;
import me.smerrybeta.toolanapp.utils.NetworkScanner;
import me.smerrybeta.toolanapp.utils.ServerEndpoint;
import me.smerrybeta.toolanapp.utils.crypto.A1Cipher;
import me.smerrybeta.toolanapp.utils.crypto.CryptoException;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class SettingsFragment extends Fragment {
    private FragmentSettingsBinding binding;
    private NetworkScanner networkScanner;
    private Handler mainHandler;
    private Handler statusUpdateHandler;
    private Runnable statusUpdateRunnable;
    private ServerListAdapter serverListAdapter;
    private final List<NetworkScanner.ServerInfo> foundServers = new ArrayList<>();
    // 本次扫描结束后是否需要自动选一台配对连接
    private boolean autoConnectPending = false;

    private NotifyService notifyService;
    private boolean serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            NotifyService.LocalBinder binder = (NotifyService.LocalBinder) service;
            notifyService = binder.getService();
            serviceBound = true;
            notifyService.setStatusCallback(new NotifyService.ConnectionStatusCallback() {
                @Override
                public void onConnected() {
                    postStatusUpdate();
                }

                @Override
                public void onDisconnected(String reason) {
                    postStatusUpdate();
                }

                @Override
                public void onError(String error) {
                    postStatusUpdate();
                }
            });
            updateConnectionStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            notifyService = null;
            updateConnectionStatus();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        networkScanner = new NetworkScanner();
        mainHandler = new Handler(Looper.getMainLooper());
        statusUpdateHandler = new Handler(Looper.getMainLooper());

        loadSettings();
        setupServerList();

        binding.btnSave.setOnClickListener(v -> saveSettings());
        binding.btnScan.setOnClickListener(v -> toggleScan());
        binding.btnTest.setOnClickListener(v -> testConnection());
        binding.btnReconnect.setOnClickListener(v -> forceReconnect());
        binding.btnVerifyKey.setOnClickListener(v -> verifyCryptoKey());

        bindNotifyService();
        startStatusUpdates();

        // 开启“自动扫描并连接”时,进入本页自动扫描并尝试重连同名服务器
        if (SPUtils.getPrefs().getBoolean("auto_scan", true)) {
            beginScan(true);
        }
    }

    private void bindNotifyService() {
        Intent intent = new Intent(requireContext(), NotifyService.class);
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startStatusUpdates() {
        statusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateConnectionStatus();
                if (statusUpdateHandler != null) {
                    statusUpdateHandler.postDelayed(this, 2000);
                }
            }
        };
        statusUpdateHandler.post(statusUpdateRunnable);
    }

    private void postStatusUpdate() {
        if (isAdded()) {
            requireActivity().runOnUiThread(this::updateConnectionStatus);
        }
    }

    private void updateConnectionStatus() {
        if (!isAdded() || binding == null) {
            return;
        }

        boolean notifyEnabled = SPUtils.getPrefs().getBoolean("notify", true);
        if (!notifyEnabled) {
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
            binding.tvConnectionStatus.setText("Notifications disabled");
            binding.btnReconnect.setEnabled(false);
            return;
        }

        if (!serviceBound || notifyService == null) {
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
            binding.tvConnectionStatus.setText("Server not connected");
            binding.btnReconnect.setEnabled(true);
            return;
        }

        if (notifyService.isConnected()) {
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connected);
            binding.tvConnectionStatus.setText("Server connected");
            binding.btnReconnect.setEnabled(true);
            return;
        }

        int attempts = notifyService.getReconnectAttempts();
        binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connecting);
        binding.tvConnectionStatus.setText(attempts > 0 ? "Reconnecting... (" + attempts + ")" : "Connecting...");
        binding.btnReconnect.setEnabled(true);
    }

    private void forceReconnect() {
        if (!SPUtils.getPrefs().getBoolean("notify", true)) {
            Toast.makeText(requireContext(), "Notifications disabled", Toast.LENGTH_SHORT).show();
            return;
        }

        if (serviceBound && notifyService != null) {
            Toast.makeText(requireContext(), "Reconnecting...", Toast.LENGTH_SHORT).show();
            notifyService.forceReconnect();
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connecting);
            binding.tvConnectionStatus.setText("Connecting...");
        } else {
            Toast.makeText(requireContext(), "Service not ready", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSettings() {
        String serverUrl = SPUtils.getPrefs().getString("server_url", "");
        int serverPort = SPUtils.getPrefs().getInt("server_port", AppConfig.DEFAULT_SERVER_PORT);
        boolean autoScan = SPUtils.getPrefs().getBoolean("auto_scan", true);
        boolean autoConnect = SPUtils.getPrefs().getBoolean("auto_connect", false);
        boolean notify = SPUtils.getPrefs().getBoolean("notify", true);
        boolean cryptoEnabled = SPUtils.getPrefs().getBoolean("crypto_enabled", false);
        String cryptoKey = SPUtils.getPrefs().getString("crypto_key", "");

        binding.etServerUrl.setText(serverUrl);
        binding.etServerPort.setText(String.valueOf(serverPort));
        binding.switchAutoScan.setChecked(autoScan);
        binding.switchAutoConnect.setChecked(autoConnect);
        binding.notify.setChecked(notify);
        binding.switchCrypto.setChecked(cryptoEnabled);
        binding.etCryptoKey.setText(cryptoKey);
    }

    private void saveSettings() {
        String serverUrl = binding.etServerUrl.getText() == null ? "" : binding.etServerUrl.getText().toString().trim();
        String portStr = binding.etServerPort.getText() == null ? "" : binding.etServerPort.getText().toString().trim();
        if (serverUrl.isEmpty()) {
            binding.etServerUrl.setError("Enter server address or URL");
            return;
        }

        int serverPort = parsePortOrDefault(serverUrl, portStr);
        if (serverPort <= 0) {
            binding.etServerPort.setError("Invalid port");
            return;
        }

        boolean cryptoEnabled = binding.switchCrypto.isChecked();
        String cryptoKey = binding.etCryptoKey.getText() == null ? "" : binding.etCryptoKey.getText().toString().trim();
        if (cryptoEnabled) {
            try {
                A1Cipher.parseNumericKey(cryptoKey);
            } catch (CryptoException e) {
                binding.etCryptoKey.setError(e.getMessage());
                return;
            }
        }

        SPUtils.getPrefs().edit()
                .putString("server_url", serverUrl)
                .putInt("server_port", serverPort)
                .putBoolean("auto_scan", binding.switchAutoScan.isChecked())
                .putBoolean("auto_connect", binding.switchAutoConnect.isChecked())
                .putBoolean("notify", binding.notify.isChecked())
                .putBoolean("crypto_enabled", cryptoEnabled)
                .putString("crypto_key", cryptoKey)
                .apply();

        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onServerSettingsChanged();
        }
    }

    /**
     * 密钥一致性验证：发送 A1 信封包裹的随机 nonce 到 /api/crypto/verify，
     * 服务端能解开并回传同一 nonce 即证明两端密钥一致。
     */
    private void verifyCryptoKey() {
        String serverUrl = binding.etServerUrl.getText() == null ? "" : binding.etServerUrl.getText().toString().trim();
        String portStr = binding.etServerPort.getText() == null ? "" : binding.etServerPort.getText().toString().trim();
        String keyText = binding.etCryptoKey.getText() == null ? "" : binding.etCryptoKey.getText().toString().trim();
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请先填写服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!binding.switchCrypto.isChecked()) {
            Toast.makeText(requireContext(), "请先打开加密开关", Toast.LENGTH_SHORT).show();
            return;
        }
        int serverPort = parsePortOrDefault(serverUrl, portStr);
        if (serverPort <= 0) {
            Toast.makeText(requireContext(), "端口号无效", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(requireContext());
        progressDialog.setMessage("正在验证密钥…");
        progressDialog.show();

        new Thread(() -> {
            String message;
            try {
                A1Cipher cipher = new A1Cipher(keyText);
                SecureRandom random = new SecureRandom();
                byte[] nonceBytes = new byte[16];
                random.nextBytes(nonceBytes);
                StringBuilder nonce = new StringBuilder();
                for (byte b : nonceBytes) {
                    nonce.append(String.format("%02x", b));
                }
                String envelope = cipher.encrypt(new JSONObject()
                        .put("nonce", nonce.toString())
                        .toString()
                        .getBytes(StandardCharsets.UTF_8));

                URL url = new URL(ServerEndpoint.appendPath(
                        ServerEndpoint.httpBaseUrl(serverUrl, serverPort),
                        AppConfig.CRYPTO_VERIFY_PATH));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(envelope.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                String body = readBody(conn, code);
                conn.disconnect();

                if (code == 200) {
                    JSONObject resp;
                    if (A1Cipher.looksEncrypted(body)) {
                        resp = new JSONObject(new String(cipher.decrypt(body), StandardCharsets.UTF_8));
                    } else {
                        resp = new JSONObject(body);
                    }
                    if (resp.optBoolean("success") && nonce.toString().equals(resp.optString("nonce"))) {
                        message = "✓ 密钥一致，加密通道可用（" + resp.optString("mode", "A1") + "）";
                    } else {
                        message = "服务端响应异常，请重试";
                    }
                } else if (code == 401) {
                    message = "✗ 密钥不一致：服务端无法解密";
                } else if (code == 429) {
                    message = "尝试过于频繁或失败次数过多被锁定，请稍后再试";
                } else if (code == 501) {
                    message = "服务端未启用加密（检查 config.yml 的 CryptoEnabled）";
                } else {
                    message = "验证失败: HTTP " + code + (body.isEmpty() ? "" : " " + body);
                }
            } catch (CryptoException e) {
                message = "密钥无效: " + e.getMessage();
            } catch (Exception e) {
                message = "验证失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            String result = message;
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                progressDialog.dismiss();
                Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private static String readBody(HttpURLConnection conn, int code) {
        try (InputStream in = code >= 200 && code < 300
                ? conn.getInputStream()
                : conn.getErrorStream()) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void setupServerList() {
        serverListAdapter = new ServerListAdapter(foundServers, this::pairServer);
        // 高亮当前已配对的服务器
        String savedUrl = SPUtils.getPrefs().getString("server_url", "");
        int savedPort = SPUtils.getPrefs().getInt("server_port", AppConfig.DEFAULT_SERVER_PORT);
        if (!savedUrl.isEmpty() && !ServerEndpoint.isDirectUrl(savedUrl)) {
            serverListAdapter.setPairedKey(savedUrl + ":" + savedPort);
        }
        binding.rvServerList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvServerList.setAdapter(serverListAdapter);
    }

    /** 扫描按钮:未扫描时开始,扫描中时停止 */
    private void toggleScan() {
        if (networkScanner != null && networkScanner.isScanning()) {
            stopScan();
        } else {
            beginScan(false);
        }
    }

    /**
     * 开始扫描。autoConnect=true 时,扫描完成后自动选择并配对一台服务器(优先同名)。
     * 不再弹不可取消的 ProgressDialog,而是在页面内嵌显示扫描状态,发现一台即时可点。
     */
    private void beginScan(boolean autoConnect) {
        if (networkScanner == null || binding == null || !isAdded()) {
            return;
        }
        autoConnectPending = autoConnect;
        foundServers.clear();
        if (serverListAdapter != null) {
            serverListAdapter.notifyDataSetChanged();
        }
        updateScanUi(true);

        networkScanner.scanLocalNetwork(AppConfig.LAN_SCAN_PORTS, new NetworkScanner.ScanCallback() {
            @Override
            public void onServerFound(String ip, int port, String info) {
                if (!isAdded() || binding == null) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    for (NetworkScanner.ServerInfo s : foundServers) {
                        if (s.ip.equals(ip) && s.port == port) {
                            return;
                        }
                    }
                    foundServers.add(new NetworkScanner.ServerInfo(ip, port, info));
                    serverListAdapter.notifyItemInserted(foundServers.size() - 1);
                    binding.tvServerListTitle.setVisibility(View.VISIBLE);
                    binding.rvServerList.setVisibility(View.VISIBLE);
                    binding.tvScanStatus.setText("正在扫描… 已发现 " + foundServers.size() + " 台（可直接点击配对）");
                });
            }

            @Override
            public void onScanComplete(List<NetworkScanner.ServerInfo> servers) {
                if (!isAdded() || binding == null) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    updateScanUi(false);
                    if (foundServers.isEmpty()) {
                        binding.tvServerListTitle.setVisibility(View.GONE);
                        binding.rvServerList.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), "未发现服务器,可手动输入地址", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (autoConnectPending) {
                        autoConnectPending = false;
                        NetworkScanner.ServerInfo best = pickBestServer(foundServers);
                        pairServer(best);
                        Toast.makeText(requireContext(), "已自动连接: " + best.ip + ":" + best.port, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), "扫描完成: 共 " + foundServers.size() + " 台", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onScanCancelled() {
                if (!isAdded() || binding == null) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    autoConnectPending = false;
                    updateScanUi(false);
                });
            }

            @Override
            public void onProgress(int current, int total) {
                // 内嵌模式下不再展示具体进度条,保持等待即可
            }
        });
    }

    /** 停止当前扫描 */
    private void stopScan() {
        autoConnectPending = false;
        if (networkScanner != null) {
            networkScanner.cancelScan();
        }
        updateScanUi(false);
    }

    /** 同步扫描相关的界面状态 */
    private void updateScanUi(boolean scanning) {
        if (binding == null) {
            return;
        }
        binding.btnScan.setText(scanning ? "停止扫描" : "扫描局域网");
        binding.btnScan.setIconResource(scanning
                ? android.R.drawable.ic_menu_close_clear_cancel
                : android.R.drawable.ic_menu_search);
        binding.layoutScanStatus.setVisibility(scanning ? View.VISIBLE : View.GONE);
        if (scanning) {
            binding.tvScanStatus.setText("正在扫描局域网…（发现服务器会自动列出,可直接点击配对）");
        }
    }

    /**
     * 点击服务器:直接填入地址、保存并触发重连配对。不中断当前扫描。
     */
    private void pairServer(NetworkScanner.ServerInfo server) {
        if (binding == null || !isAdded()) {
            return;
        }
        binding.etServerUrl.setText(server.ip);
        binding.etServerPort.setText(String.valueOf(server.port));
        SPUtils.getPrefs().edit()
                .putString("server_url", server.ip)
                .putInt("server_port", server.port)
                .putString("server_name", serverNameOf(server.info))
                .apply();

        serverListAdapter.setPairedKey(server.ip + ":" + server.port);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onServerSettingsChanged();
        }
        Toast.makeText(requireContext(), "已配对并连接: " + server.ip + ":" + server.port, Toast.LENGTH_SHORT).show();
    }

    /**
     * 从发现列表中选一台:优先匹配上次配对过的同名服务器（IP 变了也能找到）,否则选第一台。
     */
    private NetworkScanner.ServerInfo pickBestServer(List<NetworkScanner.ServerInfo> servers) {
        String lastName = SPUtils.getPrefs().getString("server_name", "");
        if (!lastName.isEmpty()) {
            for (NetworkScanner.ServerInfo s : servers) {
                if (lastName.equals(serverNameOf(s.info))) {
                    return s;
                }
            }
        }
        return servers.get(0);
    }

    /** 从 info 中提取设备身份名称(去掉末尾的 ip:port 部分),用于换 IP 后识别同一台服务器 */
    private String serverNameOf(String info) {
        return ServerEndpoint.serverNameOf(info);
    }

    private void testConnection() {
        String serverUrl = binding.etServerUrl.getText() == null ? "" : binding.etServerUrl.getText().toString().trim();
        String portStr = binding.etServerPort.getText() == null ? "" : binding.etServerPort.getText().toString().trim();
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "Enter server address or URL", Toast.LENGTH_SHORT).show();
            return;
        }

        int serverPort = parsePortOrDefault(serverUrl, portStr);
        if (serverPort <= 0) {
            Toast.makeText(requireContext(), "Invalid port", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(requireContext());
        progressDialog.setMessage("Testing connection...");
        progressDialog.show();

        new Thread(() -> {
            try {
                if (ServerEndpoint.isDirectUrl(serverUrl)) {
                    testServerInfo(ServerEndpoint.httpBaseUrl(serverUrl, serverPort));
                } else {
                    testSocket(serverUrl, serverPort);
                }
                mainHandler.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    progressDialog.dismiss();
                    Toast.makeText(requireContext(), "Connection succeeded", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded()) {
                        return;
                    }
                    progressDialog.dismiss();
                    Toast.makeText(requireContext(), "Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void testServerInfo(String baseUrl) throws Exception {
        URL url = new URL(ServerEndpoint.appendPath(baseUrl, AppConfig.SERVER_INFO_PATH));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
    }

    private void testSocket(String host, int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
        }
    }

    private int parsePortOrDefault(String serverUrl, String portStr) {
        try {
            int port = Integer.parseInt(portStr.trim());
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return ServerEndpoint.isDirectUrl(serverUrl) ? AppConfig.DEFAULT_SERVER_PORT : -1;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (statusUpdateHandler != null && statusUpdateRunnable != null) {
            statusUpdateHandler.removeCallbacks(statusUpdateRunnable);
        }
        if (serviceBound) {
            try {
                requireContext().unbindService(serviceConnection);
            } catch (Exception ignored) {
            }
            serviceBound = false;
        }
        if (networkScanner != null) {
            networkScanner.stop();
        }
        binding = null;
    }

    private static class ServerListAdapter extends RecyclerView.Adapter<ServerListAdapter.ViewHolder> {
        private final List<NetworkScanner.ServerInfo> servers;
        private final OnServerClickListener listener;
        private String pairedKey = null;

        interface OnServerClickListener {
            void onServerClick(NetworkScanner.ServerInfo server);
        }

        ServerListAdapter(List<NetworkScanner.ServerInfo> servers, OnServerClickListener listener) {
            this.servers = servers;
            this.listener = listener;
        }

        void setPairedKey(String key) {
            this.pairedKey = key;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_server, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            NetworkScanner.ServerInfo server = servers.get(position);
            holder.tvServerIp.setText(server.ip + ":" + server.port);
            holder.tvServerInfo.setText(server.info);
            boolean paired = pairedKey != null && pairedKey.equals(server.ip + ":" + server.port);
            holder.tvServerAction.setText(paired ? "当前已配对" : "点击配对");
            holder.itemView.setOnClickListener(v -> listener.onServerClick(server));
        }

        @Override
        public int getItemCount() {
            return servers.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvServerIp;
            TextView tvServerInfo;
            TextView tvServerAction;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvServerIp = itemView.findViewById(R.id.tvServerIp);
                tvServerInfo = itemView.findViewById(R.id.tvServerInfo);
                tvServerAction = itemView.findViewById(R.id.tvServerAction);
            }
        }
    }
}
