package app.multiai.mobile.models;

import androidx.annotation.DrawableRes;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import app.multiai.mobile.R;

/** Model for a local, non-project tool call. */
public class ToolCall {
    private String id;
    private String name;
    private Map<String, String> params;
    private String status;
    private String resultJson;
    private transient boolean expanded;
    private transient boolean autoExpandApplied;
    private transient boolean exploreExpanded = false;
    private transient JSONObject parsedResult;
    private transient boolean resultParsed;
    private transient Runnable reopenAction;

    public ToolCall(String id, String name, Map<String, String> params) {
        this.id = id;
        this.name = name;
        this.params = params != null ? params : new HashMap<>();
        this.status = isSupportedToolName(name) ? "loading" : "error";
        this.resultJson = null;
        this.expanded = false;
    }

    public static boolean isSupportedToolName(String name) {
        return "get_weather".equals(name)
                || "get_current_time".equals(name)
                || "web_search".equals(name)
                || "url_fetch".equals(name)
                || "save_memory".equals(name)
                || "summarize_chat_history".equals(name)
                || "summarize_past_chat".equals(name)
                || "read_skill".equals(name)
                || "read_file".equals(name)
                || "write_file".equals(name)
                || "edit_file".equals(name)
                || "delete_path".equals(name)
                || "move_path".equals(name)
                || "list_dir".equals(name)
                || "search_grep".equals(name)
                || "find_files".equals(name)
                || "execute_command".equals(name)
                || "invoke_subagent".equals(name)
                || "project_rules".equals(name)
                || "enter_plan_mode".equals(name)
                || "present_plan".equals(name)
                || "todo".equals(name)
                || "question".equals(name);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Map<String, String> getParams() { return params; }
    public String getStatus() { return status; }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }
    public boolean isAutoExpandApplied() { return autoExpandApplied; }
    public void setAutoExpandApplied(boolean applied) { this.autoExpandApplied = applied; }
    public boolean isExploreExpanded() { return exploreExpanded; }
    public void setExploreExpanded(boolean expanded) { this.exploreExpanded = expanded; }
    public void setReopenAction(Runnable action) { this.reopenAction = action; }
    public boolean canReopen() { return reopenAction != null; }
    public void reopen() { if (reopenAction != null) reopenAction.run(); }

    public JSONObject getResult() {
        if (resultJson == null) return null;
        if (resultParsed) return parsedResult;
        try {
            parsedResult = new JSONObject(resultJson);
        } catch (Exception ignored) {
            parsedResult = null;
        }
        resultParsed = true;
        return parsedResult;
    }

    public void setStatus(String status) {
        this.status = isSupportedToolName(name) ? status : "error";
    }

    public void setResult(JSONObject result) {
        this.resultJson = result != null ? result.toString() : null;
        this.parsedResult = result;
        this.resultParsed = true;
    }

    public String getDisplayText() {
        if (!isSupportedToolName(name)) {
            return "Недоступный инструмент";
        }

        if ("loading".equals(status)) {
            switch (name) {
                case "get_weather":
                    return "Получаю погоду: " + params.getOrDefault("city", "...") + "...";
                case "web_search":
                    return "Ищу: \"" + params.getOrDefault("query", "...") + "\"...";
                case "url_fetch":
                    return "Читаю: " + params.getOrDefault("url", "...") + "...";
                case "get_current_time":
                    return "Получаю время: " + params.getOrDefault("timezone", "...") + "...";
                case "save_memory":
                    return "Сохраняю воспоминание...";
                case "summarize_chat_history":
                    return "Сжимаю историю диалога отдельной AI-моделью...";
                case "summarize_past_chat":
                    return "Изучаю прошлый чат отдельной AI-моделью...";
                case "read_file":
                    return "Читаю файл...";
                case "write_file":
                    return "Записываю файл...";
                case "edit_file":
                    return "Правлю файл...";
                case "delete_path":
                    return "Удаляю путь...";
                case "move_path":
                    return "Перемещаю путь...";
                case "list_dir":
                    return "Изучаю структуру проекта...";
                case "search_grep":
                    return "Ищу в проекте...";
                case "find_files":
                    return "Ищу файлы...";
                case "execute_command":
                    return "Выполняю: " + params.getOrDefault("cmd", "...") + "...";
                case "invoke_subagent":
                    return "Запускаю сабагента...";
                case "project_rules":
                    return "Including project rules";
                case "enter_plan_mode":
                    return "Включаю Plan Mode...";
                case "present_plan":
                    return "Показываю план...";
                case "question":
                    return "Ожидаю ответ пользователя...";
                default:
                    return "Выполняю инструмент...";
            }
        }

        JSONObject result = getResult();
        if ("error".equals(status)) {
            return result != null
                    ? result.optString("error", "Ошибка выполнения инструмента")
                    : "Ошибка выполнения инструмента";
        }

        try {
            switch (name) {
                case "get_weather":
                    if (result != null) {
                        String city = result.optString("city", params.getOrDefault("city", "Город"));
                        String temp = result.optString("temp_c", "?");
                        String weather = result.optString("weather",
                                result.optString("description", ""));
                        return city + ": " + temp + "°C, " + weather;
                    }
                    break;
                case "web_search":
                    if (result != null && result.has("results")) {
                        return "Найдено " + result.getJSONArray("results").length() + " результатов";
                    }
                    break;
                case "url_fetch":
                    if (result != null) {
                        String title = result.optString("title", "Страница");
                        int length = result.optInt("length", 0);
                        return "Загружено: " + title + " (" + length + " символов)";
                    }
                    break;
                case "get_current_time":
                    if (result != null) return result.optString("formatted",
                            result.optString("datetime", ""));
                    break;
                case "save_memory":
                    if (result != null && result.optBoolean("success", false)) {
                        return "Воспоминание сохранено";
                    }
                    break;
                case "summarize_chat_history":
                    if (result != null && result.optBoolean("skipped", false)) {
                        return "История уже достаточно компактная";
                    }
                    if (result != null && result.optBoolean("success", false)) {
                        return "История чата сжата и сохранена";
                    }
                    break;
                case "summarize_past_chat":
                    if (result != null && result.optBoolean("needs_selection", false)) {
                        return "Нужен выбор чата";
                    }
                    if (result != null && !result.optString("chat_title", "").isEmpty()) {
                        return "Прошлый чат изучен: «" + result.optString("chat_title") + "»";
                    }
                    if (result != null && result.optBoolean("success", false)) {
                        return "Прошлые чаты не найдены";
                    }
                    break;
                case "read_file":
                    return "Файл прочитан";
                case "write_file":
                case "edit_file":
                    return "Файл обновлён";
                case "delete_path":
                    return "Путь удалён";
                case "move_path":
                    return "Путь перемещён";
                case "list_dir":
                    return result != null && result.optJSONArray("entries") != null
                            ? "Найдено объектов: " + result.optJSONArray("entries").length() : "Действие завершено";
                case "search_grep":
                    return result != null && result.optJSONArray("matches") != null
                            ? "Совпадений: " + result.optJSONArray("matches").length() : "Действие завершено";
                case "find_files":
                    return result != null && result.optJSONArray("files") != null
                            ? "Найдено файлов: " + result.optJSONArray("files").length() : "Действие завершено";
                case "execute_command":
                    return "Команда завершена";
                case "invoke_subagent":
                    return "Сабагент завершил задачу";
                case "project_rules":
                    return "Including project rules";
                case "enter_plan_mode":
                    return "План готов к исследованию";
                case "present_plan":
                    return "План подтверждён";
                case "todo":
                    return "План обновлён";
                case "question":
                    return result != null && result.optBoolean("dismissed", false)
                            ? "Вопрос пропущен" : "Ответ получен";
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
        return "Действие завершено";
    }

    public String getEmoji() {
        switch (name) {
            case "get_weather": return "☁";
            case "get_current_time": return "◷";
            case "web_search": return "⌕";
            case "url_fetch": return "↗";
            case "save_memory": return "✦";
            case "summarize_chat_history":
            case "summarize_past_chat": return "≋";
            case "read_file": return "▤";
            case "write_file":
            case "edit_file": return "✎";
            case "list_dir":
            case "find_files":
            case "search_grep": return "⌕";
            case "execute_command": return "›_";
            case "invoke_subagent": return "◎";
            case "project_rules": return "▤";
            case "enter_plan_mode": return "▱";
            case "present_plan": return "✓";
            case "todo": return "✓";
            case "question": return "?";
            default: return "•";
        }
    }

    @DrawableRes
    public int getIconRes() {
        switch (name) {
            case "read_skill":
                return R.drawable.ic_skill_flask;
            case "get_weather":
                return R.drawable.ic_file_image_outline;
            case "web_search":
                return R.drawable.ic_magnify;
            case "url_fetch":
                return R.drawable.ic_open_in_new;
            case "get_current_time":
                return R.drawable.ic_magnify;
            case "search_grep":
            case "find_files":
                return R.drawable.ic_magnify;
            case "execute_command":
                return R.drawable.ic_terminal_tools;
            case "save_memory":
            case "summarize_chat_history":
            case "summarize_past_chat":
                return R.drawable.ic_memory_note_check;
            case "edit_file":
                return R.drawable.ic_edit_pencil;
            case "delete_path":
                return R.drawable.ic_trash;
            case "move_path":
                return R.drawable.ic_folder_outline;
            default:
                return R.drawable.ic_file_outline;
        }
    }
}
