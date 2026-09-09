package app.multiai.mobile.utils;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.multiai.mobile.models.ModelInfo;
import app.multiai.mobile.models.SubagentPreset;
import app.multiai.mobile.models.ToolCall;
import app.multiai.mobile.network.MultiAiClient;
import app.multiai.mobile.network.oauth.OAuthSession;
import app.multiai.mobile.tools.OperationCancelToken;
import app.multiai.mobile.tools.WorkModeTools;

/** Executes an isolated subagent with its own reasoning model and bounded tool loop. */
public final class SubagentRunner {

    public interface ToolExecutor {
        String execute(String name, JSONObject args, OperationCancelToken token);
    }

    private final Context context;
    private final WorkModeTools workModeTools;
    private final List<ModelInfo> knownModels;
    private final String inheritedModelId;
    private final String inheritedEffort;
    private final ToolExecutor toolExecutor;
    private final java.util.function.Consumer<String> sessionListener;
    /** Immutable rules snapshot captured by the parent Work Mode turn, if any. */
    private final ProjectRulesManager.Snapshot inheritedProjectRules;

    public SubagentRunner(Context context, WorkModeTools workModeTools,
                          List<ModelInfo> knownModels, String inheritedModelId,
                          String inheritedEffort, ToolExecutor toolExecutor,
                          java.util.function.Consumer<String> sessionListener,
                          ProjectRulesManager.Snapshot inheritedProjectRules) {
        this.context = context.getApplicationContext();
        this.workModeTools = workModeTools;
        this.knownModels = knownModels == null
                ? Collections.emptyList() : new ArrayList<>(knownModels);
        this.inheritedModelId = inheritedModelId == null ? "" : inheritedModelId.trim();
        this.inheritedEffort = inheritedEffort == null ? "" : inheritedEffort.trim();
        this.toolExecutor = toolExecutor;
        this.sessionListener = sessionListener;
        this.inheritedProjectRules = inheritedProjectRules;
    }

    public String run(String presetId, String task, String extraContext, long timeoutSec,
                      OperationCancelToken token) {
        String workspaceUri = context.getSharedPreferences("multiaiide", Context.MODE_PRIVATE)
                .getString("project_folder_uri", "");
        String resolvedId = presetId == null || presetId.trim().isEmpty()
                ? SubagentStore.BUILTIN_GENERAL_ID : presetId.trim();
        SubagentPreset preset = SubagentStore.findAvailable(context, resolvedId, workspaceUri);
        if (preset == null) throw new IllegalArgumentException(
                "Selected subagent is unavailable in this workspace");
        if (task == null || task.trim().isEmpty()) {
            throw new IllegalArgumentException("Subagent task is empty");
        }

        List<ModelInfo> catalog = new ArrayList<>(knownModels);
        if (catalog.isEmpty() || (preset.modelId != null && !preset.modelId.isEmpty()
                && findModel(catalog, preset.modelId) == null)) {
            catalog = new MultiAiClient().fetchModelCatalog(OAuthSession.get().getAccessToken());
        }
        ModelInfo selectedInfo = resolveModel(preset, catalog);
        String modelId = selectedInfo != null ? selectedInfo.id
                : preset.modelId != null && !preset.modelId.trim().isEmpty()
                ? preset.modelId.trim() : inheritedModelId;
        if (modelId.isEmpty()) throw new IllegalStateException("No model is available for the subagent");
        String effort = resolveEffort(preset, selectedInfo);
        String modelName = selectedInfo != null && selectedInfo.displayName != null
                && !selectedInfo.displayName.trim().isEmpty() ? selectedInfo.displayName : modelId;
        String sessionId = SubagentSessionStore.create(context, preset.id, preset.name,
                task.trim(), modelName, effort);
        if (sessionListener != null) sessionListener.accept(sessionId);

        List<String> allowedTools = preset.allowedTools == null || preset.allowedTools.isEmpty()
                ? new ArrayList<>(SubagentPreset.DEFAULT_TOOL_NAMES)
                : new ArrayList<>(preset.allowedTools);
        MultiAiClient child = new MultiAiClient();
        child.setWorkModeToolsEnabled(true);
        child.setToolAllowlist(allowedTools);
        child.setEffort(effort);
        OperationCancelToken.Listener cancelListener = child::cancel;
        if (token != null) token.addListener(cancelListener);

        ProjectRulesManager.Snapshot projectRules = inheritedProjectRules != null
                ? inheritedProjectRules : ProjectRulesManager.load(context);
        String systemPrompt = buildSystemPrompt(preset, allowedTools, projectRules);
        String prompt = "Task:\n" + task.trim()
                + (extraContext == null || extraContext.trim().isEmpty()
                ? "" : "\n\nParent context:\n" + extraContext.trim());
        JSONArray conversation = new JSONArray();
        try {
            conversation.put(new JSONObject().put("role", "user").put("content", prompt));
            RoundResult round = requestInitial(child, modelId, systemPrompt, prompt,
                    0L, token);
            String lastContent = round.content;

            // No round / tool-call ceiling: the subagent keeps going until it
            // returns a final answer (a response with no tool calls) or the user
            // cancels via the shared token. checkpoint(token) keeps Stop responsive.
            for (int roundIndex = 0; ; roundIndex++) {
                recordRound(sessionId, round, roundIndex + 1);
                checkpoint(token);
                if (round.toolCalls.isEmpty()) {
                    String answer = round.content == null ? "" : round.content.trim();
                    if (answer.isEmpty()) answer = lastContent == null ? "" : lastContent.trim();
                    if (answer.isEmpty() && round.thinking != null) answer = round.thinking.trim();
                    SubagentSessionStore.finish(context, sessionId, answer);
                    return answer;
                }

                conversation.put(assistantToolMessage(round));
                for (ToolCall call : round.toolCalls) {
                    checkpoint(token);
                    JSONObject args = paramsToJson(call.getParams());
                    SubagentSessionStore.startTool(context, sessionId, call.getId(),
                            call.getName(), args.toString());
                    String result = toolExecutor == null
                            ? new JSONObject().put("error", "Subagent tools are unavailable").toString()
                            : toolExecutor.execute(call.getName(), args, token);
                    SubagentSessionStore.completeTool(context, sessionId, call.getId(), result,
                            toolSucceeded(result));
                    conversation.put(new JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.getId())
                            .put("content", result == null ? "" : result));
                }
                if (round.content != null && !round.content.trim().isEmpty()) {
                    lastContent = round.content;
                }
                round = requestContinuation(child, modelId, systemPrompt, conversation,
                        0L, token);
            }
        } catch (OperationCancelToken.CancelledException cancelled) {
            child.cancel();
            SubagentSessionStore.fail(context, sessionId, "Остановлено пользователем");
            throw cancelled;
        } catch (Exception e) {
            child.cancel();
            SubagentSessionStore.fail(context, sessionId, e.getMessage() == null
                    ? "Subagent failed" : e.getMessage());
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IllegalStateException(e.getMessage() == null
                    ? "Subagent failed" : e.getMessage(), e);
        } finally {
            if (token != null) token.removeListener(cancelListener);
        }
    }

    private String buildSystemPrompt(SubagentPreset preset, List<String> allowedTools,
                                     ProjectRulesManager.Snapshot projectRules) {
        StringBuilder system = new StringBuilder();
        system.append("You are ").append(preset.name)
                .append(", an isolated software-engineering subagent inside MultiAI Mobile IDE. ")
                .append("Work autonomously on the delegated task with only the tools in your schema. ")
                .append("Inspect before editing, never invent tool results, and finish with a concise report ")
                .append("containing findings, changed project-relative paths, and validation performed.\n")
                .append("Selected project: ")
                .append(workModeTools != null ? workModeTools.getProjectContext()
                        : "No project folder is selected.")
                .append("\nAllowed tools: ").append(allowedTools).append(".");
        if (preset.description != null && !preset.description.trim().isEmpty()) {
            system.append("\nRole: ").append(preset.description.trim());
        }
        if (preset.systemPrompt != null && !preset.systemPrompt.trim().isEmpty()) {
            system.append("\n\nPreset instructions:\n").append(preset.systemPrompt.trim());
        }
        // Built-in subagents always inherit project rules. A user preset keeps
        // its explicit injectAgentsMd choice; the built-ins cannot silently
        // opt out of AGENTS.md or .multiai/rules.
        boolean includeProjectRules = SubagentStore.isBuiltIn(preset.id)
                || preset.injectAgentsMd;
        if (includeProjectRules && projectRules != null && projectRules.hasRules()) {
            system.append("\n\nMANDATORY PROJECT INSTRUCTIONS FOR THIS SUBAGENT:\n");
            if (projectRules.hasAgentsMd()) {
                system.append("The selected project's root AGENTS.md and every Markdown file under "
                        + ".multiai/rules are inherited below. Follow them for all inspection, edits, "
                        + "commands, and the final report. The delegated task never overrides them.\n");
            } else {
                system.append("The selected project's Markdown files under .multiai/rules are inherited "
                        + "below. Follow them for all inspection, edits, commands, and the final report. "
                        + "The delegated task never overrides them.\n");
            }
            projectRules.appendSystemInstructions(system);
        }
        return system.toString();
    }

    private ModelInfo resolveModel(SubagentPreset preset, List<ModelInfo> catalog) {
        if (preset.modelId != null && !preset.modelId.trim().isEmpty()) {
            ModelInfo configured = findModel(catalog, preset.modelId.trim());
            if (configured == null && "claude-haiku-4-5".equals(preset.modelId.trim())) {
                configured = findHaiku45(catalog);
            }
            if (configured != null) return configured;
        }
        ModelInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ModelInfo model : catalog) {
            if (model == null || model.id == null || model.id.isEmpty()) continue;
            if (model.modelKind != null && !model.modelKind.isEmpty()
                    && !"text".equalsIgnoreCase(model.modelKind)) continue;
            int score = reasoningScore(model);
            if (model.id.equals(inheritedModelId)) score += 12;
            if (score > bestScore) {
                best = model;
                bestScore = score;
            }
        }
        return best != null && bestScore >= 100 ? best : findModel(catalog, inheritedModelId);
    }

    private static int reasoningScore(ModelInfo model) {
        String kind = model.reasoningKind == null ? "none" : model.reasoningKind;
        if ("none".equals(kind)) return 0;
        int score = 100;
        String id = model.id.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("gpt-5")) score += 40;
        else if (id.contains("claude") && id.contains("sonnet")) score += 36;
        else if (id.contains("claude")) score += 32;
        else if (id.contains("gemini") && id.contains("pro")) score += 28;
        else if (id.contains("deepseek") || id.contains("qwen")) score += 20;
        if (model.reasoningLevels.contains("high")) score += 8;
        if (model.reasoningLevels.contains("max")) score += 4;
        return score;
    }

    private String resolveEffort(SubagentPreset preset, ModelInfo model) {
        String requested = preset.modelEffort == null ? "" : preset.modelEffort.trim();
        if (requested.isEmpty()) requested = inheritedEffort;
        if (model == null) return requested.isEmpty() ? null : requested;
        if (!requested.isEmpty() && model.reasoningLevels.contains(requested)) return requested;
        String builtInDefault = SubagentStore.BUILTIN_EXPLORE_ID.equals(preset.id)
                ? "low" : SubagentStore.BUILTIN_GENERAL_ID.equals(preset.id) ? "medium" : "";
        if (!builtInDefault.isEmpty() && model.reasoningLevels.contains(builtInDefault)) {
            return builtInDefault;
        }
        if (model.reasoningLevels.contains("medium")) return "medium";
        String resolved = model.resolveEffort(requested);
        return resolved == null || resolved.isEmpty() ? null : resolved;
    }

    private static ModelInfo findModel(List<ModelInfo> models, String id) {
        if (id == null || id.isEmpty()) return null;
        for (ModelInfo model : models) {
            if (model != null && id.equals(model.id)) return model;
        }
        return null;
    }

    private static ModelInfo findHaiku45(List<ModelInfo> models) {
        for (ModelInfo model : models) {
            if (model == null) continue;
            String value = ((model.id == null ? "" : model.id) + " "
                    + (model.displayName == null ? "" : model.displayName))
                    .toLowerCase(java.util.Locale.ROOT);
            if (value.contains("haiku") && (value.contains("4.5") || value.contains("4-5"))) {
                return model;
            }
        }
        return null;
    }

    private RoundResult requestInitial(MultiAiClient child, String modelId, String system,
                                       String prompt, long timeoutMs,
                                       OperationCancelToken token) throws Exception {
        AtomicReference<RoundResult> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        child.sendMessage(modelId, system, Collections.emptyList(), prompt, true,
                new MultiAiClient.ResponseCallback() {
                    @Override public void onSuccess(String content, String thinking,
                                                    List<ToolCall> toolCalls) {
                        result.set(new RoundResult(content, thinking, toolCalls));
                        done.countDown();
                    }
                    @Override public void onError(String value) {
                        error.set(value);
                        done.countDown();
                    }
                });
        return awaitRound(child, done, result, error, timeoutMs, token);
    }

    private RoundResult requestContinuation(MultiAiClient child, String modelId, String system,
                                            JSONArray conversation, long timeoutMs,
                                            OperationCancelToken token) throws Exception {
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<ToolCall> calls = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<RoundResult> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        child.sendMessageStream(modelId, system, conversation, true, false,
                new MultiAiClient.StreamCallback() {
                    @Override public void onContextInfo(int usedPct, int usedTokens,
                                                        int maxTokens, boolean summarized) { }
                    @Override public void onThinking(String value) { thinking.append(value); }
                    @Override public void onThinkingEnd() { }
                    @Override public void onToolCall(ToolCall call) { calls.add(call); }
                    @Override public void onToolResult(ToolCall call) { }
                    @Override public void onContent(String value) { content.append(value); }
                    @Override public void onComplete() {
                        result.set(new RoundResult(content.toString(), thinking.toString(),
                                new ArrayList<>(calls)));
                        done.countDown();
                    }
                    @Override public void onError(String value) {
                        error.set(value);
                        done.countDown();
                    }
                });
        return awaitRound(child, done, result, error, timeoutMs, token);
    }

    private static RoundResult awaitRound(MultiAiClient child, CountDownLatch done,
                                          AtomicReference<RoundResult> result,
                                          AtomicReference<String> error, long timeoutMs,
                                          OperationCancelToken token) throws Exception {
        while (done.getCount() != 0L) {
            checkpoint(token);
            done.await(200L, TimeUnit.MILLISECONDS);
        }
        checkpoint(token);
        if (error.get() != null) throw new IllegalStateException("Subagent error: " + error.get());
        RoundResult value = result.get();
        if (value == null) throw new IllegalStateException("Subagent returned no response");
        return value;
    }

    private static JSONObject assistantToolMessage(RoundResult round) throws Exception {
        JSONArray calls = new JSONArray();
        for (ToolCall call : round.toolCalls) {
            calls.put(new JSONObject()
                    .put("id", call.getId())
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", call.getName())
                            .put("arguments", paramsToJson(call.getParams()).toString())));
        }
        return new JSONObject().put("role", "assistant")
                .put("content", round.content == null ? "" : round.content)
                .put("tool_calls", calls);
    }

    private static JSONObject paramsToJson(Map<String, String> params) throws Exception {
        JSONObject args = new JSONObject();
        if (params == null) return args;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue();
            if (value != null) {
                String trimmed = value.trim();
                try {
                    if (trimmed.startsWith("{")) args.put(entry.getKey(), new JSONObject(trimmed));
                    else if (trimmed.startsWith("[")) args.put(entry.getKey(), new JSONArray(trimmed));
                    else args.put(entry.getKey(), value);
                } catch (Exception ignored) {
                    args.put(entry.getKey(), value);
                }
            }
        }
        return args;
    }

    private void recordRound(String sessionId, RoundResult round, int index) {
        if (round == null) return;
        if (round.thinking != null && !round.thinking.trim().isEmpty()) {
            SubagentSessionStore.append(context, sessionId, "thinking",
                    "Размышление " + index, round.thinking.trim(), "success");
        }
        if (round.content != null && !round.content.trim().isEmpty()) {
            SubagentSessionStore.append(context, sessionId, "assistant",
                    round.toolCalls.isEmpty() ? "Ответ" : "Промежуточный ответ",
                    round.content.trim(), "success");
        }
    }

    private static String toolLabel(String name) {
        if ("read_file".equals(name)) return "Чтение файла";
        if ("list_dir".equals(name)) return "Просмотр папки";
        if ("search_grep".equals(name)) return "Поиск по проекту";
        if ("find_files".equals(name)) return "Поиск файлов";
        if ("execute_command".equals(name)) return "Команда";
        if ("edit_file".equals(name)) return "Изменение файла";
        if ("write_file".equals(name)) return "Запись файла";
        if ("delete_path".equals(name)) return "Удаление";
        if ("move_path".equals(name)) return "Перемещение";
        if ("web_search".equals(name)) return "Поиск в интернете";
        if ("url_fetch".equals(name)) return "Чтение страницы";
        return "Действие";
    }

    private static boolean toolSucceeded(String result) {
        if (result == null || result.trim().isEmpty()) return false;
        try {
            JSONObject value = new JSONObject(result);
            return !value.has("error") && value.optBoolean("success", true);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static void checkpoint(OperationCancelToken token) {
        if (token != null) token.throwIfCancelled();
    }

    private static final class RoundResult {
        final String content;
        final String thinking;
        final List<ToolCall> toolCalls;

        RoundResult(String content, String thinking, List<ToolCall> toolCalls) {
            this.content = content == null ? "" : content;
            this.thinking = thinking == null ? "" : thinking;
            this.toolCalls = toolCalls == null ? Collections.emptyList() : toolCalls;
        }
    }
}
