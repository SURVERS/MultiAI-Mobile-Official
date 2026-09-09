package app.multiai.mobile.utils;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;

public final class AttachmentImageLoader {
    private AttachmentImageLoader() {}
    public static Bitmap decode(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        try (InputStream in = resolver.openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) throw new IOException("Cannot decode image");
            return bitmap;
        }
    }
}
