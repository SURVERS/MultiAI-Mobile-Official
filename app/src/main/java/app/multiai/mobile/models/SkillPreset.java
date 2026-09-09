package app.multiai.mobile.models;

import org.json.JSONObject;

import java.util.UUID;

/**
 * Пользовательский Skill — переиспользуемый фрагмент инструкций для AI,
 * вызываемый по явному триггеру {@code ./slug} в начале сообщения (Work Mode).
 *
 * <p>Архитектура повторяет AgentSkill из Desktop IDE:
 * <ul>
 *   <li>{@code slug} — короткий технический идентификатор вызова ({@code ./review}).</li>
 *   <li>{@code scope} — {@link #SCOPE_USER} (доступен во всех проектах) или
 *       {@link #SCOPE_WORKSPACE} (только в текущем выбранном проекте).</li>
 *   <li>{@code colorKey} — акцентный цвет карточки, тот же набор ключей что у сабагентов
 *       ({@code AccentColor.keys()}).</li>
 *   <li>Диск-скан {@code .multiai/skills/<slug>/SKILL.md} даёт read-only Skills
 *       (поле {@code fromDisk=true}) — они не редактируются в приложении, только
 *       включаются/выключаются через override.</li>
 * </ul>
 *
 * <p>Skills активны ТОЛЬКО в Work Mode (ChatActivity). Chat Mode их не использует.
 */
public final class SkillPreset {
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_WORKSPACE = "workspace";

    public String id;
    public String name;
    /** Технический идентификатор вызова: сообщение "./slug ..." подключает этот Skill. */
    public String slug;
    public String description;
    /** Текст Skill — добавляется в system prompt при вызове через ./slug. */
    public String content;
    /** Включён ли Skill (disabled Skill не находится по ./slug). */
    public boolean enabled;
    /** Акцентный цвет карточки — один из AccentColor.keys(). */
    public String colorKey;
    /** SCOPE_USER или SCOPE_WORKSPACE. */
    public String scope;
    /** URI выбранного проекта, если scope == SCOPE_WORKSPACE. */
    public String workspaceUri;
    /** true = прочитан с диска (.multiai/skills/&lt;slug&gt;/SKILL.md), редактирование недоступно. */
    public transient boolean fromDisk;
    /** Относительный путь SKILL.md внутри проекта — только для fromDisk Skills. */
    public transient String diskRelPath;
    public long createdAt;
    public long updatedAt;

    public SkillPreset() {
        id = UUID.randomUUID().toString();
        name = "";
        slug = "";
        description = "";
        content = "";
        enabled = true;
        colorKey = "yellow";
        scope = SCOPE_USER;
        workspaceUri = "";
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    public boolean isWorkspaceScoped() {
        return SCOPE_WORKSPACE.equals(scope) && workspaceUri != null && !workspaceUri.trim().isEmpty();
    }

    public boolean isAvailableForWorkspace(String activeWorkspaceUri) {
        return !isWorkspaceScoped() || workspaceUri.equals(activeWorkspaceUri == null ? "" : activeWorkspaceUri);
    }

    /** Нормализованный slug для сопоставления с триггером "./slug" (lowercase, без пробелов). */
    public String normalizedSlug() {
        return slug == null ? "" : slug.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "-");
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("name", name);
            obj.put("slug", slug == null ? "" : slug);
            obj.put("description", description == null ? "" : description);
            obj.put("content", content == null ? "" : content);
            obj.put("enabled", enabled);
            obj.put("color_key", colorKey == null ? "yellow" : colorKey);
            obj.put("scope", scope == null ? SCOPE_USER : scope);
            obj.put("workspace_uri", workspaceUri == null ? "" : workspaceUri);
            obj.put("created_at", createdAt);
            obj.put("updated_at", updatedAt);
        } catch (Exception ignored) {}
        return obj;
    }

    public static SkillPreset fromJson(JSONObject obj) {
        if (obj == null) return null;
        SkillPreset skill = new SkillPreset();
        String id = obj.optString("id", "").trim();
        skill.id = id.isEmpty() ? UUID.randomUUID().toString() : id;
        skill.name = obj.optString("name", "").trim();
        skill.slug = obj.optString("slug", "").trim();
        // Миграция старых записей (до появления slug): выводим slug из имени,
        // иначе такой Skill стал бы недоступен для вызова через "./slug".
        if (skill.slug.isEmpty() && !skill.name.isEmpty()) {
            skill.slug = slugify(skill.name);
        }
        skill.description = obj.optString("description", "").trim();
        skill.content = obj.optString("content", "").trim();
        skill.enabled = obj.optBoolean("enabled", true);
        skill.colorKey = nonEmpty(obj.optString("color_key", ""), "yellow");
        skill.scope = SCOPE_WORKSPACE.equals(obj.optString("scope", "")) ? SCOPE_WORKSPACE : SCOPE_USER;
        skill.workspaceUri = obj.optString("workspace_uri", "").trim();
        skill.createdAt = obj.optLong("created_at", System.currentTimeMillis());
        skill.updatedAt = obj.optLong("updated_at", skill.createdAt);
        return skill;
    }

    public SkillPreset copyAsNew() {
        SkillPreset copy = new SkillPreset();
        copy.name = name == null || name.trim().isEmpty() ? "" : name.trim() + " copy";
        copy.slug = slug;
        copy.description = description;
        copy.content = content;
        copy.enabled = enabled;
        copy.colorKey = colorKey;
        copy.scope = scope;
        copy.workspaceUri = workspaceUri;
        return copy;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Превращает произвольное имя в технический slug: "Code Style!" -> "code-style". */
    public static String slugify(String raw) {
        if (raw == null) return "";
        String slug = raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        return slug;
    }
}
