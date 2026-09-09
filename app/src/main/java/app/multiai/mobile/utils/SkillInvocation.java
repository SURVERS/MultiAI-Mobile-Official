package app.multiai.mobile.utils;

import android.content.Context;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.multiai.mobile.models.SkillPreset;

/**
 * Триггер Skill по префиксу {@code ./slug} в начале пользовательского сообщения —
 * Android-эквивалент PC IDE {@code applySkillInvocation}
 * (Desktop IDE: main-src/llm/agentMessagePrep.ts).
 *
 * <p>Срабатывает ТОЛЬКО когда сообщение начинается с {@code ./<slug>} (с необязательными
 * пробелами). Если находится включённый Skill с таким slug, в system-промпт хода
 * попадает ТОЛЬКО короткая метка (имя + описание + slug) с указанием вызвать тул
 * {@code read_skill} за полным телом SKILL.md — full-body инжекция контента
 * Skill в системный промпт удалена: тело Skill является недоверенными данными
 * и не должно попадать в контекст как системная инструкция. Префикс
 * {@code ./slug} убирается из текста, который видит пользователь и который
 * уходит модели. Если slug не найден — исходный текст возвращается без изменений.
 */
public final class SkillInvocation {

    private static final Pattern SKILL_LEAD = Pattern.compile("^\\s*\\./([\\w.-]+)\\s*([\\s\\S]*)$");

    private SkillInvocation() {}

    public static final class Result {
        /** Текст без "./slug"-префикса — то, что видит пользователь и что уходит модели. */
        public final String userText;
        /** Короткий system-блок-метка сработавшего Skill (без тела!), иначе пустая строка. */
        public final String skillSystemBlock;
        /** normalizedSlug сработавшего Skill, иначе null. */
        public final String usedSkillSlug;

        Result(String userText, String skillSystemBlock, String usedSkillSlug) {
            this.userText = userText;
            this.skillSystemBlock = skillSystemBlock;
            this.usedSkillSlug = usedSkillSlug;
        }
    }

    /**
     * @param rawText      исходный текст сообщения пользователя (до отправки)
     * @param context      для доступа к SkillStore
     * @param workspaceUri URI текущего выбранного проекта (для workspace-scoped Skills)
     */
    public static Result apply(String rawText, Context context, String workspaceUri) {
        String raw = rawText == null ? "" : rawText.trim();
        Matcher m = SKILL_LEAD.matcher(raw);
        if (!m.matches()) return new Result(raw, "", null);
        String slug = m.group(1).toLowerCase(Locale.ROOT);
        String rest = m.group(2) == null ? "" : m.group(2).trim();
        SkillPreset skill = SkillStore.findBySlug(context, slug, workspaceUri);
        if (skill == null) return new Result(raw, "", null);

        String userText = rest.isEmpty() ? "(Skill вызван — следуй инструкции ниже.)" : rest;
        return new Result(userText, renderSkillBlock(skill), skill.normalizedSlug());
    }

    /**
     * Рендерит короткий system-блок-метку для сработавшего Skill — без тела.
     * Полный SKILL.md модель читает сама через read-only тул {@code read_skill}
     * с slug из этой метки. Тело НЕ вставляется в системный промпт: это
     * недоверенный контент, и его место — обычный tool-result.
     */
    public static String renderSkillBlock(SkillPreset skill) {
        if (skill == null) return "";
        String slug = skill.normalizedSlug();
        if (slug.isEmpty()) return "";
        StringBuilder block = new StringBuilder();
        block.append("#### Skill: ").append(skill.name == null || skill.name.trim().isEmpty()
                ? skill.slug : skill.name.trim()).append("\n");
        if (skill.description != null && !skill.description.trim().isEmpty()) {
            block.append(skill.description.trim()).append("\n");
        }
        block.append("The user explicitly invoked the skill './").append(slug)
                .append("' for this turn. Call the read_skill tool with slug=\"")
                .append(slug).append("\" NOW and follow the returned SKILL.md ")
                .append("instructions as the primary directive for this turn.\n");
        return block.toString();
    }
}
