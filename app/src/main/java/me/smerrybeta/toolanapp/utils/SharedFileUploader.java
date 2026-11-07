package me.smerrybeta.toolanapp.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import me.smerrybeta.toolanapp.AppConfig;
import me.smerrybeta.toolanapp.utils.crypto.CryptoCipher;
import me.smerrybeta.toolanapp.utils.crypto.CryptoSettings;
import me.smerrybeta.toolanapp.utils.crypto.StreamMeta;

public final class SharedFileUploader {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;

    private SharedFileUploader() {
    }

    /** 服务端已有同名文件（HTTP 409），由调用方确认是否覆盖后重传。 */
    public static class FileExistsException extends IOException {
        public final String fileName;

        public FileExistsException(String fileName) {
            super("服务器上已存在: " + fileName);
            this.fileName = fileName;
        }
    }

    public static void upload(Context context, Uri uri) throws IOException {
        upload(context, uri, false);
    }

    /** @param overwrite true 表示用户已确认覆盖服务端同名文件（?overwrite=1）。 */
    public static void upload(Context context, Uri uri, boolean overwrite) throws IOException {
        String uploadUrl = ServerEndpoint.uploadUrlFromPrefs();
        if (uploadUrl.isEmpty()) {
            throw new IOException("Server address not configured");
        }
        if (overwrite) {
            uploadUrl += (uploadUrl.contains("?") ? "&" : "?") + "overwrite=1";
        }

        String fileName = resolveFileName(context, uri);
        String boundary = "toolan-upload-" + System.currentTimeMillis();
        URL url = new URL(uploadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        // 加密开启时走 meta + chunk 分块信封上传，协商头让服务端切换到解密分支
        CryptoCipher cipher = CryptoSettings.cipherOrNull();
        int code;
        try {
            if (cipher != null) {
                conn.setRequestProperty(AppConfig.CRYPTO_HEADER, cipher.version());
                uploadEncrypted(context, uri, cipher, fileName, boundary, conn);
            } else {
                uploadPlain(context, uri, fileName, boundary, conn);
            }
            code = conn.getResponseCode();
        } catch (IOException e) {
            // 服务端可能在流式读取中途就返回 409（读到 meta 段即拒绝），本地先遇到
            // broken pipe——再尝试读一次真实状态码，读到 409 仍按冲突处理
            try {
                code = conn.getResponseCode();
            } catch (IOException unreadable) {
                throw e;
            }
        }
        if (code == 409) {
            conn.disconnect();
            throw new FileExistsException(fileName);
        }
        if (code < 200 || code >= 300) {
            String error = conn.getResponseMessage();
            conn.disconnect();
            throw new IOException("Upload failed: HTTP " + code + (error == null ? "" : " " + error));
        }
        conn.disconnect();
    }

    /** 明文上传：单一 file 段（兼容服务端旧逻辑）。 */
    private static void uploadPlain(Context context, Uri uri, String fileName,
                                    String boundary, HttpURLConnection conn) throws IOException {
        try (OutputStream rawOut = new BufferedOutputStream(conn.getOutputStream())) {
            writeAscii(rawOut, "--" + boundary + "\r\n");
            writeAscii(rawOut, "Content-Disposition: form-data; name=\"file\"; filename=\"" + escapeFileName(fileName) + "\"\r\n");
            String mimeType = resolveMime(context, uri);
            writeAscii(rawOut, "Content-Type: " + mimeType + "\r\n\r\n");
            copyContent(context, uri, rawOut);
            writeAscii(rawOut, "\r\n--" + boundary + "--\r\n");
            rawOut.flush();
        }
    }

    /**
     * 加密分块上传：meta 段（A1 信封包裹文件名/MIME/大小）在前，
     * 其后每个 chunk 段为一段 4MB 明文的 A1 信封，文件名不出现在明文中。
     */
    private static void uploadEncrypted(Context context, Uri uri, CryptoCipher cipher,
                                        String fileName, String boundary,
                                        HttpURLConnection conn) throws IOException {
        StreamMeta meta = new StreamMeta(fileName, resolveMime(context, uri), resolveSize(context, uri));
        String metaEnvelope;
        try {
            metaEnvelope = cipher.encrypt(meta.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("上传元数据序列化失败", e);
        }

        try (OutputStream rawOut = new BufferedOutputStream(conn.getOutputStream(), 1 << 16)) {
            writeAscii(rawOut, "--" + boundary + "\r\n");
            writeAscii(rawOut, "Content-Disposition: form-data; name=\"meta\"\r\n\r\n");
            writeAscii(rawOut, metaEnvelope);
            writeAscii(rawOut, "\r\n");

            try (InputStream in = new BufferedInputStream(context.getContentResolver().openInputStream(uri))) {
                if (in == null) {
                    throw new IOException("Unable to open shared file");
                }
                byte[] buffer = new byte[AppConfig.CRYPTO_UPLOAD_CHUNK_BYTES];
                int n;
                while ((n = readFull(in, buffer)) > 0) {
                    byte[] chunk = n == buffer.length ? buffer : Arrays.copyOf(buffer, n);
                    writeAscii(rawOut, "--" + boundary + "\r\n");
                    writeAscii(rawOut, "Content-Disposition: form-data; name=\"chunk\"\r\n\r\n");
                    writeAscii(rawOut, cipher.encrypt(chunk));
                    writeAscii(rawOut, "\r\n");
                }
            }

            writeAscii(rawOut, "--" + boundary + "--\r\n");
            rawOut.flush();
        }
    }

    private static String resolveMime(Context context, Uri uri) {
        String mimeType = context.getContentResolver().getType(uri);
        if (mimeType == null || mimeType.trim().isEmpty()) {
            mimeType = "application/octet-stream";
        }
        return mimeType;
    }

    /** 尽力取得文件大小（加密上传的完整性校验用）；取不到返回 0 表示跳过校验。 */
    private static long resolveSize(Context context, Uri uri) {
        try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null && afd.getLength() > 0) {
                return afd.getLength();
            }
        } catch (Exception ignored) {
        }
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    long size = cursor.getLong(index);
                    if (size > 0) {
                        return size;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static int readFull(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    public static String resolveFileName(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return sanitizeFileName(name.trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        String fallback = uri.getLastPathSegment();
        if (fallback == null || fallback.trim().isEmpty()) {
            fallback = "shared-" + System.currentTimeMillis();
        }
        String mimeType = resolver.getType(uri);
        String ext = extensionForMime(mimeType);
        fallback = sanitizeFileName(fallback);
        if (!ext.isEmpty() && !fallback.toLowerCase(Locale.US).endsWith("." + ext)) {
            fallback += "." + ext;
        }
        return fallback;
    }

    private static void copyContent(Context context, Uri uri, OutputStream out) throws IOException {
        try (InputStream in = new BufferedInputStream(context.getContentResolver().openInputStream(uri))) {
            if (in == null) {
                throw new IOException("Unable to open shared file");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static String sanitizeFileName(String value) {
        String cleaned = value.replace('\\', '_')
                .replace('/', '_')
                .replace('\r', '_')
                .replace('\n', '_')
                .trim();
        return cleaned.isEmpty() ? "shared-" + System.currentTimeMillis() : cleaned;
    }

    private static String escapeFileName(String value) {
        return sanitizeFileName(value).replace("\"", "'");
    }

    private static String extensionForMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        return ext == null ? "" : ext;
    }

    private static void writeAscii(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }
}
