package app.multiai.mobile.utils;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Creates, discovers and loads project-scoped Markdown rules from a SAF tree. */
public final class ProjectRulesManager {
    public static final String RULES_DIRECTORY = ".multiai/rules";
    private static final int MAX_FILES = 50;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_FILE_CHARS = 32_000;
    private static final int MAX_TOTAL_CHARS = 128_000;

    private ProjectRulesManager() { }

    public static final class RuleFile {
        public final String path;
        public final String content;
        public final boolean agentsFile;
        public final boolean truncated;

        RuleFile(String path, String content, boolean agentsFile, boolean truncated) {
            this.path = path;
            this.content = content;
            this.agentsFile = agentsFile;
            this.truncated = truncated;
        }
    }

    public static final class Snapshot {
        public final List<RuleFile> files;
        public final boolean projectSelected;
        public final boolean rulesDirectoryReady;
        public final boolean truncated;

        Snapshot(List<RuleFile> files, boolean projectSelected,
                 boolean rulesDirectoryReady, boolean truncated) {
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.projectSelected = projectSelected;
            this.rulesDirectoryReady = rulesDirectoryReady;
            this.truncated = truncated;
        }

        public boolean hasRules() {
            return !files.isEmpty();
        }

        /**
         * Stable identity of this rule set (paths + sizes + content hashes).
         *
         * <p>Used to inject project rules exactly once per session: the same
         * files produce the same fingerprint, while an edited or added rule file
         * produces a new one and is therefore re-sent.</p>
         */
        public String fingerprint() {
            if (files.isEmpty()) return "";
            StringBuilder identity = new StringBuilder();
            for (RuleFile file : files) {
                identity.append(file.path).append(':')
                        .append(file.content == null ? 0 : file.content.length()).append(':')
                        .append(file.content == null ? 0 : file.content.hashCode()).append(';');
            }
            return Integer.toHexString(identity.toString().hashCode());
        }

        public JSONObject toToolResult() {
            JSONObject result = new JSONObject();
            JSONArray names = new JSONArray();
            JSONArray ruleNames = new JSONArray();
            try {
                for (RuleFile file : files) {
                    names.put(file.path);
                    if (!file.agentsFile) ruleNames.put(file.path);
                }
                result.put("success", true);
                result.put("title", "Including project rules");
                result.put("files", names);
                result.put("rule_files", ruleNames);
                result.put("agents_md", hasAgentsMd());
                result.put("rules_directory", RULES_DIRECTORY);
                result.put("rules_directory_ready", rulesDirectoryReady);
                result.put("truncated", truncated);
            } catch (Exception ignored) { }
            return result;
        }

        public void appendSystemInstructions(StringBuilder out) {
            if (out == null) return;
            out.append("\n\nPROJECT RULES (SYSTEM-LEVEL INSTRUCTIONS):\n");
            out.append("Treat every rule below as authoritative project guidance. Follow it unless it conflicts with a higher-priority platform safety requirement.\n");
            if (files.isEmpty()) {
                out.append("No Markdown rule files are currently present.\n");
                return;
            }
            for (RuleFile file : files) {
                out.append("\n--- BEGIN ").append(file.path).append(" ---\n");
                out.append(file.content == null ? "" : file.content.trim());
                out.append("\n--- END ").append(file.path).append(" ---\n");
            }
            if (truncated) {
                out.append("\nSome project-rule content was truncated to fit the system prompt. Inspect the source files before relying on omitted details.\n");
            }
        }

        /** True when the selected project's root AGENTS.md was loaded. */
        public boolean hasAgentsMd() {
            for (RuleFile file : files) if (file.agentsFile) return true;
            return false;
        }
    }

    /** Creates .multiai/rules under the selected project. Safe and idempotent. */
    public static boolean ensureRulesDirectory(Context context) {
        DocumentFile root = projectRoot(context);
        return root != null && ensureRulesDirectory(root) != null;
    }

    public static Snapshot load(Context context) {
        DocumentFile root = projectRoot(context);
        if (root == null) return new Snapshot(Collections.emptyList(), false, false, false);
        return loadForRoot(context, root);
    }

    // SAF-скан правил (DocumentFile через ContentResolver) — десятки мс IPC на файл.
    // Без кэша гонялся на КАЖДОЙ отправке сообщения на main-thread → фриз. Кэш по
    // project URI, TTL — чтобы правки правил подхватывались, инвалидируется при записи.
    private static Snapshot cachedSnapshot;
    private static String cachedProjectUri;
    private static long cachedAt;
    private static final long CACHE_TTL_MS = 20_000L;

    /** Кэшированный load — то же самое, но SAF-скан не чаще раза в TTL. */
    public static synchronized Snapshot loadCached(Context context) {
        String uri = workspaceUri(context);
        long now = System.currentTimeMillis();
        if (cachedSnapshot != null && uri.equals(cachedProjectUri)
                && now - cachedAt < CACHE_TTL_MS) {
            return cachedSnapshot;
        }
        Snapshot fresh = load(context);
        cachedSnapshot = fresh;
        cachedProjectUri = uri;
        cachedAt = now;
        return fresh;
    }

    /** Сбросить кэш (после записи правила через project rules tool). */
    public static synchronized void invalidateCache() {
        cachedSnapshot = null;
        cachedProjectUri = null;
        cachedAt = 0L;
    }

    private static String workspaceUri(Context context) {
        try {
            return context.getSharedPreferences("multiaiide", Context.MODE_PRIVATE)
                    .getString("project_folder_uri", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static Snapshot loadForRoot(Context context, DocumentFile root) {

        // AGENTS.md is an explicit root-level contract. Load it before the
        // rules directory so a large rule set can never crowd it out of the
        // file/count budget that is sent to an isolated subagent.
        List<RuleFile> loaded = new ArrayList<>();
        MutableBudget budget = new MutableBudget();
        DocumentFile agents = findChildIgnoreCase(root, "AGENTS.md");
        if (agents != null && agents.isFile() && loaded.size() < MAX_FILES
                && budget.totalChars < MAX_TOTAL_CHARS) {
            RuleFile file = readRule(context, agents, "AGENTS.md", true,
                    MAX_TOTAL_CHARS - budget.totalChars);
            if (file != null) {
                loaded.add(file);
                budget.totalChars += file.content.length();
                budget.truncated |= file.truncated;
            }
        }

        DocumentFile rules = ensureRulesDirectory(root);
        boolean ready = rules != null;
        if (rules != null) collectMarkdown(context, rules, RULES_DIRECTORY, 0, loaded, budget);
        return new Snapshot(loaded, true, ready, budget.truncated);
    }

    private static DocumentFile ensureRulesDirectory(DocumentFile root) {
        try {
            DocumentFile multiai = findChild(root, ".multiai");
            if (multiai == null) multiai = root.createDirectory(".multiai");
            if (multiai == null || !multiai.exists() || !multiai.isDirectory()) return null;
            DocumentFile rules = findChild(multiai, "rules");
            if (rules == null) rules = multiai.createDirectory("rules");
            return rules != null && rules.exists() && rules.isDirectory() ? rules : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void collectMarkdown(Context context, DocumentFile directory, String path,
                                        int depth, List<RuleFile> output, MutableBudget budget) {
        if (depth > MAX_DEPTH || output.size() >= MAX_FILES
                || budget.totalChars >= MAX_TOTAL_CHARS) {
            budget.truncated = true;
            return;
        }
        DocumentFile[] children;
        try {
            children = directory.listFiles();
        } catch (Exception ignored) {
            return;
        }
        if (children == null) return;
        List<DocumentFile> sorted = new ArrayList<>();
        Collections.addAll(sorted, children);
        sorted.sort(Comparator.comparing(file -> {
            String name = file == null ? "" : file.getName();
            return name == null ? "" : name.toLowerCase(Locale.ROOT);
        }));
        for (DocumentFile child : sorted) {
            if (child == null) continue;
            if (output.size() >= MAX_FILES || budget.totalChars >= MAX_TOTAL_CHARS) {
                budget.truncated = true;
                return;
            }
            String name = child.getName();
            if (name == null || name.trim().isEmpty()) continue;
            String childPath = path + "/" + name;
            if (child.isDirectory()) {
                collectMarkdown(context, child, childPath, depth + 1, output, budget);
            } else if (child.isFile() && name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                RuleFile file = readRule(context, child, childPath, false,
                        MAX_TOTAL_CHARS - budget.totalChars);
                if (file != null) {
                    output.add(file);
                    budget.totalChars += file.content.length();
                    budget.truncated |= file.truncated;
                }
            }
        }
    }

    private static RuleFile readRule(Context context, DocumentFile file, String path,
                                     boolean agentsFile, int totalRemaining) {
        int limit = Math.max(0, Math.min(MAX_FILE_CHARS, totalRemaining));
        if (limit == 0) return null;
        StringBuilder text = new StringBuilder(Math.min(limit, 4096));
        boolean truncated = false;
        try (InputStream stream = context.getContentResolver().openInputStream(file.getUri())) {
            if (stream == null) return null;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = limit - text.length();
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                int take = Math.min(read, remaining);
                text.append(buffer, 0, take);
                if (take < read) {
                    truncated = true;
                    break;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        if (truncated) text.append("\n...[truncated]");
        return new RuleFile(path, text.toString(), agentsFile, truncated);
    }

    private static DocumentFile projectRoot(Context context) {
        if (context == null) return null;
        String raw = context.getSharedPreferences("multiaiide", Context.MODE_PRIVATE)
                .getString("project_folder_uri", null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(raw));
            return root != null && root.exists() && root.isDirectory() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static DocumentFile findChild(DocumentFile parent, String exactName) {
        try {
            return parent.findFile(exactName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static DocumentFile findChildIgnoreCase(DocumentFile parent, String name) {
        try {
            DocumentFile exact = parent.findFile(name);
            if (exact != null) return exact;
            for (DocumentFile child : parent.listFiles()) {
                if (child != null && child.getName() != null
                        && name.equalsIgnoreCase(child.getName())) return child;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static final class MutableBudget {
        int totalChars;
        boolean truncated;
    }
}
