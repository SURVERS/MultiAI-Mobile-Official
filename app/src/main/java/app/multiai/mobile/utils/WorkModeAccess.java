package app.multiai.mobile.utils;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.multiai.mobile.R;
import app.multiai.mobile.tools.OperationCancelToken;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.UiKit;

/**
 * Approval policy for potentially destructive Work Mode tools
 * (write_file, edit_file, execute_command).
 *
 * <h3>Global modes (persisted, shared across all chats/projects)</h3>
 * <ul>
 *   <li>{@link #MODE_PLAN} — every new turn starts in read-only Plan Mode.</li>
 *   <li>{@link #MODE_ASK} — always prompt before any file change / command.</li>
 *   <li>{@link #MODE_CONFIRM} — prompt only for potentially unsafe actions.</li>
 *   <li>{@link #MODE_FULL} — unrestricted, never prompts (shown in orange).</li>
 * </ul>
 * The global mode is only changed from the settings picker, NEVER from a
 * per-request approval sheet.
 *
 * <h3>Per-request sheet (when a prompt is shown)</h3>
 * Two buttons:
 * <ul>
 *   <li><b>Разрешить</b> (main area) — approve this one call only.</li>
 *   <li><b>▾</b> (arrow zone) — expands sub-menu:
 *     <ul>
 *       <li><b>Всегда</b> — add the command/path to the persistent whitelist.</li>
 *       <li><b>Только в этой сессии</b> — add to the active Project-chat session.
 *           These in-memory rules reset after the application restarts.</li>
 *     </ul>
 *   </li>
 *   <li><b>Отклонить</b> — rejects the tool call.</li>
 * </ul>
 */
public final class WorkModeAccess {
    // Global modes
    public static final String MODE_PLAN = "plan";
    public static final String MODE_ASK = "ask";
    public static final String MODE_CONFIRM = "confirm";
    public static final String MODE_FULL = "full";

    private static final String PREFS = "multiaiide_settings";
    private static final String KEY_MODE = "work_mode_access_mode";
    private static final String KEY_ALLOWED_COMMANDS = "work_mode_allowed_commands";
    private static final String KEY_BLOCKED_COMMANDS = "work_mode_blocked_commands";
    private static final String KEY_ALLOWED_PATHS = "work_mode_allowed_paths";

    /**
     * Session rules must be scoped to a Project chat, not a single global set.
     * The buckets intentionally live only in RAM: app restart starts a new session.
     */
    private static final Object SESSION_LOCK = new Object();
    private static final java.util.LinkedHashMap<String, SessionScopeState> sessionScopes =
            new java.util.LinkedHashMap<>();
    private static volatile String activeSessionId = "";

    private static final class SessionScopeState {
        final String id;
        String label;
        final Set<String> allowedCommands = new HashSet<>();
        final Set<String> allowedPaths = new HashSet<>();

        SessionScopeState(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** Immutable session entry used by the command-rule screen. */
    public static final class SessionScope {
        private final String id;
        private final String label;
        private final boolean active;

        SessionScope(String id, String label, boolean active) {
            this.id = id;
            this.label = label;
            this.active = active;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public boolean isActive() {
            return active;
        }
    }

    // ─────────── Pending approval registry (cancellation) ───────────

    /**
     * One in-flight approval wait: its latch, verdict and the sheet dialog.
     * Registered while the sheet is up so {@link #releasePendingApprovals()}
     * can dismiss it and unblock the worker thread from the UI side.
     */
    private static final class PendingApproval {
        final Activity activity;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean approved = new AtomicBoolean(false);
        /** Makes approve/reject/dismiss/timeout a single, race-free decision. */
        final AtomicBoolean resolved = new AtomicBoolean(false);
        volatile Dialog dialog;

        PendingApproval(Activity activity) {
            this.activity = activity;
        }

        boolean isResolved() {
            return resolved.get();
        }

        void approve() {
            resolve(true);
        }

        void reject() {
            resolve(false);
        }

        /** Backward-compatible cancellation entry point: cancelling means reject. */
        void release() {
            reject();
        }

        private void resolve(boolean decision) {
            if (!resolved.compareAndSet(false, true)) return;
            approved.set(decision);
            // Unblock first: dismiss() synchronously triggers OnDismiss on some devices.
            latch.countDown();
            Dialog sheet = dialog;
            if (sheet != null) {
                try {
                    activity.runOnUiThread(() -> {
                        try {
                            sheet.dismiss();
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final ConcurrentHashMap<PendingApproval, Boolean> pendingApprovals =
            new ConcurrentHashMap<>();
    /** Serializes modal sheets so parallel tool calls cannot hide approvals behind each other. */
    private static final java.util.concurrent.Semaphore approvalGate =
            new java.util.concurrent.Semaphore(1, true);

    /**
     * Dismiss every visible approval sheet and unblock its worker thread with
     * a "rejected" verdict. Called by ChatActivity's stop button before/with
     * {@code WorkModeTools.cancelCurrentOperation()}. Idempotent, thread-safe.
     */
    public static void releasePendingApprovals() {
        for (PendingApproval pending : pendingApprovals.keySet()) pending.release();
    }

    /** Number of approval sheets currently blocking a worker thread. */
    public static int pendingApprovalCount() {
        return pendingApprovals.size();
    }

    private WorkModeAccess() {}

    // ─────────── Global mode ───────────

    /** Current global mode (one of MODE_PLAN / MODE_ASK / MODE_CONFIRM / MODE_FULL). */
    public static String mode(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, MODE_ASK);
        return isKnownMode(value) ? value : MODE_ASK;
    }

    public static void setMode(Context context, String value) {
        String normalized = isKnownMode(value) ? value : MODE_ASK;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, normalized).apply();
    }

    private static boolean isKnownMode(String value) {
        return MODE_PLAN.equals(value) || MODE_ASK.equals(value)
                || MODE_CONFIRM.equals(value) || MODE_FULL.equals(value);
    }

    // ─────────── Session management ───────────

    /** Clears every in-memory session rule when the process starts fresh. */
    public static void resetSession() {
        synchronized (SESSION_LOCK) {
            sessionScopes.clear();
            activeSessionId = "";
        }
    }

    /**
     * Registers a Project chat for per-session rules without switching the chat
     * currently used by tool execution. The rules screen uses this to expose
     * saved Work Mode chats that have not yet run a tool during this process.
     */
    public static void ensureSession(String sessionId, String label) {
        String normalizedId = sessionId == null ? "" : sessionId.trim();
        if (normalizedId.isEmpty()) return;
        String normalizedLabel = label == null ? "" : label.trim();
        synchronized (SESSION_LOCK) {
            ensureSessionLocked(normalizedId, normalizedLabel);
        }
    }

    /** Returns the active Project-chat ID, or an empty string when none is active. */
    public static String activeSessionId() {
        return activeSessionId;
    }

    private static SessionScopeState ensureSessionLocked(String sessionId, String label) {
        SessionScopeState scope = sessionScopes.get(sessionId);
        if (scope == null) {
            scope = new SessionScopeState(sessionId, label);
            sessionScopes.put(sessionId, scope);
        } else if (label != null && !label.isEmpty()) {
            scope.label = label;
        }
        return scope;
    }

    /**
     * Makes a Project chat the active approval session. Switching chats keeps the
     * former in-memory bucket available for inspection in the rules screen, while
     * tool execution always uses only this chat's bucket.
     */
    public static void activateSession(String sessionId, String label) {
        String normalizedId = sessionId == null ? "" : sessionId.trim();
        if (normalizedId.isEmpty()) return;
        String normalizedLabel = label == null ? "" : label.trim();
        synchronized (SESSION_LOCK) {
            ensureSessionLocked(normalizedId, normalizedLabel);
            activeSessionId = normalizedId;
        }
    }

    /** A snapshot of known sessions; active comes first and no placeholder is created. */
    public static java.util.List<SessionScope> sessionScopes() {
        synchronized (SESSION_LOCK) {
            SessionScopeState active = sessionScopes.get(activeSessionId);
            java.util.List<SessionScope> result = new java.util.ArrayList<>();
            if (active != null) result.add(new SessionScope(active.id, active.label, true));
            for (SessionScopeState scope : sessionScopes.values()) {
                if (active == null || !scope.id.equals(active.id)) {
                    result.add(new SessionScope(scope.id, scope.label, false));
                }
            }
            return result;
        }
    }

    private static SessionScopeState activeSessionLocked() {
        SessionScopeState scope = sessionScopes.get(activeSessionId);
        if (scope != null) return scope;
        String generatedId = "session-" + java.util.UUID.randomUUID();
        scope = new SessionScopeState(generatedId, "");
        sessionScopes.put(generatedId, scope);
        activeSessionId = generatedId;
        return scope;
    }

    private static SessionScopeState sessionScopeLocked(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return activeSessionLocked();
        return sessionScopes.get(sessionId.trim());
    }

    // ─────────── Persistent command rules ───────────

    private static Set<String> loadAllowedCommands(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_ALLOWED_COMMANDS, new HashSet<>());
    }

    private static Set<String> loadBlockedCommands(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_BLOCKED_COMMANDS, new HashSet<>());
    }

    private static void saveAllowedCommand(Context ctx, String command) {
        Set<String> set = new HashSet<>(loadAllowedCommands(ctx));
        set.add(command);
        putAllowedCommands(ctx, set);
    }

    private static Set<String> loadAllowedPaths(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_ALLOWED_PATHS, new HashSet<>());
    }

    private static void saveAllowedPath(Context ctx, String path) {
        Set<String> set = new HashSet<>(loadAllowedPaths(ctx));
        set.add(path);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_ALLOWED_PATHS, set).apply();
    }

    // ─────────── Command-rule management API ───────────
    // Used by CommandWhitelistActivity. The special entry "*" in an allow list
    // matches every execute_command. Block rules are global and always win.

    private static final String COMMAND_KEY_PREFIX = "execute_command\u001F";

    /** Permanently allowed commands, sorted for display. */
    public static java.util.List<String> persistentCommands(Context context) {
        return readableCommands(loadAllowedCommands(context));
    }

    /** Globally blocked commands, sorted for display. */
    public static java.util.List<String> blockedCommands(Context context) {
        return readableCommands(loadBlockedCommands(context));
    }

    /** Commands attached to the active Project-chat session. */
    public static java.util.List<String> sessionCommands() {
        synchronized (SESSION_LOCK) {
            return readableCommands(activeSessionLocked().allowedCommands);
        }
    }

    /** Commands attached to a selected in-memory session. */
    public static java.util.List<String> sessionCommands(String sessionId) {
        synchronized (SESSION_LOCK) {
            SessionScopeState scope = sessionScopeLocked(sessionId);
            return scope == null ? new java.util.ArrayList<>() : readableCommands(scope.allowedCommands);
        }
    }

    private static java.util.List<String> readableCommands(Set<String> stored) {
        java.util.List<String> commands = new java.util.ArrayList<>();
        for (String entry : stored) {
            if (entry == null || !entry.startsWith(COMMAND_KEY_PREFIX)) continue;
            String detail = entry.substring(COMMAND_KEY_PREFIX.length()).trim();
            if (!detail.isEmpty()) commands.add(detail);
        }
        java.util.Collections.sort(commands, String.CASE_INSENSITIVE_ORDER);
        return commands;
    }

    /** @return false when this command already exists in the global allow list. */
    public static boolean addPersistentCommand(Context context, String command) {
        String normalized = normalizeCommand(command);
        if (normalized.isEmpty()) return false;
        Set<String> updated = new HashSet<>(loadAllowedCommands(context));
        if (!updated.add(approvalStorageKey("execute_command", normalized))) return false;
        putAllowedCommands(context, updated);
        return true;
    }

    /** @return false when this command already exists in the global block list. */
    public static boolean addBlockedCommand(Context context, String command) {
        String normalized = normalizeCommand(command);
        if (normalized.isEmpty()) return false;
        Set<String> updated = new HashSet<>(loadBlockedCommands(context));
        if (!updated.add(approvalStorageKey("execute_command", normalized))) return false;
        putBlockedCommands(context, updated);
        return true;
    }

    public static boolean addSessionCommand(String command) {
        return addSessionCommand(null, command);
    }

    public static boolean addSessionCommand(String sessionId, String command) {
        String normalized = normalizeCommand(command);
        if (normalized.isEmpty()) return false;
        synchronized (SESSION_LOCK) {
            SessionScopeState scope = sessionScopeLocked(sessionId);
            return scope != null && scope.allowedCommands.add(
                    approvalStorageKey("execute_command", normalized));
        }
    }

    public static void removePersistentCommand(Context context, String command) {
        Set<String> updated = new HashSet<>(loadAllowedCommands(context));
        if (!updated.remove(approvalStorageKey("execute_command", command))) return;
        putAllowedCommands(context, updated);
    }

    public static void removeBlockedCommand(Context context, String command) {
        Set<String> updated = new HashSet<>(loadBlockedCommands(context));
        if (!updated.remove(approvalStorageKey("execute_command", command))) return;
        putBlockedCommands(context, updated);
    }

    public static void removeSessionCommand(String command) {
        removeSessionCommand(null, command);
    }

    public static void removeSessionCommand(String sessionId, String command) {
        synchronized (SESSION_LOCK) {
            SessionScopeState scope = sessionScopeLocked(sessionId);
            if (scope != null) scope.allowedCommands.remove(
                    approvalStorageKey("execute_command", command));
        }
    }

    /** Edits a global allow entry without widening it into another capability. */
    public static void replacePersistentCommand(Context context, String oldCommand,
                                                String newCommand) {
        String normalized = normalizeCommand(newCommand);
        if (normalized.isEmpty()) return;
        Set<String> updated = new HashSet<>(loadAllowedCommands(context));
        removeCommandKey(updated, oldCommand);
        updated.add(approvalStorageKey("execute_command", normalized));
        putAllowedCommands(context, updated);
    }

    /** Edits a global block entry. A block continues to beat any allow entry. */
    public static void replaceBlockedCommand(Context context, String oldCommand,
                                             String newCommand) {
        String normalized = normalizeCommand(newCommand);
        if (normalized.isEmpty()) return;
        Set<String> updated = new HashSet<>(loadBlockedCommands(context));
        removeCommandKey(updated, oldCommand);
        updated.add(approvalStorageKey("execute_command", normalized));
        putBlockedCommands(context, updated);
    }

    public static void replaceSessionCommand(String oldCommand, String newCommand) {
        replaceSessionCommand(null, oldCommand, newCommand);
    }

    public static void replaceSessionCommand(String sessionId, String oldCommand,
                                             String newCommand) {
        String normalized = normalizeCommand(newCommand);
        if (normalized.isEmpty()) return;
        synchronized (SESSION_LOCK) {
            SessionScopeState scope = sessionScopeLocked(sessionId);
            if (scope == null) return;
            removeCommandKey(scope.allowedCommands, oldCommand);
            scope.allowedCommands.add(approvalStorageKey("execute_command", normalized));
        }
    }

    /** Removes command-only global allow rules; file-operation approvals stay intact. */
    public static void clearPersistentCommands(Context context) {
        Set<String> updated = new HashSet<>();
        for (String entry : loadAllowedCommands(context)) {
            if (entry != null && !entry.startsWith(COMMAND_KEY_PREFIX)) updated.add(entry);
        }
        putAllowedCommands(context, updated);
    }

    public static void clearBlockedCommands(Context context) {
        putBlockedCommands(context, new HashSet<>());
    }

    public static void clearSessionCommands() {
        clearSessionCommands(null);
    }

    public static void clearSessionCommands(String sessionId) {
        synchronized (SESSION_LOCK) {
            SessionScopeState scope = sessionScopeLocked(sessionId);
            if (scope != null) scope.allowedCommands.clear();
        }
    }

    private static String normalizeCommand(String command) {
        return command == null ? "" : command.trim();
    }

    private static void removeCommandKey(Set<String> target, String command) {
        String normalized = normalizeCommand(command);
        if (!normalized.isEmpty()) target.remove(approvalStorageKey("execute_command", normalized));
    }

    private static void putAllowedCommands(Context context, Set<String> value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_ALLOWED_COMMANDS, value).apply();
    }

    private static void putBlockedCommands(Context context, Set<String> value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_BLOCKED_COMMANDS, value).apply();
    }

    // ─────────── Approval check ───────────

    /** Backward-compatible overload for callers that do not have operation details. */
    public static boolean requiresApproval(Context context, String toolName) {
        return requiresApproval(context, toolName, null);
    }

    /**
     * Returns whether an operation needs an approval sheet under the selected mode.
     * In "Подтверждать за меня" only a deliberately small, read-only command set
     * is auto-approved. Every mutation and every unknown shell command still asks.
     */
    public static boolean requiresApproval(Context context, String toolName, String detail) {
        if (!isManagedTool(toolName)) return false;
        if (isOperationBlocked(context, toolName, detail)) return true;
        if (MODE_FULL.equals(mode(context))) return false;
        if (MODE_CONFIRM.equals(mode(context))) {
            return !isSafeAutoApprovedAction(toolName, detail);
        }
        return true;
    }

    /** A global command block overrides mode and any allow rule. */
    public static boolean isOperationBlocked(Context context, String toolName, String detail) {
        return "execute_command".equals(toolName) && isCommandBlocked(context, detail);
    }

    private static boolean isManagedTool(String toolName) {
        return "write_file".equals(toolName) || "edit_file".equals(toolName)
                || "delete_path".equals(toolName) || "move_path".equals(toolName)
                || "execute_command".equals(toolName);
    }

    private static boolean isCommandBlocked(Context context, String command) {
        String normalized = normalizeCommand(command);
        return !normalized.isEmpty() && loadBlockedCommands(context).contains(
                approvalStorageKey("execute_command", normalized));
    }

    private static boolean isSafeAutoApprovedAction(String toolName, String detail) {
        return "execute_command".equals(toolName) && isSafeReadOnlyCommand(detail);
    }

    /**
     * Conservative command classifier. Shell syntax, substitutions, redirects,
     * pipes and every non-listed executable must be explicitly approved.
     */
    private static boolean isSafeReadOnlyCommand(String command) {
        String normalized = normalizeCommand(command);
        if (normalized.isEmpty() || normalized.length() > 400) return false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '.'
                    || c == '/' || c == '\\' || c == ':' || c == '=' || c == '@'
                    || c == ',' || c == '+' || c == '-')) return false;
        }
        String[] parts = normalized.split("\\s+");
        if (parts.length == 0) return false;
        String program = parts[0].toLowerCase(java.util.Locale.ROOT);
        if ("pwd".equals(program) || "whoami".equals(program) || "id".equals(program)
                || "uname".equals(program) || "date".equals(program)) {
            return parts.length == 1;
        }
        if (oneOf(program, "ls", "dir", "cat", "type", "head", "tail", "grep", "rg",
                "findstr", "which", "where", "stat", "readlink")) return true;
        if ("git".equals(program)) {
            return parts.length >= 2 && oneOf(parts[1].toLowerCase(java.util.Locale.ROOT),
                    "status", "diff", "log", "branch", "remote", "rev-parse");
        }
        if (oneOf(program, "python", "python3", "node", "java", "javac", "clang", "clang++")) {
            return parts.length == 2 && oneOf(parts[1], "--version", "-V", "-version");
        }
        if ("pip".equals(program)) {
            return parts.length >= 2 && oneOf(parts[1].toLowerCase(java.util.Locale.ROOT),
                    "list", "show", "check", "--version", "-v");
        }
        if ("npm".equals(program)) {
            return parts.length >= 2 && oneOf(parts[1].toLowerCase(java.util.Locale.ROOT),
                    "list", "ls", "--version", "-v");
        }
        return "echo".equals(program);
    }

    private static boolean oneOf(String value, String... options) {
        for (String option : options) {
            if (option.equals(value)) return true;
        }
        return false;
    }

    /**
     * Builds a capability-specific whitelist key. A user approving a write to
     * one path must never implicitly approve deleting or moving that same path.
     */
    private static String approvalStorageKey(String toolName, String detail) {
        return (toolName == null ? "" : toolName) + "\u001F"
                + (detail == null ? "" : detail.trim());
    }

    /** Check whether an explicit rule exists for this command/path. */
    private static boolean isWhitelisted(Context ctx, String toolName, String detail) {
        if (detail == null || detail.trim().isEmpty()) return false;
        if (isOperationBlocked(ctx, toolName, detail)) return false;
        String key = approvalStorageKey(toolName, detail);
        if ("execute_command".equals(toolName)) {
            if (hasCommandRule(loadAllowedCommands(ctx), key)) return true;
            synchronized (SESSION_LOCK) {
                return hasCommandRule(activeSessionLocked().allowedCommands, key);
            }
        }
        // File-operation rules retain their capability-specific exact match.
        if (loadAllowedPaths(ctx).contains(key)) return true;
        synchronized (SESSION_LOCK) {
            return activeSessionLocked().allowedPaths.contains(key);
        }
    }

    private static boolean hasCommandRule(Set<String> rules, String exactKey) {
        return rules.contains(exactKey) || rules.contains(approvalStorageKey("execute_command", "*"));
    }

    private static void saveSessionApproval(String toolName, String approvalKey) {
        if (approvalKey == null || approvalKey.isEmpty()) return;
        synchronized (SESSION_LOCK) {
            SessionScopeState scope = activeSessionLocked();
            if ("execute_command".equals(toolName)) {
                scope.allowedCommands.add(approvalKey);
            } else {
                scope.allowedPaths.add(approvalKey);
            }
        }
    }

    /**
     * Blocks a worker thread until the user approves or rejects the requested action.
     * Unbounded wait (legacy behaviour kept for compatibility); see
     * {@link #awaitApproval(Activity, String, String, long, OperationCancelToken)}
     * for the bounded / cancellable variant.
     *
     * @param detail  human-readable detail: the command text or file path being approved.
     * @return true if approved, false if rejected.
     */
    public static boolean awaitApproval(Activity activity, String toolName, String detail) {
        return awaitApproval(activity, toolName, detail, 0, null);
    }

    /**
     * Bounded, cancellable approval wait.
     *
     * <p>When {@code token} is cancelled (or the global release hook fires),
     * the sheet is dismissed and false is returned immediately. When
     * {@code timeoutSec} &gt; 0 the wait also gives up after that many
     * seconds (sheet dismissed, false returned) so a worker thread can never
     * leak forever if the activity is destroyed.
     *
     * @param token      optional cancellation token from WorkModeTools' execute
     * @param timeoutSec 0 = wait indefinitely (legacy), &gt;0 = bounded wait
     * @return true if approved, false if rejected / cancelled / timed out
     */
    public static boolean awaitApproval(Activity activity, String toolName, String detail,
                                        long timeoutSec, OperationCancelToken token) {
        if (isOperationBlocked(activity, toolName, detail)) return false;
        if (!requiresApproval(activity, toolName, detail)) return true;
        if (token != null && token.check()) return false;
        // "Подтверждать за меня" never lets an allow rule bypass a dangerous or
        // unknown operation: that mode is safe-only by definition. Explicit rules
        // still work in Ask mode, where the user deliberately configured them.
        if (!MODE_CONFIRM.equals(mode(activity)) && isWhitelisted(activity, toolName, detail)) {
            return true;
        }

        final long deadlineNanos = timeoutSec > 0
                ? System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec) : Long.MAX_VALUE;
        boolean gateAcquired = false;
        try {
            // Several model tool calls can arrive in one response. Only one approval
            // sheet may be visible, otherwise hidden sheets make the round look frozen.
            while (!gateAcquired) {
                if (token != null && token.check()) return false;
                long waitMs = 200L;
                if (timeoutSec > 0) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0L) return false;
                    waitMs = Math.max(1L, Math.min(waitMs,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                }
                gateAcquired = approvalGate.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
            }

            final String approvalKey = approvalStorageKey(toolName, detail);
            final PendingApproval pending = new PendingApproval(activity);
            final OperationCancelToken.Listener cancelListener = pending::reject;
            pendingApprovals.put(pending, Boolean.TRUE);
            if (token != null) token.addListener(cancelListener);
            try {
                activity.runOnUiThread(() ->
                        showApprovalSheet(activity, toolName, detail, approvalKey, pending));
                boolean released;
                if (timeoutSec > 0) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    released = remainingNanos > 0L && pending.latch.await(
                            remainingNanos, TimeUnit.NANOSECONDS);
                } else {
                    pending.latch.await();
                    released = true;
                }
                if (!released) pending.reject();
                return pending.approved.get();
            } finally {
                if (token != null) token.removeListener(cancelListener);
                // Covers interruption and every exceptional exit while the sheet is visible.
                pending.reject();
                pendingApprovals.remove(pending);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (gateAcquired) approvalGate.release();
        }
    }

    /** Builds and shows the approval bottom sheet; runs on the UI thread. */
    private static void showApprovalSheet(Activity activity, String toolName, String detail,
                                          String approvalKey, PendingApproval pending) {
            int accent = AccentColor.resolve(activity);
            int accentFg = AccentColor.contrastForeground(accent);
            int dangerColor = ContextCompat.getColor(activity, R.color.error);
            int cardColor = ContextCompat.getColor(activity, R.color.surface_card);
            int strokeColor = ContextCompat.getColor(activity, R.color.stroke);
            int textPrimary = ContextCompat.getColor(activity, R.color.text_primary);
            int textSecondary = ContextCompat.getColor(activity, R.color.text_secondary);

            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);

            // Action type label
            String typeLabel;
            if ("execute_command".equals(toolName)) {
                typeLabel = activity.getString(R.string.work_access_type_command);
            } else if ("delete_path".equals(toolName)) {
                typeLabel = activity.getString(R.string.work_access_type_delete);
            } else if ("move_path".equals(toolName)) {
                typeLabel = activity.getString(R.string.work_access_type_move);
            } else if ("write_file".equals(toolName)) {
                typeLabel = activity.getString(R.string.work_access_type_write);
            } else {
                typeLabel = activity.getString(R.string.work_access_type_edit);
            }
            TextView typeTv = new TextView(activity);
            typeTv.setText(typeLabel);
            typeTv.setTextColor(textSecondary);
            typeTv.setTextSize(12);
            typeTv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            body.addView(typeTv);

            // Detail (command or file path) in a card
            if (detail != null && !detail.trim().isEmpty()) {
                TextView detailTv = new TextView(activity);
                String trimmed = detail.trim();
                if (trimmed.length() > 200) trimmed = trimmed.substring(0, 200) + "…";
                detailTv.setText(trimmed);
                detailTv.setTextColor(textPrimary);
                detailTv.setTextSize(14);
                detailTv.setTypeface(Typeface.MONOSPACE);
                GradientDrawable detailBg = new GradientDrawable();
                detailBg.setColor(cardColor);
                detailBg.setCornerRadius(UiKit.dp(activity, 12));
                detailBg.setStroke(UiKit.dp(activity, 1), strokeColor);
                detailTv.setBackground(detailBg);
                int dpad = UiKit.dp(activity, 14);
                detailTv.setPadding(dpad, UiKit.dp(activity, 10), dpad, UiKit.dp(activity, 10));
                LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dLp.topMargin = UiKit.dp(activity, 8);
                detailTv.setLayoutParams(dLp);
                body.addView(detailTv);
            }

            body.addView(spacer(activity, UiKit.dp(activity, 18)));

            final LinearLayout subMenu = new LinearLayout(activity);
            subMenu.setOrientation(LinearLayout.VERTICAL);
            subMenu.setVisibility(View.GONE);

            // "Разрешить" button — two click zones:
            //   • main area (left) → approve this one time only
            //   • arrow area (right) → expand/collapse sub-menu
            LinearLayout allowBtn = new LinearLayout(activity);
            allowBtn.setOrientation(LinearLayout.HORIZONTAL);
            allowBtn.setGravity(Gravity.CENTER_VERTICAL);
            allowBtn.setMinimumHeight(UiKit.dp(activity, 48));
            GradientDrawable allowBg = new GradientDrawable();
            allowBg.setColor(accent);
            allowBg.setCornerRadius(UiKit.dp(activity, 14));
            allowBtn.setBackground(allowBg);
            allowBtn.setClickable(true);

            // Main "Разрешить" label — approve once
            TextView allowLabel = new TextView(activity);
            allowLabel.setText(activity.getString(R.string.work_access_allow));
            allowLabel.setTextColor(accentFg);
            allowLabel.setTextSize(15);
            allowLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            allowLabel.setGravity(Gravity.CENTER);
            allowLabel.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            allowBtn.addView(allowLabel);

            // Separator
            View divider = new View(activity);
            divider.setBackgroundColor(accentFg);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    UiKit.dp(activity, 1), UiKit.dp(activity, 24));
            divLp.setMarginStart(UiKit.dp(activity, 8));
            divLp.setMarginEnd(UiKit.dp(activity, 8));
            divider.setLayoutParams(divLp);
            divider.setAlpha(0.4f);
            allowBtn.addView(divider);

            // Arrow zone — larger touch target, distinct background, custom icon
            LinearLayout arrowZone = new LinearLayout(activity);
            arrowZone.setOrientation(LinearLayout.HORIZONTAL);
            arrowZone.setGravity(Gravity.CENTER);
            arrowZone.setClickable(true);
            arrowZone.setFocusable(true);
            GradientDrawable arrowBtnBg = new GradientDrawable();
            arrowBtnBg.setCornerRadius(UiKit.dp(activity, 12));
            arrowBtnBg.setColor(0x33FFFFFF);
            arrowZone.setBackground(arrowBtnBg);
            int arrowPad = UiKit.dp(activity, 10);
            arrowZone.setPadding(arrowPad, arrowPad, arrowPad, arrowPad);
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                    UiKit.dp(activity, 44), UiKit.dp(activity, 36));
            arrowZone.setLayoutParams(arrowLp);
            // Custom vector icon instead of text glyph
            android.widget.ImageView arrowIcon = UiKit.icon(activity,
                    R.drawable.ic_chevron_down, 22, accentFg);
            arrowZone.addView(arrowIcon);
            allowBtn.addView(arrowZone);

            // Click on the whole button → approve once (no whitelist).
            allowBtn.setOnClickListener(v -> {
                pending.approve();
            });
            // Click on arrow zone → toggle sub-menu (do NOT approve)
            arrowZone.setOnClickListener(v -> {
                if (subMenu.getVisibility() == View.VISIBLE) {
                    // Collapse with animation
                    subMenu.animate().alpha(0f).translationY(-UiKit.dp(activity, 8))
                            .setDuration(150)
                            .withEndAction(() -> {
                                subMenu.setVisibility(View.GONE);
                                subMenu.setTranslationY(0f);
                                subMenu.setAlpha(1f);
                            }).start();
                    arrowIcon.setImageResource(R.drawable.ic_chevron_down);
                    arrowIcon.setColorFilter(accentFg);
                } else {
                    // Expand with animation
                    subMenu.setAlpha(0f);
                    subMenu.setTranslationY(-UiKit.dp(activity, 8));
                    subMenu.setVisibility(View.VISIBLE);
                    subMenu.animate().alpha(1f).translationY(0f)
                            .setDuration(180).start();
                    arrowIcon.setImageResource(R.drawable.ic_arrow_up);
                    arrowIcon.setColorFilter(accentFg);
                }
            });
            body.addView(allowBtn);

            // Sub-menu: "Всегда" / "Только в этой сессии"
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = UiKit.dp(activity, 8);
            subMenu.setLayoutParams(subLp);

            // "Всегда" — add to persistent whitelist
            LinearLayout alwaysOpt = subOption(activity,
                    activity.getString(R.string.work_access_allow_always));
            alwaysOpt.setOnClickListener(v -> {
                if (!approvalKey.isEmpty()) {
                    if ("execute_command".equals(toolName)) {
                        saveAllowedCommand(activity, approvalKey);
                    } else {
                        saveAllowedPath(activity, approvalKey);
                    }
                }
                pending.approve();
            });
            subMenu.addView(alwaysOpt);

            // "Только в этой сессии" — add to session whitelist
            LinearLayout sessionOpt = subOption(activity,
                    activity.getString(R.string.work_access_allow_session));
            LinearLayout.LayoutParams sLp = (LinearLayout.LayoutParams) sessionOpt.getLayoutParams();
            sLp.topMargin = UiKit.dp(activity, 6);
            sessionOpt.setLayoutParams(sLp);
            sessionOpt.setOnClickListener(v -> {
                saveSessionApproval(toolName, approvalKey);
                pending.approve();
            });
            subMenu.addView(sessionOpt);
            body.addView(subMenu);

            // "Отклонить" button — danger
            LinearLayout rejectBtn = actionButton(activity,
                    activity.getString(R.string.work_access_reject),
                    cardColor, dangerColor, true, false);
            LinearLayout.LayoutParams rLp = (LinearLayout.LayoutParams) rejectBtn.getLayoutParams();
            rLp.topMargin = UiKit.dp(activity, 8);
            rejectBtn.setLayoutParams(rLp);
            rejectBtn.setOnClickListener(v -> {
                pending.reject();
            });
            body.addView(rejectBtn);

            Dialog sheet = ModernSheet.custom(activity,
                    activity.getString(R.string.work_access_title), body, true);
            sheet.setCancelable(true);
            sheet.setCanceledOnTouchOutside(true);
            // Back, backdrop tap and handle-drag are all a deliberate rejection.
            sheet.setOnDismissListener(d -> pending.reject());
            Window w = sheet.getWindow();
            if (w != null) {
                w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            }
            // Expose the sheet so an early cancellation can dismiss it. If the
            // decision arrived before the UI work ran, close this just-created sheet.
            pending.dialog = sheet;
            if (pending.isResolved()) {
                try {
                    sheet.dismiss();
                } catch (Exception ignored) {
                }
            }
    }

    // ─────────── Global mode picker (settings) ───────────

    /** Opens the global mode picker as a bottom sheet (used from settings/drawer). */
    public static void showModePicker(Activity activity) {
        int accent = AccentColor.resolve(activity);
        int accentFg = AccentColor.contrastForeground(accent);
        int cardColor = ContextCompat.getColor(activity, R.color.surface_card);
        int textPrimary = ContextCompat.getColor(activity, R.color.text_primary);
        int textSecondary = ContextCompat.getColor(activity, R.color.text_secondary);
        String current = mode(activity);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        final Dialog[] ref = new Dialog[1];

        View askRow = modeRow(activity, R.drawable.ic_key, R.string.work_access_mode_ask,
                R.string.work_access_mode_ask_sub, MODE_ASK.equals(current), accent, accentFg,
                cardColor, textPrimary, textSecondary, v -> {
            setMode(activity, MODE_ASK);
            if (ref[0] != null) ref[0].dismiss();
        });
        body.addView(askRow);

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = UiKit.dp(activity, 8);
        View confirmRow = modeRow(activity, R.drawable.ic_shield, R.string.work_access_mode_confirm,
                R.string.work_access_mode_confirm_sub, MODE_CONFIRM.equals(current), accent, accentFg,
                cardColor, textPrimary, textSecondary, v -> {
            setMode(activity, MODE_CONFIRM);
            if (ref[0] != null) ref[0].dismiss();
        });
        confirmRow.setLayoutParams(sLp);
        body.addView(confirmRow);

        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = UiKit.dp(activity, 8);
        int fullAccent = ContextCompat.getColor(activity, R.color.work_access_full_color);
        int fullAccentFg = AccentColor.contrastForeground(fullAccent);
        View fullRow = modeRow(activity, R.drawable.ic_s_shield, R.string.work_access_mode_full,
                R.string.work_access_mode_full_sub, MODE_FULL.equals(current), fullAccent, fullAccentFg,
                cardColor, textPrimary, textSecondary, v -> {
            setMode(activity, MODE_FULL);
            if (ref[0] != null) ref[0].dismiss();
        });
        fullRow.setLayoutParams(fLp);
        body.addView(fullRow);

        LinearLayout.LayoutParams planLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        planLp.topMargin = UiKit.dp(activity, 16);
        View planRow = modeRow(activity, R.drawable.ic_plan_mode,
                R.string.work_access_mode_plan, R.string.work_access_mode_plan_sub,
                MODE_PLAN.equals(current), accent, accentFg,
                cardColor, textPrimary, textSecondary, v -> {
            setMode(activity, MODE_PLAN);
            if (ref[0] != null) ref[0].dismiss();
        });
        planRow.setLayoutParams(planLp);
        body.addView(planRow);

        ref[0] = ModernSheet.custom(activity,
                activity.getString(R.string.work_access_title), body, true);
    }

    /**
     * Opens the command-permission screen. Managing two scopes with add / edit /
     * delete outgrew a bottom sheet, so it is a full activity now. This still
     * covers only execute_command entries: file-operation approvals have a
     * separate, capability-specific key and must never be widened from this UI.
     */
    public static void showCommandWhitelistManager(Activity activity) {
        if (activity == null) return;
        activity.startActivity(new android.content.Intent(activity,
                app.multiai.mobile.ui.CommandWhitelistActivity.class));
    }

    // ─────────── View builders ───────────

    private static View modeRow(Context ctx, int iconRes, int titleRes, int subRes, boolean selected,
                                int accent, int accentFg, int cardColor,
                                int textPrimary, int textSecondary,
                                View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(ctx, 56));
        row.setPadding(UiKit.dp(ctx, 16), UiKit.dp(ctx, 14), UiKit.dp(ctx, 16), UiKit.dp(ctx, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? ContextCompat.getColor(ctx, R.color.surface_tonal) : cardColor);
        bg.setCornerRadius(UiKit.dp(ctx, 14));
        bg.setStroke(UiKit.dp(ctx, selected ? 2 : 1),
                selected ? accent : ContextCompat.getColor(ctx, R.color.stroke));
        row.setBackground(bg);
        row.setClickable(true);
        row.setOnClickListener(onClick);

        View leadingIcon = UiKit.icon(ctx, iconRes, 22, selected ? accent : textSecondary);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 22), UiKit.dp(ctx, 22));
        iconLp.setMarginEnd(UiKit.dp(ctx, 14));
        leadingIcon.setLayoutParams(iconLp);
        row.addView(leadingIcon);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(ctx);
        title.setText(titleRes);
        title.setTextColor(textPrimary);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        textCol.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText(subRes);
        sub.setTextColor(textSecondary);
        sub.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = UiKit.dp(ctx, 2);
        sub.setLayoutParams(subLp);
        textCol.addView(sub);
        row.addView(textCol);

        View indicator = UiKit.icon(ctx, R.drawable.ic_check, 20, accent);
        indicator.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 20), UiKit.dp(ctx, 20));
        indLp.setMarginStart(UiKit.dp(ctx, 12));
        indicator.setLayoutParams(indLp);
        row.addView(indicator);

        return row;
    }

    private static LinearLayout actionButton(Context ctx, String label,
                                              int bgColor, int textColor,
                                              boolean withStroke, boolean withArrow) {
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setMinimumHeight(UiKit.dp(ctx, 48));
        int pad = UiKit.dp(ctx, 16);
        btn.setPadding(pad, UiKit.dp(ctx, 14), pad, UiKit.dp(ctx, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(UiKit.dp(ctx, 14));
        if (withStroke) bg.setStroke(UiKit.dp(ctx, 1), ContextCompat.getColor(ctx, R.color.stroke));
        btn.setBackground(bg);
        btn.setClickable(true);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btn.addView(tv);

        if (withArrow) {
            TextView arrow = new TextView(ctx);
            arrow.setText("▾");
            arrow.setTextColor(textColor);
            arrow.setTextSize(16);
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            aLp.setMarginStart(UiKit.dp(ctx, 8));
            arrow.setLayoutParams(aLp);
            btn.addView(arrow);
        }

        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    private static LinearLayout subOption(Context ctx, String label) {
        LinearLayout opt = new LinearLayout(ctx);
        opt.setOrientation(LinearLayout.HORIZONTAL);
        opt.setGravity(Gravity.CENTER_VERTICAL);
        opt.setMinimumHeight(UiKit.dp(ctx, 46));
        int pad = UiKit.dp(ctx, 16);
        opt.setPadding(pad, UiKit.dp(ctx, 12), pad, UiKit.dp(ctx, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(ctx, R.color.surface_card));
        bg.setCornerRadius(UiKit.dp(ctx, 12));
        bg.setStroke(UiKit.dp(ctx, 1), ContextCompat.getColor(ctx, R.color.stroke));
        opt.setBackground(bg);
        opt.setClickable(true);

        // Small accent dot indicator on the left
        View dot = new View(ctx);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(AccentColor.resolve(ctx));
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 8), UiKit.dp(ctx, 8));
        dotLp.setMarginEnd(UiKit.dp(ctx, 12));
        dot.setLayoutParams(dotLp);
        opt.addView(dot);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        opt.addView(tv);

        // Chevron-right hint icon
        android.widget.ImageView hint = UiKit.icon(ctx,
                R.drawable.ic_chevron_right, 18,
                ContextCompat.getColor(ctx, R.color.text_hint));
        opt.addView(hint);

        opt.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return opt;
    }

    private static View spacer(Context ctx, int heightPx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return v;
    }
}
