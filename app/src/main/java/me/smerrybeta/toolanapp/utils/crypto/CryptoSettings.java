package me.smerrybeta.toolanapp.utils.crypto;

import me.smerrybeta.toolanapp.SPUtils;

/**
 * 加密设置的统一读取入口：开关、密钥、以及"当前是否可构建加密器"。
 * SP 键：crypto_enabled / crypto_key。
 */
public final class CryptoSettings {

    private CryptoSettings() {
    }

    /** 加密开关是否开启。 */
    public static boolean enabled() {
        return SPUtils.getPrefs().getBoolean("crypto_enabled", false);
    }

    /** 已保存的密钥文本（十进制或 0x 十六进制）。 */
    public static String key() {
        return SPUtils.getPrefs().getString("crypto_key", "");
    }

    /** 开启且密钥格式有效时返回 A1 加密器；否则返回 null（明文模式）。 */
    public static CryptoCipher cipherOrNull() {
        if (!enabled()) {
            return null;
        }
        String key = key();
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        try {
            return CipherFactory.create(CipherFactory.VERSION_A1, key.trim());
        } catch (CryptoException e) {
            return null;
        }
    }
}
