package app.multiai.mobile.utils;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Файловый логгер для полевой отладки (без logcat — у юзера он не ворк).
 *
 * <p>Пишет в {@code filesDir/logs/debug.log} (внутренняя папка приложения, root не нужен —
 * приложение имеет полный доступ к своей дате). Каждая строка: "HH:mm:ss.SSS TAG: message".
 * Файл ротируется при старте процесса если больше 2 МБ. Потокобезопасен, запись в фоне.
 *
 * <p>Читать с девайса: {@code adb shell run-as app.multiai.mobile cat files/logs/debug.log}
 * (или через root: /data/data/app.multiai.mobile/files/logs/debug.log).
 */
public final class DebugFileLog {

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "debug-file-log");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static volatile File logFile;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private DebugFileLog() {}

    /** Инициализировать один раз (лениво при первом вызове тоже ок). */
    public static void init(Context ctx) {
        if (logFile != null || ctx == null) return;
        File dir = new File(ctx.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        logFile = new File(dir, "debug.log");
        // Ротация: старый лог в debug.log.prev, свежий старт.
        if (logFile.length() > MAX_BYTES) {
            File prev = new File(dir, "debug.log.prev");
            if (prev.exists()) prev.delete();
            logFile.renameTo(prev);
        }
        write("DebugFileLog", "=== session start ===");
    }

    /** Записать строку. Безопасно звать из любого потока. Пишет только в DEBUG-сборках. */
    public static void log(String tag, String message) {
        if (!app.multiai.mobile.BuildConfig.DEBUG) return;
        write(tag, message);
    }

    private static void write(String tag, String message) {
        if (logFile == null) {
            Context ctx = MultiAIAppHolder.get();
            if (ctx != null) init(ctx);
            if (logFile == null) return;
        }
        final String line = TS.format(new Date()) + " " + tag + ": " + message + "\n";
        WRITER.execute(() -> {
            try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
                out.print(line);
            } catch (Exception ignored) {}
        });
    }

    /** Lazy holder application context (чтобы не требовать Context в каждом вызове). */
    private static final class MultiAIAppHolder {
        static Context get() {
            return app.multiai.mobile.MultiAIApp.getAppContext();
        }
    }
}
