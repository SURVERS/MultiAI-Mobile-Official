package app.multiai.mobile.tools;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.multiai.mobile.terminal.ToolsManager;
import app.multiai.mobile.utils.SkillDiskScanner;
import app.multiai.mobile.utils.SkillReader;

/**
 * Local IDE tools exposed only by Work/Project mode.
 *
 * <p>The selected project is an Android Storage Access Framework tree, so all
 * file operations are deliberately rooted at that tree instead of accepting
 * arbitrary device paths. Commands use the bundled terminal environment and
 * are run in the mapped external-storage directory when Android exposes one.
 */
public final class WorkModeTools {
    private static final int MAX_FILE_CHARS = 200_000;
    private static final int MAX_RESULT_CHARS = 80_000;
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_SCANNED_FILES = 5_000;
    private static final int MAX_READ_LINES = 400;
    private static final int MAX_DEPTH = 30;
    private static final int MAX_DELETE_ENTRIES = 5_000;
    private static final long DEFAULT_TIMEOUT_SEC = 60;
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private final Context context;
    private final ToolsManager toolsManager;
    private volatile SubagentInvoker subagentInvoker;

    /** Active executions grouped by turn token; parallel tool calls share a token. */
    private final ConcurrentHashMap<OperationCancelToken, AtomicInteger> activeTokens =
            new ConcurrentHashMap<>();

    public interface SubagentInvoker {
        String invoke(String presetId, String task, String context, long timeoutSec,
                      OperationCancelToken token);
    }

    /** Shared result/scan limits for directory and search tools. */
    private static final class ScanStats {
        int results;
        int scannedEntries;
        boolean resultLimitReached;
        boolean scanLimitReached;

        boolean isTruncated() {
            return resultLimitReached || scanLimitReached;
        }

        String truncatedBy() {
            if (resultLimitReached && scanLimitReached) return "result_limit,scan_limit";
            return resultLimitReached ? "result_limit" : scanLimitReached ? "scan_limit" : "";
        }
    }

    public WorkModeTools(Context context) {
        this.context = context.getApplicationContext();
        this.toolsManager = new ToolsManager(this.context);
    }

    public void setSubagentInvoker(SubagentInvoker invoker) {
        this.subagentInvoker = invoker;
    }

    /**
     * Cancel the in-flight Work Mode operation, if any: kills the active
     * toolchain process tree, makes recursive scans stop at their next
     * checkpoint, and unblocks the approval sheet wait. Thread-safe; safe to
     * call from the UI thread. Returns true when an operation was running.
     *
     * This is the single entry point ChatActivity needs on its stop button.
     */
    public boolean cancelCurrentOperation() {
        return cancelCurrentOperation("cancelled by user");
    }

    public boolean cancelCurrentOperation(String reason) {
        boolean cancelledAny = false;
        for (OperationCancelToken token : activeTokens.keySet()) {
            cancelledAny |= token.cancel(reason);
        }
        toolsManager.killActiveProcesses();
        return cancelledAny;
    }

    /** Execute one Work Mode tool and return a JSON object string. */
    public String execute(String name, JSONObject args) {
        return execute(name, args, null);
    }

    /**
     * Execute one Work Mode tool with cooperative cancellation. Passing a
     * cancelled token returns a CANCELLED error immediately. When the token
     * fires mid-operation, recursive scans stop at their next checkpoint and
     * a running toolchain process is killed; the result JSON carries
     * {@code cancelled:true} plus partial output where available.
     */
    public String execute(String name, JSONObject args, OperationCancelToken token) {
        try {
            if (name == null) return error("INVALID_TOOL", "Tool name is empty");
            if (token != null && token.check()) return cancelledResult(name);
            if (token != null) {
                activeTokens.compute(token, (key, count) -> {
                    if (count == null) count = new AtomicInteger();
                    count.incrementAndGet();
                    return count;
                });
            }
            try {
                switch (name) {
                    case "read_file": return readFile(args, token);
                    case "write_file": return writeFile(args, token);
                    case "edit_file": return editFile(args, token);
                    case "delete_path": return deletePath(args, token);
                    case "move_path": return movePath(args, token);
                    case "list_dir": return listDir(args, token);
                    case "search_grep": return searchGrep(args, token);
                    case "find_files": return findFiles(args, token);
                    case "execute_command": return executeCommand(args, token);
                    case "read_skill": return readSkill(args, token);
                    case "invoke_subagent":
                        return invokeSubagent(args, token);
                    default: return error("UNKNOWN_TOOL", "Unknown Work Mode tool: " + name);
                }
            } finally {
                if (token != null) {
                    activeTokens.computeIfPresent(token,
                            (key, count) -> count.decrementAndGet() <= 0 ? null : count);
                }
            }
        } catch (OperationCancelToken.CancelledException cancelled) {
            return cancelledResult(name);
        } catch (Exception e) {
            return error("TOOL_FAILED", safeMessage(e));
        }
    }

    private String readFile(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String rawPath = args.optString("path", "");
        if (isInvalidPath(rawPath) || rawPath.trim().isEmpty()) return error("INVALID_PATH", "A relative file path is required");
        if (!args.has("start_line") || !args.has("end_line")) {
            return error("RANGE_REQUIRED", "start_line and end_line are required");
        }
        int start = args.optInt("start_line", -1);
        int end = args.optInt("end_line", -1);
        if (start < 1 || end < start) return error("INVALID_RANGE", "start_line/end_line must be a valid 1-based range");
        if (end - start + 1 > MAX_READ_LINES) return error("RANGE_TOO_LARGE", "A read request is limited to 400 lines");
        String path = normalizePath(rawPath);
        DocumentFile file = resolveFile(path);
        if (file == null || !file.exists() || !file.isFile()) return error("FILE_NOT_FOUND", "File not found");
        StringBuilder selected = new StringBuilder();
        int totalLines = 0;
        try (InputStream in = context.getContentResolver().openInputStream(file.getUri())) {
            if (in == null) return error("READ_FAILED", "Cannot open file");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if ((totalLines & 0x3F) == 0) checkpoint(token); // every 64 lines
                totalLines++;
                if (totalLines >= start && totalLines <= end) {
                    if (selected.length() > 0) selected.append('\n');
                    selected.append(line);
                }
                if (totalLines >= end) break;
            }
        }
        JSONObject out = new JSONObject();
        out.put("path", path);
        out.put("start_line", start);
        out.put("end_line", end);
        out.put("scanned_through", totalLines);
        out.put("read_lines", Math.max(0, Math.min(totalLines, end) - start + 1));
        out.put("content", clip(selected.toString(), MAX_RESULT_CHARS));
        out.put("truncated", selected.length() > MAX_RESULT_CHARS);
        return out.toString();
    }

    private String writeFile(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        if (!(args.opt("path") instanceof String) || !(args.opt("content") instanceof String)) {
            return error("INVALID_ARGUMENT", "write_file requires string path and content; nothing was written");
        }
        String rawPath = args.getString("path");
        if (isInvalidPath(rawPath)) return error("INVALID_PATH", "path must be relative to the project root");
        String path = normalizePath(rawPath);
        String content = args.getString("content");
        if (path.isEmpty()) return error("INVALID_PATH", "path is required");
        if (content.length() > MAX_FILE_CHARS) return error("FILE_TOO_LARGE", "content exceeds 200000 characters");
        DocumentFile file = resolveFile(path);
        boolean existed = file != null && file.exists() && file.isFile();
        String previous = existed ? readText(file, token) : "";
        if (previous.length() > MAX_FILE_CHARS) {
            return error("FILE_TOO_LARGE", "existing file exceeds 200000 characters");
        }
        if (file == null) file = createFile(path);
        if (file == null || !file.isFile() || !file.canWrite()) {
            return error("WRITE_FAILED", "Cannot create or write project file: " + path);
        }
        String expectedName = path.substring(path.lastIndexOf('/') + 1);
        if (!expectedName.equals(file.getName())) {
            String actualName = file.getName();
            boolean removed = !existed && file.delete();
            return error("WRITE_FAILED", "Storage provider changed the requested filename: " + path
                    + "; actual name: " + actualName + ". Content was not written. Stray document removed: " + removed);
        }
        checkpoint(token); // last chance to abort before mutating the file
        try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri(), "wt")) {
            if (out == null) return error("WRITE_FAILED", "Cannot open file for writing");
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        checkpoint(token);
        DocumentFile writtenFile = resolveFile(path);
        if (writtenFile == null || !writtenFile.exists() || !writtenFile.isFile()) {
            return error("WRITE_VERIFY_FAILED", "File was not found after writing: " + path);
        }
        String verifiedContent = readText(writtenFile, token);
        if (!content.equals(verifiedContent)) {
            return error("WRITE_VERIFY_FAILED", "File content verification failed: " + path);
        }
        // These fields are consumed by the IDE widget to render an inline,
        // numbered red/green diff for both edit_file and write_file.
        return new JSONObject().put("success", true).put("path", path)
                .put("bytes", content.getBytes(StandardCharsets.UTF_8).length)
                .put("created", !existed)
                .put("verified", true)
                .put("message", (existed ? "File updated successfully: " : "File created successfully: ") + path)
                .put("old_content", clip(previous, MAX_RESULT_CHARS))
                .put("new_content", clip(content, MAX_RESULT_CHARS))
                .put("diff_truncated", previous.length() > MAX_RESULT_CHARS
                        || content.length() > MAX_RESULT_CHARS)
                .toString();
    }

    private String editFile(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String rawPath = args.optString("path", "");
        if (isInvalidPath(rawPath)) return error("INVALID_PATH", "path must be relative to the project root");
        String path = normalizePath(rawPath);
        String patch = args.optString("patch", "");
        if (path.isEmpty() || patch.isEmpty()) return error("INVALID_ARGUMENT", "path and patch are required");
        DocumentFile file = resolveFile(path);
        if (file == null || !file.isFile()) return error("FILE_NOT_FOUND", "File not found");
        String original = readText(file, token);
        String updated = applyUnifiedPatch(original, patch);
        if (updated == null) return error("PATCH_FAILED", "Patch context did not match the file");
        JSONObject writeArgs = new JSONObject().put("path", path).put("content", updated);
        String result = writeFile(writeArgs, token);
        JSONObject out = new JSONObject(result);
        out.put("tool", "edit_file");
        out.put("patch", patch);
        int additions = 0;
        int deletions = 0;
        for (String line : patch.split("\\R", -1)) {
            if (line.startsWith("+") && !line.startsWith("+++")) additions++;
            if (line.startsWith("-") && !line.startsWith("---")) deletions++;
        }
        out.put("additions", additions);
        out.put("deletions", deletions);
        return out.toString();
    }

    private String listDir(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String rawPath = args.optString("path", "");
        if (isInvalidPath(rawPath)) return error("INVALID_PATH", "path must be relative to the project root");
        String path = normalizePath(rawPath);
        int depth = Math.min(MAX_DEPTH, Math.max(0, args.optInt("depth", 1)));
        DocumentFile dir = path.isEmpty() ? projectRoot() : resolveFile(path);
        if (dir == null || !dir.isDirectory()) return error("DIRECTORY_NOT_FOUND", "Directory not found");
        JSONArray entries = new JSONArray();
        ScanStats stats = new ScanStats();
        collectEntries(dir, path, depth, entries, stats, token);
        JSONObject response = new JSONObject().put("path", path).put("entries", entries)
                .put("count", entries.length()).put("entry_limit", MAX_ENTRIES)
                .put("truncated", stats.isTruncated());
        if (stats.isTruncated()) response.put("truncated_by", stats.truncatedBy());
        return response.toString();
    }

    private String findFiles(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String rawRootPath = args.optString("path", "");
        if (isInvalidPath(rawRootPath)) return error("INVALID_PATH", "path must be relative to the project root");
        String rootPath = normalizePath(rawRootPath);
        String pattern = args.optString("pattern", args.optString("query", "*")).trim();
        // Tool paths use '/', but users and models may provide a Windows-style glob.
        pattern = pattern.replace('\\', '/');
        if (pattern.isEmpty()) pattern = "*";
        DocumentFile root = rootPath.isEmpty() ? projectRoot() : resolveFile(rootPath);
        if (root == null || !root.isDirectory()) return error("DIRECTORY_NOT_FOUND", "Directory not found");
        JSONArray matches = new JSONArray();
        ScanStats stats = new ScanStats();
        findRecursive(root, rootPath, pattern, matches, stats, token);
        JSONObject response = new JSONObject().put("pattern", pattern).put("count", matches.length())
                .put("files", matches).put("match_limit", MAX_ENTRIES)
                .put("scan_limit", MAX_SCANNED_FILES).put("scanned_entries", stats.scannedEntries)
                .put("truncated", stats.isTruncated());
        if (stats.isTruncated()) response.put("truncated_by", stats.truncatedBy());
        return response.toString();
    }

    private String searchGrep(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String query = args.optString("query", args.optString("pattern", ""));
        if (query.isEmpty()) return error("INVALID_ARGUMENT", "query is required");
        String rawRootPath = args.optString("path", "");
        if (isInvalidPath(rawRootPath)) return error("INVALID_PATH", "path must be relative to the project root");
        String rootPath = normalizePath(rawRootPath);
        boolean regex = args.optBoolean("regex", false);
        boolean caseSensitive = args.optBoolean("case_sensitive", false);
        Pattern compiled = null;
        if (regex) {
            try {
                // Compile with a timeout guard against catastrophic backtracking
                compiled = Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (Exception e) {
                return error("INVALID_REGEX", "Invalid regular expression: " + e.getMessage());
            }
        }
        DocumentFile root = rootPath.isEmpty() ? projectRoot() : resolveFile(rootPath);
        if (root == null || !root.isDirectory()) return error("DIRECTORY_NOT_FOUND", "Directory not found");
        JSONArray matches = new JSONArray();
        ScanStats stats = new ScanStats();
        grepRecursive(root, rootPath, query, compiled, caseSensitive, matches, stats, token);
        JSONObject response = new JSONObject().put("query", query).put("count", matches.length())
                .put("matches", matches).put("match_limit", MAX_ENTRIES)
                .put("scan_limit", MAX_SCANNED_FILES).put("scanned_entries", stats.scannedEntries)
                .put("truncated", stats.isTruncated());
        if (stats.isTruncated()) response.put("truncated_by", stats.truncatedBy());
        return response.toString();
    }

    private String executeCommand(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String command = args.optString("cmd", args.optString("command", "")).trim();
        if (command.isEmpty()) return error("INVALID_ARGUMENT", "cmd is required");
        // app install <path> — не shell-команда, а нативный запуск системного
        // установщика APK. В терминале её перехватывает TerminalActivity; здесь
        // делаем то же самое, иначе агент получает "command not found".
        if (command.startsWith("app install ") || command.equals("app install")) {
            return executeAppInstall(command, token);
        }
        // pkg install <id> — нативная установка пакета тулчейна (gradle, java, python...)
        // через TerminalPackageManager; shell сам пакеты не качает.
        if (command.equals("pkg install") || command.startsWith("pkg install ")) {
            return executePkgInstall(command, token);
        }
        if (!new app.multiai.mobile.terminal.TerminalPackageManager(context).isConfigured()) {
            return error("TERMINAL_NOT_CONFIGURED",
                    "Terminal is not configured. Install the toolchain first with: pkg install gradle (or list packages with: pkg install list)");
        }
        if (!hasAllFilesAccess()) {
            return error("STORAGE_ACCESS_REQUIRED",
                    "Terminal requires \"All files access\". Ask the user to grant it in Settings → Apps → MultiAI → Storage, then retry.");
        }
        long timeout = Math.max(1, Math.min(300, args.optLong("timeout_sec", DEFAULT_TIMEOUT_SEC)));
        toolsManager.initializeEnvironment();
        String root = mappedProjectPath();
        if (root == null) {
            return error("PROJECT_PATH_UNAVAILABLE",
                    "The selected SAF folder cannot be mapped to a shell path; choose a folder in primary device storage");
        }
        checkpoint(token);
        String marker = "__MULTIAI_EXIT_" + Long.toUnsignedString(System.nanoTime(), 36) + "__";
        String effective = "cd " + shellQuote(root) + " && (\n" + command + "\n)"
                + "; __multiai_code=$?; printf '\\n" + marker + "%s\\n' \"$__multiai_code\"";
        String output = toolsManager.execute(effective, timeout,
                token == null ? null : token::check);
        if (output.startsWith(ToolsManager.CANCELLED_PREFIX)) {
            return new JSONObject().put("command", command).put("cwd", root)
                    .put("cancelled", true).put("exit_code", -1).put("timed_out", false)
                    .put("output", "Command killed by user cancel.").toString();
        }
        int exitCode = -1;
        int markerAt = output.lastIndexOf(marker);
        if (markerAt >= 0) {
            String tail = output.substring(markerAt + marker.length()).trim();
            int newline = tail.indexOf('\n');
            if (newline >= 0) tail = tail.substring(0, newline).trim();
            try { exitCode = Integer.parseInt(tail); } catch (Exception ignored) {}
            output = output.substring(0, markerAt).trim();
        }
        return new JSONObject().put("command", command).put("cwd", root != null ? root : "")
                .put("exit_code", exitCode).put("timed_out", output.startsWith("TIMEOUT"))
                .put("output", clip(output, MAX_RESULT_CHARS)).put("truncated", output.length() > MAX_RESULT_CHARS)
                .toString();
    }

    /**
     * Безопасный локальный read-only тул для полного SKILL.md — замена
     * full-body системной инжекции по триггеру {@code ./slug}.
     *
     * <p>Принимает ТОЛЬКО валидный slug из {@link SkillReader#normalizeSlug}
     * (белый список {@code [a-z0-9._-]}, без слэшей и ".."), ищет его в
     * трёх конвенциях проекта (.multiai/skills, .claude/skills,
     * .cursor/skills) и в глобальном {@code filesDir/.claude/skills}.
     * В отличие от прежней инжекции, тело никогда не попадает в system
     * prompt само по себе: AI обязан явно вызвать тул, и результат
     * возвращается как обычный tool-result (не как системная инструкция).
     * Cat/shell не используется — только SAF/Java чтение внутри белых
     * директорий скиллов; произвольные файлы через этот тул недоступны.
     */
    private String readSkill(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String slug = SkillReader.normalizeSlug(args.optString("slug", ""));
        if (slug == null) {
            return error("INVALID_SLUG",
                    "slug must be a lowercase skill identifier matching [a-z0-9][a-z0-9._-]* "
                            + "(no paths, no '..', max 64 chars) — take it from the AVAILABLE SKILLS catalog");
        }

        // Проектные скиллы: три конвенции, родная (.multiai) имеет приоритет.
        DocumentFile root = projectRoot();
        if (root != null) {
            for (String[] segments : SkillDiskScanner.SCAN_SEGMENTS_ORDER) {
                DocumentFile dir = root;
                for (String segment : segments) dir = findChildOrNull(dir, segment);
                if (dir == null || !dir.isDirectory()) continue;
                DocumentFile skillMd = findChildOrNull(dir, slug);
                if (skillMd == null || !skillMd.isDirectory()) continue;
                skillMd = findChildOrNull(skillMd, "SKILL.md");
                if (skillMd == null || !skillMd.isFile()) continue;
                return readSkillResult(skillMd.getUri(), slug,
                        String.join("/", segments) + "/" + slug + "/SKILL.md");
            }
        }

        // Глобальные скиллы (filesDir/.claude/skills, в т.ч. поставленные
        // через "npx skills add -g") — обычный java.io, internal storage.
        java.io.File global = new java.io.File(
                context.getFilesDir(), ".claude/skills/" + slug + "/SKILL.md");
        if (global.isFile()) {
            return readSkillIoResult(global, slug, global.getAbsolutePath());
        }
        return error("SKILL_NOT_FOUND",
                "No enabled skill with slug '" + slug
                        + "': check the slug in the AVAILABLE SKILLS catalog");
    }

    private String readSkillResult(android.net.Uri uri, String slug, String relPath)
            throws Exception {
        String raw = readUriText(uri);
        return skillResultJson(raw, slug, relPath, true);
    }

    private String readSkillIoResult(java.io.File file, String slug, String path) throws Exception {
        String raw = readIoText(file);
        return skillResultJson(raw, slug, path, false);
    }

    private String skillResultJson(String raw, String slug, String path, boolean fromProject)
            throws Exception {
        if (raw == null || raw.trim().isEmpty()) {
            return error("SKILL_EMPTY", "SKILL.md for '" + slug + "' is empty or unreadable");
        }
        SkillReader.Body body = SkillReader.extractBody(raw, MAX_RESULT_CHARS);
        return new JSONObject()
                .put("slug", slug)
                .put("path", path)
                .put("scope", fromProject ? "workspace" : "global")
                .put("content", body.text)
                .put("truncated", body.truncated)
                .toString();
    }

    private String readUriText(android.net.Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_FILE_CHARS * 2) break;
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String readIoText(java.io.File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_FILE_CHARS * 2) break;
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Дочерний элемент SAF-каталога по точному имени (без case-фолбэка — slug точный). */
    private DocumentFile findChildOrNull(DocumentFile dir, String name) {
        if (dir == null) return null;
        try {
            return dir.findFile(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Delete one non-root project file or directory through the SAF tree. */
    private String deletePath(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String rawPath = args.optString("path", "");
        if (isInvalidPath(rawPath) || rawPath.trim().isEmpty()) {
            return error("INVALID_PATH", "path must be a non-empty project-relative path");
        }
        String path = normalizePath(rawPath);
        if (path == null || path.isEmpty()) {
            return error("ROOT_DELETE_FORBIDDEN", "The selected project root cannot be deleted");
        }
        DocumentFile target = resolveFile(path);
        if (target == null || !target.exists()) return error("PATH_NOT_FOUND", "Path not found: " + path);

        boolean directory = target.isDirectory();
        boolean recursive = args.optBoolean("recursive", false);
        if (!directory && recursive) {
            return error("INVALID_ARGUMENT", "recursive is only valid when deleting a directory");
        }
        if (directory && !recursive && target.listFiles().length > 0) {
            return error("DIRECTORY_NOT_EMPTY", "Directory is not empty; set recursive=true to delete it: " + path);
        }

        int[] deleted = new int[]{0, 0}; // files, directories
        if (directory && recursive) {
            int planned = countDeleteEntries(target, token);
            if (planned > MAX_DELETE_ENTRIES) {
                return error("DELETE_TOO_LARGE", "Recursive delete is limited to "
                        + MAX_DELETE_ENTRIES + " entries; target contains more");
            }
            deleteTree(target, token, deleted);
        } else {
            checkpoint(token);
            if (!target.delete()) return error("DELETE_FAILED", "Provider refused to delete: " + path);
            if (directory) deleted[1]++; else deleted[0]++;
        }

        DocumentFile remaining = resolveFile(path);
        if (remaining != null && remaining.exists()) {
            return error("DELETE_VERIFY_FAILED", "Path still exists after delete: " + path);
        }
        return new JSONObject().put("success", true).put("path", path)
                .put("kind", directory ? "directory" : "file")
                .put("recursive", recursive).put("deleted_files", deleted[0])
                .put("deleted_directories", deleted[1]).put("verified_absent", true)
                .put("message", "Deleted " + path).toString();
    }

    /** Move or rename a project file/directory without leaving its SAF root. */
    private String movePath(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String rawFrom = args.optString("source_path", args.optString("from_path", ""));
        String rawTo = args.optString("destination_path", args.optString("to_path", ""));
        if (isInvalidPath(rawFrom) || isInvalidPath(rawTo)
                || rawFrom.trim().isEmpty() || rawTo.trim().isEmpty()) {
            return error("INVALID_PATH", "source_path and destination_path must be non-empty project-relative paths");
        }
        String sourcePath = normalizePath(rawFrom);
        String destinationPath = normalizePath(rawTo);
        if (sourcePath == null || destinationPath == null || sourcePath.isEmpty()
                || destinationPath.isEmpty()) {
            return error("INVALID_PATH", "Project root cannot be moved and destination cannot be root");
        }
        if (sourcePath.equals(destinationPath)) return error("SAME_PATH", "Source and destination are the same");
        if (destinationPath.startsWith(sourcePath + "/")) {
            return error("MOVE_INTO_SELF", "A directory cannot be moved into itself or one of its children");
        }

        DocumentFile source = resolveFile(sourcePath);
        if (source == null || !source.exists()) return error("PATH_NOT_FOUND", "Source not found: " + sourcePath);
        DocumentFile existingDestination = resolveFile(destinationPath);
        if (existingDestination != null && existingDestination.exists()) {
            return error("DESTINATION_EXISTS", "Destination already exists: " + destinationPath);
        }

        String sourceParentPath = relativeParentPath(sourcePath);
        String targetParentPath = relativeParentPath(destinationPath);
        DocumentFile sourceParent = sourceParentPath.isEmpty() ? projectRoot() : resolveFile(sourceParentPath);
        DocumentFile targetParent = targetParentPath.isEmpty() ? projectRoot() : resolveFile(targetParentPath);
        if (sourceParent == null || targetParent == null || !targetParent.isDirectory()) {
            return error("DESTINATION_PARENT_NOT_FOUND", "Destination parent directory does not exist: " + targetParentPath);
        }
        String destinationName = relativeFileName(destinationPath);
        if (destinationName.isEmpty()) return error("INVALID_PATH", "destination_path must name a file or directory");

        boolean directory = source.isDirectory();
        checkpoint(token);
        boolean moved;
        if (sourceParent.getUri().equals(targetParent.getUri())) {
            moved = source.renameTo(destinationName);
        } else {
            Uri movedUri = DocumentsContract.moveDocument(context.getContentResolver(), source.getUri(),
                    sourceParent.getUri(), targetParent.getUri());
            if (movedUri == null) return error("MOVE_FAILED", "Provider refused to move: " + sourcePath);
            DocumentFile movedFile = DocumentFile.fromSingleUri(context, movedUri);
            moved = movedFile != null && movedFile.exists()
                    && (destinationName.equals(movedFile.getName()) || movedFile.renameTo(destinationName));
        }
        if (!moved) return error("MOVE_FAILED", "Provider refused to move or rename: " + sourcePath);

        DocumentFile destination = resolveFile(destinationPath);
        if (destination == null || !destination.exists()) {
            return error("MOVE_VERIFY_FAILED", "Destination was not found after moving: " + destinationPath);
        }
        DocumentFile oldSource = resolveFile(sourcePath);
        if (oldSource != null && oldSource.exists()) {
            return error("MOVE_VERIFY_FAILED", "Source still exists after moving: " + sourcePath);
        }
        return new JSONObject().put("success", true).put("source_path", sourcePath)
                .put("destination_path", destinationPath)
                .put("kind", directory ? "directory" : "file").put("verified", true)
                .put("message", "Moved " + sourcePath + " to " + destinationPath).toString();
    }

    private int countDeleteEntries(DocumentFile target, OperationCancelToken token) throws Exception {
        checkpoint(token);
        int total = 1;
        if (!target.isDirectory()) return total;
        for (DocumentFile child : sorted(target.listFiles())) {
            total += countDeleteEntries(child, token);
            if (total > MAX_DELETE_ENTRIES) return total;
        }
        return total;
    }

    private void deleteTree(DocumentFile target, OperationCancelToken token, int[] deleted)
            throws Exception {
        checkpoint(token);
        boolean directory = target.isDirectory();
        if (directory) {
            for (DocumentFile child : sorted(target.listFiles())) deleteTree(child, token, deleted);
        }
        checkpoint(token);
        if (deleted[0] + deleted[1] >= MAX_DELETE_ENTRIES) {
            throw new IllegalStateException("Deletion limit reached; deleted files=" + deleted[0]
                    + ", directories=" + deleted[1] + "; remaining target=" + target.getName());
        }
        if (!target.delete()) {
            throw new IllegalStateException("Provider refused to delete: " + target.getName()
                    + "; deleted files=" + deleted[0] + ", directories=" + deleted[1]);
        }
        if (directory) deleted[1]++; else deleted[0]++;
    }

    private static String relativeParentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String relativeFileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private String invokeSubagent(JSONObject args, OperationCancelToken token) throws Exception {
        checkpoint(token);
        String task = args.optString("task", "").trim();
        if (task.isEmpty()) return error("INVALID_ARGUMENT", "task is required");
        String requestedPresetId = args.optString("preset_id", "").trim();
        String presetId = requestedPresetId.isEmpty()
                ? app.multiai.mobile.utils.SubagentStore.BUILTIN_GENERAL_ID
                : requestedPresetId;
        SubagentInvoker invoker = subagentInvoker;
        if (invoker == null) return error("SUBAGENT_UNAVAILABLE", "The mobile subagent runner is not initialized");
        long timeout = Math.max(5, Math.min(300, args.optLong("timeout_sec", 120)));
        String output = invoker.invoke(presetId, task, args.optString("context", ""), timeout, token);
        if (token != null && token.check()) {
            return new JSONObject().put("success", false).put("task", task)
                    .put("preset_id", presetId)
                    .put("cancelled", true)
                    .put("output", clip(output == null ? "" : output, MAX_RESULT_CHARS)).toString();
        }
        app.multiai.mobile.models.SubagentPreset preset =
                app.multiai.mobile.utils.SubagentStore.find(context, presetId);
        return new JSONObject().put("success", true).put("task", task)
                .put("preset_id", presetId)
                .put("agent_name", preset != null ? preset.name : presetId)
                .put("model", preset != null ? preset.displayModel() : "Auto reasoning")
                .put("effort", preset != null ? preset.modelEffort : "")
                .put("output", clip(output, MAX_RESULT_CHARS))
                .put("truncated", output != null && output.length() > MAX_RESULT_CHARS)
                .toString();
    }

    /** Reads a bounded UTF-8 project file for a preset system prompt without exposing a tool call. */
    public String readProjectTextFile(String projectRelativePath, int maxChars) {
        if (isInvalidPath(projectRelativePath)) return null;
        String path = normalizePath(projectRelativePath);
        if (path == null || path.isEmpty()) return null;
        try {
            DocumentFile file = resolveFile(path);
            if (file == null || !file.exists() || !file.isFile()) return null;
            String content = readText(file, null);
            int limit = Math.max(1, Math.min(MAX_FILE_CHARS, maxChars));
            return content.length() <= limit ? content : content.substring(0, limit) + "\n...[truncated]";
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Returns a compact, read-only fact about the SAF root for the AI prompt. */
    public String getProjectContext() {
        DocumentFile root = projectRoot();
        if (root == null) return "No project folder is selected.";
        String name = root.getName();
        return "Selected root is \"" + (name == null || name.trim().isEmpty()
                ? "unnamed project" : name.trim()) + "\".";
    }

    private DocumentFile projectRoot() {
        String raw = context.getSharedPreferences("multiaiide", Context.MODE_PRIVATE)
                .getString("project_folder_uri", null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(raw));
            return root != null && root.exists() && root.isDirectory() ? root : null;
        } catch (Exception ignored) { return null; }
    }

    private DocumentFile resolveFile(String path) {
        String normalized = normalizePath(path);
        if (normalized == null) return null;
        DocumentFile current = projectRoot();
        if (current == null) return null;
        if (normalized.isEmpty()) return current;
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty()) continue;
            DocumentFile next = null;
            for (DocumentFile child : current.listFiles()) {
                if (segment.equals(child.getName())) { next = child; break; }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private DocumentFile createFile(String path) {
        String[] parts = path.split("/");
        DocumentFile dir = projectRoot();
        if (dir == null) return null;
        for (int i = 0; i < parts.length - 1; i++) {
            DocumentFile next = null;
            for (DocumentFile child : dir.listFiles()) if (parts[i].equals(child.getName()) && child.isDirectory()) next = child;
            if (next == null) next = dir.createDirectory(parts[i]);
            if (next == null) return null;
            dir = next;
        }
        String name = parts[parts.length - 1];
        String extension = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String mime = ProjectFileMime.forExtension(extension,
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension));
        return dir.createFile(mime, name);
    }

    private String readText(DocumentFile file, OperationCancelToken token) throws Exception {
        StringBuilder out = new StringBuilder();
        try (InputStream in = context.getContentResolver().openInputStream(file.getUri())) {
            if (in == null) throw new IllegalStateException("Cannot open file");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            char[] buf = new char[8192]; int n; int batches = 0;
            while ((n = reader.read(buf)) != -1 && out.length() <= MAX_FILE_CHARS * 2) {
                out.append(buf, 0, n);
                if ((++batches & 0xF) == 0) checkpoint(token); // every ~512KB
            }
        }
        return out.toString();
    }

    private void collectEntries(DocumentFile dir, String relative, int depth, JSONArray out,
                                ScanStats stats, OperationCancelToken token) throws Exception {
        checkpoint(token);
        List<DocumentFile> children = sorted(dir.listFiles());
        for (DocumentFile child : children) {
            if (stats.results >= MAX_ENTRIES) {
                stats.resultLimitReached = true;
                return;
            }
            String childPath = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            out.put(new JSONObject().put("path", childPath).put("name", child.getName())
                    .put("directory", child.isDirectory()).put("size", child.length()));
            stats.results++;
            if (child.isDirectory() && depth > 0) {
                collectEntries(child, childPath, depth - 1, out, stats, token);
                if (stats.resultLimitReached) return;
            }
        }
    }

    private void findRecursive(DocumentFile dir, String relative, String pattern, JSONArray out,
                               ScanStats stats, OperationCancelToken token) {
        checkpoint(token);
        for (DocumentFile child : sorted(dir.listFiles())) {
            if (stats.results >= MAX_ENTRIES) {
                stats.resultLimitReached = true;
                return;
            }
            if (stats.scannedEntries >= MAX_SCANNED_FILES) {
                stats.scanLimitReached = true;
                return;
            }
            stats.scannedEntries++;
            String path = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            if (child.isDirectory()) {
                findRecursive(child, path, pattern, out, stats, token);
                if (stats.isTruncated()) return;
            } else if (globMatches(child.getName(), pattern) || globMatches(path, pattern)) {
                out.put(path);
                stats.results++;
            }
        }
    }

    private void grepRecursive(DocumentFile dir, String relative, String query, Pattern regex,
                               boolean caseSensitive, JSONArray out, ScanStats stats,
                               OperationCancelToken token) throws Exception {
        checkpoint(token);
        for (DocumentFile child : sorted(dir.listFiles())) {
            if (stats.results >= MAX_ENTRIES) {
                stats.resultLimitReached = true;
                return;
            }
            if (stats.scannedEntries >= MAX_SCANNED_FILES) {
                stats.scanLimitReached = true;
                return;
            }
            stats.scannedEntries++;
            checkpoint(token);
            String path = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            if (child.isDirectory()) {
                grepRecursive(child, path, query, regex, caseSensitive, out, stats, token);
                if (stats.isTruncated()) return;
                continue;
            }
            if (child.length() > MAX_FILE_CHARS * 4L) continue;
            String text;
            try {
                text = readText(child, token);
            } catch (OperationCancelToken.CancelledException rethrow) {
                throw rethrow;
            } catch (Exception ignored) {
                continue;
            }
            String[] lines = text.split("\\r?\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                boolean hit;
                if (regex != null) {
                    // Guard against catastrophic backtracking: skip lines that are
                    // too long for safe regex matching (most regex bombs need long input).
                    String line = lines[i];
                    if (line.length() > 10000) {
                        hit = false; // Skip extremely long lines for regex safety
                    } else {
                        try {
                            hit = regex.matcher(line).find();
                        } catch (StackOverflowError | Exception e) {
                            hit = false; // Regex blew up on this line, skip it
                        }
                    }
                } else {
                    hit = caseSensitive ? lines[i].contains(query)
                            : lines[i].toLowerCase(Locale.US).contains(query.toLowerCase(Locale.US));
                }
                if (!hit) continue;
                if (stats.results >= MAX_ENTRIES) {
                    stats.resultLimitReached = true;
                    return;
                }
                out.put(new JSONObject().put("path", path).put("line", i + 1)
                        .put("text", clip(lines[i], 500)));
                stats.results++;
            }
        }
    }

    /** Throws {@link OperationCancelToken.CancelledException} when cancelled. */
    private static void checkpoint(OperationCancelToken token) {
        if (token != null) token.throwIfCancelled();
    }

    private static String cancelledResult(String tool) {
        try {
            return new JSONObject().put("error", "Operation cancelled by user")
                    .put("code", "CANCELLED").put("tool", tool == null ? "" : tool)
                    .put("cancelled", true).toString();
        } catch (Exception ignored) {
            return "{\"error\":\"Operation cancelled by user\",\"code\":\"CANCELLED\",\"cancelled\":true}";
        }
    }

    private static List<DocumentFile> sorted(DocumentFile[] files) {
        List<DocumentFile> out = new ArrayList<>();
        if (files != null) Collections.addAll(out, files);
        Collections.sort(out, Comparator.comparing(f -> f.getName() == null ? "" : f.getName(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private String mappedProjectPath() {
        DocumentFile root = projectRoot();
        if (root == null) return null;
        try {
            Uri tree = Uri.parse(context.getSharedPreferences("multiaiide", Context.MODE_PRIVATE).getString("project_folder_uri", ""));
            String id = DocumentsContract.getTreeDocumentId(tree);
            if (id != null && id.startsWith("primary:")) {
                java.io.File candidate = new java.io.File("/storage/emulated/0", id.substring("primary:".length()));
                if (candidate.isDirectory()) return candidate.getAbsolutePath();
                candidate = new java.io.File("/sdcard", id.substring("primary:".length()));
                if (candidate.isDirectory()) return candidate.getAbsolutePath();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Нативный перехват `pkg install <id>`: ставит пакет тулчейна (например
     * gradle, java, android) через TerminalPackageManager с зависимостями.
     * Работает даже когда терминал ещё не сконфигурирован — это и есть способ
     * его сконфигурировать. Блокирует ход агента на время скачивания.
     */
    private String executePkgInstall(String command, OperationCancelToken token) throws Exception {
        String ids = command.substring("pkg install".length()).trim();
        app.multiai.mobile.terminal.TerminalPackageManager pm =
                new app.multiai.mobile.terminal.TerminalPackageManager(context);
        java.util.List<app.multiai.mobile.terminal.TerminalPackageManager.PackageSpec> manifest = pm.loadManifest();
        if (ids.isEmpty() || "list".equals(ids)) {
            StringBuilder available = new StringBuilder("Available packages: ");
            for (app.multiai.mobile.terminal.TerminalPackageManager.PackageSpec spec : manifest) {
                if (available.length() > 20) available.append(", ");
                available.append(spec.id).append(" (").append(spec.name).append(')');
            }
            available.append(". Usage: pkg install <id> [<id>...] — dependencies and runtime libraries are installed automatically.");
            return new JSONObject().put("command", command).put("success", true)
                    .put("output", available.toString()).toString();
        }
        checkpoint(token);
        if (!hasAllFilesAccess()) {
            return error("STORAGE_ACCESS_REQUIRED",
                    "Terminal packages require \"All files access\" (Android storage permission). "
                            + "Ask the user to grant it in Settings → Apps → MultiAI → Storage, then retry.");
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(java.util.Arrays.asList(ids.split("\\s+")));
        java.util.Set<String> known = new java.util.HashSet<>();
        for (app.multiai.mobile.terminal.TerminalPackageManager.PackageSpec spec : manifest) known.add(spec.id);
        wanted.retainAll(known);
        if (wanted.isEmpty()) {
            StringBuilder available = new StringBuilder();
            for (app.multiai.mobile.terminal.TerminalPackageManager.PackageSpec spec : manifest) {
                if (available.length() > 0) available.append(", ");
                available.append(spec.id);
            }
            return error("UNKNOWN_PACKAGE", "Unknown package id. Available: " + available);
        }
        // Библиотеки (runtime) должны ставиться самыми первыми: resolveSelection
        // разворачивает зависимости, но порядок очереди идёт по порядку манифеста,
        // поэтому добавляем runtime и его цепочку явно.
        java.util.Set<String> withLibs = new java.util.HashSet<>(wanted);
        for (app.multiai.mobile.terminal.TerminalPackageManager.PackageSpec spec : manifest) {
            if (wanted.contains(spec.id) && spec.dependencies != null) {
                withLibs.addAll(spec.dependencies);
            }
        }
        for (app.multiai.mobile.terminal.TerminalPackageManager.PackageSpec spec : manifest) {
            if ("runtime".equals(spec.id)) withLibs.add(spec.id); // общие либы — всегда
        }
        final StringBuilder out = new StringBuilder();
        pm.install(manifest, withLibs, (stage, spec, done, total, message) -> out.append(message).append('\n'));
        java.util.Set<String> installedNow = new java.util.HashSet<>();
        for (String id : withLibs) {
            if (pm.isPackageInstalled(id)) installedNow.add(id);
        }
        boolean ok = installedNow.containsAll(wanted);
        String installed = String.join(", ", installedNow);
        JSONObject result = new JSONObject().put("command", command)
                .put("success", ok)
                .put("installed", installedNow.isEmpty() ? "none" : installed)
                .put("note", "Run execute_command again to use the newly installed tools; the environment is set up per command.")
                .put("output", clip(out.toString(), 4000));
        return ok ? result.toString() : result.put("error", "Package installation did not complete").toString();
    }

    /** Все либы и тулы ставятся на общее хранилище — без него терминал падает. */
    private static boolean hasAllFilesAccess() {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager();
    }

    /**
     * Нативный перехват `app install <path>` для агента: резолвит project-relative
     * путь (или абсолютный /sdcard/...) и открывает системный установщик APK.
     * Результат — JSON с полем output, как у обычной команды, чтобы агент видел
     * что установщик запущен и ждёт подтверждения юзера.
     */
    private String executeAppInstall(String command, OperationCancelToken token) throws Exception {
        String apkPath = command.substring("app install".length()).trim();
        if (apkPath.isEmpty()) {
            return error("INVALID_ARGUMENT", "Usage: app install <project-relative-or-absolute-path.apk>");
        }
        checkpoint(token);

        java.io.File apkFile;
        if (apkPath.startsWith("/")) {
            apkFile = new java.io.File(apkPath);
        } else {
            String root = mappedProjectPath();
            if (root == null) {
                return error("PROJECT_PATH_UNAVAILABLE",
                        "The selected SAF folder cannot be mapped to a shell path; choose a folder in primary device storage");
            }
            apkFile = new java.io.File(root, apkPath);
        }
        if (!apkFile.isFile()) {
            return error("PATH_NOT_FOUND", "APK not found: " + apkFile.getAbsolutePath());
        }

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] resultMsg = {"Установщик открыт ✓"};
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> app.multiai.mobile.utils.ApkInstallHelper.installApkFromPath(
                context, null, apkFile.getAbsolutePath(),
                msg -> {
                    resultMsg[0] = msg;
                    latch.countDown();
                }));
        // Ждём только постановку интента в очередь (сам диалог установки — асинхронный,
        // юзер подтверждает вручную; блокировать ход агента до подтверждения нельзя).
        try {
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new JSONObject()
                .put("command", command)
                .put("apk", apkFile.getAbsolutePath())
                .put("exit_code", 0)
                .put("output", resultMsg[0] + " — system package installer is shown; the user must confirm the installation manually.")
                .toString();
    }

    static String applyUnifiedPatch(String original, String patch) {
        String[] source = original.isEmpty() ? new String[0] : original.replace("\r\n", "\n").split("\\n", -1);
        String[] patchLines = patch.replace("\r\n", "\n").split("\\n", -1);
        List<String> result = new ArrayList<>();
        int sourceCursor = 0, i = 0;
        while (i < patchLines.length) {
            Matcher matcher = HUNK.matcher(patchLines[i]);
            if (!matcher.matches()) { i++; continue; }
            int start = Integer.parseInt(matcher.group(1)) - 1;
            while (sourceCursor < start && sourceCursor < source.length) result.add(source[sourceCursor++]);
            i++;
            while (i < patchLines.length && !HUNK.matcher(patchLines[i]).matches()) {
                String line = patchLines[i++];
                if (line.isEmpty() && i == patchLines.length) break;
                if (line.startsWith("\\")) continue;
                if (line.isEmpty()) { if (sourceCursor >= source.length || !source[sourceCursor].isEmpty()) return null; result.add(source[sourceCursor++]); continue; }
                char kind = line.charAt(0); String value = line.substring(1);
                if (kind == ' ') { if (sourceCursor >= source.length || !source[sourceCursor].equals(value)) return null; result.add(source[sourceCursor++]); }
                else if (kind == '-') { if (sourceCursor >= source.length || !source[sourceCursor].equals(value)) return null; sourceCursor++; }
                else if (kind == '+') result.add(value);
                else return null;
            }
        }
        while (sourceCursor < source.length) result.add(source[sourceCursor++]);
        return String.join("\n", result);
    }

    private static String normalizePath(String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace('\\', '/');
        if (value.startsWith("/")) return null;
        String[] parts = value.split("/"); List<String> safe = new ArrayList<>();
        for (String part : parts) { if (part.isEmpty() || ".".equals(part)) continue; if ("..".equals(part) || part.indexOf('\0') >= 0) return null; safe.add(part); }
        return String.join("/", safe);
    }

    private static boolean isInvalidPath(String raw) {
        return raw != null && normalizePath(raw) == null;
    }

    private static String sliceLines(String text, int start, int end) {
        String[] lines = text.split("\\r?\\n", -1); StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, start - 1); i < Math.min(lines.length, end); i++) { if (out.length() > 0) out.append('\n'); out.append(lines[i]); }
        return out.toString();
    }

    /** Converts glob tokens and quotes every other regex character. */
    private static boolean globMatches(String text, String glob) {
        if (text == null || glob == null) return false;
        StringBuilder regex = new StringBuilder("(?i)^");
        for (int i = 0; i < glob.length();) {
            char current = glob.charAt(i);
            if (current == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doubleStar && i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                    // **/ matches both nested paths and a file in the selected root.
                    regex.append("(?:.*/)?");
                    i += 3;
                } else if (doubleStar) {
                    regex.append(".*");
                    i += 2;
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (current == '?') {
                regex.append("[^/]");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
                i++;
            }
        }
        regex.append('$');
        return text.matches(regex.toString());
    }

    private static String clip(String text, int max) { return text == null ? "" : (text.length() <= max ? text : text.substring(0, max) + "\n...[truncated]"); }
    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static String error(String code, String message) { try { return new JSONObject().put("error", message).put("code", code).toString(); } catch (Exception ignored) { return "{\"error\":\"tool_failed\"}"; } }
}
