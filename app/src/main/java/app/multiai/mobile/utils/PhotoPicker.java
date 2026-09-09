package app.multiai.mobile.utils;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;

public final class PhotoPicker {
    public static final int MAX_IMAGES = 10;
    private PhotoPicker() {}

    public static Intent intent() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                    .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                            Math.min(MAX_IMAGES, MediaStore.getPickImagesMaxLimit()));
        }
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    public static List<Uri> results(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data == null) return uris;
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null && !uris.contains(uri)) uris.add(uri);
            }
        } else if (data.getData() != null) uris.add(data.getData());
        return uris;
    }
}
