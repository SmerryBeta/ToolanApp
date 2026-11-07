package me.smerrybeta.toolanapp;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import me.smerrybeta.toolanapp.utils.SharedFileUploader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ShareUploadActivity extends AppCompatActivity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SPUtils.init(getApplicationContext());
        handleShareIntent(getIntent());
    }

    private void handleShareIntent(Intent intent) {
        List<Uri> uris = collectSharedUris(intent);
        if (uris.isEmpty()) {
            Toast.makeText(this, "No shared file found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(uris.size() == 1 ? "Uploading shared file..." : "Uploading shared files...");
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(() -> uploadFiles(uris)).start();
    }

    private void uploadFiles(List<Uri> uris) {
        int uploaded = 0;
        int skipped = 0;
        Exception lastError = null;
        for (Uri uri : uris) {
            try {
                SharedFileUploader.upload(this, uri, false);
                uploaded++;
            } catch (SharedFileUploader.FileExistsException e) {
                // 服务端已有同名文件：先问用户，确认覆盖才带 overwrite 重传
                if (askOverwrite(e.fileName)) {
                    try {
                        SharedFileUploader.upload(this, uri, true);
                        uploaded++;
                    } catch (Exception retryError) {
                        lastError = retryError;
                    }
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                lastError = e;
            }
        }

        int finalUploaded = uploaded;
        int finalSkipped = skipped;
        Exception finalLastError = lastError;
        mainHandler.post(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            if (finalUploaded == uris.size()) {
                Toast.makeText(this, "Upload complete: " + finalUploaded, Toast.LENGTH_SHORT).show();
            } else if (finalUploaded > 0) {
                Toast.makeText(this, "Uploaded " + finalUploaded + "/" + uris.size(), Toast.LENGTH_LONG).show();
            } else if (finalSkipped > 0) {
                Toast.makeText(this, "Skipped " + finalSkipped + " existing file(s)", Toast.LENGTH_LONG).show();
            } else {
                String message = finalLastError == null ? "Upload failed" : finalLastError.getMessage();
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }

    /** 后台线程阻塞等待用户在主线程选择是否覆盖；Activity 已销毁时默认跳过。 */
    private boolean askOverwrite(String fileName) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                future.complete(false);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("文件已存在")
                    .setMessage("服务器上已有「" + fileName + "」，用本次分享的文件覆盖它吗？")
                    .setCancelable(false)
                    .setNegativeButton("跳过", (d, w) -> future.complete(false))
                    .setPositiveButton("覆盖", (d, w) -> future.complete(true))
                    .show();
        });
        try {
            return Boolean.TRUE.equals(future.get());
        } catch (Exception e) {
            return false;
        }
    }

    private List<Uri> collectSharedUris(Intent intent) {
        Set<Uri> result = new LinkedHashSet<>();
        if (intent == null) {
            return new ArrayList<>();
        }

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                result.add(stream);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                result.addAll(streams);
            }
        }

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    result.add(uri);
                }
            }
        }

        return new ArrayList<>(result);
    }
}
