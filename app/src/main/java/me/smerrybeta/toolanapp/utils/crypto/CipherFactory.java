package me.smerrybeta.toolanapp.utils.crypto;

/**
 * 加密器工厂：按信封版本号构造实现。当前实装 A1（对称）；
 * R1（非对称混合）等后续方案在此注册。
 */
public final class CipherFactory {

    public static final String VERSION_A1 = "A1";

    private CipherFactory() {
    }

    public static CryptoCipher create(String version, String keyText) throws CryptoException {
        if (VERSION_A1.equals(version)) {
            return new A1Cipher(keyText);
        }
        throw new CryptoException("不支持的加密方案: " + version);
    }
}
