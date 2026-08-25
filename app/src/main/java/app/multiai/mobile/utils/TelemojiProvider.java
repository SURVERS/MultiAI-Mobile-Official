package app.multiai.mobile.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieCompositionFactory;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Загружает Lottie-файлы Telemoji и хранит их в дисковом и memory-кеше.
 * Статичный режим тоже использует Lottie: в Bitmap рендерится первый кадр.
 */
public final class TelemojiProvider {

    private static final String TAG = "TelemojiProvider";
    private static final String CDN_BASE = "https://saeedtahmtan.github.io/telemoji/";
    private static final String INDEX_ASSET = "telemoji_index.json";
    private static final int MAX_STATIC_FRAME_SIZE = 256;

    private static volatile TelemojiProvider instance;

    private final Context appContext;
    private final OkHttpClient httpClient;
    private final File cacheDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService loaderExecutor = Executors.newFixedThreadPool(3);

    private final Map<String, String> lottieMap = new HashMap<>();
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    private final Map<String, List<OnDrawableLoaded>> inFlight = new HashMap<>();

    /** Композиции можно безопасно разделять; LottieDrawable для каждого span создаётся новый. */
    private final LruCache<String, LottieComposition> compositionCache =
            new LruCache<>(48);

    /** В статичном режиме кешируется уже отрисованный первый кадр. */
    private final LruCache<String, Drawable> drawableCache =
            new LruCache<String, Drawable>(8 * 1024) {
                @Override
                protected int sizeOf(@NonNull String key, @NonNull Drawable value) {
                    if (value instanceof BitmapDrawable) {
                        Bitmap bitmap = ((BitmapDrawable) value).getBitmap();
                        return Math.max(1, bitmap.getByteCount() / 1024);
                    }
                    return 1;
                }
            };

    private int maxEmojiLength = 12;

    private TelemojiProvider(@NonNull Context context) {
        appContext = context.getApplicationContext();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        cacheDir = new File(appContext.getCacheDir(), "telemoji");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Could not create cache directory: " + cacheDir);
        }
        loadIndex();
    }

    @NonNull
    public static TelemojiProvider get(@NonNull Context context) {
        if (instance == null) {
            synchronized (TelemojiProvider.class) {
                if (instance == null) instance = new TelemojiProvider(context);
            }
        }
        return instance;
    }

    private void loadIndex() {
        try (InputStream input = appContext.getAssets().open(INDEX_ASSET)) {
            String json = new String(readAllBytes(input), StandardCharsets.UTF_8);
            JsonArray entries = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                String emoji = stringValue(object, "emoji");
                String lottiePath = stringValue(object, "file_lottie");
                if (emoji == null || emoji.isEmpty() || lottiePath == null || lottiePath.isEmpty()) {
                    continue;
                }
                storeEmoji(emoji, lottiePath);
                maxEmojiLength = Math.max(maxEmojiLength, emoji.length() + 1);
            }
            Log.i(TAG, "Index loaded: mappings=" + lottieMap.size());
        } catch (Exception error) {
            Log.e(TAG, "Failed to load " + INDEX_ASSET, error);
        }
    }

    @Nullable
    private static String stringValue(@NonNull JsonObject object, @NonNull String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private void storeEmoji(@NonNull String emoji, @NonNull String path) {
        lottieMap.putIfAbsent(emoji, path);
        String normalized = normalizeEmoji(emoji);
        lottieMap.putIfAbsent(normalized, path);
    }

    @NonNull
    private static String normalizeEmoji(@NonNull String emoji) {
        return emoji.replace("\uFE0F", "");
    }

    public boolean isEnabled() {
        return !new AppSettings(appContext).isEmojiModeSystem();
    }

    public boolean isAnimatedMode() {
        return new AppSettings(appContext).isEmojiModeAnimated();
    }

    /**
     * Оба Telemoji-режима используют file_lottie. Параметр animated сохранён в API,
     * чтобы вызывающий код не зависел от способа получения статичного кадра.
     */
    @Nullable
    public String getPathForEmoji(@NonNull String emoji, boolean animated) {
        String path = lottieMap.get(emoji);
        if (path != null) return path;
        return lottieMap.get(normalizeEmoji(emoji));
    }

    public int getMaxEmojiLength() {
        return maxEmojiLength;
    }

    @Nullable
    public Drawable getCachedDrawable(@NonNull String emoji, boolean animated) {
        String path = getPathForEmoji(emoji, animated);
        if (path == null) return null;
        if (animated) {
            LottieComposition composition = compositionCache.get(path);
            return composition == null ? null : createLottieDrawable(composition);
        }
        Drawable cached = drawableCache.get(path);
        if (!(cached instanceof BitmapDrawable)) return null;
        Bitmap bitmap = ((BitmapDrawable) cached).getBitmap();
        return new BitmapDrawable(appContext.getResources(), bitmap);
    }

    public void loadDrawableAsync(@NonNull String emoji, boolean animated,
                                  @NonNull OnDrawableLoaded callback) {
        Drawable cached = getCachedDrawable(emoji, animated);
        if (cached != null) {
            mainHandler.post(() -> callback.onLoaded(cached));
            return;
        }

        String path = getPathForEmoji(emoji, animated);
        if (path == null) {
            mainHandler.post(() -> callback.onLoaded(null));
            return;
        }
        String requestKey = (animated ? "animated:" : "static:") + path;
        synchronized (inFlight) {
            List<OnDrawableLoaded> callbacks = inFlight.get(requestKey);
            if (callbacks != null) {
                callbacks.add(callback);
                return;
            }
            callbacks = new ArrayList<>();
            callbacks.add(callback);
            inFlight.put(requestKey, callbacks);
        }

        loaderExecutor.execute(() -> {
            boolean loaded = false;
            try {
                loaded = loadAsset(path, animated);
            } catch (Exception error) {
                Log.e(TAG, "Could not load " + path, error);
            }
            final boolean success = loaded;
            mainHandler.post(() -> completeRequest(requestKey, emoji, animated, success));
        });
    }

    private void completeRequest(@NonNull String requestKey, @NonNull String emoji,
                                 boolean animated, boolean success) {
        List<OnDrawableLoaded> callbacks;
        synchronized (inFlight) {
            callbacks = inFlight.remove(requestKey);
        }
        if (callbacks == null) return;
        for (OnDrawableLoaded callback : callbacks) {
            Drawable result = success ? getCachedDrawable(emoji, animated) : null;
            callback.onLoaded(result);
        }
    }

    public interface OnDrawableLoaded {
        void onLoaded(@Nullable Drawable drawable);
    }

    private boolean loadAsset(@NonNull String path, boolean animated) {
        LottieComposition composition = compositionCache.get(path);
        if (composition == null) {
            Object newLock = new Object();
            Object existingLock = fileLocks.putIfAbsent(path, newLock);
            Object fileLock = existingLock == null ? newLock : existingLock;
            synchronized (fileLock) {
                composition = compositionCache.get(path);
                if (composition == null) {
                    composition = loadComposition(path);
                    if (composition != null) compositionCache.put(path, composition);
                }
            }
        }
        if (composition == null) return false;
        if (animated) return true;

        if (drawableCache.get(path) == null) {
            Bitmap frame = renderFirstFrame(composition);
            if (frame == null) return false;
            drawableCache.put(path, new BitmapDrawable(appContext.getResources(), frame));
        }
        return true;
    }

    @Nullable
    private LottieComposition loadComposition(@NonNull String path) {
        File file = new File(cacheDir, sanitizeFilename(path));
        boolean hadCachedFile = file.isFile();
        if (hadCachedFile) {
            LottieComposition cached = decodeLottie(file);
            if (cached != null) return cached;
            if (!file.delete()) Log.w(TAG, "Could not delete invalid cache file: " + file);
        }

        if (!downloadFile(CDN_BASE + path, file)) return null;
        LottieComposition downloaded = decodeLottie(file);
        if (downloaded == null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete undecodable file: " + file);
        }
        return downloaded;
    }

    private boolean downloadFile(@NonNull String url, @NonNull File destination) {
        File temporary = new File(destination.getAbsolutePath() + ".tmp");
        if (temporary.exists() && !temporary.delete()) return false;

        Request request = new Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            if (destination.exists() && !destination.delete()) return false;
            if (!temporary.renameTo(destination)) return false;
            return true;
        } catch (IOException error) {
            Log.w(TAG, "Download failed: " + url, error);
            return false;
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Could not remove temporary file: " + temporary);
            }
        }
    }

    @Nullable
    private LottieComposition decodeLottie(@NonNull File file) {
        try (InputStream fileInput = new FileInputStream(file)) {
            byte[] data = readAllBytes(fileInput);
            if (isGzip(data)) {
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    data = readAllBytes(gzip);
                }
            }
            String json = new String(data, StandardCharsets.UTF_8);
            LottieResult<LottieComposition> result =
                    LottieCompositionFactory.fromJsonStringSync(json, null);
            if (result.getValue() != null) return result.getValue();
            Log.w(TAG, "Invalid Lottie file: " + file,
                    result.getException());
        } catch (Exception error) {
            Log.w(TAG, "Lottie decode failed: " + file, error);
        }
        return null;
    }

    @Nullable
    private static Bitmap renderFirstFrame(@NonNull LottieComposition composition) {
        Rect bounds = composition.getBounds();
        int sourceWidth = Math.max(1, bounds.width());
        int sourceHeight = Math.max(1, bounds.height());
        float scale = Math.min(1f, MAX_STATIC_FRAME_SIZE
                / (float) Math.max(sourceWidth, sourceHeight));
        int width = Math.max(1, Math.round(sourceWidth * scale));
        int height = Math.max(1, Math.round(sourceHeight * scale));
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            LottieDrawable drawable = createLottieDrawable(composition);
            drawable.setProgress(0f);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            drawable.clearComposition();
            return bitmap;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not render static Telemoji frame", error);
            return null;
        }
    }

    @NonNull
    private static LottieDrawable createLottieDrawable(@NonNull LottieComposition composition) {
        LottieDrawable drawable = new LottieDrawable();
        drawable.setComposition(composition);
        drawable.setRepeatCount(LottieDrawable.INFINITE);
        return drawable;
    }

    private static boolean isGzip(@NonNull byte[] data) {
        return data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
    }

    @NonNull
    private static byte[] readAllBytes(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    @NonNull
    private static String sanitizeFilename(@NonNull String path) {
        return path.replace('/', '_').replace('\\', '_');
    }

    /** Очищает только memory-кеш; скачанные JSON остаются для мгновенного повторного запуска. */
    public void clearCache() {
        compositionCache.evictAll();
        drawableCache.evictAll();
    }

    public int lottieMapSize() {
        return lottieMap.size();
    }
}
