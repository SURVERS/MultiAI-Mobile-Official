package app.multiai.mobile.models;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** A user-configured Work Mode subagent preset stored only on this device. */
public final class SubagentPreset {
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_WORKSPACE = "workspace";
    public static final String TOOLS_DEFAULT = "default";
    public static final String TOOLS_CUSTOM = "custom";

    public static final List<String> DEFAULT_TOOL_NAMES;
    static {
        List<String> names = new ArrayList<>();
        Collections.addAll(names, "read_file", "list_dir", "search_grep", "find_files");
        DEFAULT_TOOL_NAMES = Collections.unmodifiableList(names);
    }

    public String id;
    public String name;
    public String colorKey;
    public String modelId;
    public String modelName;
    public String modelEffort;
    public String scope;
    public String workspaceUri;
    public String toolsMode;
    public List<String> allowedTools;
    public String description;
    public String systemPrompt;
    public boolean injectAgentsMd;
    public long createdAt;
    public long updatedAt;

    public SubagentPreset() {
        id = UUID.randomUUID().toString();
        name = "";
        colorKey = "yellow";
        modelId = "";
        modelName = "";
        modelEffort = "";
        scope = SCOPE_USER;
        workspaceUri = "";
        toolsMode = TOOLS_DEFAULT;
        allowedTools = new ArrayList<>(DEFAULT_TOOL_NAMES);
        description = "";
        systemPrompt = "";
        injectAgentsMd = true;
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    public boolean isWorkspaceScoped() {
        return SCOPE_WORKSPACE.equals(scope) && workspaceUri != null && !workspaceUri.trim().isEmpty();
    }

    public boolean isAvailableForWorkspace(String activeWorkspaceUri) {
        return !isWorkspaceScoped() || workspaceUri.equals(activeWorkspaceUri == null ? "" : activeWorkspaceUri);
    }

    public String displayModel() {
        return modelName == null || modelName.trim().isEmpty() ? "Наследовать" : modelName.trim();
    }

    public SubagentPreset copyAsNew() {
        SubagentPreset copy = new SubagentPreset();
        copy.name = name == null || name.trim().isEmpty() ? "" : name.trim() + " copy";
        copy.colorKey = colorKey;
        copy.modelId = modelId;
        copy.modelName = modelName;
        copy.modelEffort = modelEffort;
        copy.scope = scope;
        copy.workspaceUri = workspaceUri;
        copy.toolsMode = toolsMode;
        copy.allowedTools = new ArrayList<>(allowedTools == null ? DEFAULT_TOOL_NAMES : allowedTools);
        copy.description = description;
        copy.systemPrompt = systemPrompt;
        copy.injectAgentsMd = injectAgentsMd;
        return copy;
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("name", name);
            object.put("color_key", colorKey);
            object.put("model_id", modelId);
            object.put("model_name", modelName);
            object.put("model_effort", modelEffort);
            object.put("scope", scope);
            object.put("workspace_uri", workspaceUri);
            object.put("tools_mode", toolsMode);
            JSONArray tools = new JSONArray();
            for (String tool : allowedTools == null ? Collections.<String>emptyList() : allowedTools) {
                if (tool != null && !tool.trim().isEmpty()) tools.put(tool);
            }
            object.put("allowed_tools", tools);
            object.put("description", description);
            object.put("system_prompt", systemPrompt);
            object.put("inject_agents_md", injectAgentsMd);
            object.put("created_at", createdAt);
            object.put("updated_at", updatedAt);
        } catch (Exception ignored) {
        }
        return object;
    }

    public static SubagentPreset fromJson(JSONObject object) {
        if (object == null) return null;
        SubagentPreset preset = new SubagentPreset();
        preset.id = nonEmpty(object.optString("id", ""), preset.id);
        preset.name = object.optString("name", "").trim();
        preset.colorKey = nonEmpty(object.optString("color_key", ""), "gold");
        preset.modelId = object.optString("model_id", "").trim();
        preset.modelName = object.optString("model_name", "").trim();
        preset.modelEffort = object.optString("model_effort", "").trim();
        preset.scope = SCOPE_WORKSPACE.equals(object.optString("scope", ""))
                ? SCOPE_WORKSPACE : SCOPE_USER;
        preset.workspaceUri = object.optString("workspace_uri", "").trim();
        preset.toolsMode = TOOLS_CUSTOM.equals(object.optString("tools_mode", ""))
                ? TOOLS_CUSTOM : TOOLS_DEFAULT;
        preset.allowedTools.clear();
        JSONArray tools = object.optJSONArray("allowed_tools");
        if (tools != null) {
            for (int i = 0; i < tools.length(); i++) {
                String tool = tools.optString(i, "").trim();
                if (!tool.isEmpty() && !preset.allowedTools.contains(tool)) preset.allowedTools.add(tool);
            }
        }
        if (preset.allowedTools.isEmpty()) preset.allowedTools.addAll(DEFAULT_TOOL_NAMES);
        preset.description = object.optString("description", "").trim();
        preset.systemPrompt = object.optString("system_prompt", "").trim();
        preset.injectAgentsMd = object.optBoolean("inject_agents_md", true);
        preset.createdAt = object.optLong("created_at", System.currentTimeMillis());
        preset.updatedAt = object.optLong("updated_at", preset.createdAt);
        return preset;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
