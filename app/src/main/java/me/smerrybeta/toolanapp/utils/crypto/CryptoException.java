package me.smerrybeta.toolanapp.utils.crypto;

import java.io.IOException;

/**
 * 应用层加密异常：密钥格式错误、解密失败（密钥不符/密文被篡改/格式非法）等。
 * 继承 IOException，流式接口可以统一按 IOException 处理。
 */
public class CryptoException extends IOException {
    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
