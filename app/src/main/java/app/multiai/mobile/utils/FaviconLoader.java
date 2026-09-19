package app.multiai.mobile.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.multiai.mobile.R;

/**
 * Загрузка favicon сайта провайдера (по его baseURL) с дисковым кэшем.
 *
 * <p>Логика: host baseURL → {@code https://host/favicon.ico} (падает — гугловый
 * резолвер {@code /s2/favicons?domain=host&sz=64}). Результат кладётся в
 * {@code cacheDir/favicons/<host>.png} и в memory-кэш, повторные показы бесплатны.
 *
 * <p>Пока иконка грузится (или если её нет) — показывается fallback drawable.
 * В тёмной теме белые/светлые логотипы на прозрачном фоне не читаются, поэтому
 * favicon НЕ тонируется цветом (setColorFilter не вызывается) — он показывается
 * как есть; тонировка применяется только к векторному fallback.
 */
public final class FaviconLoader {

    private static final Map<String, Bitmap> MEMORY = new ConcurrentHashMap<>();
    private static final Map<String, java.util.List<java.lang.ref.WeakReference<ImageView>>> IN_FLIGHT =
            new java.util.HashMap<>();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);

    private FaviconLoader() {}

    /** Origin из baseURL провайдера ("https://freeinference.org/v1" → "https://freeinference.org"). */
    @Nullable
    private static String originOf(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return null;
        try {
            URL u = new URL(baseUrl.trim());
            String host = u.getHost();
            if (host == null || host.isEmpty()) return null;
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            return u.getProtocol() + "://" + host + port;
        } catch (Exception e) {
            return null;
        }
    }

    /** Хост из baseURL провайдера ("https://api.foo.com/v1" → "api.foo.com"). */
    @Nullable
    public static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return null;
        try {
            String host = new URL(baseUrl.trim()).getHost();
            return host == null || host.isEmpty() ? null : host;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Асинхронно ставит favicon в ImageView. Пока грузится — fallbackRes
     * (тонированный tintRes). Тег вьюхи = host, чтобы отбросить устаревшие
     * колбэки при переиспользовании.
     */
    public static void into(Context ctx, ImageView view, String baseUrl,
                            int fallbackRes, int tintRes) {
        into(ctx, view, baseUrl, fallbackRes, tintRes, null);
    }

    /**
     * То же + letterLabel: если сайт не отдал иконку (SPA без favicon, как
     * freeinference.org), рисует цветной кружок с буквой провайдера вместо
     * безликой заглушки.
     */
    public static void into(Context ctx, ImageView view, String baseUrl,
                            int fallbackRes, int tintRes, String letterLabel) {
        String host = hostOf(baseUrl);
        if (host == null) {
            view.setTag(null);
            showFallback(view, fallbackRes, tintRes);
            return;
        }
        view.setTag(host);
        synchronized (IN_FLIGHT) {
            Bitmap cached = MEMORY.get(host);
            if (cached != null) {
                showBitmap(view, cached);
                return;
            }
            showFallback(view, fallbackRes, tintRes);
            java.util.List<java.lang.ref.WeakReference<ImageView>> waiting = IN_FLIGHT.get(host);
            if (waiting != null) {
                waiting.removeIf(reference -> reference.get() == null || reference.get() == view);
                waiting.add(new java.lang.ref.WeakReference<>(view));
                return;
            }
            waiting = new java.util.ArrayList<>();
            waiting.add(new java.lang.ref.WeakReference<>(view));
            IN_FLIGHT.put(host, waiting);
        }

        Context application = ctx.getApplicationContext();
        EXEC.execute(() -> {
            Bitmap bmp = null;
            try {
                File diskFile = diskFile(application, host);
                bmp = loadFromDisk(diskFile);
                if (bmp == null) bmp = download(baseUrl, host, diskFile);
                if (bmp == null) bmp = letterBitmap(letterLabel != null ? letterLabel : host);
            } catch (Exception e) {
                log("load failed " + host + ": " + e.getClass().getSimpleName());
            } finally {
                final Bitmap result = bmp;
                final java.util.List<java.lang.ref.WeakReference<ImageView>> waiting;
                // Cache publication and waiter removal must be atomic with registration.
                synchronized (IN_FLIGHT) {
                    if (result != null) MEMORY.put(host, result);
                    waiting = IN_FLIGHT.remove(host);
                }
                if (result != null && waiting != null) MAIN.post(() -> {
                    for (java.lang.ref.WeakReference<ImageView> reference : waiting) {
                        ImageView target = reference.get();
                        if (target != null && host.equals(target.getTag())) showBitmap(target, result);
                    }
                });
            }
        });
    }

    /** Круглая иконка с первой буквой провайдера — когда сайт не отдаёт favicon. */
    private static Bitmap letterBitmap(String label) {
        try {
            String letter = (label == null || label.trim().isEmpty() ? "?" :
                    label.trim().substring(0, 1)).toUpperCase(java.util.Locale.ROOT);
            int size = 96;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            // Цвет круга — детерминированный по хэшу имени (приятная палитра).
            int[] palette = {0xFF5B8DEF, 0xFF7C5CFC, 0xFF3FA46A, 0xFFE07A3F,
                    0xFFD2556B, 0xFF2AA5A0, 0xFF8A6D3B, 0xFF5E7CE2};
            int color = palette[Math.abs(label.hashCode()) % palette.length];
            android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            bg.setColor(color);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg);
            android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(size * 0.48f);
            text.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
            text.setTextAlign(android.graphics.Paint.Align.CENTER);
            float y = size / 2f - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(letter, size / 2f, y, text);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private static void showFallback(ImageView view, int fallbackRes, int tintRes) {
        view.setImageResource(fallbackRes);
        view.setColorFilter(androidx.core.content.ContextCompat.getColor(view.getContext(), tintRes));
    }

    private static void log(String msg) {
        // Полевая отладка — пишет в файл только в DEBUG-сборках (no-op в release).
        DebugFileLog.log("FaviconLoader", msg);
    }

    private static void showBitmap(ImageView view, Bitmap bmp) {
        view.clearColorFilter();
        view.setImageBitmap(bmp);
    }

    private static File diskFile(Context ctx, String host) {
        File dir = new File(ctx.getCacheDir(), "favicons");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, host.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png");
    }

    private static Bitmap loadFromDisk(File file) {
        if (!file.isFile()) return null;
        // Кэш живёт неделю — иконки сайтов меняются редко, но не вечны.
        if (System.currentTimeMillis() - file.lastModified() > 7L * 24 * 3600 * 1000) return null;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap download(String baseUrl, String host, File diskFile) {
        // Порядок: 1) парсим <link rel="icon"> из HTML главной (самый точный),
        // 2) /favicon.ico на корне, 3) Google-резолвер как последний фолбэк.
        String origin = originOf(baseUrl);
        String home = origin != null ? origin : "https://" + host;
        Bitmap bmp = fetchFromHtmlLink(home);
        log((bmp != null ? "html-link OK " : "html-link null ") + home);
        if (bmp == null) bmp = fetch(home + "/favicon.ico");
        if (bmp == null) bmp = fetch("https://www.google.com/s2/favicons?domain=" + host + "&sz=64");
        if (bmp == null) return null;
        try (FileOutputStream out = new FileOutputStream(diskFile)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {}
        return bmp;
    }

    /**
     * Тащит favicon из HTML главной страницы: ищет <link rel="...icon..." href="...">
     * (icon / shortcut icon / apple-touch-icon). Так работают браузеры — многие сайты
     * (freeinference.org) не отдают /favicon.ico, но объявляют иконку в разметке.
     */
    private static Bitmap fetchFromHtmlLink(String homeUrl) {
        String html = fetchText(homeUrl, 64_000);
        if (html == null) return null;
        // <link ... rel="icon" ... href="..."> в любом порядке атрибутов.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<link[^>]+rel=[\"'][^\"']*(?:icon|apple-touch-icon)[^\"']*[\"'][^>]*>",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        String href = null;
        while (m.find()) {
            java.util.regex.Matcher h = java.util.regex.Pattern
                    .compile("href=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(m.group());
            if (h.find()) { href = h.group(1); break; }
        }
        if (href == null) return null;
        // Относительный путь → абсолютный от origin.
        String iconUrl;
        try {
            iconUrl = new URL(new URL(homeUrl), href).toString();
        } catch (Exception e) {
            return null;
        }
        return fetch(iconUrl);
    }

    private static String fetchText(String url, int maxChars) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MultiAI-Mobile");
            int code = conn.getResponseCode();
            if (code != 200) {
                log("fetchText HTTP " + code + " " + url);
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1 && sb.length() < maxChars) {
                    sb.append(buf, 0, Math.min(n, maxChars - sb.length()));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log("fetchText EX " + url + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MultiAI-Mobile");
            int code = conn.getResponseCode();
            if (code != 200) {
                log("fetch HTTP " + code + " " + url);
                return null;
            }
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                bytes = readAllBytes(in, 512_000);
            }
            Bitmap bmp = decodeIcon(bytes, url);
            if (bmp == null) log("fetch decode-null " + url + " bytes=" + (bytes == null ? -1 : bytes.length));
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAllBytes(InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Декодирует иконку. BitmapFactory НЕ понимает .ico — большинство favicon.ico
     * это контейнер ICO с PNG внутри. Достаём первый PNG-кадр из контейнера;
     * PNG/JPEG/WebP отдаём в BitmapFactory напрямую.
     */
    private static Bitmap decodeIcon(byte[] bytes, String url) {
        if (bytes == null || bytes.length < 4) return null;
        // ICO: 00 00 01 00 → ищем PNG-сигнатуру внутри.
        if (bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 1 && bytes[3] == 0) {
            byte[] pngSig = {(byte) 0x89, 0x50, 0x4E, 0x47};
            for (int i = 0; i + 8 < bytes.length; i++) {
                if (bytes[i] == pngSig[0] && bytes[i + 1] == pngSig[1]
                        && bytes[i + 2] == pngSig[2] && bytes[i + 3] == pngSig[3]) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, i, bytes.length - i);
                    if (bmp != null) return bmp;
                }
            }
            return null;
        }
        // Обычный растровый формат (PNG/JPEG/WebP/GIF).
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }
}
