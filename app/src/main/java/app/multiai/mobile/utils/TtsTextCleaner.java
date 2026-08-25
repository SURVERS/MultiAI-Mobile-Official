package app.multiai.mobile.utils;

import java.util.regex.Pattern;

/**
 * Чистка текста перед отправкой в TTS:
 * - убирает эмодзи (Edge читает их как "эмоджи" или тупит)
 * - убирает markdown (**, ##, списки)
 * - нормализует whitespace
 */
public final class TtsTextCleaner {

    private TtsTextCleaner() {}

    // Диапазоны Unicode-эмодзи + служебные символы
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F300}-\\x{1F9FF}]" +   // symbols, pictographs, supp
                    "|[\\x{1FA00}-\\x{1FAFF}]" + // extended symbols A/B
                    "|[\\x{2600}-\\x{27BF}]" +  // misc symbols, dingbats
                    "|[\\x{1F000}-\\x{1F2FF}]" + // mahjong, domino, cards, enclosed
                    "|[\\x{1F100}-\\x{1F1FF}]" + // enclosed alphanum supp (flags pieces)
                    "|[\\x{200D}\\x{FE0F}\\x{20E3}\\x{2049}\\x{203C}]" + // ZWJ, VS, combining
                    "|[\\x{E000}-\\x{F8FF}]"    // PUA (some platform glyphs)
    );

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]+`");
    private static final Pattern MD_THINK = Pattern.compile("(?s)<think>.*?</think>");
    private static final Pattern MD_HEADER = Pattern.compile("(?m)^#{1,6}\\s*");
    private static final Pattern MD_HEADER_INLINE = Pattern.compile("##+");
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern MD_ITALIC = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern MD_BULLET = Pattern.compile("(?m)^[-*•]\\s+");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]+\\)");
    private static final Pattern MULTISPACE = Pattern.compile("[ \\t]+");
    private static final Pattern NEWLINES = Pattern.compile("\\n{2,}");

    /** Полная чистка для TTS. Возвращает произносимый текст. */
    public static String clean(String text) {
        if (text == null || text.isEmpty()) return "";

        String s = text;
        s = MD_THINK.matcher(s).replaceAll("");
        s = CODE_BLOCK.matcher(s).replaceAll(" ");
        s = INLINE_CODE.matcher(s).replaceAll(" ");
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = MD_BOLD.matcher(s).replaceAll("$1");
        s = MD_ITALIC.matcher(s).replaceAll("$1");
        s = MD_HEADER.matcher(s).replaceAll("");
        s = MD_HEADER_INLINE.matcher(s).replaceAll(" ");
        s = MD_BULLET.matcher(s).replaceAll("");
        s = EMOJI.matcher(s).replaceAll("");
        s = s.replaceAll("[~>_]", "");
        s = NEWLINES.matcher(s).replaceAll(". ");
        s = s.replace('\n', ' ');
        s = MULTISPACE.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+([,.!?;:])", "$1");
        s = s.trim();
        return s;
    }
}
