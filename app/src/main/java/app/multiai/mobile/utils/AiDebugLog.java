package app.multiai.mobile.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.multiai.mobile.BuildConfig;

/**
 * Detailed AI debug logger. Writes full tool calls, AI responses, and streaming events
 * to a file in the app's private directory. Only active in DEBUG builds.
 *
 * Log file: /data/data/app.multiai.mobile/files/ai_debug.log
 */
public final class AiDebugLog {
    private static final String TAG = "AiDebug";
    private static final String LOG_FILE = "ai_debug.log";
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5 MB

    private static ExecutorService logExecutor;
    private static File logFile;
    private static boolean initialized = false;

    private AiDebugLog() {}

    public static synchronized void init(Context context) {
        if (initialized || !BuildConfig.DEBUG) return;
        logFile = new File(context.getFilesDir(), LOG_FILE);
        try {
            File extDir = context.getExternalFilesDir(null);
            if (extDir != null) {
                externalRoot = extDir;
                // Зеркало НЕ чистим между запусками (тапы юзера не должны
                // теряться при рестарте приложения) — просто ставим маркер
                File mirror = new File(extDir, LOG_FILE);
                try (PrintWriter pw = new PrintWriter(new FileWriter(mirror, true))) {
                    pw.println("=== app start " + new Date() + " ===");
                }
            }
        } catch (Exception ignored) {
        }
        logExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ai-debug-log");
            t.setDaemon(true);
            return t;
        });
        initialized = true;
        log("=== AI Debug Log started ===");
    }

    public static void log(String message) {
        if (!BuildConfig.DEBUG || !initialized) return;
        // Log.i вместо Log.d: часть OEM-прошивок фильтрует debug-уровень
        Log.i(TAG, message);
        writeToFile(message);
    }

    public static void logToolCall(String toolName, String params, String status) {
        if (!BuildConfig.DEBUG) return;
        String msg = String.format(Locale.US,
                "[TOOL] %s | status=%s | params=%s",
                toolName, status, truncate(params, 500));
        log(msg);
    }

    public static void logToolResult(String toolName, String result, boolean success) {
        if (!BuildConfig.DEBUG) return;
        String msg = String.format(Locale.US,
                "[TOOL_RESULT] %s | success=%s | result=%s",
                toolName, success, truncate(result, 500));
        log(msg);
    }

    public static void logStreamChunk(String type, String content) {
        if (!BuildConfig.DEBUG) return;
        String msg = String.format(Locale.US,
                "[STREAM] %s | %s",
                type, truncate(content, 200));
        log(msg);
    }

    public static void logAiResponse(String content, int length) {
        if (!BuildConfig.DEBUG) return;
        String msg = String.format(Locale.US,
                "[AI_RESPONSE] len=%d | %s",
                length, truncate(content, 300));
        log(msg);
    }

    public static void logError(String context, String error) {
        if (!BuildConfig.DEBUG) return;
        String msg = String.format(Locale.US,
                "[ERROR] %s | %s",
                context, truncate(error, 500));
        log(msg);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(" + (s.length() - maxLen) + " more)";
    }

    private static void writeToFile(String message) {
        if (logExecutor == null || logFile == null) return;
        logExecutor.execute(() -> {
            try {
                // Rotate if too large
                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                    File backup = new File(logFile.getParent(), LOG_FILE + ".old");
                    if (backup.exists()) backup.delete();
                    logFile.renameTo(backup);
                }

                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date());
                String line = timestamp + " " + message;
                try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
                    pw.println(line);
                }
                // Зеркало во внешний каталог: /sdcard/Android/data/<pkg>/files/
                // — его можно вытащить через `adb pull` без run-as (SELinux
                // на части устройств блокирует доступ к internal files).
                try {
                    File extRoot = externalRoot;
                    if (extRoot != null) {
                        File mirror = new File(extRoot, LOG_FILE);
                        try (PrintWriter pw = new PrintWriter(new FileWriter(mirror, true))) {
                            pw.println(line);
                        }
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception ignored) {
                // Never crash on logging
            }
        });
    }

    /** Внешний каталог для зеркала лога (инициализируется в init). */
    private static volatile File externalRoot;
}
