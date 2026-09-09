package app.multiai.mobile.utils;

/**
 * Чистая логика безопасного чтения SKILL.md для тула {@code read_skill}.
 *
 * <p>Раньше триггер {@code ./slug} вставлял ВСЁ тело Skill в system prompt
 * (см. {@code SkillInvocation}) — это full-body системная инжекция: любой
 * установленный скилл целиком попадал в контекст без ведома AI-цикла тулов.
 * Теперь AI сам читает полный SKILL.md через локальный read-only тул, а этот
 * класс отвечает за две вещи:
 * <ul>
 *   <li>{@link #normalizeSlug(String)} — валидация slug по строгому белому
 *       списку ({@code [a-z0-9._-]}, без разделителей пути, без "..", ≤64
 *       символов). Тул принимает ТОЛЬКО валидный slug из каталога, а не
 *       произвольный путь — читать arbitrary files через read_skill нельзя.</li>
 *   <li>{@link #extractBody(String, int)} — достаёт тело SKILL.md (без YAML
 *       frontmatter) и ограничивает его размер, как это делает read_file.</li>
 * </ul>
 *
 * <p>Оба метода чистые (без Android) и покрыты JVM-тестами.
 */
public final class SkillReader {

    /** Максимальная длина slug — достаточна для любого реального скилла. */
    private static final int MAX_SLUG_CHARS = 64;

    private SkillReader() {}

    /**
     * Нормализует и валидирует slug для read_skill.
     *
     * @return нормализованный slug (lowercase) или {@code null}, если вход
     *         не проходит белый список: путь/разделители, "..", пустой,
     *         управляющие символы или длиннее {@link #MAX_SLUG_CHARS}.
     */
    public static String normalizeSlug(String raw) {
        if (raw == null) return null;
        String slug = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (slug.isEmpty() || slug.length() > MAX_SLUG_CHARS) return null;
        if (slug.contains("..")) return null;
        // Первый символ — буква/цифра, дальше буквы/цифры/точка/дефис/подчёркивание.
        // Отсекает "./", "../", скрытые имена, слэши и прочий path-мусор.
        if (!slug.matches("^[a-z0-9][a-z0-9._-]*$")) return null;
        return slug;
    }

    /** Результат извлечения тела SKILL.md. */
    public static final class Body {
        /** Тело инструкции (frontmatter срезан), обрезанное до лимита. */
        public final String text;
        /** true, если тело не влезло целиком и было обрезано. */
        public final boolean truncated;

        Body(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    /**
     * Достаёт тело SKILL.md: срезает YAML frontmatter ({@link SkillFrontmatter})
     * и клипует до {@code maxChars} с маркером усечения — ровно как read_file.
     */
    public static Body extractBody(String rawSkillMd, int maxChars) {
        String raw = rawSkillMd == null ? "" : rawSkillMd;
        String body = SkillFrontmatter.parse(raw).body;
        if (body == null) body = "";
        body = body.trim();
        int limit = Math.max(1, maxChars);
        if (body.length() <= limit) return new Body(body, false);
        return new Body(body.substring(0, limit) + "\n...[truncated]", true);
    }
}
