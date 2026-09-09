package app.multiai.mobile.ui;

import android.app.Activity;
import android.content.ContentValues;import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.OutputStream;

import app.multiai.mobile.R;
import app.multiai.mobile.models.Message;

/**
 * Fullscreen image viewer (ChatGPT-style): black backdrop, the generated image
 * centered, close on the left, download + white "Поделиться" pill on the right.
 */
public class ImageViewerActivity extends Activity {

    /** Messages can exceed the Intent binder limit — hand the payload over statically. */
    @Nullable
    private static Message pendingMessage;

    private Message message;
    private Bitmap bitmap;

    public static void open(Context context, Message message) {
        pendingMessage = message;
        Intent intent = new Intent(context, ImageViewerActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        if (context instanceof Activity) {
            ((Activity) context).overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);
        message = pendingMessage;
        pendingMessage = null;

        ImageView image = findViewById(R.id.viewerImage);
        ProgressBar progress = findViewById(R.id.viewerProgress);
        View close = findViewById(R.id.btnViewerClose);
        View download = findViewById(R.id.btnViewerDownload);
        View share = findViewById(R.id.btnViewerShare);

        close.setOnClickListener(v -> finish());

        download.setOnClickListener(v -> {
            if (bitmap == null) return;
            Uri saved = saveToGallery(this, message, bitmap);
            Toast.makeText(this, saved != null ? R.string.image_viewer_saved
                    : R.string.image_viewer_save_failed, Toast.LENGTH_SHORT).show();
        });

        share.setOnClickListener(v -> {
            if (bitmap == null) return;
            Uri saved = saveToGallery(this, message, bitmap);
            if (saved == null) {
                Toast.makeText(this, R.string.image_viewer_save_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, saved);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.image_viewer_chooser_title)));
        });

        // Full-res decode off the UI thread — thumbs use a downscaled cache.
        new Thread(() -> {
            Bitmap decoded = decodeFull(message);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                if (decoded == null) {
                    Toast.makeText(this, R.string.image_viewer_open_failed, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                bitmap = decoded;
                image.setImageBitmap(bitmap);
            });
        }, "viewer-decode").start();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        super.onDestroy();
    }

    @Nullable
    private static Bitmap decodeFull(@Nullable Message message) {
        if (message == null) return null;
        try {
            String base64 = message.getImageBase64();
            byte[] bytes;
            if (base64 != null && !base64.isEmpty()) {
                if (base64.startsWith("data:image")) base64 = base64.substring(base64.indexOf(',') + 1);
                bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            } else if (message.getImagePath() != null) {
                return BitmapFactory.decodeFile(message.getImagePath());
            } else {
                return null;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Persist into Pictures/MultiAI via MediaStore; the returned URI is shareable. */
    @Nullable
    private static Uri saveToGallery(Context context, @Nullable Message message, Bitmap bitmap) {
        String name = message != null ? message.getImageName() : null;
        if (name == null || name.trim().isEmpty()) name = "multiai-image-" + System.currentTimeMillis() + ".png";
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) name += ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MultiAI");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = context.getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new java.io.IOException("compress failed");
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, done, null, null);
            }
            return uri;
        } catch (Exception e) {
            context.getContentResolver().delete(uri, null, null);
            return null;
        }
    }
}
