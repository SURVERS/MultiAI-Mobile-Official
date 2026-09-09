package app.multiai.mobile.utils;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Превращает настройки экрана «Персонализация» (тон + черты) в текстовую
 * инструкцию для системного промпта. Единый билдер для обоих режимов —
 * раньше туда попадал только свободный текст инструкций, а тон/черты
 * игнорировались.
 */
public final class PersonalizationPromptBuilder {

    private PersonalizationPromptBuilder() {}

    /** Блок «СТИЛЬ ОБЩЕНИЯ» для системного промпта; null, если всё нейтрально. */
    public static String buildStyleBlock(Context ctx) {
        AppSettings s = new AppSettings(ctx);
        StringBuilder sb = new StringBuilder();

        String tone = s.getPersonalizationTone();
        String tonePhrase = tonePhrase(tone);
        if (tonePhrase != null) {
            sb.append("Отвечай в ").append(tonePhrase).append(".");
        }

        Map<String, String> traitPhrases = new LinkedHashMap<>();
        traitPhrases.put("warmth", "будь теплее и дружелюбнее в общении");
        traitPhrases.put("excitement", "добавляй больше энергии и энтузиазма");
        traitPhrases.put("headers", "структурируй ответ заголовками и списками");
        traitPhrases.put("emoji", "используй эмодзи в ответах");

        for (Map.Entry<String, String> e : traitPhrases.entrySet()) {
            String level = s.getTraitLevel(e.getKey());
            if ("more".equals(level)) {
                appendPhrase(sb, e.getValue());
            } else if ("less".equals(level)) {
                appendPhrase(sb, "минимизируй: " + e.getValue()
                        .replace("будь теплее и дружелюбнее", "будь сдержан")
                        .replace("добавляй больше энергии и энтузиазма", "будь спокойнее")
                        .replace("структурируй ответ заголовками и списками", "избегай заголовков и списков")
                        .replace("используй эмодзи", "избегай эмодзи"));
            }
        }

        if (sb.length() == 0) return null;
        return "СТИЛЬ ОБЩЕНИЯ: " + sb;
    }

    private static void appendPhrase(StringBuilder sb, String phrase) {
        if (sb.length() > 0) sb.append(" ");
        sb.append(phrase).append(".");
    }

    private static String tonePhrase(String tone) {
        if (tone == null) return null;
        switch (tone) {
            case "professional": return "деловом тоне, без эмодзи";
            case "friendly": return "дружелюбном тоне";
            case "candid": return "прямом, откровенном тоне";
            case "whimsical": return "лёгком, игривом тоне";
            case "efficient": return "максимально сжатом и делеовом тоне, без эмодзи";
            case "sarcastic": return "ироничном тоне";
            case "default":
                // Дефолт продукта: живой друг, а не сухой ассистент.
                return "дружелюбном тоне: пиши тепло и неформально, как друг, уместно добавляй эмодзи";
            default: return null;
        }
    }
}
