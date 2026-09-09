package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared image-generation options (size / quality), mirroring the desktop
 * picker: gpt-image-2 → low/medium/high, nano-banana → standard only.
 * Persisted in the app settings prefs so the model card sheet and the
 * generation request stay in sync across both chat modes.
 */
public final class ImageGenOptions {
    public static final String SIZE_SQUARE = "1024x1024";
    public static final String SIZE_PORTRAIT = "1024x1536";
    public static final String SIZE_LANDSCAPE = "1536x1024";
    public static final String QUALITY_STANDARD = "standard";

    private static final String PREFS = "multiaiide_settings";
    private static final String KEY_SIZE = "imagegen_size";
    private static final String KEY_QUALITY = "imagegen_quality";

    private ImageGenOptions() {}

    /** gpt-image / nano-banana / dall-e / imagen / flux style image models. */
    public static boolean isImageModel(String modelId) {
        if (modelId == null) return false;
        String s = modelId.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("ma-")) s = s.substring(3);
        return s.contains("image") || s.contains("banana") || s.contains("dall-e")
                || s.contains("imagen") || s.contains("flux");
    }

    public static List<String> sizes() {
        return Arrays.asList(SIZE_SQUARE, SIZE_PORTRAIT, SIZE_LANDSCAPE);
    }

    /** Aspect-ratio labels for the size chips: 1:1, 2:3, 3:2. */
    public static List<String> sizeLabels() {
        return Arrays.asList("1:1", "2:3", "3:2");
    }

    public static List<String> qualitiesFor(String modelId) {
        if (modelId != null && modelId.toLowerCase(java.util.Locale.ROOT).contains("banana")) {
            return new ArrayList<>(List.of(QUALITY_STANDARD));
        }
        return new ArrayList<>(Arrays.asList("low", "medium", "high"));
    }

    public static int qualityLabelRes(String quality) {
        switch (quality) {
            case "low": return app.multiai.mobile.R.string.mc_q_low;
            case "medium": return app.multiai.mobile.R.string.mc_q_medium;
            case "high": return app.multiai.mobile.R.string.mc_q_high;
            default: return app.multiai.mobile.R.string.mc_q_standard;
        }
    }

    public static String savedSize(Context ctx, String modelId) {
        String saved = prefs(ctx).getString(KEY_SIZE, null);
        return sizes().contains(saved) ? saved : SIZE_SQUARE;
    }

    public static String savedQuality(Context ctx, String modelId) {
        String saved = prefs(ctx).getString(KEY_QUALITY, null);
        List<String> allowed = qualitiesFor(modelId);
        return allowed.contains(saved) ? saved : allowed.get(0);
    }

    public static void saveSize(Context ctx, String size) {
        prefs(ctx).edit().putString(KEY_SIZE, size).apply();
    }

    public static void saveQuality(Context ctx, String quality) {
        prefs(ctx).edit().putString(KEY_QUALITY, quality).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
