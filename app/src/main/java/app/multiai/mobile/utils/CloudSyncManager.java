package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.multiai.mobile.models.Chat;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cloud Sync Manager — syncs instructions, memories, and chats with Supabase via backend API.
 * All methods are synchronous and must be called from a background thread.
 */
public class CloudSyncManager {
    private static final String TAG = "CloudSyncManager";
    private static final String BACKEND_URL = "https://multiai.store";
    private static final String SYNC_ENDPOINT = BACKEND_URL + "/api/cloud-sync";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String OUTBOX_PREFS = "multiai_cloud_outbox";
    private static final String[] OUTBOX_TYPES = {
            "instructions", "memories", "personalization"
    };
    private static final ScheduledExecutorService OUTBOX_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "cloud-outbox");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean OUTBOX_SCHEDULED = new AtomicBoolean(false);

    private final Context context;
    private final Gson gson;
    private final OkHttpClient client;

    public CloudSyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.gson = new Gson();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        scheduleOutboxFlush(0L);
    }

    private String getAccessToken() {
        return app.multiai.mobile.network.oauth.OAuthSession.get().getAccessToken();
    }

    // ═══════════════════════════════════════════
    // INSTRUCTIONS
    // ═══════════════════════════════════════════

    /** Push local instructions to cloud */
    public boolean pushInstructions() {
        String token = getAccessToken();
        if (token == null) return false;

        AppSettings settings = new AppSettings(context);
        String instructions = settings.getUserInstructions();
        return setCloud(token, "instructions", instructions);
    }

    /** Pull instructions from cloud to local */
    public boolean pullInstructions() {
        String token = getAccessToken();
        if (token == null) return false;

        String data = getCloud(token, "instructions");
        if (data == null) return false;
        if (!looksLikeRealInstructions(data)) {
            // Служебный мусор (например «fresh-<ts>») не должен затирать локальные
            // пользовательские инструкции — пропускаем значение целиком.
            android.util.Log.w(TAG, "instructions pull skipped: placeholder-looking payload");
            return false;
        }

        AppSettings settings = new AppSettings(context);
        settings.setUserInstructions(data);
        return true;
    }

    /**
     * Эвристика «мусорного» значения инструкций: короткая строка вида
     * слово-цифры (например fresh-1786983114506) — это служебный маркер,
     * а не текст, который юзер осмысленно писал в настройках.
     */
    private static boolean looksLikeRealInstructions(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.length() >= 40) return true;
        // «fresh-1786983114506» / «abc-1234567890123»: префикс + 10+ цифр
        return !trimmed.matches("(?i)[a-z]{2,12}-\\d{10,}");
    }

    // ═══════════════════════════════════════════
    // MEMORIES
    // ═══════════════════════════════════════════

    /** Push local memories to cloud */
    public boolean pushMemories() {
        String token = getAccessToken();
        if (token == null) return false;

        return setCloud(token, "memories", MemoryStore.read(context).toString());
    }

    /** Pull memories from cloud to local. Empty/malformed cloud values become []. */
    public boolean pullMemories() {
        String token = getAccessToken();
        if (token == null) return false;

        String data = getCloud(token, "memories");
        if (data == null) return false;

        SharedPreferences prefs = context.getSharedPreferences("multiai_memories", Context.MODE_PRIVATE);
        prefs.edit().putString("memories_json", MemoryStore.normalize(data)).apply();
        return true;
    }

    // ═══════════════════════════════════════════
    // PERSONALIZATION (nickname, profession, about, tone, pers-instructions)
    // ═══════════════════════════════════════════

    /**
     * Push персонализации в облако одним JSON: псевдоним, профессия,
     * «больше о вас», тон и инструкции персонализации.
     */
    public boolean pushPersonalization() {
        String token = getAccessToken();
        if (token == null) return false;

        AppSettings settings = new AppSettings(context);
        JSONObject data = new JSONObject();
        try {
            data.put("nickname", settings.getNickname());
            data.put("profession", settings.getProfession());
            data.put("about", settings.getAbout());
            data.put("tone", settings.getPersonalizationTone());
            data.put("instructions", settings.getPersonalizationInstructions());
        } catch (Exception e) {
            return false;
        }
        return setCloud(token, "personalization", data.toString());
    }

    /**
     * Pull персонализации из облака. Пустых полей из облака не трогаем —
     * не затираем локальные значения пустотой.
     */
    public boolean pullPersonalization() {
        String token = getAccessToken();
        if (token == null) return false;

        String raw = getCloud(token, "personalization");
        if (raw == null) return false;

        try {
            JSONObject data = new JSONObject(raw);
            AppSettings settings = new AppSettings(context);
            String v;

            if (data.has("nickname")) settings.setNicknameLocal(data.optString("nickname", ""));
            if (data.has("profession")) settings.setProfessionLocal(data.optString("profession", ""));
            if (data.has("about")) settings.setAboutLocal(data.optString("about", ""));
            if (data.has("tone")) settings.setPersonalizationToneLocal(data.optString("tone", ""));
            if (data.has("instructions")) {
                String instructions = data.optString("instructions", "");
                if (looksLikeRealInstructions(instructions)) {
                    settings.setPersonalizationInstructionsLocal(instructions);
                } else {
                    // Служебный мусор («fresh-<ts>») не затирает локальные инструкции.
                    android.util.Log.w(TAG, "personalization pull: skipped placeholder-looking instructions");
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════
    // CHATS
    // ═══════════════════════════════════════════

    /** Push local chats to cloud */
    public boolean pushChats() {
        String token = getAccessToken();
        if (token == null) return false;

        ChatManager chatManager = new ChatManager(context, "multiai_chats", false);
        List<Chat> chats = chatManager.loadChats();
        String chatsJson = chatManager.wrapCloudSnapshot(gson.toJson(chats));
        return setCloud(token, "chats", chatsJson);
    }

    /** Push готового JSON-снапшота (без перечитки префсов — для дебаунса ChatManager). */
    public boolean pushChats(String chatsJson) {
        String token = getAccessToken();
        if (token == null) return false;
        String payload = new ChatManager(context, "multiai_chats", false)
                .wrapCloudSnapshot(chatsJson);
        return setCloud(token, "chats", payload);
    }

    /** Pull chats from cloud to local */
    public boolean pullChats() {
        String token = getAccessToken();
        if (token == null) return false;

        String data = getCloud(token, "chats");
        if (data == null || data.trim().isEmpty()) return false;

        try {
            ChatManager chatManager = new ChatManager(context, "multiai_chats", false);
            return chatManager.mergeCloudSnapshot(data);
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════
    // CHAT MODE CHATS (separate from project chats)
    // ═══════════════════════════════════════════

    /** Push chat-mode chats to cloud */
    public boolean pushChatModeChats() {
        String token = getAccessToken();
        if (token == null) return false;

        android.content.SharedPreferences prefs = context.getSharedPreferences("multiai_chatmode_chats", android.content.Context.MODE_PRIVATE);
        String chatsJson = prefs.getString("chats", "[]");
        ChatManager chatManager = new ChatManager(context, "multiai_chatmode_chats", false);
        return setCloud(token, "chatmode_chats", chatManager.wrapCloudSnapshot(chatsJson));
    }

    /** Push готового JSON-снапшота (без перечитки префсов — для дебаунса ChatManager). */
    public boolean pushChatModeChats(String chatsJson) {
        String token = getAccessToken();
        if (token == null) return false;
        String payload = new ChatManager(context, "multiai_chatmode_chats", false)
                .wrapCloudSnapshot(chatsJson);
        return setCloud(token, "chatmode_chats", payload);
    }

    /** Pull chat-mode chats from cloud to local */
    public boolean pullChatModeChats() {
        String token = getAccessToken();
        if (token == null) return false;

        String data = getCloud(token, "chatmode_chats");
        if (data == null || data.trim().isEmpty()) return false;

        ChatManager chatManager = new ChatManager(context, "multiai_chatmode_chats", false);
        return chatManager.mergeCloudSnapshot(data);
    }

    // ═══════════════════════════════════════════
    // FULL SYNC
    // ═══════════════════════════════════════════

    /** Pull all data from cloud (on login / app start) */
    public boolean pullAll() {
        if (getAccessToken() == null) return false;
        boolean instructions = pullInstructions();
        boolean memories = pullMemories();
        boolean chats = pullChats();
        boolean chatModeChats = pullChatModeChats();
        boolean personalization = pullPersonalization();
        return instructions || memories || chats || chatModeChats || personalization;
    }

    /** Push all local data to cloud */
    public boolean pushAll() {
        boolean a = pushInstructions();
        boolean b = pushMemories();
        boolean c = pushChats();
        boolean d = pushChatModeChats();
        boolean e = pushPersonalization();
        return a && b && c && d && e;
    }

    // ═══════════════════════════════════════════
    // LOW-LEVEL HTTP
    // ═══════════════════════════════════════════

    private String getCloud(String accessToken, String type) {
        try {
            Request request = new Request.Builder()
                    .url(SYNC_ENDPOINT + "?action=get&type=" + type)
                    .header("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    android.util.Log.w(TAG, "get " + type + " -> HTTP " + response.code());
                    return null;
                }
                String body = response.body().string();
                JSONObject json = new JSONObject(body);
                if (!json.optBoolean("ok", false)) {
                    android.util.Log.w(TAG, "get " + type + " -> ok=false");
                    return null;
                }
                android.util.Log.i(TAG, "get " + type + " -> OK");
                return json.optString(type, null);
            }
        } catch (Exception ignored) {
            android.util.Log.w(TAG, "get " + type + " failed");
            return null;
        }
    }

    private boolean setCloud(String accessToken, String type, String data) {
        boolean success = setCloudDirect(accessToken, type, data);
        if (success) {
            clearOutboxEntry(type, data);
        } else if (isDurableOutboxType(type)) {
            enqueueOutbox(type, data);
        }
        return success;
    }

    private boolean setCloudDirect(String accessToken, String type, String data) {
        try {
            JSONObject body = new JSONObject();
            body.put("action", "set");
            body.put("type", type);
            body.put("data", data);

            Request request = new Request.Builder()
                    .url(SYNC_ENDPOINT)
                    .header("Authorization", "Bearer " + accessToken)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return false;
                JSONObject result = new JSONObject(response.body().string());
                return result.optBoolean("ok", false);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDurableOutboxType(String type) {
        for (String allowed : OUTBOX_TYPES) if (allowed.equals(type)) return true;
        return false;
    }

    private void enqueueOutbox(String type, String data) {
        SharedPreferences prefs = context.getSharedPreferences(OUTBOX_PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(type + "_data", data)
                .putInt(type + "_retry", 0)
                .putLong(type + "_retry_at", 0L)
                .apply();
        scheduleOutboxFlush(1_000L);
    }

    private void clearOutboxEntry(String type, String expectedData) {
        SharedPreferences prefs = context.getSharedPreferences(OUTBOX_PREFS, Context.MODE_PRIVATE);
        if (!expectedData.equals(prefs.getString(type + "_data", null))) return;
        prefs.edit()
                .remove(type + "_data")
                .remove(type + "_retry")
                .remove(type + "_retry_at")
                .apply();
    }

    private void scheduleOutboxFlush(long delayMs) {
        if (!OUTBOX_SCHEDULED.compareAndSet(false, true)) return;
        OUTBOX_EXECUTOR.schedule(() -> {
            try {
                flushOutbox();
            } finally {
                OUTBOX_SCHEDULED.set(false);
                if (hasPendingOutbox()) scheduleOutboxFlush(nextOutboxDelay());
            }
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void flushOutbox() {
        String token = getAccessToken();
        if (token == null) return;
        SharedPreferences prefs = context.getSharedPreferences(OUTBOX_PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        for (String type : OUTBOX_TYPES) {
            String data = prefs.getString(type + "_data", null);
            if (data == null || prefs.getLong(type + "_retry_at", 0L) > now) continue;
            if (setCloudDirect(token, type, data)) {
                clearOutboxEntry(type, data);
            } else {
                int retry = Math.min(10, prefs.getInt(type + "_retry", 0) + 1);
                long delay = Math.min(60_000L, 1_000L << Math.min(6, retry - 1));
                prefs.edit()
                        .putInt(type + "_retry", retry)
                        .putLong(type + "_retry_at", now + delay)
                        .apply();
            }
        }
    }

    private boolean hasPendingOutbox() {
        SharedPreferences prefs = context.getSharedPreferences(OUTBOX_PREFS, Context.MODE_PRIVATE);
        for (String type : OUTBOX_TYPES) {
            if (prefs.contains(type + "_data")) return true;
        }
        return false;
    }

    private long nextOutboxDelay() {
        SharedPreferences prefs = context.getSharedPreferences(OUTBOX_PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long delay = 60_000L;
        for (String type : OUTBOX_TYPES) {
            if (!prefs.contains(type + "_data")) continue;
            delay = Math.min(delay,
                    Math.max(0L, prefs.getLong(type + "_retry_at", now) - now));
        }
        return delay;
    }
}
