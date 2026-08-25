package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Resilient local storage for AI memories. */
public final class MemoryStore {
    private static final String PREFS = "multiai_memories";
    private static final String KEY = "memories_json";

    private MemoryStore() {}

    public static synchronized JSONArray read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return parse(prefs.getString(KEY, "[]"));
    }

    public static synchronized JSONObject append(Context context, String name, String content)
            throws Exception {
        JSONArray memories = read(context);
        JSONObject memory = new JSONObject()
                .put("name", name)
                .put("content", content)
                .put("timestamp", System.currentTimeMillis());
        memories.put(memory);
        write(context, memories);
        return memory;
    }

    public static synchronized void write(Context context, JSONArray memories) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, memories != null ? memories.toString() : "[]").apply();
    }

    public static String normalize(String raw) {
        return parse(raw).toString();
    }

    private static JSONArray parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            FileLog.d("MemoryStore", "memories_json_invalid");
            return new JSONArray();
        }
    }
}