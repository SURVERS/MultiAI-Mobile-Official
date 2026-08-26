package app.multiai.mobile.utils;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Собирает последние строки logcat для отправки в поддержку.
 * Работает только в debug-сборке (release logcat недоступен без permission).
 */
public final class LogCollector {

    private LogCollector() {}

    /** Собрать последние {@code maxLines} строк logcat, отфильтрованные по MultiAI. */
    public static String collect(int maxLines) {
        Process process = null;
        try {
            // pid filter работает на Android 24+, на старых берём всё и фильтруем
            String pidFilter = Build.VERSION.SDK_INT >= 24
                    ? "--pid=" + android.os.Process.myPid()
                    : "";
            ProcessBuilder pb = new ProcessBuilder(
                    "logcat", "-d", "-t", String.valueOf(maxLines * 2), pidFilter);
            pb.redirectErrorStream(true);
            process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    // Выкидываем системный спам: ImeTracker и всё, что не относится к приложению.
                    if (line.contains("ImeTracker:") || line.contains("BufferQueueDump")
                            || line.contains("SurfaceFlinger") || line.contains("InputMethodManager")) {
                        continue;
                    }
                    // Только MultiAI-теги, ошибки и варнинги.
                    if (line.contains("MultiAI") || line.contains("multiai")
                            || line.contains("AndroidRuntime") || line.contains("FATAL")
                            || line.contains("OAuth") || line.contains("CloudSync")
                            || line.contains("MultiAiClient") || line.contains("ChatActivity")
                            || line.contains("ChatModeActivity")) {
                        lines.add(cleanLogLine(line));
                    }
                }
                process.waitFor();

                if (lines.isEmpty()) return "";
                StringBuilder sb = new StringBuilder();
                int start = Math.max(0, lines.size() - maxLines);
                for (int i = start; i < lines.size(); i++) {
                    if (sb.length() > 0) sb.append("\\n");
                    sb.append(lines.get(i));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.d("LogCollector", "collect_failed: " + e.getMessage());
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Собрать последние 20 строк logcat. */
    public static String collect() {
        return collect(20);
    }

    /** Обрезать строку лога, чтобы не тащить UUID, токены, большие JSON. */
    private static String cleanLogLine(String line) {
        if (line == null) return "";
        // Limit length of one line to 200 chars.
        if (line.length() > 200) {
            line = line.substring(0, 200) + "...";
        }
        return line.replace('"', '\'');
    }
}
