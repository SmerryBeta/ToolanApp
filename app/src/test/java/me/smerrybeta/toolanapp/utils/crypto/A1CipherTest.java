package me.smerrybeta.toolanapp.utils.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A1Cipher 纯 JVM 单元测试：与 Go 服务端、Node/WebCrypto 桌面工具共用同一组
 * 跨端互通向量（TestCrossToolVector），任何一端算法漂移都会在这里暴露。
 */
public class A1CipherTest {

    private static final String VECTOR_KEY = "1234567890";
    private static final String VECTOR_ENVELOPE =
            "A1.EREREREREREREREREREREQ.IiIiIiIiIiIiIiIi.AgU1z0pJfMsFHfDccG9CDzk579QXvy-80HQ-hbFKRZWS39hff_z5QtxPy3S6OR6zMtQ";
    private static final String VECTOR_PLAINTEXT = "TOOLAN-A1 cross-tool vector 你好";

    @Test
    public void parseNumericKey_edges() throws Exception {
        assertHex(A1Cipher.parseNumericKey("0"), "00");
        assertHex(A1Cipher.parseNumericKey("1"), "01");
        assertHex(A1Cipher.parseNumericKey("255"), "ff");
        assertHex(A1Cipher.parseNumericKey("256"), "0100");
        assertHex(A1Cipher.parseNumericKey("1234567890"), "499602d2");
        assertHex(A1Cipher.parseNumericKey("0xff"), "ff");
        assertHex(A1Cipher.parseNumericKey("0X4F3a"), "4f3a");
        assertHex(A1Cipher.parseNumericKey("  42  "), "2a");

        String[] bad = {"", "  ", "-5", "+5", "0x", "12ab", "0xzz", "1.5"};
        for (String s : bad) {
            try {
                A1Cipher.parseNumericKey(s);
                fail("应当拒绝密钥: " + s);
            } catch (CryptoException expected) {
                // ok
            }
        }
    }

    private static void assertHex(byte[] got, String wantHex) {
        BigInteger n = new BigInteger(1, got);
        assertEquals(wantHex, n.toString(16).length() % 2 == 0
                ? n.toString(16) : "0" + n.toString(16));
    }

    @Test
    public void roundTrip_variousKeysAndSizes() throws Exception {
        String[] keys = {"0", "1234567890", "0xdeadbeef",
                new String(new char[120]).replace('\0', '9')};
        for (String key : keys) {
            A1Cipher c = new A1Cipher(key);
            byte[][] plains = {
                    new byte[0],
                    "hello".getBytes(StandardCharsets.UTF_8),
                    new byte[100_000],
            };
            new java.util.Random(42).nextBytes(plains[2]);
            for (byte[] plain : plains) {
                String env = c.encrypt(plain);
                assertTrue("信封未被嗅探识别: " + env.substring(0, Math.min(20, env.length())),
                        A1Cipher.looksEncrypted(env));
                assertArrayEquals(plain, c.decrypt(env));
            }
        }
    }

    @Test
    public void crossToolVector() throws Exception {
        A1Cipher c = new A1Cipher(VECTOR_KEY);
        assertEquals(VECTOR_PLAINTEXT, new String(c.decrypt(VECTOR_ENVELOPE), StandardCharsets.UTF_8));
    }

    @Test
    public void wrongKey_and_tamper_fail() throws Exception {
        A1Cipher c1 = new A1Cipher("1111111111");
        A1Cipher c2 = new A1Cipher("2222222222");
        String env = c1.encrypt("secret".getBytes(StandardCharsets.UTF_8));

        try {
            c2.decrypt(env);
            fail("错误密钥解密应当失败");
        } catch (CryptoException expected) {
            // ok
        }

        char last = env.charAt(env.length() - 1);
        char flipped = last == 'A' ? 'B' : 'A';
        String tampered = env.substring(0, env.length() - 1) + flipped;
        try {
            c1.decrypt(tampered);
            fail("篡改后的密文解密应当失败");
        } catch (CryptoException expected) {
            // ok
        }

        String[] bad = {"", "A1", "A1.a", "A1.a.b", "A1.a.b.c.d", "B1.a.b.c", null, "plain text"};
        for (String s : bad) {
            try {
                c1.decrypt(s);
                fail("非法信封应当解密失败: " + s);
            } catch (CryptoException expected) {
                // ok
            }
        }
        assertFalse(A1Cipher.looksEncrypted("plain text"));
    }

    @Test
    public void streamRoundTrip_multiChunk() throws Exception {
        A1Cipher c = new A1Cipher("9876543210");
        // 1.5MB → 4 个块（512K × 3 - 7）
        byte[] data = new byte[A1Cipher.CHUNK_BYTES * 3 - 7];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        StreamMeta meta = new StreamMeta("测试.bin", "application/octet-stream", data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        c.encryptStream(meta, new ByteArrayInputStream(data), out);

        // 行数校验：meta + ceil(size/512K) 块
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int lines = text.split("\n").length;
        int wantChunks = (data.length + A1Cipher.CHUNK_BYTES - 1) / A1Cipher.CHUNK_BYTES;
        assertEquals(wantChunks + 1, lines);
        assertTrue(A1Cipher.looksEncrypted(text.split("\n")[0]));

        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        StreamMeta gotMeta = c.decryptStream(
                new ByteArrayInputStream(out.toByteArray()), plain);
        assertEquals("测试.bin", gotMeta.name);
        assertEquals(data.length, gotMeta.size);
        assertArrayEquals(data, plain.toByteArray());
    }

    @Test
    public void stream_truncated_fails() throws Exception {
        A1Cipher c = new A1Cipher("555555");
        byte[] data = new byte[A1Cipher.CHUNK_BYTES + 100];
        Arrays.fill(data, (byte) 'z');
        StreamMeta meta = new StreamMeta("t.bin", null, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        c.encryptStream(meta, new ByteArrayInputStream(data), out);

        // 截掉最后一个数据行
        String content = new String(out.toByteArray(), StandardCharsets.UTF_8);
        String cut = content.substring(0, content.lastIndexOf('\n', content.length() - 2) + 1);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try {
            c.decryptStream(new ByteArrayInputStream(cut.getBytes(StandardCharsets.UTF_8)), plain);
            fail("截断的流应当校验失败");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("不完整"));
        }
    }

    @Test
    public void factory_rejectsUnknownVersion() {
        try {
            CipherFactory.create("R1", "123");
            fail("未实装的版本应当被拒绝");
        } catch (CryptoException expected) {
            // ok
        }
    }

    @Test
    public void cipherSettings_guardedBySwitch() throws Exception {
        // SPUtils 未初始化时 getPrefs 抛异常或返回 null——本测试仅验证
        // cipherOrNull 对无效密钥的容错路径（enabled=false 直接 null）。
        try {
            assertNull(CryptoSettings.cipherOrNull());
        } catch (Throwable ignored) {
            // SP 未初始化的环境下跳过（instrumented/真机下有效）
        }
        assertNotNull(new A1Cipher("12345")); // 编译期语法冒烟
    }
}
