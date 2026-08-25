package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

public final class SubagentSessionStore {
    private static final String PREFS = "multiai_subagent_sessions";
    private static final String KEY_INDEX = "session_index";
    private static final String KEY_PREFIX = "session_";
    private static final int MAX_SESSIONS = 40;
    private static final int MAX_EVENT_CHARS = 60_000;

    private SubagentSessionStore() { }

    public static synchronized String create(Context context, String presetId, String agentName,
                                             String task, String model, String effort) {
        String id = UUID.randomUUID().toString();
        JSONObject session = new JSONObject();
        try {
            session.put("id", id);
            session.put("preset_id", safe(presetId));
            session.put("agent_name", safe(agentName));
            session.put("task", safe(task));
            session.put("model", safe(model));
            session.put("effort", safe(effort));
            session.put("status", "running");
            session.put("created_at", System.currentTimeMillis());
            session.put("updated_at", System.currentTimeMillis());
            session.put("events", new JSONArray());
        } catch (Exception ignored) { }
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray index = readIndex(preferences);
        JSONArray next = new JSONArray();
        next.put(id);
        for (int i = 0; i < index.length() && next.length() < MAX_SESSIONS; i++) {
            String existing = index.optString(i, "");
            if (!existing.isEmpty() && !id.equals(existing)) next.put(existing);
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_PREFIX + id, session.toString())
                .putString(KEY_INDEX, next.toString());
        for (int i = MAX_SESSIONS - 1; i < index.length(); i++) {
            String stale = index.optString(i, "");
            if (!stale.isEmpty()) editor.remove(KEY_PREFIX + stale);
        }
        editor.apply();
        append(context, id, "task", "Задача", task, "success");
        return id;
    }

    public static synchronized void append(Context context, String id, String type, String title,
                                           String detail, String status) {
        JSONObject session = load(context, id);
        if (session == null) return;
        try {
            JSONArray events = session.optJSONArray("events");
            if (events == null) events = new JSONArray();
            JSONObject event = new JSONObject();
            event.put("type", safe(type));
            event.put("title", safe(title));
            event.put("detail", clip(detail));
            event.put("status", safe(status));
            event.put("timestamp", System.currentTimeMillis());
            events.put(event);
            session.put("events", events);
            session.put("updated_at", System.currentTimeMillis());
            save(context, id, session);
        } catch (Exception ignored) { }
    }

    public static synchronized void startTool(Context context, String id, String callId,
                                              String toolName, String arguments) {
        JSONObject session = load(context, id);
        if (session == null) return;
        try {
            JSONArray events = session.optJSONArray("events");
            if (events == null) events = new JSONArray();
            events.put(new JSONObject().put("type", "tool").put("call_id", safe(callId))
                    .put("tool_name", safe(toolName)).put("arguments", clip(arguments))
                    .put("status", "loading").put("timestamp", System.currentTimeMillis()));
            session.put("events", events);
            session.put("updated_at", System.currentTimeMillis());
            save(context, id, session);
        } catch (Exception ignored) { }
    }

    public static synchronized void completeTool(Context context, String id, String callId,
                                                 String result, boolean success) {
        JSONObject session = load(context, id);
        if (session == null) return;
        try {
            JSONArray events = session.optJSONArray("events");
            if (events == null) return;
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject event = events.optJSONObject(i);
                if (event != null && "tool".equals(event.optString("type"))
                        && safe(callId).equals(event.optString("call_id"))) {
                    event.put("result", clip(result));
                    event.put("status", success ? "success" : "error");
                    break;
                }
            }
            session.put("updated_at", System.currentTimeMillis());
            save(context, id, session);
        } catch (Exception ignored) { }
    }

    public static synchronized void finish(Context context, String id, String output) {
        updateStatus(context, id, "completed", output, null);
    }

    public static synchronized void fail(Context context, String id, String error) {
        updateStatus(context, id, "failed", null, error);
    }

    public static synchronized JSONObject load(Context context, String id) {
        if (context == null || id == null || id.trim().isEmpty()) return null;
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + id, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void updateStatus(Context context, String id, String status,
                                     String output, String error) {
        JSONObject session = load(context, id);
        if (session == null) return;
        try {
            session.put("status", status);
            session.put("updated_at", System.currentTimeMillis());
            if (output != null) session.put("output", clip(output));
            if (error != null) session.put("error", clip(error));
            save(context, id, session);
        } catch (Exception ignored) { }
    }

    private static void save(Context context, String id, JSONObject session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREFIX + id, session.toString()).apply();
    }

    private static JSONArray readIndex(SharedPreferences preferences) {
        try {
            return new JSONArray(preferences.getString(KEY_INDEX, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static String clip(String value) {
        String safe = safe(value);
        return safe.length() <= MAX_EVENT_CHARS ? safe
                : safe.substring(0, MAX_EVENT_CHARS) + "\n…[truncated]";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
