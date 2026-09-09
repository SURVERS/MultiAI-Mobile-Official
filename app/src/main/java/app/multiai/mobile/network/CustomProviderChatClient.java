package app.multiai.mobile.network;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import app.multiai.mobile.models.CustomProvider;
import app.multiai.mobile.models.Message;
import app.multiai.mobile.models.ToolCall;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Клиент для отправки chat completion запросов напрямую к кастомному
 * (пользовательскому) AI-провайдеру — минуя multiai.store.
 *
 * <p>Поддерживает два wire-формата, включая function-calling:
 * <ul>
 *   <li>{@link CustomProvider#PARADIGM_OPENAI} — OpenAI Chat Completions API
 *       ({@code POST <baseURL>/chat/completions}, tools как
 *       {@code [{type:"function", function:{name,description,parameters}}]},
 *       ответ — {@code delta.tool_calls[]}, tool result — {@code role:"tool"} +
 *       {@code tool_call_id}).</li>
 *   <li>{@link CustomProvider#PARADIGM_ANTHROPIC} — нативный Anthropic Messages API
 *       ({@code POST <baseURL>/messages}, tools как
 *       {@code [{name,description,input_schema}]}, tool result — блоки
 *       {@code {type:"tool_result", tool_use_id, content}} в user-сообщении).</li>
 * </ul>
 *
 * <p>Историю в формате OpenAI ({@code role:"tool"} + {@code tool_calls}) этот клиент
 * при необходимости конвертирует в Anthropic-блоки {@code tool_use}/{@code tool_result},
 * поэтому ChatActivity может собирать историю один раз, не зная paradigm провайдера.
 */
public final class CustomProviderChatClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /** Anthropic требует max_tokens; у нас нет точного лимита модели — берём щедрый дефолт. */
    private static final int ANTHROPIC_DEFAULT_MAX_TOKENS = 8192;
    private static final int MAX_TOOL_CALLS = 16;

    private final OkHttpClient client;
    private final Handler mainHandler;
    private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
    /** Схемы tools переиспользуем из MultiAiClient — там же живут флаги Work Mode/Memory. */
    private final MultiAiClient schemaSource;

    public CustomProviderChatClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.schemaSource = new MultiAiClient();
    }

    /** Синхронизирует доступность tools с настройками (Work Mode, память). */
    public void syncToolsFrom(MultiAiClient source) {
        if (source == null) return;
        schemaSource.setWorkModeToolsEnabled(source.isWorkModeToolsEnabled());
        schemaSource.setMemoryToolsEnabled(source.isMemoryToolsEnabled());
    }

    /** Отменяет все текущие запросы этого клиента. */
    public void cancel() {
        for (Call call : new ArrayList<>(activeCalls)) {
            if (!call.isCanceled()) call.cancel();
        }
    }

    /**
     * Отправляет сообщение выбранной модели кастомного провайдера.
     *
     * @param useTools   разрешить function-calling (Work Mode)
     * @param toolPolicy какие tools отдать (FULL или PLAN_READ_ONLY)
     */
    public void sendMessageStream(CustomProvider provider, String requestModelId,
                                  String systemPrompt, List<Message> history, String userMessage,
                                  String imageBase64, boolean useTools, boolean stream,
                                  MultiAiClient.ToolPolicy toolPolicy,
                                  MultiAiClient.StreamCallback callback) {
        if (app.multiai.mobile.utils.UpdateManager.isUnsupportedBlocked(app.multiai.mobile.MultiAIApp.APP)) {
            postError(callback, app.multiai.mobile.utils.UpdateManager.unsupportedMessage(
                    app.multiai.mobile.MultiAIApp.APP));
            return;
        }
        try {
            boolean anthropic = provider.isAnthropic();
            JSONObject body = anthropic
                    ? buildAnthropicBody(requestModelId, systemPrompt, history, userMessage,
                            imageBase64, useTools, stream, toolPolicy)
                    : buildOpenAiBody(requestModelId, systemPrompt, history, userMessage,
                            imageBase64, useTools, stream, toolPolicy);
            dispatch(provider, body, anthropic, stream, callback);
        } catch (Exception e) {
            postError(callback, "Не удалось подготовить запрос: " + safeMessage(e));
        }
    }

    /**
     * Продолжение разговора из уже собранного {@code messages} (после результата
     * инструмента). Массив приходит в OpenAI-формате (assistant.tool_calls +
     * role=tool); для Anthropic-провайдера он конвертируется в tool_use/tool_result.
     */
    public void sendMessageStream(CustomProvider provider, String requestModelId,
                                  String systemPrompt, JSONArray messages, boolean useTools,
                                  boolean stream, MultiAiClient.ToolPolicy toolPolicy,
                                  MultiAiClient.StreamCallback callback) {
        if (app.multiai.mobile.utils.UpdateManager.isUnsupportedBlocked(app.multiai.mobile.MultiAIApp.APP)) {
            postError(callback, app.multiai.mobile.utils.UpdateManager.unsupportedMessage(
                    app.multiai.mobile.MultiAIApp.APP));
            return;
        }
        try {
            boolean anthropic = provider.isAnthropic();
            JSONObject body = anthropic
                    ? buildAnthropicBodyFromArray(requestModelId, systemPrompt, messages,
                            useTools, stream, toolPolicy)
                    : buildOpenAiBodyFromArray(requestModelId, systemPrompt, messages,
                            useTools, stream, toolPolicy);
            dispatch(provider, body, anthropic, stream, callback);
        } catch (Exception e) {
            postError(callback, "Не удалось подготовить запрос: " + safeMessage(e));
        }
    }

    private void dispatch(CustomProvider provider, JSONObject body, boolean anthropic,
                          boolean stream, MultiAiClient.StreamCallback callback) {
        // Последняя линия обороны: единый обязательный гейт перед любым
        // сетевым запросом кастомного провайдера (обязательное обновление).
        if (app.multiai.mobile.utils.UpdateManager.isUnsupportedBlocked(app.multiai.mobile.MultiAIApp.APP)) {
            postError(callback, app.multiai.mobile.utils.UpdateManager.unsupportedMessage(
                    app.multiai.mobile.MultiAIApp.APP));
            return;
        }
        String url = CustomProviderClient.chatUrl(provider);
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        if (anthropic) {
            reqBuilder.header("x-api-key", safe(provider.apiKey));
            reqBuilder.header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            reqBuilder.header("Authorization", "Bearer " + safe(provider.apiKey));
        }
        Request request = reqBuilder.build();
        Call call = client.newCall(request);
        activeCalls.add(call);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call failedCall, java.io.IOException e) {
                activeCalls.remove(failedCall);
                if (failedCall.isCanceled()) return;
                postError(callback, "Ошибка сети: " + safeMessage(e));
            }

            @Override public void onResponse(Call responseCall, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        postError(callback, describeHttpError(response));
                        return;
                    }
                    readResponse(response, anthropic, stream, callback);
                } catch (Exception e) {
                    if (!responseCall.isCanceled()) {
                        postError(callback, "Ошибка чтения ответа: " + safeMessage(e));
                    }
                } finally {
                    response.close();
                    activeCalls.remove(responseCall);
                }
            }
        });
    }

    // ══════════════════ Tools schemas ══════════════════

    /** OpenAI-формат: [{type:"function", function:{name, description, parameters}}]. */
    private JSONArray openAiTools(MultiAiClient.ToolPolicy toolPolicy) {
        return schemaSource.buildToolsSchema(toolPolicy);
    }

    /** Anthropic-формат: [{name, description, input_schema}] — конвертация из OpenAI-схем. */
    private JSONArray anthropicTools(MultiAiClient.ToolPolicy toolPolicy) throws JSONException {
        JSONArray openAi = openAiTools(toolPolicy);
        JSONArray out = new JSONArray();
        for (int i = 0; i < openAi.length(); i++) {
            JSONObject t = openAi.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) continue;
            JSONObject tool = new JSONObject();
            tool.put("name", fn.optString("name", ""));
            tool.put("description", fn.optString("description", ""));
            JSONObject params = fn.optJSONObject("parameters");
            tool.put("input_schema", params != null ? params
                    : new JSONObject().put("type", "object").put("properties", new JSONObject()));
            out.put(tool);
        }
        return out;
    }

    private void putTools(JSONObject body, boolean useTools, boolean anthropic,
                          MultiAiClient.ToolPolicy toolPolicy) throws JSONException {
        if (!useTools) return;
        JSONArray tools = anthropic ? anthropicTools(toolPolicy) : openAiTools(toolPolicy);
        if (tools.length() > 0) {
            body.put("tools", tools);
            if (!anthropic) body.put("tool_choice", "auto");
        }
    }

    // ══════════════════ Body builders ══════════════════

    private JSONObject buildOpenAiBody(String modelId, String systemPrompt, List<Message> history,
                                       String userMessage, String imageBase64, boolean useTools,
                                       boolean stream, MultiAiClient.ToolPolicy toolPolicy)
            throws JSONException {
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }
        appendHistory(messages, history);
        JSONObject userMsg = new JSONObject().put("role", "user");
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", userMessage));
            for (String image : app.multiai.mobile.utils.ImageAttachments.unpack(imageBase64)) {
                parts.put(new JSONObject().put("type", "image_url").put("image_url",
                        new JSONObject().put("url", "data:image/jpeg;base64," + image)));
            }
            userMsg.put("content", parts);
        } else {
            userMsg.put("content", userMessage);
        }
        messages.put(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", modelId);
        body.put("messages", messages);
        body.put("stream", stream);
        putTools(body, useTools, false, toolPolicy);
        return body;
    }

    private JSONObject buildAnthropicBody(String modelId, String systemPrompt, List<Message> history,
                                          String userMessage, String imageBase64, boolean useTools,
                                          boolean stream, MultiAiClient.ToolPolicy toolPolicy)
            throws JSONException {
        JSONArray messages = new JSONArray();
        appendHistory(messages, history);
        JSONObject userMsg = new JSONObject().put("role", "user");
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            JSONArray blocks = new JSONArray();
            for (String image : app.multiai.mobile.utils.ImageAttachments.unpack(imageBase64)) {
                blocks.put(new JSONObject().put("type", "image").put("source",
                        new JSONObject().put("type", "base64")
                                .put("media_type", "image/jpeg").put("data", image)));
            }
            blocks.put(new JSONObject().put("type", "text").put("text", userMessage));
            userMsg.put("content", blocks);
        } else {
            userMsg.put("content", userMessage);
        }
        messages.put(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", modelId);
        body.put("max_tokens", ANTHROPIC_DEFAULT_MAX_TOKENS);
        body.put("messages", messages);
        body.put("stream", stream);
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            body.put("system", systemPrompt);
        }
        putTools(body, useTools, true, toolPolicy);
        return body;
    }

    private JSONObject buildOpenAiBodyFromArray(String modelId, String systemPrompt,
                                                JSONArray messages, boolean useTools,
                                                boolean stream, MultiAiClient.ToolPolicy toolPolicy)
            throws JSONException {
        JSONArray finalMessages = prependSystemIfMissing(messages, systemPrompt);
        JSONObject body = new JSONObject();
        body.put("model", modelId);
        body.put("messages", finalMessages);
        body.put("stream", stream);
        putTools(body, useTools, false, toolPolicy);
        return body;
    }

    private JSONObject buildAnthropicBodyFromArray(String modelId, String systemPrompt,
                                                   JSONArray messages, boolean useTools,
                                                   boolean stream, MultiAiClient.ToolPolicy toolPolicy)
            throws JSONException {
        // Anthropic не принимает role=system внутри messages — убираем его в поле system.
        // И конвертируем OpenAI-формат истории (tool_calls / role=tool) в блоки
        // tool_use / tool_result, которые ждёт Messages API.
        JSONArray cleaned = new JSONArray();
        List<JSONObject> pendingToolResults = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if ("system".equals(role)) continue;

            if ("tool".equals(role)) {
                // role=tool → блок tool_result; склеивается с соседними в одно user-сообщение.
                JSONObject block = new JSONObject();
                block.put("type", "tool_result");
                block.put("tool_use_id", m.optString("tool_call_id", ""));
                block.put("content", m.optString("content", ""));
                pendingToolResults.add(block);
                continue;
            }
            if (!pendingToolResults.isEmpty()) {
                cleaned.put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray(pendingToolResults)));
                pendingToolResults.clear();
            }

            if ("assistant".equals(role)) {
                JSONArray toolCalls = m.optJSONArray("tool_calls");
                if (toolCalls != null && toolCalls.length() > 0) {
                    // assistant.tool_calls → content-блоки tool_use
                    JSONArray blocks = new JSONArray();
                    String text = m.optString("content", "");
                    if (!text.trim().isEmpty()) {
                        blocks.put(new JSONObject().put("type", "text").put("text", text));
                    }
                    for (int j = 0; j < toolCalls.length(); j++) {
                        JSONObject tc = toolCalls.optJSONObject(j);
                        if (tc == null) continue;
                        JSONObject fn = tc.optJSONObject("function");
                        JSONObject block = new JSONObject();
                        block.put("type", "tool_use");
                        block.put("id", tc.optString("id", ""));
                        block.put("name", fn != null ? fn.optString("name", "") : "");
                        String args = fn != null ? fn.optString("arguments", "{}") : "{}";
                        try {
                            block.put("input", new JSONObject(args));
                        } catch (JSONException e) {
                            block.put("input", new JSONObject());
                        }
                        blocks.put(block);
                    }
                    cleaned.put(new JSONObject().put("role", "assistant").put("content", blocks));
                    continue;
                }
            }
            cleaned.put(m);
        }
        if (!pendingToolResults.isEmpty()) {
            cleaned.put(new JSONObject().put("role", "user")
                    .put("content", new JSONArray(pendingToolResults)));
        }

        JSONObject body = new JSONObject();
        body.put("model", modelId);
        body.put("max_tokens", ANTHROPIC_DEFAULT_MAX_TOKENS);
        body.put("messages", cleaned);
        body.put("stream", stream);
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            body.put("system", systemPrompt);
        }
        putTools(body, useTools, true, toolPolicy);
        return body;
    }

    private JSONArray prependSystemIfMissing(JSONArray messages, String systemPrompt) throws JSONException {
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) return messages;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null && "system".equals(m.optString("role"))) return messages;
        }
        JSONArray withSystem = new JSONArray();
        withSystem.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        for (int i = 0; i < messages.length(); i++) withSystem.put(messages.get(i));
        return withSystem;
    }

    /** Общая фильтрация/маппинг истории — те же правила, что в MultiAiClient. */
    private void appendHistory(JSONArray messages, List<Message> history) throws JSONException {
        if (history == null) return;
        for (Message msg : history) {
            int type = msg.getType();
            if (type == Message.TYPE_AI_TYPING || type == Message.TYPE_ERROR
                    || type == Message.TYPE_TOOL_EXECUTION || type == Message.TYPE_RECONNECTING) {
                continue;
            }
            String role = type == Message.TYPE_USER ? "user" : "assistant";
            String content = msg.getContent() != null ? msg.getContent() : "";
            if (content.trim().isEmpty()) continue;
            messages.put(new JSONObject().put("role", role).put("content", content));
        }
    }

    // ══════════════════ Response / SSE parsing ══════════════════

    /** Аккумулятор стримящихся tool calls (индекс → name/id/args кусками). */
    private static final class ToolCallAccumulator {
        final Map<Integer, StringBuilder> names = new HashMap<>();
        final Map<Integer, StringBuilder> ids = new HashMap<>();
        final Map<Integer, StringBuilder> args = new HashMap<>();
        /** Anthropic: index блока tool_use → порядковый номер tool call. */
        final Map<Integer, Integer> blockIndexToToolIndex = new HashMap<>();
        int nextToolIndex = 0;

        void append(Map<Integer, StringBuilder> map, int index, String part) {
            map.computeIfAbsent(index, k -> new StringBuilder()).append(part);
        }
    }

    private void readResponse(Response response, boolean anthropic, boolean stream,
                              MultiAiClient.StreamCallback callback) throws Exception {
        if (response.body() == null) {
            postError(callback, "Пустой ответ сервера");
            return;
        }
        ToolCallAccumulator tools = new ToolCallAccumulator();
        if (!stream) {
            String raw = response.body().string();
            JSONObject json = new JSONObject(raw);
            if (anthropic) dispatchAnthropicEvent(json, callback, tools);
            else dispatchOpenAiChunk(json, callback, tools);
            flushToolCalls(tools, callback);
            postComplete(callback);
            return;
        }
        StringBuilder eventData = new StringBuilder();
        boolean gotAnyPayload = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body().byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventData.length() > 0) {
                        String payload = eventData.toString();
                        eventData.setLength(0);
                        if ("[DONE]".equals(payload.trim())) continue;
                        try {
                            JSONObject json = new JSONObject(payload);
                            gotAnyPayload = true;
                            if (anthropic) dispatchAnthropicEvent(json, callback, tools);
                            else dispatchOpenAiChunk(json, callback, tools);
                        } catch (JSONException ignored) {
                            // Малоформатное событие — пропускаем, не рвём стрим.
                        }
                    }
                } else if (line.startsWith("data:")) {
                    String part = line.substring(5);
                    if (part.startsWith(" ")) part = part.substring(1);
                    if (eventData.length() > 0) eventData.append('\n');
                    eventData.append(part);
                }
                // "event:" / "id:" / ": comment" строки игнорируем — нам нужен только payload.
            }
        }
        // Финальное событие без завершающей пустой строки (некоторые прокси так делают).
        if (eventData.length() > 0) {
            try {
                JSONObject json = new JSONObject(eventData.toString());
                gotAnyPayload = true;
                if (anthropic) dispatchAnthropicEvent(json, callback, tools);
                else dispatchOpenAiChunk(json, callback, tools);
            } catch (JSONException ignored) {}
        }
        if (!gotAnyPayload) {
            postError(callback, "Провайдер не вернул данные (проверь модель/ключ)");
            return;
        }
        flushToolCalls(tools, callback);
        postComplete(callback);
    }

    /** Эмитит накопленные tool calls по завершении стрима (до onComplete). */
    private void flushToolCalls(ToolCallAccumulator acc, MultiAiClient.StreamCallback callback) {
        if (acc.names.isEmpty()) return;
        List<Integer> indexes = new ArrayList<>(acc.names.keySet());
        java.util.Collections.sort(indexes);
        int nonce = (int) (System.currentTimeMillis() % 100000);
        for (int index : indexes) {
            String name = acc.names.get(index).toString();
            if (name.isEmpty()) continue;
            StringBuilder idSb = acc.ids.get(index);
            String rawId = idSb != null ? idSb.toString() : "";
            String canonicalId = (rawId.isEmpty() ? "call" : rawId) + "-" + nonce + "-" + index;
            StringBuilder argsSb = acc.args.get(index);
            String args = argsSb != null ? argsSb.toString() : "";
            ToolCall toolCall = new ToolCall(canonicalId, name, jsonArgsToMap(args));
            mainHandler.post(() -> callback.onToolCall(toolCall));
        }
        acc.names.clear();
        acc.ids.clear();
        acc.args.clear();
        acc.blockIndexToToolIndex.clear();
    }

    /** Строка JSON-аргументов → Map<String,String> для ToolCall (как в MultiAiClient). */
    private static Map<String, String> jsonArgsToMap(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.trim().isEmpty()) return map;
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray names = obj.names();
            if (names == null) return map;
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                Object value = obj.opt(key);
                map.put(key, value == null || value == JSONObject.NULL ? "" : value.toString());
            }
        } catch (Exception ignored) {}
        return map;
    }

    /** optString, который НЕ возвращает строку "null" для JSON null (типичный SSE-кейс). */
    private static String optStringOrEmpty(JSONObject obj, String key) {
        Object v = obj.opt(key);
        return v == null || v == JSONObject.NULL ? "" : v.toString();
    }

    /** OpenAI-compatible SSE chunk: choices[0].delta.content / reasoning_content / tool_calls. */
    private void dispatchOpenAiChunk(JSONObject chunk, MultiAiClient.StreamCallback callback,
                                     ToolCallAccumulator tools) {
        JSONObject errorObj = chunk.optJSONObject("error");
        if (errorObj != null) {
            String message = errorObj.optString("message", errorObj.optString("type", "upstream_error"));
            postError(callback, message);
            return;
        }
        JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return;
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return;
        JSONObject payload = choice.optJSONObject("delta");
        if (payload == null) payload = choice.optJSONObject("message");
        if (payload == null) return;
        String reasoning = optStringOrEmpty(payload, "reasoning_content");
        if (reasoning.isEmpty()) reasoning = optStringOrEmpty(payload, "reasoning");
        if (!reasoning.isEmpty()) postThinking(callback, reasoning);
        String content = optStringOrEmpty(payload, "content");
        if (!content.isEmpty()) postContent(callback, content);

        JSONArray toolCalls = payload.optJSONArray("tool_calls");
        if (toolCalls != null && toolCalls.length() > 0) {
            if (tools.names.size() >= MAX_TOOL_CALLS) return;
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject tc = toolCalls.optJSONObject(i);
                if (tc == null) continue;
                int index = tc.optInt("index", i);
                JSONObject fn = tc.optJSONObject("function");
                String name = fn == null ? "" : optStringOrEmpty(fn, "name");
                String args = fn == null ? "" : optStringOrEmpty(fn, "arguments");
                String id = optStringOrEmpty(tc, "id");
                if (!name.isEmpty()) tools.append(tools.names, index, name);
                if (!id.isEmpty()) tools.append(tools.ids, index, id);
                if (!args.isEmpty()) tools.append(tools.args, index, args);
            }
        }
    }

    /** Нативный Anthropic Messages streaming event (включая tool_use блоки). */
    private void dispatchAnthropicEvent(JSONObject event, MultiAiClient.StreamCallback callback,
                                        ToolCallAccumulator tools) {
        String type = event.optString("type", "");
        if ("error".equals(type)) {
            JSONObject err = event.optJSONObject("error");
            postError(callback, err != null ? err.optString("message", "upstream_error") : "upstream_error");
            return;
        }
        switch (type) {
            case "content_block_start": {
                JSONObject block = event.optJSONObject("content_block");
                if (block == null) break;
                if ("tool_use".equals(block.optString("type", ""))) {
                    int blockIndex = event.optInt("index", 0);
                    int toolIndex = tools.nextToolIndex++;
                    tools.blockIndexToToolIndex.put(blockIndex, toolIndex);
                    String id = optStringOrEmpty(block, "id");
                    String name = optStringOrEmpty(block, "name");
                    if (!id.isEmpty()) tools.append(tools.ids, toolIndex, id);
                    if (!name.isEmpty()) tools.append(tools.names, toolIndex, name);
                }
                break;
            }
            case "content_block_delta": {
                JSONObject delta = event.optJSONObject("delta");
                if (delta == null) break;
                String deltaType = delta.optString("type", "");
                if ("thinking_delta".equals(deltaType)) {
                    String thinking = optStringOrEmpty(delta, "thinking");
                    if (!thinking.isEmpty()) postThinking(callback, thinking);
                } else if ("text_delta".equals(deltaType)) {
                    String text = optStringOrEmpty(delta, "text");
                    if (!text.isEmpty()) postContent(callback, text);
                } else if ("input_json_delta".equals(deltaType)) {
                    // Кусок JSON-аргументов tool_use
                    int blockIndex = event.optInt("index", -1);
                    Integer toolIndex = tools.blockIndexToToolIndex.get(blockIndex);
                    if (toolIndex != null) {
                        String partial = optStringOrEmpty(delta, "partial_json");
                        if (!partial.isEmpty()) tools.append(tools.args, toolIndex, partial);
                    }
                }
                break;
            }
            default:
                // message_start / message_delta / message_stop / content_block_stop —
                // не несут текста для пользователя; игнорируем.
                break;
        }
        // Не-стриминговый ответ (stream=false): content — массив блоков целиком.
        if ("message".equals(type) || event.has("content")) {
            JSONArray content = event.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block == null) continue;
                    String blockType = block.optString("type", "");
                    if ("text".equals(blockType)) {
                        String text = optStringOrEmpty(block, "text");
                        if (!text.isEmpty()) postContent(callback, text);
                    } else if ("tool_use".equals(blockType)) {
                        int toolIndex = tools.nextToolIndex++;
                        String id = optStringOrEmpty(block, "id");
                        String name = optStringOrEmpty(block, "name");
                        if (!id.isEmpty()) tools.append(tools.ids, toolIndex, id);
                        if (!name.isEmpty()) tools.append(tools.names, toolIndex, name);
                        JSONObject input = block.optJSONObject("input");
                        if (input != null) tools.append(tools.args, toolIndex, input.toString());
                    }
                }
            }
        }
    }

    // ══════════════════ callback helpers (main thread) ══════════════════

    private void postContent(MultiAiClient.StreamCallback callback, String text) {
        mainHandler.post(() -> callback.onContent(text));
    }

    private void postThinking(MultiAiClient.StreamCallback callback, String text) {
        mainHandler.post(() -> callback.onThinking(text));
    }

    private void postComplete(MultiAiClient.StreamCallback callback) {
        mainHandler.post(callback::onComplete);
    }

    private void postError(MultiAiClient.StreamCallback callback, String message) {
        mainHandler.post(() -> callback.onError(message == null ? "Неизвестная ошибка" : message));
    }

    private static String describeHttpError(Response response) {
        String body = "";
        try {
            if (response.body() != null) {
                body = response.body().string();
                if (body.length() > 500) body = body.substring(0, 500);
            }
        } catch (Exception ignored) {}
        String message = extractJsonMessage(body);
        return "HTTP " + response.code() + (message != null && !message.isEmpty() ? ": " + message : "");
    }

    private static String extractJsonMessage(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) return error.optString("message", null);
            return json.optString("message", null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
