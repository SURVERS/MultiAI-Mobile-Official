package app.multiai.mobile.utils;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.multiai.mobile.models.SkillPreset;

/**
 * Диск-сканирование Skills из выбранного проекта — Android-аналог PC IDE
 * {@code scanSkillsDirectory}/{@code loadClaudeWorkspaceSkills} (main-src/llm/agentMessagePrep.ts).
 *
 * <p>Ищет {@code <dir>/skills/<slug>/SKILL.md} по трём конвенциям, в порядке
 * возрастания приоритета (последняя переопределяет совпадающий slug):
 * <ol>
 *   <li>{@code .claude/skills} — совместимость с Claude Code</li>
 *   <li>{@code .cursor/skills} — совместимость с Cursor</li>
 *   <li>{@code .multiai/skills} — родная конвенция этого приложения, всегда выигрывает</li>
 * </ol>
 *
 * <p>Каждый {@code SKILL.md} может начинаться с простого YAML frontmatter
 * ({@code name:}/{@code title:}/{@code description:}) — та же упрощённая грамматика,
 * что и в {@code stripSimpleFrontmatter} на PC. Slug берётся из имени папки, а не
 * из frontmatter (тоже как на PC).
 *
 * <p>В отличие от PC, здесь нет диск-скана уровня пользователя ({@code ~/.claude/skills}):
 * Android sandbox не даёт приложению читать произвольный домашний каталог через SAF,
 * поэтому "user scope" Skills на мобильном — это только созданные внутри приложения.
 */
public final class SkillDiskScanner {

    /** Родная конвенция приложения — используется также для импорта/создания через UI. */
    public static final String[] NATIVE_SEGMENTS = {".multiai", "skills"};

    /**
     * Конвенции сканирования в порядке возрастания приоритета (последняя
     * побеждает). Публично — тул read_skill в WorkModeTools ищет SKILL.md
     * по тем же конвенциям, что и диск-сканер каталога.
     */
    public static final String[][] SCAN_SEGMENTS_ORDER = {
            {".claude", "skills"},
            {".cursor", "skills"},
            {".multiai", "skills"},
    };

    private static final String[][] SCAN_SEGMENTS = SCAN_SEGMENTS_ORDER;
    private static final String SKILL_FILE = "SKILL.md";
    private static final int MAX_SKILLS_PER_DIR = 40;
    private static final int MAX_FILE_CHARS = 80_000;

    private SkillDiskScanner() {}

    /**
     * Сканирует выбранный проект (SAF) на все три конвенции и возвращает
     * итоговый список после merge-by-slug (последняя конвенция побеждает).
     * Пустой список, если проект не выбран или директорий нет.
     */
    public static List<SkillPreset> scanWorkspaceSkills(Context context) {
        DocumentFile root = projectRoot(context);
        if (root == null) return new ArrayList<>();
        String workspaceUri = workspaceUriOf(context);

        java.util.LinkedHashMap<String, SkillPreset> bySlug = new java.util.LinkedHashMap<>();
        for (String[] segments : SCAN_SEGMENTS) {
            for (SkillPreset skill : scanSegments(context, root, segments, workspaceUri)) {
                bySlug.put(skill.normalizedSlug(), skill);
            }
        }
        return new ArrayList<>(bySlug.values());
    }

    private static List<SkillPreset> scanSegments(Context context, DocumentFile root, String[] segments,
                                                   String workspaceUri) {
        List<SkillPreset> out = new ArrayList<>();
        DocumentFile dir = root;
        for (String segment : segments) {
            dir = findChild(dir, segment);
            if (dir == null || !dir.isDirectory()) return out;
        }
        DocumentFile[] children;
        try {
            children = dir.listFiles();
        } catch (Exception ignored) {
            return out;
        }
        if (children == null) return out;
        String relPrefix = String.join("/", segments);
        int count = 0;
        for (DocumentFile child : children) {
            if (count >= MAX_SKILLS_PER_DIR) break;
            if (child == null || !child.isDirectory()) continue;
            String slugRaw = child.getName();
            if (slugRaw == null || slugRaw.trim().isEmpty()) continue;
            DocumentFile skillMd = findChild(child, SKILL_FILE);
            if (skillMd == null || !skillMd.isFile()) continue;
            String raw = readFile(context, skillMd, MAX_FILE_CHARS);
            if (raw == null || raw.trim().isEmpty()) continue;
            SkillFrontmatter.Parsed fm = SkillFrontmatter.parse(raw);
            String slug = slugRaw.trim().toLowerCase(Locale.ROOT);

            SkillPreset skill = new SkillPreset();
            skill.id = "disk-skill:" + relPrefix + ":" + slug;
            skill.name = fm.title != null && !fm.title.trim().isEmpty() ? fm.title.trim() : slugRaw;
            skill.slug = slug;
            skill.description = fm.description != null && !fm.description.trim().isEmpty()
                    ? fm.description.trim()
                    : "Skill проекта из " + relPrefix + "/" + slugRaw + "/" + SKILL_FILE;
            skill.content = fm.body.trim();
            skill.enabled = true;
            skill.colorKey = "blue";
            skill.scope = SkillPreset.SCOPE_WORKSPACE;
            skill.workspaceUri = workspaceUri;
            skill.fromDisk = true;
            skill.diskRelPath = relPrefix + "/" + slugRaw + "/" + SKILL_FILE;
            out.add(skill);
            count++;
        }
        return out;
    }

    private static String readFile(Context context, DocumentFile file, int maxChars) {
        StringBuilder text = new StringBuilder(Math.min(maxChars, 4096));
        try (InputStream stream = context.getContentResolver().openInputStream(file.getUri())) {
            if (stream == null) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = maxChars - text.length();
                if (remaining <= 0) break;
                text.append(buffer, 0, Math.min(read, remaining));
            }
        } catch (Exception ignored) {
            return null;
        }
        return text.toString();
    }

    /**
     * Сканирует глобальные Skills пользователя — {@code filesDir/.claude/skills/<slug>/SKILL.md}.
     * Сюда же попадает установка через {@code npx skills add -g}: терминал приложения
     * живёт с HOME=filesDir, поэтому skills CLI кладёт глобальные скиллы именно сюда.
     * Обычный java.io (не SAF) — internal storage приложения.
     */
    public static List<SkillPreset> scanGlobalSkills(Context context) {
        List<SkillPreset> out = new ArrayList<>();
        if (context == null) return out;
        java.io.File base = new java.io.File(context.getFilesDir(), ".claude/skills");
        if (!base.isDirectory()) return out;
        java.io.File[] children = base.listFiles();
        if (children == null) return out;
        int count = 0;
        for (java.io.File child : children) {
            if (count >= MAX_SKILLS_PER_DIR) break;
            if (child == null || !child.isDirectory()) continue;
            String slugRaw = child.getName();
            if (slugRaw == null || slugRaw.trim().isEmpty()) continue;
            java.io.File skillMd = new java.io.File(child, SKILL_FILE);
            if (!skillMd.isFile()) continue;
            String raw = readIoFile(skillMd, MAX_FILE_CHARS);
            if (raw == null || raw.trim().isEmpty()) continue;
            SkillFrontmatter.Parsed fm = SkillFrontmatter.parse(raw);
            String slug = slugRaw.trim().toLowerCase(Locale.ROOT);

            SkillPreset skill = new SkillPreset();
            skill.id = "disk-skill:global:" + slug;
            skill.name = fm.title != null && !fm.title.trim().isEmpty() ? fm.title.trim() : slugRaw;
            skill.slug = slug;
            skill.description = fm.description != null && !fm.description.trim().isEmpty()
                    ? fm.description.trim()
                    : "Глобальный Skill из .claude/skills/" + slugRaw;
            skill.content = fm.body.trim();
            skill.enabled = true;
            skill.colorKey = "purple";
            skill.scope = SkillPreset.SCOPE_USER;
            skill.workspaceUri = "";
            skill.fromDisk = true;
            skill.diskRelPath = skillMd.getAbsolutePath();
            out.add(skill);
            count++;
        }
        return out;
    }

    private static String readIoFile(java.io.File file, int maxChars) {
        StringBuilder text = new StringBuilder(Math.min(maxChars, 4096));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = maxChars - text.length();
                if (remaining <= 0) break;
                text.append(buffer, 0, Math.min(read, remaining));
            }
        } catch (Exception ignored) {
            return null;
        }
        return text.toString();
    }

    private static DocumentFile projectRoot(Context context) {
        String raw = workspaceUriOf(context);
        if (raw.isEmpty()) return null;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(raw));
            return root != null && root.exists() && root.isDirectory() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String workspaceUriOf(Context context) {
        if (context == null) return "";
        String raw = context.getSharedPreferences("multiaiide", Context.MODE_PRIVATE)
                .getString("project_folder_uri", null);
        return raw == null ? "" : raw;
    }

    private static DocumentFile findChild(DocumentFile parent, String exactName) {
        if (parent == null) return null;
        try {
            DocumentFile exact = parent.findFile(exactName);
            if (exact != null) return exact;
            DocumentFile[] children = parent.listFiles();
            if (children == null) return null;
            for (DocumentFile child : children) {
                if (child != null && exactName.equalsIgnoreCase(child.getName())) return child;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Создаёт (если нужно) {@code .multiai/skills/<slug>/SKILL.md} в выбранном проекте
     * и записывает туда контент — используется при "Импортировать → сохранить в проект".
     * Возвращает true при успехе.
     */
    public static boolean writeNativeSkill(Context context, String slug, String name,
                                           String description, String content) {
        DocumentFile root = projectRoot(context);
        if (root == null || slug == null || slug.trim().isEmpty()) return false;
        try {
            DocumentFile dir = root;
            for (String segment : NATIVE_SEGMENTS) {
                DocumentFile next = findChild(dir, segment);
                if (next == null) next = dir.createDirectory(segment);
                if (next == null) return false;
                dir = next;
            }
            DocumentFile slugDir = findChild(dir, slug);
            if (slugDir == null) slugDir = dir.createDirectory(slug);
            if (slugDir == null) return false;
            DocumentFile file = findChild(slugDir, SKILL_FILE);
            if (file == null) file = slugDir.createFile("text/markdown", SKILL_FILE);
            if (file == null) return false;
            StringBuilder out = new StringBuilder();
            out.append("---\n");
            out.append("name: ").append(name == null ? slug : name.replace("\n", " ")).append("\n");
            if (description != null && !description.trim().isEmpty()) {
                out.append("description: ").append(description.replace("\n", " ")).append("\n");
            }
            out.append("---\n\n");
            out.append(content == null ? "" : content.trim()).append("\n");
            try (java.io.OutputStream os = context.getContentResolver().openOutputStream(file.getUri(), "wt")) {
                if (os == null) return false;
                os.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
