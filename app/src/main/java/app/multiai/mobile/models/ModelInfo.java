package app.multiai.mobile.models;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Полная модель из каталога MultiAI {@code GET /v1/models}.
 *
 * <p>Хранит все поля каталога: контекст, лимиты вывода, модальности,
 * биллинг-мультипликаторы («сколько она ест»), reasoning-уровни (effort).
 * Плюс вычисляемые: красивое имя ({@link #displayName}) и категория
 * ({@link #category}) для группировки в пикере.
 */
public class ModelInfo {

    /** Категории пикера (порядок = порядок вкладок). */
    public static final String CAT_ALL = "all";
    public static final String CAT_FREE = "free";
    public static final String CAT_OPENAI = "openai";
    public static final String CAT_ANTHROPIC = "anthropic";
    public static final String CAT_GOOGLE = "google";
    public static final String CAT_CHINESE = "chinese";
    public static final String CAT_OTHER = "other";

    public final String id;
    public final String displayName;      // "Big Pickle", "Claude Opus 4.6"
    public final String category;         // одна из CAT_*
    public final boolean free;            // billing_free
    public final long contextWindow;      // context_window (0 = неизвестно)
    public final long maxOutputTokens;    // max_output_tokens (0 = неизвестно)
    public final String inputModalities;  // "text,image"
    public final String modelKind;        // text / image_generation / ...
    public final double inputMultiplier;
    public final double outputMultiplier;
    public final double cachedInputMultiplier;
    public final String reasoningKind;    // levels / managed / toggle / none
    public final List<String> reasoningLevels = new ArrayList<>();
    public final String reasoningDefault; // "medium" / "none" / ...
    public final boolean reasoningAllowDisable;

    /** Выбранный effort (меняется в пикере, дефолт — reasoningDefault). */
    public String effort;

    public ModelInfo(JSONObject m) {
        id = m.optString("id", "");
        free = m.optBoolean("billing_free", false);
        long ctx = firstPositive(m, "context_window", "contextWindow",
                "context_length", "max_context_tokens", "max_input_tokens");
        if (ctx <= 0) ctx = knownContext(id);
        contextWindow = ctx;
        maxOutputTokens = firstPositive(m, "max_output_tokens", "max_tokens");
        inputMultiplier = m.optDouble("input_multiplier", 0);
        outputMultiplier = m.optDouble("output_multiplier", 0);
        cachedInputMultiplier = m.optDouble("cached_input_multiplier", 0);
        modelKind = m.optString("model_kind", "text");

        StringBuilder mods = new StringBuilder();
        org.json.JSONArray arr = m.optJSONArray("input_modalities");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                if (mods.length() > 0) mods.append(",");
                mods.append(arr.optString(i, ""));
            }
        }
        inputModalities = mods.toString();

        JSONObject r = m.optJSONObject("reasoning");
        String parsedKind = "none";
        String parsedDefault = null;
        boolean parsedAllowDisable = false;
        if (r != null) {
            parsedAllowDisable = r.optBoolean("allow_disable",
                    r.optBoolean("allowDisable", false));
            org.json.JSONArray lv = r.optJSONArray("levels");
            if (lv == null) lv = r.optJSONArray("efforts");
            if (lv != null) {
                for (int i = 0; i < lv.length(); i++) {
                    String level = lv.optString(i, "").trim().toLowerCase(java.util.Locale.ROOT);
                    if (!level.isEmpty() && !reasoningLevels.contains(level)) {
                        reasoningLevels.add(level);
                    }
                }
            }

            parsedKind = r.optString("kind", "").trim().toLowerCase(java.util.Locale.ROOT);
            if (parsedKind.isEmpty()) {
                // reasoning:{} без levels означает provider-managed reasoning.
                parsedKind = reasoningLevels.isEmpty() ? "managed" : "levels";
            }
            String d = r.optString("default_effort",
                    r.optString("defaultEffort", r.optString("default", "")))
                    .trim().toLowerCase(java.util.Locale.ROOT);
            if (!d.isEmpty()) parsedDefault = d;

            // "none" допустим только когда каталог явно разрешил отключение.
            if (!parsedAllowDisable) {
                reasoningLevels.remove("none");
            } else if (!"managed".equals(parsedKind) && !"none".equals(parsedKind)
                    && !reasoningLevels.isEmpty() && !reasoningLevels.contains("none")) {
                reasoningLevels.add(0, "none");
            }
            if ("managed".equals(parsedKind) || "none".equals(parsedKind)
                    || ("none".equals(parsedDefault) && !parsedAllowDisable)) {
                parsedDefault = null;
            } else if (parsedDefault != null && !reasoningLevels.isEmpty()
                    && !reasoningLevels.contains(parsedDefault)) {
                parsedDefault = null;
            }
        }
        reasoningKind = parsedKind;
        reasoningDefault = parsedDefault;
        reasoningAllowDisable = parsedAllowDisable;
        effort = reasoningDefault;

        displayName = prettyName(id);
        category = categorize(id, free);
    }

    /** true, если у модели есть выбор effort (больше одного уровня). */
    public boolean hasEffort() {
        return reasoningLevels.size() > 1 && !"managed".equals(reasoningKind)
                && !"none".equals(reasoningKind);
    }

    /**
     * Приводит сохранённый/чатовый effort к актуальному каталогу.
     * null означает «не отправлять поле и оставить решение провайдеру».
     */
    public String resolveEffort(String candidate) {
        if ("managed".equals(reasoningKind) || "none".equals(reasoningKind)) {
            return null;
        }
        if (candidate == null || candidate.trim().isEmpty()) {
            return reasoningDefault;
        }
        String normalized = candidate.trim().toLowerCase(java.util.Locale.ROOT);
        if ("none".equals(normalized)) {
            return reasoningAllowDisable ? "none" : null;
        }
        if (!reasoningLevels.isEmpty() && !reasoningLevels.contains(normalized)) {
            return reasoningDefault;
        }
        return normalized;
    }

    /** Короткая подпись контекста: "200K", "1M", "?" */
    public String contextLabel() {
        if (contextWindow <= 0) return "?";
        if (contextWindow >= 1_000_000 && contextWindow % 1_000_000 == 0)
            return (contextWindow / 1_000_000) + "M";
        if (contextWindow >= 1000) return Math.round(contextWindow / 1000.0) + "K";
        return String.valueOf(contextWindow);
    }

    /** Доля контекста от максимума каталога (1M) — для кольца, 0..1. */
    public float contextFraction(long catalogMax) {
        if (contextWindow <= 0 || catalogMax <= 0) return 0f;
        return Math.min(1f, contextWindow / (float) catalogMax);
    }

    private static long firstPositive(JSONObject m, String... keys) {
        for (String k : keys) {
            long v = m.optLong(k, 0);
            if (v > 0) return v;
        }
        return 0;
    }

    /**
     * Каталог не отдаёт context window для некоторых моделей (поля просто
     * отсутствуют в /v1/models). Используем официальные лимиты семейств,
     * чтобы кольцо и подпись не показывали "?" у известных моделей.
     */
    private static long knownContext(String id) {
        String s = id.toLowerCase();
        if (s.startsWith("ma-")) s = s.substring(3);
        if (s.contains("gemini")) return 1_000_000;          // Gemini 3.x Pro/Flash
        if (s.contains("claude-opus-5")) return 1_000_000;   // Opus 5 = 1M
        if (s.contains("gpt-oss")) return 131_072;           // gpt-oss-120b
        // image_generation-модели (gpt-image-2, nano-banana) — короткое окно
        if (s.contains("image") || s.contains("banana")) return 32_768;
        return 0;
    }

    /** "big-pickle" → "Big Pickle", "claude-opus-4-8" → "Claude Opus 4.8",
     *  "glm-5.2" → "GLM 5.2", "gpt-5.6-luna" → "GPT 5.6 Luna".
     *  Хвостовые цифровые сегменты склеиваются в версию с точкой. */
    public static String prettyName(String id) {
        if (id == null || id.isEmpty()) return "?";
        // Хардкод-переименования для конкретных моделей каталога.
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ma-")) lower = lower.substring(3);
        if (lower.equals("x-preview-f-free")) return "Ox Alpha Free";
        if (lower.equals("muse-spark-1.2-contributor-free")) return "Muse Spark 1.2 Free";
        String s = id;
        if (s.startsWith("ma-")) s = s.substring(3);
        String[] parts = s.split("-");
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            // "4-8" в хвосте: если текущий токен число и предыдущий тоже число
            // (а перед ним не число) — это минорная версия, склеиваем через точку
            if (!tokens.isEmpty() && p.matches("\\d+")) {
                int last = tokens.size() - 1;
                String prev = tokens.get(last);
                if (prev.matches("\\d+")) {
                    tokens.set(last, prev + "." + p);
                    continue;
                }
            }
            tokens.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(prettyToken(t));
        }
        return sb.length() > 0 ? sb.toString() : id;
    }

    private static String prettyToken(String t) {
        // Дробное число — как есть: "5.2"
        if (t.matches("\\d+(\\.\\d+)?")) return t;
        String lower = t.toLowerCase();
        switch (lower) {
            case "gpt": return "GPT";
            case "glm": return "GLM";
            case "oss": return "OSS";
            case "ai":  return "AI";
            case "api": return "API";
            default:
                return Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
    }

    private static String categorize(String id, boolean free) {
        String s = id.toLowerCase();
        if (s.startsWith("ma-")) s = s.substring(3);
        if (s.contains("claude")) return CAT_ANTHROPIC;
        if (s.contains("gpt") || s.contains("o1") || s.contains("o3") || s.contains("o4")) return CAT_OPENAI;
        if (s.contains("gemini") || s.contains("nano-banana") || s.contains("nano_banana")) return CAT_GOOGLE;
        if (s.contains("deepseek") || s.contains("glm") || s.contains("qwen")
                || s.contains("kimi") || s.contains("minimax") || s.contains("longcat")
                || s.contains("mimo") || s.contains("laguna")) return CAT_CHINESE;
        return CAT_OTHER;
    }
}
