package app.multiai.mobile.utils;

import android.content.Context;

import app.multiai.mobile.models.Message;
import app.multiai.mobile.ui.ImageViewerActivity;

/** Entry point for generated-image interactions (fullscreen preview, download, share). */
public final class GeneratedImageActions {
    private GeneratedImageActions() {}

    public static void show(Context context, Message message) {
        ImageViewerActivity.open(context, message);
    }
}
