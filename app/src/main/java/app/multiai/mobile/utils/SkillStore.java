package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import app.multiai.mobile.models.SkillPreset;

/**
 * Хранилище Skills (SharedPreferences) + слияние с диск-сканом проекта.
 *
 * <p>Архитектура повторяет PC IDE (main-src/llm/agentMessagePrep.ts):
 * <ul>
 *   <li>Skills, созданные в приложении, живут в {@code multiaiide_skills}.</li>
 *   <li>{@link SkillDiskScanner} сканирует {@code .multiai/skills}, {@code .cursor/skills},
 *       {@code .claude/skills} в выбранном проекте — эти Skills read-only (их контент
 *       не редактируется в приложении, только slug служит ключом слияния).</li>
 *   <li>{@link #availableForWorkspace} — итоговый список: диск ПЕРЕОПРЕДЕЛЯЕТ
 *       созданный в приложении Skill с тем же slug (как {@code mergeSkillsBySlug} на PC),
 *       затем к диск-скиллам применяется локальный enable/disable override,
 *       потому что сам файл на диске недоступен для редактирования.</li>
 * </ul>
 *
 * <p>Skills инжектируются ТОЛЬКО в Work Mode (ChatActivity), и только по явному
 * триггеру {@code ./slug} в начале сообщения — см. {@code SkillInvocation}.
 */
public final class SkillStore {

    private static final String PREFS = "multiaiide_skills";
    private static final String KEY_SKILLS = "skills_json";
    private static final String KEY_DISK_OVERRIDES = "disk_enabled_overrides_json";
    private static final int SCHEMA_VERSION = 2;

    // Кэш merged-списка: SAF-скан диска (DocumentFile через ContentResolver) — это IPC
    // по десяткам мс на файл. Без кэша каталог скиллов гонял SAF при КАЖДОМ сообщении
    // и каждом tool-round на главном потоке → фризы. Инвалидируется по записи/тоглу
    // и по смене проекта, плюс TTL на случай внешней установки скилла (npx/git).
    private static List<SkillPreset> cachedAvailable;
    private static String cachedAvailableScopeKey;
    private static long cachedAvailableAt;
    private static final long AVAILABLE_TTL_MS = 15_000L;

    /** Сбрасывает кэш merged-списка (вызывать при записи/удалении/тогле скилла). */
    public static synchronized void invalidateCache() {
        cachedAvailable = null;
        cachedAvailableScopeKey = null;
        cachedAvailableAt = 0L;
        cachedCatalog = null;
        cachedCatalogScopeKey = null;
    }

    public static synchronized List<SkillPreset> getAll(Context context) {
        List<SkillPreset> list = new ArrayList<>();
        if (context == null) return list;
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SKILLS, "{\"schema_version\":2,\"skills\":[]}");
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("skills");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    SkillPreset skill = SkillPreset.fromJson(arr.optJSONObject(i));
                    if (skill != null && !skill.name.isEmpty()) list.add(skill);
                }
            }
        } catch (Exception ignored) {}
        Collections.sort(list, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return list;
    }

    /**
     * Итоговый список для UI и для инжекции: приложенческие Skills, доступные
     * в этом scope (user всегда + workspace если совпадает URI), слитые по slug
     * с диск-сканом проекта (диск побеждает при совпадении slug — как на PC),
     * с применённым enable/disable override для диск-Skills.
     */
    public static synchronized List<SkillPreset> availableForWorkspace(Context context, String workspaceUri) {
        String scopeKey = workspaceUri == null ? "" : workspaceUri;
        long now = System.currentTimeMillis();
        if (cachedAvailable != null && scopeKey.equals(cachedAvailableScopeKey)
                && now - cachedAvailableAt < AVAILABLE_TTL_MS) {
            return new ArrayList<>(cachedAvailable);
        }
        LinkedHashMap<String, SkillPreset> bySlug = new LinkedHashMap<>();
        JSONObject overrides = readDiskOverrides(context);
        // Глобальные диск-Skills (filesDir/.claude/skills, в т.ч. поставленные через
        // "npx skills add -g") — самый низкий приоритет, их могут перекрыть.
        for (SkillPreset disk : SkillDiskScanner.scanGlobalSkills(context)) {
            String norm = disk.normalizedSlug();
            if (overrides.has(norm)) disk.enabled = overrides.optBoolean(norm, true);
            bySlug.put(keyFor(disk), disk);
        }
        for (SkillPreset skill : getAll(context)) {
            if (!skill.isAvailableForWorkspace(workspaceUri)) continue;
            bySlug.put(keyFor(skill), skill);
        }
        for (SkillPreset disk : SkillDiskScanner.scanWorkspaceSkills(context)) {
            String norm = disk.normalizedSlug();
            if (overrides.has(norm)) disk.enabled = overrides.optBoolean(norm, true);
            bySlug.put(keyFor(disk), disk);
        }
        cachedAvailable = new ArrayList<>(bySlug.values());
        cachedAvailableScopeKey = scopeKey;
        cachedAvailableAt = now;
        return new ArrayList<>(cachedAvailable);
    }

    /** Только включённые Skills — используется резолвером триггера "./slug". */
    public static List<SkillPreset> getEnabledForWorkspace(Context context, String workspaceUri) {
        List<SkillPreset> enabled = new ArrayList<>();
        for (SkillPreset skill : availableForWorkspace(context, workspaceUri)) {
            if (skill.enabled) enabled.add(skill);
        }
        return enabled;
    }

    /** Ищет включённый Skill по slug (без учёта регистра) для триггера "./slug". */
    public static SkillPreset findBySlug(Context context, String slug, String workspaceUri) {
        if (slug == null || slug.trim().isEmpty()) return null;
        String norm = slug.trim().toLowerCase(Locale.ROOT);
        for (SkillPreset skill : getEnabledForWorkspace(context, workspaceUri)) {
            if (norm.equals(skill.normalizedSlug())) return skill;
        }
        return null;
    }

    /**
     * Каталог включённых Skills для системного промпта (progressive disclosure,
     * как у Anthropic Agent Skills): ТОЛЬКО метаданные — имя, ./slug и описание.
     * Полное тело SKILL.md AI читает САМ через безопасный read-only тул
     * read_skill (slug из каталога), когда задача совпадает со скиллом —
     * тело не грузится в контекст заранее и не инжектится в system prompt.
     * ~100 токенов на Skill.
     */
    public static String buildCatalogForSystemPrompt(Context context, String workspaceUri) {
        List<SkillPreset> enabled = getEnabledForWorkspace(context, workspaceUri);
        if (enabled.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("AVAILABLE SKILLS (").append(enabled.size()).append("):\n");
        sb.append("Each entry is an instruction pack: the full SKILL.md is read on demand with the ")
          .append("read_skill tool (pass the slug, e.g. {\"slug\":\"banner-design\"}). When the user's ")
          .append("task matches a skill's description, call read_skill for it and follow the returned ")
          .append("instructions exactly — do NOT wait for the user and do NOT require any command. ")
          .append("If a '#### Skill:' block is present in this prompt, the user explicitly invoked that ")
          .append("skill: read it first, then follow it.\n");
        for (SkillPreset s : enabled) {
            sb.append("- ./").append(s.normalizedSlug()).append(" — ")
              .append(s.name == null || s.name.trim().isEmpty() ? s.normalizedSlug() : s.name.trim());
            if (s.description != null && !s.description.trim().isEmpty()) {
                sb.append(": ").append(s.description.trim());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // Кэш САМОГО каталога (строки) — собирается один раз, SAF/диск не дергается
    // на каждый чанк стрима. Это главный фикс фризов при ответе AI.
    private static String cachedCatalog;
    private static String cachedCatalogScopeKey;

    /** Каталог из кэша; при промахе — собирает синхронно (первый раз) и кладёт в кэш. */
    public static synchronized String catalogCached(Context context, String workspaceUri) {
        String scopeKey = workspaceUri == null ? "" : workspaceUri;
        if (cachedCatalog != null && scopeKey.equals(cachedCatalogScopeKey)) {
            return cachedCatalog;
        }
        String built = buildCatalogForSystemPrompt(context, workspaceUri);
        cachedCatalog = built;
        cachedCatalogScopeKey = scopeKey;
        return built;
    }

    /** true, если slug уже занят другим Skill (для валидации формы редактора). */
    public static boolean slugTaken(Context context, String slug, String excludeId) {
        String norm = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) return false;
        for (SkillPreset skill : getAll(context)) {
            if (skill.id.equals(excludeId)) continue;
            if (norm.equals(skill.normalizedSlug())) return true;
        }
        return false;
    }

    /**
     * Включает/выключает Skill, прочитанный с диска (сам файл не трогаем — только
     * локальный override, ключ — normalizedSlug). Для обычных Skills делегирует в save().
     */
    public static synchronized void setDiskSkillEnabled(Context context, String normalizedSlug, boolean enabled) {
        if (context == null || normalizedSlug == null || normalizedSlug.trim().isEmpty()) return;
        JSONObject overrides = readDiskOverrides(context);
        try {
            overrides.put(normalizedSlug, enabled);
        } catch (Exception ignored) {}
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DISK_OVERRIDES, overrides.toString()).apply();
        invalidateCache();
    }

    private static JSONObject readDiskOverrides(Context context) {
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DISK_OVERRIDES, "{}");
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String keyFor(SkillPreset skill) {
        String slug = skill.normalizedSlug();
        return slug.isEmpty() ? "id:" + skill.id : "slug:" + slug;
    }

    public static synchronized void save(Context context, SkillPreset skill) {
        if (context == null || skill == null || skill.name.trim().isEmpty()) return;
        if (skill.slug == null || skill.slug.trim().isEmpty()) {
            skill.slug = SkillPreset.slugify(skill.name);
        }
        List<SkillPreset> list = getAll(context);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (skill.id.equals(list.get(i).id)) {
                skill.createdAt = list.get(i).createdAt;
                list.set(i, skill);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(skill);
        skill.updatedAt = System.currentTimeMillis();
        write(context, list);
    }

    public static synchronized void delete(Context context, String id) {
        if (context == null || id == null) return;
        List<SkillPreset> list = getAll(context);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (id.equals(list.get(i).id)) list.remove(i);
        }
        write(context, list);
    }

    public static synchronized SkillPreset find(Context context, String id) {
        if (id == null) return null;
        for (SkillPreset skill : getAll(context)) {
            if (id.equals(skill.id)) return skill;
        }
        return null;
    }

    private static void write(Context context, List<SkillPreset> list) {
        JSONArray arr = new JSONArray();
        for (SkillPreset skill : list) arr.put(skill.toJson());
        JSONObject root = new JSONObject();
        try {
            root.put("schema_version", SCHEMA_VERSION);
            root.put("skills", arr);
        } catch (Exception ignored) {}
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SKILLS, root.toString()).apply();
        invalidateCache();
    }
}
