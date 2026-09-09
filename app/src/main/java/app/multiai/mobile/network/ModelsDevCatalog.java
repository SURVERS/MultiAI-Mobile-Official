package app.multiai.mobile.network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Дополняет бедные /models-каталоги публичными метаданными models.dev. */
final class ModelsDevCatalog {
    private static final String URL = "https://models.dev/api.json";
    private static final long TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final Object LOCK = new Object();
    private static JSONObject cachedRoot;
    private static long cachedAt;

    private ModelsDevCatalog() {}

    static List<CustomProviderClient.FetchedModel> enrich(
            String baseUrl, List<CustomProviderClient.FetchedModel> source) {
        String providerKey = providerKey(baseUrl);
        boolean bai = baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).contains("api.b.ai");
        if ((providerKey == null && !bai) || source == null || source.isEmpty()) return source;
        JSONObject root = load();
        if (root == null) return source;
        Map<String, JSONObject> byId = bai ? genericModelIndex(root) : providerModelIndex(root, providerKey);
        if (byId.isEmpty()) return source;

        List<CustomProviderClient.FetchedModel> out = new ArrayList<>(source.size());
        for (CustomProviderClient.FetchedModel current : source) {
            JSONObject meta = byId.get(current.id.toLowerCase(Locale.ROOT));
            out.add(meta == null ? current : merge(current, meta, !bai));
        }
        return out;
    }

    static CustomProviderClient.FetchedModel merge(
            CustomProviderClient.FetchedModel current, JSONObject meta) {
        return merge(current, meta, true);
    }

    static CustomProviderClient.FetchedModel merge(
            CustomProviderClient.FetchedModel current, JSONObject meta, boolean includeCost) {
        String name = meta.optString("name", "").trim();
        String display = current.displayName == null || current.displayName.trim().isEmpty()
                || current.displayName.equals(current.id) ? name : current.displayName;
        if (display == null || display.isEmpty()) display = current.displayName;

        JSONObject limit = meta.optJSONObject("limit");
        long context = current.contextWindow > 0 ? current.contextWindow
                : positiveLong(limit, "context");
        long output = current.maxOutputTokens > 0 ? current.maxOutputTokens
                : positiveLong(limit, "output");

        String modalities = current.inputModalities;
        JSONObject modalityObj = meta.optJSONObject("modalities");
        if ((modalities == null || modalities.isEmpty()) && modalityObj != null) {
            modalities = csv(modalityObj.optJSONArray("input"));
        }
        // Тип модели: models.dev знает output-модальности — "embedding" для bge-m3.
        String kind = current.kind;
        if ((kind == null || kind.isEmpty()) && modalityObj != null
                && csv(modalityObj.optJSONArray("output")).contains("embedding")) {
            kind = "embedding";
        }

        String levels = current.reasoningLevels;
        String defaultLevel = current.reasoningDefault;
        if ((levels == null || levels.isEmpty()) && meta.optBoolean("reasoning", false)) {
            JSONArray options = meta.optJSONArray("reasoning_options");
            if (options != null) {
                for (int i = 0; i < options.length(); i++) {
                    JSONObject option = options.optJSONObject(i);
                    if (option == null) continue;
                    if ("effort".equals(option.optString("type"))) {
                        levels = csv(option.optJSONArray("values"));
                        defaultLevel = contains(levels, "medium") ? "medium" : first(levels);
                        break;
                    }
                    if ("toggle".equals(option.optString("type")) && (levels == null || levels.isEmpty())) {
                        levels = "none,high";
                        defaultLevel = "high";
                    }
                }
            }
            if (levels == null || levels.isEmpty()) {
                levels = "low,medium,high";
                defaultLevel = "medium";
            }
        }

        boolean hasPricing = current.hasPricing;
        double input = current.priceInput;
        double completion = current.priceOutput;
        double cached = current.priceCached;
        JSONObject cost = meta.optJSONObject("cost");
        if (includeCost && !hasPricing && cost != null && (cost.has("input") || cost.has("output"))) {
            hasPricing = true;
            input = nonNegative(cost, "input");
            completion = nonNegative(cost, "output");
            cached = nonNegative(cost, "cache_read");
        }
        boolean free = current.free || current.id.toLowerCase(Locale.ROOT).endsWith(":free")
                || (hasPricing && input == 0 && completion == 0);

        return new CustomProviderClient.FetchedModel(current.id, display, context, output,
                modalities, levels, defaultLevel, input, completion, cached, hasPricing, free,
                kind);
    }

    private static Map<String, JSONObject> providerModelIndex(JSONObject root, String providerKey) {
        JSONObject provider = root.optJSONObject(providerKey);
        return index(provider == null ? null : provider.optJSONObject("models"));
    }

    private static Map<String, JSONObject> genericModelIndex(JSONObject root) {
        Map<String, JSONObject> result = new HashMap<>();
        java.util.Iterator<String> providers = root.keys();
        while (providers.hasNext()) {
            JSONObject provider = root.optJSONObject(providers.next());
            JSONObject models = provider == null ? null : provider.optJSONObject("models");
            Map<String, JSONObject> candidates = index(models);
            for (Map.Entry<String, JSONObject> entry : candidates.entrySet()) {
                JSONObject current = result.get(entry.getKey());
                if (current == null || metadataScore(entry.getValue()) > metadataScore(current)) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    private static Map<String, JSONObject> index(JSONObject models) {
        Map<String, JSONObject> result = new HashMap<>();
        if (models == null) return result;
        java.util.Iterator<String> keys = models.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject model = models.optJSONObject(key);
            if (model == null) continue;
            result.put(key.toLowerCase(Locale.ROOT), model);
            String id = model.optString("id", key).trim();
            if (!id.isEmpty()) result.put(id.toLowerCase(Locale.ROOT), model);
        }
        return result;
    }

    private static int metadataScore(JSONObject model) {
        int score = model.optJSONObject("limit") == null ? 0 : 2;
        if (model.optJSONObject("modalities") != null) score += 2;
        if (model.optBoolean("reasoning", false)) score += 1;
        if (!model.optString("name", "").isEmpty()) score += 1;
        return score;
    }

    private static JSONObject load() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (cachedRoot != null && now - cachedAt < TTL_MS) return cachedRoot;
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .build();
                Request request = new Request.Builder().url(URL).get()
                        .header("Accept", "application/json").build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        cachedRoot = new JSONObject(response.body().string());
                        cachedAt = now;
                    }
                }
            } catch (Exception ignored) {
                // Stale cache is intentionally kept when models.dev is unavailable.
            }
            return cachedRoot;
        }
    }

    private static String providerKey(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        if (value.contains("opencode.ai/zen/go")) return "opencode-go";
        if (value.contains("opencode.ai/zen")) return "opencode";
        if (value.contains("developer.amd.com.cn")) return "amd";
        if (value.contains("openrouter.ai")) return "openrouter";
        return null;
    }

    private static long positiveLong(JSONObject obj, String key) {
        if (obj == null) return 0;
        long value = obj.optLong(key, 0);
        return value > 0 ? value : 0;
    }

    private static double nonNegative(JSONObject obj, String key) {
        if (obj == null || !obj.has(key)) return 0;
        double value = obj.optDouble(key, 0);
        return Double.isFinite(value) && value >= 0 ? value : 0;
    }

    private static String csv(JSONArray values) {
        if (values == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    private static boolean contains(String csv, String value) {
        if (csv == null) return false;
        for (String item : csv.split(",")) if (value.equals(item.trim())) return true;
        return false;
    }

    private static String first(String csv) {
        if (csv == null || csv.isEmpty()) return "";
        int comma = csv.indexOf(',');
        return comma < 0 ? csv : csv.substring(0, comma);
    }
}
