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
        return getDisplayText(null);
    }

    /** Localized display text for tool status cards. */
    public String getDisplayText(android.content.Context ctx) {
        if (!isSupportedToolName(name)) {
            return ctx != null ? ctx.getString(R.string.tool_unsupported) : "Недоступный инструмент";
        }

        if ("loading".equals(status)) {
            switch (name) {
                case "get_weather":
                    return ctx != null ? ctx.getString(R.string.tool_weather_loading, params.getOrDefault("city", "...")) : "Получаю погоду: " + params.getOrDefault("city", "...") + "...";
                case "web_search":
                    return ctx != null ? ctx.getString(R.string.tool_search_loading, params.getOrDefault("query", "...")) : "Ищу: \"" + params.getOrDefault("query", "...") + "\"...";
                case "url_fetch":
                    return ctx != null ? ctx.getString(R.string.tool_fetch_loading, params.getOrDefault("url", "...")) : "Читаю: " + params.getOrDefault("url", "...") + "...";
                case "get_current_time":
                    return ctx != null ? ctx.getString(R.string.tool_time_loading, params.getOrDefault("timezone", "...")) : "Получаю время: " + params.getOrDefault("timezone", "...") + "...";
                case "save_memory":
                    return ctx != null ? ctx.getString(R.string.tool_memory_saving) : "Сохраняю воспоминание...";
                case "summarize_chat_history":
                    return ctx != null ? ctx.getString(R.string.tool_summarize_history) : "Сжимаю историю диалога отдельной AI-моделью...";
                case "summarize_past_chat":
                    return ctx != null ? ctx.getString(R.string.tool_summarize_past) : "Изучаю прошлый чат отдельной AI-моделью...";
                case "read_file":
                    return ctx != null ? ctx.getString(R.string.tool_read_file) : "Читаю файл...";
                case "write_file":
                    return ctx != null ? ctx.getString(R.string.tool_write_file) : "Записываю файл...";
                case "edit_file":
                    return ctx != null ? ctx.getString(R.string.tool_edit_file) : "Правлю файл...";
                case "delete_path":
                    return ctx != null ? ctx.getString(R.string.tool_delete_path) : "Удаляю путь...";
                case "move_path":
                    return ctx != null ? ctx.getString(R.string.tool_move_path) : "Перемещаю путь...";
                case "list_dir":
                    return ctx != null ? ctx.getString(R.string.tool_list_dir) : "Изучаю структуру проекта...";
                case "search_grep":
                    return ctx != null ? ctx.getString(R.string.tool_search_grep) : "Ищу в проекте...";
                case "find_files":
                    return ctx != null ? ctx.getString(R.string.tool_find_files) : "Ищу файлы...";
                case "execute_command":
                    return ctx != null ? ctx.getString(R.string.tool_exec_loading, params.getOrDefault("cmd", "...")) : "Выполняю: " + params.getOrDefault("cmd", "...") + "...";
                case "invoke_subagent":
                    return ctx != null ? ctx.getString(R.string.tool_subagent_loading) : "Запускаю сабагента...";
                case "project_rules":
                    return "Including project rules";
                case "enter_plan_mode":
                    return ctx != null ? ctx.getString(R.string.tool_plan_mode_enter) : "Включаю Plan Mode...";
                case "present_plan":
                    return ctx != null ? ctx.getString(R.string.tool_present_plan) : "Показываю план...";
                case "question":
                    return ctx != null ? ctx.getString(R.string.tool_question_waiting) : "Ожидаю ответ пользователя...";
                default:
                    return ctx != null ? ctx.getString(R.string.tool_generic_loading) : "Выполняю инструмент...";
            }
        }

        JSONObject result = getResult();
        if ("error".equals(status)) {
            String fallback = ctx != null ? ctx.getString(R.string.tool_error) : "Ошибка выполнения инструмента";
            return result != null ? result.optString("error", fallback) : fallback;
        }

        try {
            switch (name) {
                case "get_weather":
                    if (result != null) {
                        String city = result.optString("city", params.getOrDefault("city",
                                ctx != null ? ctx.getString(R.string.tool_city_fallback) : "Город"));
                        String temp = result.optString("temp_c", "?");
                        String weather = result.optString("weather",
                                result.optString("description", ""));
                        return city + ": " + temp + "°C, " + weather;
                    }
                    break;
                case "web_search":
                    if (result != null && result.has("results")) {
                        int n = result.getJSONArray("results").length();
                        return ctx != null ? ctx.getResources().getQuantityString(R.plurals.tool_results_found, n, n)
                                : "Найдено " + n + " результатов";
                    }
                    break;
                case "url_fetch":
                    if (result != null) {
                        String title = result.optString("title",
                                ctx != null ? ctx.getString(R.string.tool_page_fallback) : "Страница");
                        int length = result.optInt("length", 0);
                        return ctx != null ? ctx.getString(R.string.tool_fetch_done, title, length)
                                : "Загружено: " + title + " (" + length + " символов)";
                    }
                    break;
                case "get_current_time":
                    if (result != null) return result.optString("formatted",
                            result.optString("datetime", ""));
                    break;
                case "save_memory":
                    if (result != null && result.optBoolean("success", false)) {
                        return ctx != null ? ctx.getString(R.string.tool_memory_saved) : "Воспоминание сохранено";
                    }
                    break;
                case "summarize_chat_history":
                    if (result != null && result.optBoolean("skipped", false)) {
                        return ctx != null ? ctx.getString(R.string.tool_history_compact) : "История уже достаточно компактная";
                    }
                    if (result != null && result.optBoolean("success", false)) {
                        return ctx != null ? ctx.getString(R.string.tool_history_saved) : "История чата сжата и сохранена";
                    }
                    break;
                case "summarize_past_chat":
                    if (result != null && result.optBoolean("needs_selection", false)) {
                        return ctx != null ? ctx.getString(R.string.tool_chat_selection_needed) : "Нужен выбор чата";
                    }
                    if (result != null && !result.optString("chat_title", "").isEmpty()) {
                        return ctx != null ? ctx.getString(R.string.tool_past_chat_read, result.optString("chat_title", ""))
                                : "Прошлый чат изучен: «" + result.optString("chat_title", "") + "»";
                    }
                    if (result != null && result.optBoolean("success", false)) {
                        return ctx != null ? ctx.getString(R.string.tool_no_past_chats) : "Прошлые чаты не найдены";
                    }
                    break;
                case "read_file":
                    return ctx != null ? ctx.getString(R.string.tool_file_read) : "Файл прочитан";
                case "write_file":
                case "edit_file":
                    return ctx != null ? ctx.getString(R.string.tool_file_updated) : "Файл обновлён";
                case "delete_path":
                    return ctx != null ? ctx.getString(R.string.tool_path_deleted) : "Путь удалён";
                case "move_path":
                    return ctx != null ? ctx.getString(R.string.tool_path_moved) : "Путь перемещён";
                case "list_dir":
                    return result != null && result.optJSONArray("entries") != null
                            ? (ctx != null ? ctx.getString(R.string.tool_entries_found, result.optJSONArray("entries").length())
                                : "Найдено объектов: " + result.optJSONArray("entries").length())
                            : (ctx != null ? ctx.getString(R.string.tool_done) : "Действие завершено");
                case "search_grep":
                    return result != null && result.optJSONArray("matches") != null
                            ? (ctx != null ? ctx.getString(R.string.tool_matches_found, result.optJSONArray("matches").length())
                                : "Совпадений: " + result.optJSONArray("matches").length())
                            : (ctx != null ? ctx.getString(R.string.tool_done) : "Действие завершено");
                case "find_files":
                    return result != null && result.optJSONArray("files") != null
                            ? (ctx != null ? ctx.getString(R.string.tool_files_found, result.optJSONArray("files").length())
                                : "Найдено файлов: " + result.optJSONArray("files").length())
                            : (ctx != null ? ctx.getString(R.string.tool_done) : "Действие завершено");
                case "execute_command":
                    return ctx != null ? ctx.getString(R.string.tool_command_done) : "Команда завершена";
                case "invoke_subagent":
                    return ctx != null ? ctx.getString(R.string.tool_subagent_done) : "Сабагент завершил задачу";
                case "project_rules":
                    return "Including project rules";
                case "enter_plan_mode":
                    return ctx != null ? ctx.getString(R.string.tool_plan_ready) : "План готов к исследованию";
                case "present_plan":
                    return ctx != null ? ctx.getString(R.string.tool_plan_confirmed) : "План подтверждён";
                case "todo":
                    return ctx != null ? ctx.getString(R.string.tool_plan_updated) : "План обновлён";
                case "question":
                    return result != null && result.optBoolean("dismissed", false)
                            ? (ctx != null ? ctx.getString(R.string.tool_question_skipped) : "Вопрос пропущен")
                            : (ctx != null ? ctx.getString(R.string.tool_answer_received) : "Ответ получен");
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
        return ctx != null ? ctx.getString(R.string.tool_done) : "Действие завершено";
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
