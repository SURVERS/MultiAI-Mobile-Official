package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import app.multiai.mobile.models.SubagentPreset;

/** Local persistence and workspace filtering for user-created subagent presets. */
public final class SubagentStore {
    public static final String BUILTIN_GENERAL_ID = "builtin:general-purpose";
    public static final String BUILTIN_EXPLORE_ID = "builtin:explore";

    private static final String PREFS = "multiai_subagents";
    private static final String KEY_PRESETS = "presets_json";
    private static final String KEY_BUILTIN_GENERAL_MODEL_ID = "builtin_general_model_id";
    private static final String KEY_BUILTIN_GENERAL_MODEL_NAME = "builtin_general_model_name";
    private static final String KEY_BUILTIN_GENERAL_EFFORT = "builtin_general_effort";
    private static final String KEY_BUILTIN_EXPLORE_MODEL_ID = "builtin_explore_model_id";
    private static final String KEY_BUILTIN_EXPLORE_MODEL_NAME = "builtin_explore_model_name";
    private static final String KEY_BUILTIN_EXPLORE_EFFORT = "builtin_explore_effort";
    private static final int SCHEMA_VERSION = 1;

    private SubagentStore() {
    }

    public static synchronized List<SubagentPreset> getAll(Context context) {
        List<SubagentPreset> presets = new ArrayList<>();
        if (context == null) return presets;
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PRESETS, "{\"schema_version\":1,\"presets\":[]}");
            JSONObject root = new JSONObject(raw);
            JSONArray values = root.optJSONArray("presets");
            if (values != null) {
                for (int i = 0; i < values.length(); i++) {
                    SubagentPreset preset = SubagentPreset.fromJson(values.optJSONObject(i));
                    if (preset != null && !preset.name.isEmpty()) presets.add(preset);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(presets, new Comparator<SubagentPreset>() {
            @Override public int compare(SubagentPreset first, SubagentPreset second) {
                return Long.compare(second.updatedAt, first.updatedAt);
            }
        });
        return presets;
    }

    public static List<SubagentPreset> builtIns(Context context) {
        List<SubagentPreset> values = new ArrayList<>();

        SubagentPreset general = new SubagentPreset();
        general.id = BUILTIN_GENERAL_ID;
        general.name = context.getString(app.multiai.mobile.R.string.subagents_general_name);
        general.description = context.getString(app.multiai.mobile.R.string.subagents_general_desc);
        general.colorKey = "yellow";
        general.modelId = "claude-haiku-4-5";
        general.modelName = "Claude Haiku 4.5";
        general.modelEffort = "medium";
        general.toolsMode = SubagentPreset.TOOLS_CUSTOM;
        general.allowedTools.clear();
        Collections.addAll(general.allowedTools,
                "read_file", "list_dir", "search_grep", "find_files",
                "execute_command", "edit_file", "write_file", "delete_path", "move_path",
                "web_search", "url_fetch");
        general.systemPrompt = "Act as an autonomous general-purpose software engineering agent. "
                + "Inspect the project before changing it, use the available tools, validate your work, "
                + "and return a concise report with changed paths and checks.";
        values.add(general);

        SubagentPreset explore = new SubagentPreset();
        explore.id = BUILTIN_EXPLORE_ID;
        explore.name = context.getString(app.multiai.mobile.R.string.subagents_explore_name);
        explore.description = context.getString(app.multiai.mobile.R.string.subagents_explore_desc);
        explore.colorKey = "blue";
        explore.modelId = "claude-haiku-4-5";
        explore.modelName = "Claude Haiku 4.5";
        explore.modelEffort = "low";
        explore.toolsMode = SubagentPreset.TOOLS_CUSTOM;
        explore.allowedTools.clear();
        Collections.addAll(explore.allowedTools,
                "read_file", "list_dir", "search_grep", "find_files");
        explore.systemPrompt = "Explore the selected codebase deeply but remain strictly read-only. "
                + "Trace relevant code paths, cite project-relative files, and return concrete findings.";
        values.add(explore);

        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        applyBuiltInRuntime(preferences, general, KEY_BUILTIN_GENERAL_MODEL_ID,
                KEY_BUILTIN_GENERAL_MODEL_NAME, KEY_BUILTIN_GENERAL_EFFORT);
        applyBuiltInRuntime(preferences, explore, KEY_BUILTIN_EXPLORE_MODEL_ID,
                KEY_BUILTIN_EXPLORE_MODEL_NAME, KEY_BUILTIN_EXPLORE_EFFORT);
        return values;
    }

    public static synchronized void saveBuiltInRuntime(Context context, SubagentPreset preset) {
        if (context == null || preset == null || !isBuiltIn(preset.id)) return;
        boolean general = BUILTIN_GENERAL_ID.equals(preset.id);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(general ? KEY_BUILTIN_GENERAL_MODEL_ID : KEY_BUILTIN_EXPLORE_MODEL_ID,
                        preset.modelId == null ? "" : preset.modelId)
                .putString(general ? KEY_BUILTIN_GENERAL_MODEL_NAME : KEY_BUILTIN_EXPLORE_MODEL_NAME,
                        preset.modelName == null ? "" : preset.modelName)
                .putString(general ? KEY_BUILTIN_GENERAL_EFFORT : KEY_BUILTIN_EXPLORE_EFFORT,
                        preset.modelEffort == null ? "" : preset.modelEffort)
                .apply();
    }

    private static void applyBuiltInRuntime(SharedPreferences preferences, SubagentPreset preset,
                                            String modelIdKey, String modelNameKey, String effortKey) {
        preset.modelId = preferences.getString(modelIdKey, preset.modelId);
        preset.modelName = preferences.getString(modelNameKey, preset.modelName);
        preset.modelEffort = preferences.getString(effortKey, preset.modelEffort);
    }

    public static boolean isBuiltIn(String id) {
        return BUILTIN_GENERAL_ID.equals(id) || BUILTIN_EXPLORE_ID.equals(id);
    }

    public static SubagentPreset findBuiltIn(Context context, String id) {
        for (SubagentPreset preset : builtIns(context)) {
            if (preset.id.equals(id)) return preset;
        }
        return null;
    }

    public static synchronized List<SubagentPreset> availableForWorkspace(Context context,
                                                                            String workspaceUri) {
        List<SubagentPreset> available = new ArrayList<>(builtIns(context));
        for (SubagentPreset preset : getAll(context)) {
            if (preset.isAvailableForWorkspace(workspaceUri)) available.add(preset);
        }
        return available;
    }

    public static synchronized SubagentPreset find(Context context, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        SubagentPreset builtIn = findBuiltIn(context, id);
        if (builtIn != null) return builtIn;
        for (SubagentPreset preset : getAll(context)) {
            if (id.equals(preset.id)) return preset;
        }
        return null;
    }

    public static synchronized SubagentPreset findAvailable(Context context, String id,
                                                              String workspaceUri) {
        if (id == null || id.trim().isEmpty()) return null;
        SubagentPreset builtIn = findBuiltIn(context, id);
        if (builtIn != null) return builtIn;
        for (SubagentPreset preset : getAll(context)) {
            if (id.equals(preset.id) && preset.isAvailableForWorkspace(workspaceUri)) return preset;
        }
        return null;
    }

    public static synchronized void save(Context context, SubagentPreset preset) {
        if (context == null || preset == null || preset.name == null || preset.name.trim().isEmpty()) return;
        List<SubagentPreset> values = getAll(context);
        boolean replaced = false;
        for (int i = 0; i < values.size(); i++) {
            if (preset.id.equals(values.get(i).id)) {
                preset.createdAt = values.get(i).createdAt;
                values.set(i, preset);
                replaced = true;
                break;
            }
        }
        if (!replaced) values.add(preset);
        preset.updatedAt = System.currentTimeMillis();
        write(context, values);
    }

    public static synchronized void delete(Context context, String id) {
        if (context == null || id == null || id.trim().isEmpty()) return;
        List<SubagentPreset> values = getAll(context);
        for (int i = values.size() - 1; i >= 0; i--) {
            if (id.equals(values.get(i).id)) values.remove(i);
        }
        write(context, values);
    }

    private static void write(Context context, List<SubagentPreset> presets) {
        JSONArray values = new JSONArray();
        for (SubagentPreset preset : presets) values.put(preset.toJson());
        JSONObject root = new JSONObject();
        try {
            root.put("schema_version", SCHEMA_VERSION);
            root.put("presets", values);
        } catch (Exception ignored) {
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_PRESETS, root.toString()).apply();
    }
}
