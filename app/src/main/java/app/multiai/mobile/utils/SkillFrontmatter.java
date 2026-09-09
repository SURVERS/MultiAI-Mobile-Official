package app.multiai.mobile.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Упрощённый парсер YAML frontmatter в SKILL.md-файлах — тот же формат, что и
 * PC IDE {@code stripSimpleFrontmatter} (main-src/llm/agentMessagePrep.ts):
 * блок {@code ---\n...\n---} в начале файла с плоскими ключами {@code key: value},
 * из которого нас интересуют только {@code name}/{@code title} и {@code description}.
 *
 * <p>Используется и диск-сканером ({@link SkillDiskScanner}), и ручным импортом
 * произвольного {@code .md} файла через UI (Skills → Импортировать).
 */
public final class SkillFrontmatter {

    private static final Pattern LINE = Pattern.compile("^([a-zA-Z0-9_-]+)\\s*:\\s*(.*)$");

    private SkillFrontmatter() {}

    public static final class Parsed {
        public final String body;
        public final String title;
        public final String description;
        Parsed(String body, String title, String description) {
            this.body = body;
            this.title = title;
            this.description = description;
        }
    }

    public static Parsed parse(String markdown) {
        if (markdown == null) return new Parsed("", null, null);
        String trimmed = markdown.trim();
        if (!trimmed.startsWith("---")) {
            return new Parsed(markdown, null, null);
        }
        int end = trimmed.indexOf("\n---", 3);
        if (end < 0) {
            return new Parsed(markdown, null, null);
        }
        String yamlBlock = trimmed.substring(3, end).trim();
        String body = trimmed.substring(end + 4).trim();
        String title = null;
        String description = null;
        for (String line : yamlBlock.split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1);
            String value = m.group(2) == null ? "" : m.group(2).replaceAll("^[\"']|[\"']$", "").trim();
            if (("name".equalsIgnoreCase(key) || "title".equalsIgnoreCase(key)) && title == null) {
                title = value;
            } else if ("description".equalsIgnoreCase(key)) {
                description = value;
            }
        }
        return new Parsed(body, title, description);
    }
}
