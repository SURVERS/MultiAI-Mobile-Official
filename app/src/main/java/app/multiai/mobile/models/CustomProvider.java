package app.multiai.mobile.models;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Кастомный AI-провайдер (OpenAI-compatible или Anthropic wire format).
 * Хранит базовый URL, API-ключ и список моделей.
 *
 * <p>Аналог UserLlmProvider из SettingsCustomProvidersPanel в Desktop IDE.
 */
public final class CustomProvider {

    /** Поддерживаемые wire-форматы. */
    public static final String PARADIGM_OPENAI = "openai-compatible";
    public static final String PARADIGM_ANTHROPIC = "anthropic";

    public String id;
    public String displayName;
    /** PARADIGM_OPENAI или PARADIGM_ANTHROPIC. */
    public String paradigm;
    public String apiKey;
    public String baseURL;
    /** Список моделей этого провайдера (id + displayName). */
    public List<ProviderModel> models;
    public long createdAt;

    public CustomProvider() {
        id = UUID.randomUUID().toString();
        displayName = "";
        paradigm = PARADIGM_OPENAI;
        apiKey = "";
        baseURL = "";
        models = new ArrayList<>();
        createdAt = System.currentTimeMillis();
    }

    /** Одна модель провайдера. */
    public static final class ProviderModel {
        /** ID запроса — то, что отправляется в API (например "gpt-4o"). */
        public String requestName;
        /** Отображаемое имя. */
        public String displayName;
        /** Окно контекста (0 = сервер не отдал). */
        public long contextWindow;
        /** Лимит вывода (0 = не отдан). */
        public long maxOutputTokens;
        /** Модальности ввода CSV ("text,image"). */
        public String inputModalities;
        /** Уровни размышления CSV ("low,medium,high") если поддерживаются. */
        public String reasoningLevels;
        /** Дефолтный уровень размышления. */
        public String reasoningDefault;
        /** Цена входа (0 = не отдано). */
        public double priceInput;
        /** Цена вывода. */
        public double priceOutput;
        /** Цена чтения кэша. */
        public double priceCached;
        /** Сервер явно отдал pricing, включая корректные нулевые цены. */
        public boolean hasPricing;
        /** true: USD за 1M токенов; false: внутренний мультипликатор. */
        public boolean dollarPricing;
        /** Явный признак бесплатной модели, а не вывод из отсутствующей цены. */
        public boolean free;
        /** Тип модели: "embedding" — /v1/embeddings-модель; пусто = обычная чат-модель. */
        public String kind;

        public ProviderModel() {}

        public ProviderModel(String requestName, String displayName) {
            this.requestName = requestName;
            this.displayName = displayName;
        }

        public ProviderModel(String requestName, String displayName,
                             long contextWindow, long maxOutputTokens, String inputModalities) {
            this(requestName, displayName, contextWindow, maxOutputTokens, inputModalities, "", "", 0, 0, 0);
        }

        public ProviderModel(String requestName, String displayName,
                             long contextWindow, long maxOutputTokens, String inputModalities,
                             String reasoningLevels, String reasoningDefault) {
            this(requestName, displayName, contextWindow, maxOutputTokens, inputModalities,
                    reasoningLevels, reasoningDefault, 0, 0, 0);
        }

        public ProviderModel(String requestName, String displayName,
                             long contextWindow, long maxOutputTokens, String inputModalities,
                             String reasoningLevels, String reasoningDefault,
                             double priceInput, double priceOutput, double priceCached) {
            this(requestName, displayName, contextWindow, maxOutputTokens, inputModalities,
                    reasoningLevels, reasoningDefault, priceInput, priceOutput, priceCached,
                    priceInput > 0 || priceOutput > 0 || priceCached > 0, true, false);
        }

        public ProviderModel(String requestName, String displayName,
                             long contextWindow, long maxOutputTokens, String inputModalities,
                             String reasoningLevels, String reasoningDefault,
                             double priceInput, double priceOutput, double priceCached,
                             boolean hasPricing, boolean dollarPricing, boolean free) {
            this.requestName = requestName;
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
            this.dollarPricing = dollarPricing;
            this.free = free;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("request_name", requestName == null ? "" : requestName);
                obj.put("display_name", displayName == null ? "" : displayName);
                if (contextWindow > 0) obj.put("context_window", contextWindow);
                if (maxOutputTokens > 0) obj.put("max_output_tokens", maxOutputTokens);
                if (inputModalities != null && !inputModalities.isEmpty())
                    obj.put("input_modalities", inputModalities);
                if (reasoningLevels != null && !reasoningLevels.isEmpty())
                    obj.put("reasoning_levels", reasoningLevels);
                if (reasoningDefault != null && !reasoningDefault.isEmpty())
                    obj.put("reasoning_default", reasoningDefault);
                if (hasPricing) {
                    obj.put("price_input", priceInput);
                    obj.put("price_output", priceOutput);
                    obj.put("price_cached", priceCached);
                    obj.put("pricing_unit", dollarPricing ? "usd_per_million" : "multiplier");
                }
                if (free) obj.put("free", true);
                if (kind != null && !kind.isEmpty()) obj.put("model_kind", kind);
            } catch (Exception ignored) {}
            return obj;
        }

        public static ProviderModel fromJson(JSONObject obj) {
            if (obj == null) return null;
            ProviderModel m = new ProviderModel();
            m.requestName = obj.optString("request_name", "").trim();
            m.displayName = obj.optString("display_name", "").trim();
            m.contextWindow = obj.optLong("context_window", 0);
            m.maxOutputTokens = obj.optLong("max_output_tokens", 0);
            m.inputModalities = obj.optString("input_modalities", "");
            m.reasoningLevels = obj.optString("reasoning_levels", "");
            m.reasoningDefault = obj.optString("reasoning_default", "");
            m.priceInput = obj.optDouble("price_input", 0);
            m.priceOutput = obj.optDouble("price_output", 0);
            m.priceCached = obj.optDouble("price_cached", 0);
            m.hasPricing = obj.has("pricing_unit") || obj.has("price_input")
                    || obj.has("price_output") || obj.has("price_cached");
            m.dollarPricing = !"multiplier".equals(obj.optString("pricing_unit", "usd_per_million"));
            m.free = obj.optBoolean("free", false);
            m.kind = obj.optString("model_kind", "");
            return m;
        }
    }

    public boolean isOpenAiCompatible() {
        return PARADIGM_OPENAI.equals(paradigm);
    }

    public boolean isAnthropic() {
        return PARADIGM_ANTHROPIC.equals(paradigm);
    }

    public String paradigmLabel() {
        return PARADIGM_ANTHROPIC.equals(paradigm) ? "Anthropic" : "OpenAI";
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("display_name", displayName == null ? "" : displayName);
            obj.put("paradigm", paradigm == null ? PARADIGM_OPENAI : paradigm);
            obj.put("api_key", apiKey == null ? "" : apiKey);
            obj.put("base_url", baseURL == null ? "" : baseURL);
            JSONArray arr = new JSONArray();
            if (models != null) {
                for (ProviderModel m : models) arr.put(m.toJson());
            }
            obj.put("models", arr);
            obj.put("created_at", createdAt);
        } catch (Exception ignored) {}
        return obj;
    }

    public static CustomProvider fromJson(JSONObject obj) {
        if (obj == null) return null;
        CustomProvider provider = new CustomProvider();
        String id = obj.optString("id", "").trim();
        provider.id = id.isEmpty() ? UUID.randomUUID().toString() : id;
        provider.displayName = obj.optString("display_name", "").trim();
        String paradigm = obj.optString("paradigm", PARADIGM_OPENAI).trim();
        provider.paradigm = PARADIGM_ANTHROPIC.equals(paradigm) ? PARADIGM_ANTHROPIC : PARADIGM_OPENAI;
        provider.apiKey = obj.optString("api_key", "").trim();
        provider.baseURL = obj.optString("base_url", "").trim();
        JSONArray arr = obj.optJSONArray("models");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                ProviderModel m = ProviderModel.fromJson(arr.optJSONObject(i));
                if (m != null && m.requestName != null && !m.requestName.isEmpty()) {
                    provider.models.add(m);
                }
            }
        }
        provider.createdAt = obj.optLong("created_at", System.currentTimeMillis());
        return provider;
    }
}
