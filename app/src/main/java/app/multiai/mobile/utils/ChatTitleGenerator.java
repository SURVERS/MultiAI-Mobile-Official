package app.multiai.mobile.utils;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import app.multiai.mobile.models.Chat;
import app.multiai.mobile.models.Message;
import app.multiai.mobile.network.oauth.OAuthSession;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Generates concise chat titles via the MultiAI API (free model, OAuth token). */
public final class ChatTitleGenerator {

    public interface CurrentChatProvider {
        Chat getCurrentChat();
    }

    private ChatTitleGenerator() {}

    public static void generate(Activity host, Chat requestedChat, ChatManager manager,
                                CurrentChatProvider provider, Runnable onUpdated) {
        if (host == null || requestedChat == null || manager == null
                || !requestedChat.isTitleAuto()) return; // титул уже задан — не трогаем
        String user = null;
        String assistant = null;
        for (Message message : requestedChat.getMessages()) {
            if (message.getType() == Message.TYPE_USER && user == null) user = message.getContent();
            else if (message.getType() == Message.TYPE_AI && assistant == null) assistant = message.getContent();
            if (user != null && assistant != null) break;
        }
        if (user == null || user.trim().isEmpty()) return;
        final String userText = user;
        final String aiText = assistant;
        final String chatId = requestedChat.getId();
        new Thread(() -> {
            String generated = null;
            try {
                String accessToken = OAuthSession.get().getAccessToken();
                if (accessToken != null && !accessToken.isEmpty()) {
                    JSONObject body = new JSONObject();
                    body.put("model", "big-pickle"); // бесплатная модель каталога
                    JSONArray messages = new JSONArray();
                    messages.put(new JSONObject()
                            .put("role", "system")
                            .put("content", "Generate a very short chat title (2-5 words, no quotes, no period) in the SAME language as the user message. Just output the title, nothing else."));
                    String prompt = "User: " + userText;
                    if (aiText != null && !aiText.trim().isEmpty()) {
                        prompt += "\nAssistant: " + aiText.substring(0, Math.min(200, aiText.length()));
                    }
                    messages.put(new JSONObject().put("role", "user").put("content", prompt));
                    body.put("messages", messages);
                    body.put("max_tokens", 20);
                    body.put("temperature", 0.3);
                    Request request = new Request.Builder()
                            .url("https://multiai.store/v1/chat/completions")
                            .addHeader("Authorization", "Bearer " + accessToken)
                            .addHeader("Content-Type", "application/json")
                            .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                            .build();
                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .build();
                    try (Response response = client.newCall(request).execute()) {
                        String responseBody = response.body() == null ? "" : response.body().string();
                        if (response.isSuccessful() && !responseBody.isEmpty()) {
                            generated = new JSONObject(responseBody).getJSONArray("choices")
                                    .getJSONObject(0).getJSONObject("message")
                                    .getString("content").trim()
                                    .replaceAll("^[\"'`]+|[\"'`]+$", "").trim();
                            if (generated.length() > 40) generated = generated.substring(0, 40);
                        }
                    }
                }
            } catch (Exception error) {
            }
            final String title = generated;
            host.runOnUiThread(() -> {
                Chat current = provider == null ? requestedChat : provider.getCurrentChat();
                if (current == null || !chatId.equals(current.getId())
                        || !current.isTitleAuto()) return; // уже переименован — не затираем
                if (title != null && !title.isEmpty()) current.setTitle(title);
                else current.updateTitleFromFirstMessage();
                manager.updateChat(current);
                if (onUpdated != null) onUpdated.run();
            });
        }).start();
    }
}
