package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Защита от отката системного времени (для грейса обязательных обновлений).
 *
 * <p>Хранит high-water mark — «максимальное когда-либо виденное время».
 * Системные часы юзер может отмотать назад, но записанная метка от этого
 * не уменьшается. Метка пополняется из двух источников:</p>
 * <ol>
 *     <li>системные часы, когда они идут вперёд;</li>
 *     <li>сетевые Time-API: каждый обычный HTTPS-ответ несёт заголовок
 *         {@code Date} с серверным временем. Опрашивается 10+ крупных
 *         сервисов (google, cloudflare, github, …) — чтобы получить
 *         поддельную метку, атакующему пришлось бы подменить трафик ко
 *         всем им одновременно.</li>
 * </ol>
 *
 * <p>Медиана сетевых ответов устойчива к одиночному скомпрометированному
 * или лгущему источнику. Согласие: берём медиану только если хотя бы
 * {@link #MIN_AGREE} ответов лежат в минутном окне друг от друга.</p>
 */
public final class TimeGuard {

    private static final String PREFS = "time_guard";
    private static final String KEY_HWALL = "hwall_ms";
    private static final String KEY_NET_MS = "net_sync_ms";
    private static final String KEY_NET_AT = "net_sync_at";

    /** Хоть сколько независимых серверов должны согласиться (из 10+). */
    private static final int MIN_AGREE = 3;

    /** Endpoints, отдающие серверное время в заголовке Date (HEAD/GET). */
    private static final String[] TIME_APIS = {
            "https://www.google.com/generate_204",
            "https://www.gstatic.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://1.1.1.1/",
            "https://api.github.com/",
            "https://multiai.store/",
            "https://registry.npmjs.org/",
            "https://www.microsoft.com/",
            "https://download.docker.com/",
            "https://archive.ubuntu.com/",
            "https://pypi.org/simple/",
            "https://gitlab.com/",
            "https://api.anthropic.com/",
    };

    private TimeGuard() {}

    /**
     * Доверенное «сейчас»: max(системные часы, high-water mark). Откат
     * системных часов назад метку не уменьшает.
     */
    public static long now(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long stored = p.getLong(KEY_HWALL, 0L);
        long sys = System.currentTimeMillis();
        if (sys > stored) {
            p.edit().putLong(KEY_HWALL, sys).apply();
            return sys;
        }
        return stored > 0 ? stored : sys;
    }

    /** Epoch ms последней успешной сетевой синхронизации (0 — не было). */
    public static long lastNetworkTime(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getLong(KEY_NET_MS, 0L);
    }

    /**
     * Синхронизация с сетью в фоновом потоке: опрашиваем {@link #TIME_APIS},
     * парсим заголовок Date каждого ответа, берём медиану при согласии
     * ≥ {@link #MIN_AGREE} источников в минутном окне. Результат — новые
     * high-water mark (только если больше сохранённого).
     */
    public static void refreshFromNetwork(Context ctx) {
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build();
            long[] samples = new long[TIME_APIS.length];
            int n = 0;
            for (String url : TIME_APIS) {
                long ms = fetchDateHeader(client, url);
                if (ms > 0) samples[n++] = ms;
            }
            if (n < MIN_AGREE) return;
            long median = median(samples, n);
            // Согласие: минимум MIN_AGREE ответов в ±60с от медианы.
            int agree = 0;
            for (int i = 0; i < n; i++) {
                if (Math.abs(samples[i] - median) <= 60_000L) agree++;
            }
            if (agree < MIN_AGREE) return;

            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putLong(KEY_NET_MS, median).putLong(KEY_NET_AT, median).apply();
            long stored = p.getLong(KEY_HWALL, 0L);
            if (median > stored) {
                p.edit().putLong(KEY_HWALL, median).apply();
            }
        }, "time-guard-sync").start();
    }

    /** HEAD → GET, вернуть серверное время из заголовка Date (epoch ms) или 0. */
    private static long fetchDateHeader(OkHttpClient client, String url) {
        try {
            Request request = new Request.Builder().url(url).head()
                    .header("User-Agent", "MultiAI-Mobile").build();
            try (Response response = client.newCall(request).execute()) {
                String date = response.header("Date");
                if (date != null) return parseHttpDate(date);
            }
            Request get = new Request.Builder().url(url).get()
                    .header("User-Agent", "MultiAI-Mobile").build();
            try (Response response = client.newCall(get).execute()) {
                String date = response.header("Date");
                return date != null ? parseHttpDate(date) : 0L;
            }
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** "Fri, 11 Sep 2026 07:31:38 GMT" → epoch ms. */
    private static long parseHttpDate(String date) {
        try {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = fmt.parse(date);
            return d != null ? d.getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Медиана первых n элементов (копия, сортировка на месте не нужна). */
    private static long median(long[] values, int n) {
        long[] copy = new long[n];
        System.arraycopy(values, 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        return copy[n / 2];
    }

    /** JSON-фолбэк (не используется в основном пути; задел на будущее). */
    private static long parseJsonTime(String body) {
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("unixtime")) return json.getLong("unixtime") * 1000L;
        } catch (Exception ignored) {
        }
        return 0L;
    }
}
