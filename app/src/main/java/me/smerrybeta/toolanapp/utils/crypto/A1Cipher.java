package me.smerrybeta.toolanapp.utils.crypto;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONException;

/**
 * A1 对称加密：AES-256-GCM + PBKDF2-HMAC-SHA256（310000 迭代）。
 * 信封格式 A1.&lt;b64url(salt16)&gt;.&lt;b64url(iv12)&gt;.&lt;b64url(ct+tag16)&gt;，
 * 与服务端 Go 实现、浏览器 WebCrypto 实现、桌面工具三方互通。
 *
 * <p>性能：加密方向使用实例级固定 salt（构造时随机生成，信封内仍携带），
 * 使 PBKDF2 派生在进程内只发生一次；IV 每条随机，GCM 安全性不受影响。
 * 解密方向按信封内 salt 派生并缓存（上限 {@link #MAX_CACHED_SALTS} 个）。</p>
 */
public class A1Cipher implements CryptoCipher {

    public static final String VERSION = "A1";
    public static final int ITERATIONS = 310000;
    public static final int SALT_BYTES = 16;
    public static final int IV_BYTES = 12;
    public static final int KEY_BITS = 256;
    public static final int TAG_BITS = 128;
    /** A1L 流式传输单个数据块的明文上限（与服务端一致）。 */
    public static final int CHUNK_BYTES = 512 * 1024;
    private static final int MAX_CACHED_SALTS = 8;

    private final byte[] rawKey;
    private final byte[] instanceSalt;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKeySpec> keyCache = new HashMap<>();

    public A1Cipher(String keyText) throws CryptoException {
        this.rawKey = parseNumericKey(keyText);
        this.instanceSalt = new byte[SALT_BYTES];
        random.nextBytes(instanceSalt);
    }

    @Override
    public String version() {
        return VERSION;
    }

    /** 嗅探文本是否为 A1 单段信封（前缀 + 恰好 4 段）。 */
    public static boolean looksEncrypted(String text) {
        return text != null && text.startsWith(VERSION + ".")
                && countDots(text) == 3;
    }

    private static int countDots(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '.') {
                n++;
            }
        }
        return n;
    }

    /**
     * 按桌面工具的规则解析用户密钥：纯十进制或 0x 开头的十六进制大整数，
     * 转为最短无符号大端字节序（0 编码为单字节 00）。
     */
    public static byte[] parseNumericKey(String text) throws CryptoException {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            throw new CryptoException("密钥不能为空");
        }

        BigInteger n;
        if (s.startsWith("0x") || s.startsWith("0X")) {
            String hex = s.substring(2);
            if (hex.isEmpty()) {
                throw new CryptoException("密钥格式错误：0x 后需要十六进制数字");
            }
            try {
                n = new BigInteger(hex, 16);
            } catch (NumberFormatException e) {
                throw new CryptoException("密钥格式错误：非法十六进制数字");
            }
        } else {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '-' || c == '+') {
                    throw new CryptoException("密钥只能是十进制数字，或 0x 开头的十六进制数字");
                }
                if (c < '0' || c > '9') {
                    throw new CryptoException("密钥只能是十进制数字，或 0x 开头的十六进制数字");
                }
            }
            try {
                n = new BigInteger(s, 10);
            } catch (NumberFormatException e) {
                throw new CryptoException("密钥格式错误");
            }
        }

        if (n.signum() < 0) {
            throw new CryptoException("密钥不能为负数");
        }
        if (n.signum() == 0) {
            return new byte[]{0};
        }
        // BigInteger.toByteArray 对正数可能带符号位填充的前导 0，裁掉保持"最短大端"
        byte[] bytes = n.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    /** 按 salt 派生 AES 密钥（带缓存）。 */
    private SecretKeySpec keyFor(byte[] salt) throws CryptoException {
        String cacheKey = java.util.Base64.getEncoder().encodeToString(salt);
        synchronized (keyCache) {
            SecretKeySpec cached = keyCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        // 注意：不能用 PBEKeySpec——它会把口令字符按 UTF-8 编码，密钥字节 ≥0x80
        // 时会膨胀成两字节，与 Go/JS 侧"原始字节直接作口令"的语义不一致。
        // 这里手写 PBKDF2（RFC 2898），直接以原始字节为 HMAC 口令，三端对齐。
        try {
            byte[] derived = pbkdf2(rawKey, salt, ITERATIONS, KEY_BITS / 8);
            SecretKeySpec key = new SecretKeySpec(derived, "AES");

            synchronized (keyCache) {
                if (keyCache.size() >= MAX_CACHED_SALTS) {
                    keyCache.clear();
                }
                keyCache.put(cacheKey, key);
            }
            return key;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("密钥派生失败", e);
        }
    }

    /** PBKDF2-HMAC-SHA256（RFC 2898）：口令为原始字节，与 Go/JS 实现完全一致。 */
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int keyLen)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        int hLen = mac.getMacLength();
        int blocks = (keyLen + hLen - 1) / hLen;
        byte[] out = new byte[blocks * hLen];

        byte[] blockBase = new byte[salt.length + 4];
        System.arraycopy(salt, 0, blockBase, 0, salt.length);
        for (int i = 1; i <= blocks; i++) {
            blockBase[salt.length] = (byte) (i >>> 24);
            blockBase[salt.length + 1] = (byte) (i >>> 16);
            blockBase[salt.length + 2] = (byte) (i >>> 8);
            blockBase[salt.length + 3] = (byte) i;

            byte[] u = mac.doFinal(blockBase);
            byte[] t = u.clone();
            for (int iter = 1; iter < iterations; iter++) {
                u = mac.doFinal(u);
                for (int j = 0; j < hLen; j++) {
                    t[j] ^= u[j];
                }
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen);
        }
        return java.util.Arrays.copyOf(out, keyLen);
    }

    private Cipher gcmCipher(SecretKeySpec key, int mode, byte[] iv) throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM 初始化失败", e);
        }
    }

    @Override
    public String encrypt(byte[] plain) throws CryptoException {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            SecretKeySpec key = keyFor(instanceSalt);
            byte[] sealed = gcmCipher(key, Cipher.ENCRYPT_MODE, iv).doFinal(plain);

            java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder().withoutPadding();
            return VERSION + "."
                    + enc.encodeToString(instanceSalt) + "."
                    + enc.encodeToString(iv) + "."
                    + enc.encodeToString(sealed);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("加密失败", e);
        }
    }

    @Override
    public byte[] decrypt(String envelope) throws CryptoException {
        String text = envelope == null ? "" : envelope.trim();
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new CryptoException("密文格式不正确");
        }

        java.util.Base64.Decoder dec = java.util.Base64.getUrlDecoder();
        byte[] salt;
        byte[] iv;
        byte[] data;
        try {
            salt = dec.decode(parts[1]);
            iv = dec.decode(parts[2]);
            data = dec.decode(parts[3]);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("密文编码错误");
        }

        if (salt.length != SALT_BYTES) {
            throw new CryptoException("密文 salt 长度错误");
        }
        if (iv.length != IV_BYTES) {
            throw new CryptoException("密文 nonce 长度错误");
        }
        if (data.length < TAG_BITS / 8) {
            throw new CryptoException("密文长度过短");
        }

        try {
            SecretKeySpec key = keyFor(salt);
            return gcmCipher(key, Cipher.DECRYPT_MODE, iv).doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("解密失败：密钥不正确或密文已被修改");
        }
    }

    @Override
    public void encryptStream(StreamMeta meta, InputStream in, OutputStream out) throws IOException {
        try {
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(out, StandardCharsets.UTF_8), 128 * 1024);
            writer.write(encrypt(meta.toJson().getBytes(StandardCharsets.UTF_8)));
            writer.write('\n');

            byte[] buf = new byte[CHUNK_BYTES];
            while (true) {
                int n = readFull(in, buf);
                if (n > 0) {
                    byte[] chunk = n == buf.length ? buf : java.util.Arrays.copyOf(buf, n);
                    writer.write(encrypt(chunk));
                    writer.write('\n');
                }
                if (n < buf.length) {
                    break; // EOF
                }
            }
            writer.flush();
        } catch (JSONException e) {
            throw new IOException("流元数据序列化失败", e);
        } catch (CryptoException e) {
            throw e;
        }
    }

    @Override
    public StreamMeta decryptStream(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 128 * 1024);

        String metaLine = reader.readLine();
        if (metaLine == null) {
            throw new IOException("加密流为空");
        }
        StreamMeta meta;
        try {
            meta = StreamMeta.fromJson(new String(decrypt(metaLine), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("流元数据格式错误", e);
        }

        long written = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            byte[] plain = decrypt(line);
            out.write(plain);
            written += plain.length;
        }

        if (meta.size > 0 && written != meta.size) {
            throw new IOException("流不完整：期望 " + meta.size + " 字节，实际 " + written + " 字节");
        }
        return meta;
    }

    /** 尽量填满缓冲；返回实际读取数（< len 表示已到流末尾）。 */
    private static int readFull(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }
}
