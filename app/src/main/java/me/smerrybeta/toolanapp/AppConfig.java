package me.smerrybeta.toolanapp;

public final class AppConfig {
    public static final int DEFAULT_SERVER_PORT = 8000;
    public static final int LEGACY_SERVER_PORT = 1225;
    public static final int[] LAN_SCAN_PORTS = {
            DEFAULT_SERVER_PORT,
            LEGACY_SERVER_PORT
    };
    public static final String SERVER_PROTOCOL = "toolan-gin-ws-v1";
    public static final String SERVER_KIND = "TOOLAN_GIN_BACKEND";
    public static final String SERVER_INFO_PATH = "/server_info";
    public static final String WEBSOCKET_PATH = "/ws";

    /** 密钥一致性验证接口：POST，body 为 A1 信封包裹的随机 nonce。 */
    public static final String CRYPTO_VERIFY_PATH = "/api/crypto/verify";
    /** 加密上传时单个数据块的明文大小。 */
    public static final int CRYPTO_UPLOAD_CHUNK_BYTES = 4 * 1024 * 1024;
    /** 加密协商头：携带此头的请求/响应走 A1 加密通道。 */
    public static final String CRYPTO_HEADER = "X-Toolan-Crypto";

    private AppConfig() {
    }
}
