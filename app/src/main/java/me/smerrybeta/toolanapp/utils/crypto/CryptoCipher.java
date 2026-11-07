package me.smerrybeta.toolanapp.utils.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 应用层加密方案统一接口。信封首段为版本号（A1、预留的 R1 等），
 * 新方案实现本接口并在 {@link CipherFactory} 注册即可。
 */
public interface CryptoCipher {
    /** 信封版本标识，如 "A1"。 */
    String version();

    /** 加密任意字节，返回单段信封文本（A1.salt.iv.ciphertext）。 */
    String encrypt(byte[] plain) throws CryptoException;

    /** 解密单段信封；格式错误、密钥不符或被篡改时抛出 {@link CryptoException}。 */
    byte[] decrypt(String envelope) throws CryptoException;

    /** 把 in 的内容分块加密为 A1L 文本流（首行 meta，其后每行一个数据块）写入 out。 */
    void encryptStream(StreamMeta meta, InputStream in, OutputStream out) throws IOException;

    /** 逐行解密 A1L 流并把明文写入 out，返回首行 meta；size>0 时校验总字节数。 */
    StreamMeta decryptStream(InputStream in, OutputStream out) throws IOException;
}
