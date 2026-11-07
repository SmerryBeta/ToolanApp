package me.smerrybeta.toolanapp.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import androidx.core.content.FileProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

import me.smerrybeta.toolanapp.AppConfig;
import me.smerrybeta.toolanapp.utils.crypto.CryptoCipher;
import me.smerrybeta.toolanapp.utils.crypto.CryptoSettings;
import me.smerrybeta.toolanapp.utils.crypto.StreamMeta;

/**
 * 控制面板 WebView 的文件下载 / 图片保存 / 分享工具。
 *
 * <p>与系统 {@link android.app.DownloadManager} 不同,这里全部由 App 自己开线程直连下载并落盘,
 * 因此不会触发"仅在 WiFi 下载""大文件蜂窝网络二次确认"等系统弹窗,体验更接近社交软件。</p>
 */
public final class WebFileUtils {

    /** 简单的结果回调,统一切回主线程执行。uri 在分享场景下为 FileProvider 内容地址。 */
    public interface Callback {
        void onResult(boolean success, String message, Uri uri);
    }

    /**
     * 流式下载到公共「下载」目录，全程不整包进内存（同步推送的文件上限与服务端
     * 一致可达 2GB，readAll 会 OOM）。加密响应（A1L）逐行解密直写，明文按块拷贝；
     * 返回落盘 Uri，失败抛 IOException 并清理半截文件。
     */
    public static Uri streamToDownloads(Context context, String url, String fileName,
                                        String mime, long knownSize, StreamProgress progress) throws IOException {
        Context app = context.getApplicationContext();
        HttpURLConnection conn = openConnection(url);
        try {
            boolean encrypted = "A1L".equalsIgnoreCase(conn.getHeaderField("X-Toolan-Crypto-Stream"));
            String safeName = sanitize(fileName);
            String mimeType = (mime != null && !mime.isEmpty()) ? mime : mimeForName(safeName);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = app.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("无法创建下载文件");
                }
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    if (os == null) {
                        throw new IOException("无法写入下载文件");
                    }
                    copyStream(conn, os, encrypted, knownSize, progress);
                    return uri;
                } catch (Exception e) {
                    resolver.delete(uri, null, null);
                    if (e instanceof IOException) {
                        throw (IOException) e;
                    }
                    throw new IOException(safeMsg((Exception) e));
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建下载目录");
                }
                File file = new File(dir, safeName);
                try (OutputStream os = new FileOutputStream(file)) {
                    copyStream(conn, os, encrypted, knownSize, progress);
                } catch (Exception e) {
                    file.delete();
                    if (e instanceof IOException) {
                        throw (IOException) e;
                    }
                    throw new IOException(safeMsg(e));
                }
                return Uri.fromFile(file);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copyStream(HttpURLConnection conn, OutputStream os, boolean encrypted,
                                   long knownSize, StreamProgress progress) throws IOException {
        CountingOutputStream counting = new CountingOutputStream(os, progress);
        if (encrypted) {
            CryptoCipher cipher = CryptoSettings.cipherOrNull();
            if (cipher == null) {
                throw new IOException("收到加密数据但本机未启用加密");
            }
            // meta.size>0 时 decryptStream 内部已校验总字节数；进度总量用已知大小
            cipher.decryptStream(conn.getInputStream(), counting);
        } else {
            long total = conn.getContentLength();
            if (total <= 0) {
                total = knownSize;
            }
            counting.setTotal(total);
            byte[] buffer = new byte[8192];
            int read;
            InputStream in = conn.getInputStream();
            while ((read = in.read(buffer)) != -1) {
                counting.write(buffer, 0, read);
            }
        }
        counting.reportFinal();
    }

    /** 计数并节流上报进度的输出流（每 256KB 或收尾时回调一次）。 */
    private static final class CountingOutputStream extends java.io.FilterOutputStream {
        private final StreamProgress progress;
        private long done = 0;
        private long lastReport = 0;
        private long total = -1;

        CountingOutputStream(OutputStream out, StreamProgress progress) {
            super(out);
            this.progress = progress;
        }

        void setTotal(long total) {
            this.total = total;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            done += len;
            maybeReport();
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            done++;
            maybeReport();
        }

        private void maybeReport() throws IOException {
            if (progress != null && done - lastReport >= 256 * 1024) {
                lastReport = done;
                progress.onProgress(done, total);
            }
        }

        void reportFinal() {
            if (progress != null) {
                progress.onProgress(done, total > 0 ? total : done);
            }
        }
    }

    /** 按文件扩展名推导分享/落盘用 MIME（图片→image/* 等,未知回退字节流）。 */
    public static String mimeForName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "application/octet-stream";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    /** 同步取一张小图（通知/弹框缩略图）：明文与 A1L 加密响应都支持，调用方自备 IO 线程。 */
    public static Bitmap fetchBitmap(String url) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IOException("图片地址为空");
        }
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url);
            byte[] data = readAll(conn.getInputStream());
            if (isEncryptedStream(conn, data)) {
                CryptoCipher cipher = CryptoSettings.cipherOrNull();
                if (cipher == null) {
                    throw new IOException("收到加密数据但本机未启用加密");
                }
                ByteArrayOutputStream plain = new ByteArrayOutputStream();
                cipher.decryptStream(new ByteArrayInputStream(data), plain);
                data = plain.toByteArray();
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) {
                throw new IOException("图片数据无效");
            }
            return bitmap;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 流式下载进度回调，IO 线程调用；total<=0 表示总量未知。 */
    public interface StreamProgress {
        void onProgress(long done, long total);
    }

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WebFileUtils() {
    }

    /**
     * 把指定 URL 的文件直连下载到公共"下载"目录(完成后的提示由调用方通过 callback 弹出)。
     * 若内容是 Android 安装包(按内容识别,后缀不限——仓库里常以 .zip 后缀存 APK),
     * 额外写一份 .apk 副本到缓存目录,经 callback 的 uri(FileProvider 地址)交给调用方安装。
     */
    public static void downloadToDownloads(Context context, String url, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = openConnection(url);
                String mimeType = conn.getContentType();
                // getContentType 可能带上参数,如 text/plain; charset=utf-8
                if (mimeType != null && mimeType.contains(";")) {
                    mimeType = mimeType.split(";")[0].trim();
                }
                String fileName = URLUtil.guessFileName(url, null, mimeType);
                byte[] data = readAll(conn.getInputStream());

                // 加密下载：响应为 A1L 流（首行 meta 信封 + 逐块数据信封），解密后
                // 文件名/MIME 以信封内 meta 为准
                if (isEncryptedStream(conn, data)) {
                    CryptoCipher cipher = CryptoSettings.cipherOrNull();
                    if (cipher == null) {
                        throw new IOException("收到加密数据但本机未启用加密");
                    }
                    ByteArrayOutputStream plain = new ByteArrayOutputStream();
                    StreamMeta meta = cipher.decryptStream(new ByteArrayInputStream(data), plain);
                    data = plain.toByteArray();
                    if (meta.name != null && !meta.name.trim().isEmpty()) {
                        fileName = meta.name.trim();
                    }
                    if (meta.mime != null && !meta.mime.isEmpty()) {
                        mimeType = meta.mime;
                    }
                }

                saveToDownloads(app, fileName, mimeType, data);
                File apk = prepareApkForInstall(app, fileName, data);
                if (apk != null) {
                    Uri installUri = FileProvider.getUriForFile(
                            app, app.getPackageName() + ".fileprovider", apk);
                    post(callback, true, "检测到安装包:" + fileName, installUri);
                } else {
                    post(callback, true, "已下载:" + fileName, null);
                }
            } catch (Exception e) {
                post(callback, false, "下载失败:" + safeMsg(e), null);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /** 响应头声明 A1L，或响应体以 "A1." 开头（嗅探兜底）。 */
    private static boolean isEncryptedStream(HttpURLConnection conn, byte[] data) {
        if ("A1L".equalsIgnoreCase(conn.getHeaderField("X-Toolan-Crypto-Stream"))) {
            return true;
        }
        return data != null && data.length > 3
                && data[0] == 'A' && data[1] == '1' && data[2] == '.';
    }

    /**
     * 若下载数据是 APK(本质是含 AndroidManifest.xml 的 zip),写一份统一 .apk 后缀的
     * 副本到缓存 apk/ 目录并返回;不是 APK 返回 null。zip 魔数先粗筛,普通文件零开销。
     */
    private static File prepareApkForInstall(Context app, String fileName, byte[] data) {
        if (data == null || data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            return null;
        }
        File dir = new File(app.getCacheDir(), "apk");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String name = sanitize(fileName);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        File file = new File(dir, name + ".apk");
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(data);
        } catch (Exception e) {
            file.delete();
            return null;
        }
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("AndroidManifest.xml") == null) {
                file.delete();
                return null;
            }
        } catch (Exception e) {
            file.delete();
            return null;
        }
        return file;
    }

    /**
     * 下载(或解码 dataURI)图片并保存到系统相册 Pictures/Toolan。
     */
    public static void saveImageToGallery(Context context, String imageUrl, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                byte[] data = loadImageBytes(imageUrl);
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) {
                    post(callback, false, "图片数据无效,无法保存", null);
                    return;
                }
                String fileName = imageFileName(imageUrl);
                String mime = mimeForImage(fileName);
                saveBitmapToGallery(app, fileName, bitmap, mime);
                bitmap.recycle();
                post(callback, true, "已保存到相册", null);
            } catch (Exception e) {
                post(callback, false, "保存失败:" + safeMsg(e), null);
            }
        });
    }

    /**
     * 流式下载任意类型文件到缓存 shared/ 目录,返回 FileProvider 分享地址。
     * 保存原始字节（不转码）,图片/视频/音频按扩展名的 MIME 分享,QQ/微信等
     * 按"发图片/发文件"的原生流程接收;加密响应（A1L）自动逐行解密。
     * 分享前清空目录只保留本次文件——目标 App 在分享发起后仍会异步读取该地址。
     */
    public static void prepareSharedFile(Context context, String url, String fileName, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            HttpURLConnection conn = null;
            try {
                File dir = new File(app.getCacheDir(), "shared");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建分享缓存目录");
                }
                File[] oldFiles = dir.listFiles();
                if (oldFiles != null) {
                    for (File f : oldFiles) {
                        f.delete();
                    }
                }
                String safeName = sanitize(fileName);
                conn = openConnection(url);
                boolean encrypted = "A1L".equalsIgnoreCase(conn.getHeaderField("X-Toolan-Crypto-Stream"));
                File file = new File(dir, safeName);
                try (OutputStream os = new FileOutputStream(file)) {
                    copyStream(conn, os, encrypted, 0, null);
                } catch (Exception e) {
                    file.delete();
                    if (e instanceof IOException) {
                        throw (IOException) e;
                    }
                    throw new IOException(safeMsg(e));
                }
                Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", file);
                post(callback, true, safeName, uri);
            } catch (Exception e) {
                post(callback, false, "分享准备失败:" + safeMsg(e), null);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "ToolanApp");
        // 加密开启时声明加密通道：/api/download 等端点据此返回 A1L 流；
        // 明文端点（/static 等）忽略此头，行为不变
        CryptoCipher cipher = CryptoSettings.cipherOrNull();
        if (cipher != null) {
            conn.setRequestProperty(AppConfig.CRYPTO_HEADER, cipher.version());
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        return conn;
    }

    private static byte[] loadImageBytes(String imageUrl) throws IOException {
        if (imageUrl == null || imageUrl.isEmpty()) {
            throw new IOException("图片地址为空");
        }
        if (imageUrl.startsWith("data:")) {
            int comma = imageUrl.indexOf(',');
            if (comma < 0) {
                throw new IOException("dataURI 格式错误");
            }
            return Base64.decode(imageUrl.substring(comma + 1), Base64.DEFAULT);
        }
        HttpURLConnection conn = openConnection(imageUrl);
        try {
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static void saveToDownloads(Context ctx, String fileName, String mime, byte[] data) throws IOException {
        String safeName = sanitize(fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
            values.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建下载文件");
            }
            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) {
                    throw new IOException("无法写入下载文件");
                }
                os.write(data);
            }
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建下载目录");
            }
            try (FileOutputStream fos = new FileOutputStream(new File(dir, safeName))) {
                fos.write(data);
            }
        }
    }

    private static Uri saveBitmapToGallery(Context ctx, String fileName, Bitmap bitmap, String mime) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, sanitize(fileName));
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Toolan");
        }
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("无法创建图片文件");
        }
        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) {
                throw new IOException("无法写入图片文件");
            }
            compress(bitmap, fileName, os);
        }
        return uri;
    }

    private static void compress(Bitmap bitmap, String fileName, OutputStream os) throws IOException {
        Bitmap.CompressFormat format = fileName.toLowerCase(Locale.US).endsWith(".png")
                ? Bitmap.CompressFormat.PNG
                : Bitmap.CompressFormat.JPEG;
        if (!bitmap.compress(format, 95, os)) {
            throw new IOException("图片编码失败");
        }
    }

    private static String imageFileName(String imageUrl) {
        if (imageUrl != null && imageUrl.startsWith("data:")) {
            String ext = imageUrl.contains("image/png") ? "png" : "jpg";
            return "IMG_" + System.currentTimeMillis() + "." + ext;
        }
        String path = imageUrl == null ? "" : imageUrl;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty() || !name.contains(".")) {
            return "IMG_" + System.currentTimeMillis() + ".jpg";
        }
        return name;
    }

    private static String mimeForImage(String fileName) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "jpg";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.US));
        return mime == null ? "image/jpeg" : mime;
    }

    private static String sanitize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "file-" + System.currentTimeMillis();
        }
        return value.replace('\\', '_')
                .replace('/', '_')
                .replace('\r', '_')
                .replace('\n', '_')
                .trim();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }
        return bos.toByteArray();
    }

    private static String safeMsg(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private static void post(Callback callback, boolean success, String message, Uri uri) {
        if (callback == null) {
            return;
        }
        MAIN.post(() -> callback.onResult(success, message, uri));
    }
}
