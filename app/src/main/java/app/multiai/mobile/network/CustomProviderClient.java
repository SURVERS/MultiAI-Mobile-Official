package app.multiai.mobile.network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import app.multiai.mobile.R;
import app.multiai.mobile.models.CustomProvider;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * HTTP-клиент для кастомных провайдеров (OpenAI-compatible или Anthropic).
 *
 * <p>Используется в двух сценариях:
 * <ol>
 *   <li>Автоматическая загрузка списка моделей из {@code GET /v1/models} при добавлении провайдера.</li>
 *   <li>Отправка запроса chat completion через провайдер (если выбрана его модель).</li>
 * </ol>
 *
 * <p>Чат-запросы делегируются в MultiAiClient через специальный конструктор с кастомными параметрами;
 * здесь живёт только логика получения моделей и вспомогательные методы.
 */
public final class CustomProviderClient {

    private static final int FETCH_TIMEOUT_SEC = 20;
    /** Совпадает с PC fetchProviderModels: baseURL уже кончается на /v1 или /v1beta? */
    private static final Pattern VERSIONED_BASE = Pattern.compile("/v\\d+(?:beta)?$", Pattern.CASE_INSENSITIVE);

    public static final class FetchedModel {
        public final String id;
        public final String displayName;
        /** Окно контекста (context_length и т.п.), 0 если сервер не отдал. */
        public final long contextWindow;
        /** Лимит вывода (max_output_tokens и т.п.), 0 если не отдан. */
        public final long maxOutputTokens;
        /** Модальности ввода CSV ("text,image"), может быть пустым. */
        public final String inputModalities;
        /** Уровни размышления CSV ("low,medium,high") если supported_sampling_parameters содержит reasoning_effort. */
        public final String reasoningLevels;
        /** Дефолтный уровень размышления. */
        public final String reasoningDefault;
        /** Цена входа (за 1M токенов или мультипликатор), 0 = не отдано. */
        public final double priceInput;
        /** Цена вывода. */
        public final double priceOutput;
        /** Цена чтения кэша, нормализованная в USD за 1M токенов. */
        public final double priceCached;
        /** Сервер явно отдал pricing, включая корректный ноль. */
        public final boolean hasPricing;
        /** Явный признак бесплатности, не выводится из отсутствующих цен. */
        public final boolean free;
        /** Тип модели: "embedding" — /v1/embeddings-модель, в чате не используется. */
        public final String kind;

        public FetchedModel(String id, String displayName) {
            this(id, displayName, 0, 0, "", "", "", 0, 0, 0, false, false, "");
        }

        public FetchedModel(String id, String displayName, long contextWindow,
                            long maxOutputTokens, String inputModalities) {
            this(id, displayName, contextWindow, maxOutputTokens, inputModalities, "", "", 0, 0, 0, false, false, "");
        }

        public FetchedModel(String id, String displayName, long contextWindow,
                            long maxOutputTokens, String inputModalities,
                            String reasoningLevels, String reasoningDefault) {
            this(id, displayName, contextWindow, maxOutputTokens, inputModalities,
                    reasoningLevels, reasoningDefault, 0, 0, 0, false, false, "");
        }

        public FetchedModel(String id, String displayName, long contextWindow,
                            long maxOutputTokens, String inputModalities,
                            String reasoningLevels, String reasoningDefault,
                            double priceInput, double priceOutput, double priceCached,
                            boolean hasPricing, boolean free) {
            this(id, displayName, contextWindow, maxOutputTokens, inputModalities,
                    reasoningLevels, reasoningDefault, priceInput, priceOutput, priceCached,
                    hasPricing, free, "");
        }

        public FetchedModel(String id, String displayName, long contextWindow,
                            long maxOutputTokens, String inputModalities,
                            String reasoningLevels, String reasoningDefault,
                            double priceInput, double priceOutput, double priceCached,
                            boolean hasPricing, boolean free, String kind) {
            this.id = id;
            this.displayName = displayName;
            this.contextWindow = contextWindow;
            this.maxOutputTokens = maxOutputTokens;
            this.inputModalities = inputModalities == null ? "" : inputModalities;
            this.reasoningLevels = reasoningLevels == null ? "" : reasoningLevels;
            this.reasoningDefault = reasoningDefault == null ? "" : reasoningDefault;
            this.priceInput = priceInput;
            this.priceOutput = priceOutput;
            this.priceCached = priceCached;
            this.hasPricing = hasPricing;
            this.free = free;
            this.kind = kind == null ? "" : kind;
        }
    }

    private CustomProviderClient() {}

    /**
     * Загружает список моделей из {@code GET <baseURL>/models}.
     * Логика полностью повторяет PC IDE {@code fetchProviderModels}
     * (main-src/llm/fetchProviderModels.ts):
     * <ul>
     *   <li>URL нормализуется: если {@code baseURL} не кончается на {@code /v1} или
     *       {@code /v1beta}, к нему добавляется {@code /v1} перед {@code /models}.
     *       Без этого шага провайдеры, у которых в настройках указан "голый" домен
     *       без версии в пути, никогда не отдавали список моделей.</li>
     *   <li>Для Anthropic-провайдеров сначала пробуем Bearer-заголовок (многие
     *       Anthropic-совместимые шлюзы — zai, freeinference, OpenRouter — отдают
     *       расширенный каталог только через Bearer), и только если это не сработало,
     *       откатываемся на нативный {@code x-api-key + anthropic-version}.</li>
     * </ul>
     *
     * @return список моделей или пустой список при ошибке/пустом ответе
     */
    public static List<FetchedModel> fetchModels(CustomProvider provider) {
        List<FetchedModel> result = new ArrayList<>();
        if (provider == null || provider.baseURL == null || provider.apiKey == null
                || provider.baseURL.trim().isEmpty() || provider.apiKey.trim().isEmpty()) {
            return result;
        }
        String base = provider.baseURL.trim().replaceAll("/+$", "");
        String versionedBase = VERSIONED_BASE.matcher(base).find() ? base : base + "/v1";
        String url = versionedBase + "/models";

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();

        boolean anthropic = CustomProvider.PARADIGM_ANTHROPIC.equals(provider.paradigm);
        if (anthropic) {
            List<FetchedModel> viaBearer = fetchWithHeaders(client, url, req ->
                    req.header("Authorization", "Bearer " + provider.apiKey));
            if (!viaBearer.isEmpty()) return enrichProviderModels(base, viaBearer);
            List<FetchedModel> nativeModels = fetchWithHeaders(client, url, req -> req
                    .header("x-api-key", provider.apiKey)
                    .header("anthropic-version", "2023-06-01"));
            return enrichProviderModels(base, nativeModels);
        }
        List<FetchedModel> fetched = fetchWithHeaders(client, url, req ->
                req.header("Authorization", "Bearer " + provider.apiKey));
        return enrichProviderModels(base, fetched);
    }

    static List<FetchedModel> enrichProviderModels(String base, List<FetchedModel> models) {
        List<FetchedModel> enriched = ModelsDevCatalog.enrich(base, models);
        if (!base.toLowerCase(java.util.Locale.ROOT).contains("api.b.ai")) return enriched;
        List<FetchedModel> result = new ArrayList<>(enriched.size());
        for (FetchedModel model : enriched) {
            String id = model.id.toLowerCase(java.util.Locale.ROOT);
            boolean limitedFree = id.equals("hy3") || id.equals("mimo-v2.5")
                    || id.equals("glm-5.3-flash") || id.equals("qwen3.8-flash");
            result.add(limitedFree && !model.free
                    ? new FetchedModel(model.id, model.displayName, model.contextWindow,
                    model.maxOutputTokens, model.inputModalities, model.reasoningLevels,
                    model.reasoningDefault, model.priceInput, model.priceOutput,
                    model.priceCached, model.hasPricing, true, model.kind)
                    : model);
        }
        return result;
    }

    private interface HeaderApplier {
        Request.Builder apply(Request.Builder builder);
    }

    private static List<FetchedModel> fetchWithHeaders(OkHttpClient client, String url, HeaderApplier headers) {
        List<FetchedModel> result = new ArrayList<>();
        try {
            Request.Builder reqBuilder = new Request.Builder().url(url).get()
                    .header("Accept", "application/json");
            headers.apply(reqBuilder);
            try (Response response = client.newCall(reqBuilder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) return result;
                return parseModelCatalog(response.body().string(), isPerTokenPricing(url));
            }
        } catch (Exception ignored) {}
        return result;
    }

    static List<FetchedModel> parseModelCatalog(String body) {
        return parseModelCatalog(body, false);
    }

    private static boolean isPerTokenPricing(String url) {
        String value = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        return value.contains("openrouter.ai") || value.contains("developer.amd.com.cn");
    }

    static List<FetchedModel> parseModelCatalog(String body, boolean perTokenPricing) {
        List<FetchedModel> result = new ArrayList<>();
        try {
                JSONObject json = new JSONObject(body);
                JSONArray data = json.optJSONArray("data");
                if (data == null) data = json.optJSONArray("models");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        Object item = data.opt(i);
                        if (!(item instanceof JSONObject)) {
                            // Плоский формат ["gpt-4o", ...] — только id.
                            if (item instanceof String && !((String) item).trim().isEmpty()) {
                                String sid = ((String) item).trim();
                                result.add(new FetchedModel(sid, toDisplayName(sid)));
                            }
                            continue;
                        }
                        JSONObject m = (JSONObject) item;
                        String id = m.optString("id", m.optString("name", "")).trim();
                        if (id.isEmpty()) continue;
                        // Pretty-имя: name/display_name из каталога, иначе prettifier из id.
                        String rawName = m.optString("name", m.optString("display_name", "")).trim();
                        String display = (rawName.isEmpty() || rawName.equals(id))
                                ? toDisplayName(id) : cleanVendorName(rawName);
                        long contextWindow = firstLong(m,
                                "context_length", "context_window", "contextWindow",
                                "max_context_tokens", "max_input_tokens");
                        long maxOutput = firstLong(m,
                                "max_output_tokens", "max_output_length", "maxOutputTokens",
                                "max_completion_tokens", "max_tokens");

                        // Модальности: плоский input_modalities ИЛИ OpenRouter-стиль
                        // architecture.input_modalities (amd.com.cn). Дедуп через
                        // LinkedHashSet: дубли вроде "text,text" ломали карточку.
                        java.util.LinkedHashSet<String> mods = new java.util.LinkedHashSet<>();
                        collectModalities(m.optJSONArray("input_modalities"), mods);
                        if (mods.isEmpty()) {
                            JSONObject arch = m.optJSONObject("architecture");
                            if (arch != null) collectModalities(arch.optJSONArray("input_modalities"), mods);
                        }
                        String inputModalities = String.join(",", mods);

                        // Размышление: reasoning.{kind=levels|supported_efforts} (OpenRouter),
                        // supported_sampling_parameters/supported_parameters с reasoning_effort,
                        // или просто "reasoning" в supported_parameters.
                        String reasoningLevels = "";
                        String reasoningDefault = "";
                        JSONObject reasoning = m.optJSONObject("reasoning");
                        if (reasoning != null) {
                            if ("levels".equals(reasoning.optString("kind", ""))) {
                                reasoningLevels = csvArray(reasoning.optJSONArray("levels"));
                                if (reasoningLevels.isEmpty())
                                    reasoningLevels = csvArray(reasoning.optJSONArray("efforts"));
                                reasoningDefault = reasoning.optString("default",
                                        reasoning.optString("default_effort", "")).trim();
                            }
                            // OpenRouter: supported_efforts + default_effort прямо на модели.
                            if (reasoningLevels.isEmpty())
                                reasoningLevels = csvArray(reasoning.optJSONArray("supported_efforts"));
                            if (reasoningDefault.isEmpty())
                                reasoningDefault = reasoning.optString("default_effort",
                                        reasoning.optString("default", "")).trim();
                        }
                        if (reasoningLevels.isEmpty()) {
                            if (hasParam(m, "supported_sampling_parameters", "reasoning_effort")
                                    || hasParam(m, "supported_parameters", "reasoning_effort")) {
                                reasoningLevels = "low,medium,high";
                                reasoningDefault = "medium";
                            } else if (hasParam(m, "supported_parameters", "reasoning")) {
                                reasoningLevels = "none,low,medium,high";
                                reasoningDefault = "medium";
                            }
                        }

                        JSONObject pricing = m.optJSONObject("pricing");
                        boolean hasPricing = pricing != null && (pricing.has("prompt")
                                || pricing.has("completion") || pricing.has("input_cache_reads")
                                || pricing.has("input_cache_read") || pricing.has("cache_reads")
                                || pricing.has("cached_input"));
                        double priceInput = hasPricing ? price(pricing, perTokenPricing, "prompt") : 0;
                        double priceOutput = hasPricing ? price(pricing, perTokenPricing, "completion") : 0;
                        double priceCached = hasPricing ? price(pricing, perTokenPricing, "input_cache_reads",
                                "input_cache_read", "cache_reads", "cached_input") : 0;
                        boolean explicitFree = m.optBoolean("billing_free", false)
                                || m.optBoolean("free", false)
                                || id.toLowerCase(java.util.Locale.ROOT).endsWith(":free")
                                || (hasPricing && priceInput == 0 && priceOutput == 0);
                        // Тип модели: output_modalities с "embedding" (bge-m3 у
                        // freeinference) — эмбеддинг-модель, в чате не используется.
                        java.util.LinkedHashSet<String> outMods = new java.util.LinkedHashSet<>();
                        collectModalities(m.optJSONArray("output_modalities"), outMods);
                        String kind = outMods.contains("embedding") ? "embedding" : "";
                        if ("embedding".equals(kind) || isEmbeddingModelId(id)) continue;
                        result.add(new FetchedModel(id, display, contextWindow, maxOutput,
                                inputModalities, reasoningLevels, reasoningDefault,
                                priceInput, priceOutput, priceCached, hasPricing, explicitFree, kind));
                    }
                }
        } catch (Exception ignored) {}
        return result;
    }

    /** Первое ненулевое числовое поле из списка ключей (число или число-строкой). */
    private static long firstLong(JSONObject obj, String... keys) {
        for (String key : keys) {
            Object v = obj.opt(key);
            if (v == null || v == JSONObject.NULL) continue;
            try {
                long n = v instanceof Number ? ((Number) v).longValue()
                        : Long.parseLong(v.toString().trim());
                if (n > 0) return n;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    /** true, если массив параметров модели содержит значение (без учёта регистра). */
    private static boolean hasParam(JSONObject obj, String arrayKey, String value) {
        JSONArray arr = obj.optJSONArray(arrayKey);
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            if (value.equalsIgnoreCase(arr.optString(i, ""))) return true;
        }
        return false;
    }

    /** Нормализует цену в USD за 1M; провайдеры могут отдавать уже per-million. */
    private static double price(JSONObject pricing, boolean perToken, String... keys) {
        double value = priceRaw(pricing, keys);
        return perToken ? value * 1_000_000d : value;
    }

    private static double priceRaw(JSONObject pricing, String... keys) {
        for (String key : keys) {
            Object value = pricing.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            try {
                double perToken = value instanceof Number ? ((Number) value).doubleValue()
                        : Double.parseDouble(value.toString().trim());
                if (perToken >= 0 && Double.isFinite(perToken)) return perToken;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    /** Собирает модальности в set с дедупом; "file" — тоже валидная модальность OpenRouter. */
    private static void collectModalities(JSONArray arr, java.util.Set<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim().toLowerCase(java.util.Locale.ROOT);
            if (!s.isEmpty()) out.add(s);
        }
    }

    /** JSONArray строк → CSV ("text,image"). */
    private static String csvArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim().toLowerCase(java.util.Locale.ROOT);
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        return sb.toString();
    }

    /** Простой prettifier для сырого model id (например "gpt-4o" → "GPT 4o"). */
    private static String toDisplayName(String id) {
        if (id == null || id.isEmpty()) return id;
        return app.multiai.mobile.models.ModelInfo.prettyName(id);
    }

    /**
     * OpenRouter-style имена вида "Qwen: Qwen3.6 Max Preview (free)" содержат
     * вендорный префикс и маркетинговый суффикс. Оставляем чистое имя модели:
     * префикс до ": " отрезаем, " (free)/(batch)/(beta)" снимаем.
     */
    static String cleanVendorName(String name) {
        if (name == null) return "";
        String out = name.trim();
        int colon = out.indexOf(": ");
        if (colon > 0 && colon <= 24) out = out.substring(colon + 2).trim();
        out = out.replaceAll("\\s*\\((free|batch|beta|alpha|preview|older[^)]*)\\)\\s*$", "");
        return out.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * Строит URL для chat completion запроса: {@code <versionedBase>/chat/completions}
     * (OpenAI) или {@code <versionedBase>/messages} (Anthropic), где {@code versionedBase}
     * — тот же нормализованный базовый URL, что и в {@link #fetchModels}, с добавленным
     * {@code /v1} если его не было. Без этой нормализации провайдер вроде официального
     * Anthropic API (baseURL {@code https://api.anthropic.com}, без {@code /v1} — там его
     * добавляет сам SDK на ПК) получал бы 404 при отправке сообщения.
     */
    public static String chatUrl(CustomProvider provider) {
        if (provider == null || provider.baseURL == null || provider.baseURL.trim().isEmpty()) return "";
        String base = provider.baseURL.trim().replaceAll("/+$", "");
        String versionedBase = VERSIONED_BASE.matcher(base).find() ? base : base + "/v1";
        if (CustomProvider.PARADIGM_ANTHROPIC.equals(provider.paradigm)) {
            return versionedBase + "/messages";
        }
        return versionedBase + "/chat/completions";
    }

    /** Результат embeddings-запроса: размерность, норма и первые значения вектора. */
    public static final class EmbeddingResult {
        public final int dims;
        public final float norm;
        public final String headValues;
        public final int inputTokens;

        EmbeddingResult(int dims, float norm, String headValues, int inputTokens) {
            this.dims = dims;
            this.norm = norm;
            this.headValues = headValues;
            this.inputTokens = inputTokens;
        }
    }

    /** true, если id модели — embedding (bge-m3, text-embedding-…, embed-…). */
    public static boolean isEmbeddingModelId(String modelId) {
        String m = modelId == null ? "" : modelId.trim().toLowerCase(java.util.Locale.ROOT);
        if (m.startsWith("ma-")) m = m.substring(3);
        return java.util.regex.Pattern.compile("(^|[-_/])(embed|embedding|embeddings|bge|e5)([-_/]|$)")
                .matcher(m).find();
    }

    /**
     * Прогнать текст через OpenAI-совместимый {@code POST /v1/embeddings}.
     * Вызывается из фонового потока. При ошибке бросает исключение с текстом HTTP-статуса.
     */
    public static EmbeddingResult embed(CustomProvider provider, String model, String input) throws Exception {
        String url = chatUrl(provider);
        if (url.isEmpty()) throw new IllegalStateException("provider base URL is empty");
        url = url.replaceFirst("/chat/completions$", "/embeddings")
                .replaceFirst("/messages$", "/embeddings");

        org.json.JSONObject body = new org.json.JSONObject();
        body.put("model", model);
        body.put("input", input);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).post(
                okhttp3.RequestBody.create(body.toString(),
                        okhttp3.MediaType.parse("application/json")))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + provider.apiKey)
                .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code() + " "
                        + respBody.substring(0, Math.min(respBody.length(), 240)));
            }
            org.json.JSONObject json = new org.json.JSONObject(respBody);
            org.json.JSONArray data = json.optJSONArray("data");
            if (data == null || data.length() == 0) throw new IllegalStateException("embedding response has no vector");
            org.json.JSONArray vec = data.optJSONObject(0).optJSONArray("embedding");
            if (vec == null || vec.length() == 0) throw new IllegalStateException("embedding response has no vector");

            double sumSquares = 0;
            StringBuilder head = new StringBuilder();
            int headCount = Math.min(vec.length(), 8);
            for (int i = 0; i < vec.length(); i++) {
                double v = vec.optDouble(i, 0);
                sumSquares += v * v;
                if (i < headCount) {
                    if (head.length() > 0) head.append(", ");
                    head.append(String.format(java.util.Locale.US, "%.4f", v));
                }
            }
            org.json.JSONObject usage = json.optJSONObject("usage");
            int tokens = usage == null ? 0 : (int) usage.optLong("prompt_tokens",
                    usage.optLong("input_tokens", 0));
            return new EmbeddingResult(vec.length(), (float) Math.sqrt(sumSquares),
                    head.toString(), tokens);
        }
    }

    /** Человекочитаемая сводка вектора для сообщения в чате (вся локализация в ресурсах). */
    public static String formatEmbeddingSummary(android.content.Context ctx, String model,
                                                String input, EmbeddingResult r) {
        String ellipsed = input.replaceAll("\\s+", " ").trim();
        if (ellipsed.length() > 160) ellipsed = ellipsed.substring(0, 160) + "…";
        String tokensLine = r.inputTokens > 0
                ? "\n" + ctx.getString(R.string.embed_input_tokens, r.inputTokens) : "";
        return ctx.getString(R.string.embed_summary,
                model,
                ellipsed,
                String.valueOf(r.dims),
                String.format(java.util.Locale.US, "%.4f", r.norm),
                r.headValues + ", … (" + Math.min(8, r.dims) + "/" + r.dims + ")")
                + tokensLine;
    }
}
