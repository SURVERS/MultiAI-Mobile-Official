package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.multiai.mobile.models.Chat;
import app.multiai.mobile.models.ToolCall;

/**
 * Менеджер для сохранения и загрузки чатов
 */
public class ChatManager {
    private static final String PREFS_NAME = "multiai_chats";
    private static final String KEY_CHATS = "chats";
    private static final String KEY_CHATS_BACKUP = "chats_backup";
    private static final String KEY_ACTIVE_CHAT_ID = "active_chat_id";
    private static final String KEY_TOMBSTONES = "chat_tombstones";
    private static final String KEY_CLOUD_REVISION = "cloud_revision";
    private static final String KEY_CLOUD_OUTBOX = "cloud_outbox";
    private static final String KEY_CLOUD_RETRY = "cloud_retry";
    private static final String KEY_CLOUD_RETRY_AT = "cloud_retry_at";
    private static final int MAX_CLOUD_ATTACHMENT_BYTES = 1_500_000;
    private static final int MAX_CLOUD_ATTACHMENTS_TOTAL_BYTES = 3_000_000;
    private static final java.util.concurrent.ScheduledExecutorService SYNC_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chats-cloud-sync");
                t.setDaemon(true);
                return t;
            });

    private final SharedPreferences prefs;
    private final Gson gson;
    private final Context context;
    private final String prefsName;
    // Durable storage stays SharedPreferences for compatibility; this snapshot
    // avoids reparsing the complete history during every UI refresh.
    private volatile String cachedChatsJson;
    private volatile List<Chat> cachedChats;

    public ChatManager(Context context) {
        this(context, PREFS_NAME, true);
    }

    public ChatManager(Context context, String prefsName) {
        this(context, prefsName, true);
    }

    ChatManager(Context context, String prefsName, boolean resumeOutbox) {
        this.context = context.getApplicationContext();
        this.prefsName = prefsName;
        this.prefs = this.context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        this.gson = new GsonBuilder()
            .registerTypeAdapter(ToolCall.class, new ToolCallTypeAdapter())
            // Тяжёлые поля не сериализуем (SharedPrefs не резиновый), но и НЕ
            // мутируем живые объекты адаптера — исключаем на уровне Gson.
            .addSerializationExclusionStrategy(new com.google.gson.ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(com.google.gson.FieldAttributes f) {
                    String n = f.getName();
                    return "imageBase64".equals(n) || "attachedFileContent".equals(n);
                }
                @Override
                public boolean shouldSkipClass(Class<?> c) {
                    return false;
                }
            })
            .create();
        if (resumeOutbox) resumeCloudOutbox();
    }
    
    /**
     * Custom TypeAdapter for ToolCall to handle both old (result: JSONObject) 
     * and new (resultJson: String) serialization formats.
     */
    private static class ToolCallTypeAdapter extends TypeAdapter<ToolCall> {
        @Override
        public void write(JsonWriter out, ToolCall tc) throws IOException {
            if (tc == null) { out.nullValue(); return; }
            out.beginObject();
            out.name("id").value(tc.getId());
            out.name("name").value(tc.getName());
            out.name("status").value(tc.getStatus());
            // Write params
            out.name("params");
            if (tc.getParams() != null) {
                out.beginObject();
                for (Map.Entry<String, String> e : tc.getParams().entrySet()) {
                    out.name(e.getKey()).value(e.getValue());
                }
                out.endObject();
            } else {
                out.nullValue();
            }
            // Write result as string
            org.json.JSONObject result = tc.getResult();
            out.name("resultJson").value(result != null ? result.toString() : null);
            out.endObject();
        }
        
        @Override
        public ToolCall read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            
            String id = null, name = null, status = null, resultJson = null;
            Map<String, String> params = new HashMap<>();
            
            in.beginObject();
            while (in.hasNext()) {
                String fieldName = in.nextName();
                if (in.peek() == JsonToken.NULL) { in.nextNull(); continue; }
                switch (fieldName) {
                    case "id": id = in.nextString(); break;
                    case "name": name = in.nextString(); break;
                    case "status": status = in.nextString(); break;
                    case "resultJson":
                        resultJson = in.nextString();
                        break;
                    case "result":
                        // Old format: result was JSONObject, skip gracefully
                        resultJson = readJsonValueAsString(in);
                        break;
                    case "params":
                        if (in.peek() == JsonToken.BEGIN_OBJECT) {
                            in.beginObject();
                            while (in.hasNext()) {
                                String key = in.nextName();
                                if (in.peek() == JsonToken.NULL) { in.nextNull(); continue; }
                                params.put(key, in.nextString());
                            }
                            in.endObject();
                        } else {
                            in.skipValue();
                        }
                        break;
                    default:
                        in.skipValue();
                        break;
                }
            }
            in.endObject();
            
            ToolCall tc = new ToolCall(id, name, params);
            if (status != null) tc.setStatus(status);
            if (resultJson != null) {
                try {
                    tc.setResult(new org.json.JSONObject(resultJson));
                } catch (Exception ignored) {}
            }
            return tc;
        }
        
        /** Read any JSON value and return it as a string (for old result: {} format) */
        private String readJsonValueAsString(JsonReader in) throws IOException {
            switch (in.peek()) {
                case STRING: return in.nextString();
                case BEGIN_OBJECT:
                    // Read the whole object and convert to string
                    StringBuilder sb = new StringBuilder();
                    sb.append("{");
                    in.beginObject();
                    boolean first = true;
                    while (in.hasNext()) {
                        if (!first) sb.append(",");
                        first = false;
                        String key = in.nextName();
                        // Handle "nameValuePairs" from old JSONObject serialization
                        if ("nameValuePairs".equals(key)) {
                            // This is the actual data, read it recursively
                            String inner = readJsonValueAsString(in);
                            in.endObject();
                            return inner;
                        }
                        sb.append("\"").append(key).append("\":");
                        sb.append(readJsonValueAsString(in));
                    }
                    in.endObject();
                    sb.append("}");
                    return sb.toString();
                case BEGIN_ARRAY:
                    StringBuilder arr = new StringBuilder("[");
                    in.beginArray();
                    boolean arrFirst = true;
                    while (in.hasNext()) {
                        if (!arrFirst) arr.append(",");
                        arrFirst = false;
                        arr.append(readJsonValueAsString(in));
                    }
                    in.endArray();
                    arr.append("]");
                    return arr.toString();
                case NUMBER: return String.valueOf(in.nextDouble());
                case BOOLEAN: return String.valueOf(in.nextBoolean());
                case NULL: in.nextNull(); return null;
                default: in.skipValue(); return null;
            }
        }
    }

    /**
     * Сохранить список чатов.
     *
     * <p>Перед сериализацией тяжёлые вложения (base64 картинок, содержимое
     * файлов) выгружаются на диск в {@code filesDir/attachments/}, а в JSON
     * остаются только пути — вложения переживают рестарт и не рвут prefs.
     */
    public void saveChats(List<Chat> chats) {
        persistAttachmentsToFiles(chats);
        String json = gson.toJson(chats);
        persistChatsJson(json);
        syncToCloud();
    }

    private synchronized void persistChatsJson(String json) {
        String previous = prefs.getString(KEY_CHATS, null);
        SharedPreferences.Editor editor = prefs.edit();
        if (previous != null && !previous.trim().isEmpty()) editor.putString(KEY_CHATS_BACKUP, previous);
        String safe = json == null ? "[]" : json;
        editor.putString(KEY_CHATS, safe).apply();
        cachedChatsJson = safe;
        try {
            Type type = new TypeToken<List<Chat>>(){}.getType();
            cachedChats = gson.fromJson(safe, type);
        } catch (Exception ignored) {
            cachedChats = null;
        }
    }

    /** Выгрузить base64/текстовые вложения в файлы, оставив в Message путь. */
    private void persistAttachmentsToFiles(List<Chat> chats) {
        try {
            java.io.File dir = new java.io.File(context.getFilesDir(), "attachments");
            if (!dir.exists()) dir.mkdirs();
            for (Chat c : chats) {
                for (app.multiai.mobile.models.Message m : c.getMessages()) {
                    if (m.hasImage()) m.ensureImageAttachmentId();
                    if (m.hasAttachedFile()) m.ensureFileAttachmentId();
                    if (m.getImageBase64() != null && !m.getImageBase64().isEmpty()) {
                        java.io.File f = new java.io.File(dir,
                                m.ensureImageAttachmentId() + "_" + sanitizeFileName(
                                        m.getImageName() != null ? m.getImageName() : "image.jpg"));
                        String payload = m.getImageBase64();
                        java.util.List<String> images = ImageAttachments.unpack(payload);
                        if (images.size() > 1) {
                            java.io.File bundle = new java.io.File(dir, m.ensureImageAttachmentId() + ".images.json");
                            java.nio.file.Files.write(bundle.toPath(), payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            m.setImageBundlePath(bundle.getAbsolutePath());
                        }
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                            String first = images.get(0);
                            int comma = first.indexOf(','); // strip data URI prefix if present
                            fos.write(android.util.Base64.decode(comma >= 0 ? first.substring(comma + 1) : first,
                                    android.util.Base64.DEFAULT));
                            m.setImagePath(f.getAbsolutePath());
                            m.setImageBase64(null); // рендер читает из пути
                        } catch (Exception ignored) {
                        }
                    }
                    if (m.getAttachedFileContent() != null && !m.getAttachedFileContent().isEmpty()) {
                        java.io.File f = new java.io.File(dir,
                                m.ensureFileAttachmentId() + "_" + sanitizeFileName(
                                        m.getAttachedFileName() != null ? m.getAttachedFileName() : "file.txt"));
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                            fos.write(m.getAttachedFileContent().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            m.setAttachedFilePath(f.getAbsolutePath());
                            m.setAttachedFileContent(null); // рендер читает из пути
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Загрузить список чатов
     */
    public synchronized List<Chat> loadChats() {
        String json = prefs.getString(KEY_CHATS, null);
        if (json == null) {
            cachedChatsJson = null;
            cachedChats = new ArrayList<>();
            return new ArrayList<>();
        }
        if (json.equals(cachedChatsJson) && cachedChats != null) return cachedChats;

        Type type = new TypeToken<List<Chat>>(){}.getType();
        try {
            List<Chat> chats = gson.fromJson(json, type);
            if (chats == null) {
                cachedChatsJson = json;
                cachedChats = new ArrayList<>();
                return new ArrayList<>();
            }
            sortPinnedFirst(chats);
            cachedChatsJson = json;
            cachedChats = chats;
            return chats;
        } catch (Exception e) {
            // Restore the last known-good snapshot before returning. This keeps
            // the next save from silently destroying the user's history.
            String backup = prefs.getString(KEY_CHATS_BACKUP, null);
            if (backup != null) {
                try {
                    List<Chat> restored = gson.fromJson(backup, type);
                    if (restored != null) {
                        persistChatsJson(backup);
                        sortPinnedFirst(restored);
                        cachedChatsJson = backup;
                        cachedChats = restored;
                        return restored;
                    }
                } catch (Exception ignored) {
                }
            }
            return new ArrayList<>();
        }
    }

    /**
     * Сохранить ID активного чата
     */
    public void saveActiveChatId(String chatId) {
        prefs.edit().putString(KEY_ACTIVE_CHAT_ID, chatId).apply();
    }

    /**
     * Загрузить ID активного чата
     */
    public String loadActiveChatId() {
        return prefs.getString(KEY_ACTIVE_CHAT_ID, null);
    }

    /**
     * Добавить новый чат
     */
    public Chat createNewChat() {
        List<Chat> chats = loadChats();
        Chat newChat = new Chat();
        chats.add(0, newChat); // Добавляем в начало
        saveChats(chats);
        saveActiveChatId(newChat.getId());
        return newChat;
    }

    /**
     * Удалить чат
     */
    public void deleteChat(String chatId) {
        List<Chat> chats = loadChats();
        Chat removedChat = null;
        for (Chat chat : chats) if (chat != null && chatId != null && chatId.equals(chat.getId())) removedChat = chat;
        chats.removeIf(chat -> chat.getId().equals(chatId));
        if (removedChat != null) deleteAttachments(java.util.Collections.singletonList(removedChat));
        recordTombstone(chatId, System.currentTimeMillis());
        String json = gson.toJson(chats);
        persistChatsJson(json);
        
        // Если удалили активный чат, сбрасываем
        if (chatId.equals(loadActiveChatId())) {
            if (!chats.isEmpty()) {
                saveActiveChatId(chats.get(0).getId());
            } else {
                saveActiveChatId(null);
            }
        }
        syncToCloud();
    }

    /**
     * Обновить чат.
     *
     * Пустой чат (без единого сообщения) не сохраняется — чат попадает
     * в историю только с первым сообщением (ленивое создание). Это касается
     * обоих режимов: пустой «Новый чат» не должен мусорить в списке.
     * Исключение — чат уже есть в хранилище (update существующего).
     */
    public void updateChat(Chat updatedChat) {
        updateChatInternal(updatedChat, true);
    }

    /**
     * Лёгкий persist во время стриминга: НЕ выгружает вложения на диск и НЕ
     * гоняет облачный снапшот (оба — тяжёлые base64/IO на каждый чанк).
     * Только обновляет JSON в prefs. Вызывать из фонового потока.
     * Тяжёлый updateChat вызывается по завершении хода (onComplete/onError).
     */
    public void updateChatLight(Chat updatedChat) {
        updateChatInternal(updatedChat, false);
    }

    private void updateChatInternal(Chat updatedChat, boolean heavy) {
        if (updatedChat == null) return;
        // A deleted UUID is never reusable. This also blocks a stale Activity
        // instance from resurrecting a chat after another device deleted it.
        if (loadTombstones().containsKey(updatedChat.getId())) return;
        List<Chat> chats = loadChats();
        boolean found = false;
        for (int i = 0; i < chats.size(); i++) {
            if (chats.get(i).getId().equals(updatedChat.getId())) {
                chats.set(i, updatedChat);
                found = true;
                break;
            }
        }
        // Пустой и ранее не сохранённый чат — черновик, на диск не пишем.
        if (!found
                && (updatedChat.getMessages() == null || updatedChat.getMessages().isEmpty())) {
            return;
        }
        // Чат мог исчезнуть из префсов из-за параллельного пулла — не теряем
        // данные молча, добавляем заново.
        if (!found) {
            chats.add(0, updatedChat);
        }
        // Обязательно: base64 в JSON не пишется (ExclusionStrategy) — без
        // выгрузки на диск вложение терялось бы навсегда. Только в heavy-режиме.
        if (heavy) persistAttachmentsToFiles(chats);
        String json = gson.toJson(chats);
        persistChatsJson(json);
        if (heavy) syncToCloud();
    }

    /**
     * Архивировать чат
     */
    public void archiveChat(String chatId) {
        List<Chat> chats = loadChats();
        for (Chat chat : chats) {
            if (chat.getId().equals(chatId)) {
                chat.setArchived(true);
                break;
            }
        }
        String json = gson.toJson(chats);
        persistChatsJson(json);
        syncToCloud();
    }

    /**
     * Разархивировать чат
     */
    public void unarchiveChat(String chatId) {
        List<Chat> chats = loadChats();
        for (Chat chat : chats) {
            if (chat.getId().equals(chatId)) {
                chat.setArchived(false);
                break;
            }
        }
        String json = gson.toJson(chats);
        persistChatsJson(json);
        syncToCloud();
    }

    /**
     * Загрузить только активные (не архивные) чаты
     */
    public List<Chat> loadActiveChats() {
        List<Chat> all = loadChats();
        List<Chat> active = new ArrayList<>();
        for (Chat c : all) {
            if (!c.isArchived()) active.add(c);
        }
        return active;
    }

    /**
     * Загрузить только архивные чаты
     */
    public List<Chat> loadArchivedChats() {
        List<Chat> all = loadChats();
        List<Chat> archived = new ArrayList<>();
        for (Chat c : all) {
            if (c.isArchived()) archived.add(c);
        }
        return archived;
    }

    /**
     * Получить чат по ID
     */
    public Chat getChatById(String chatId) {
        List<Chat> chats = loadChats();
        for (Chat chat : chats) {
            if (chat.getId().equals(chatId)) {
                return chat;
            }
        }
        return null;
    }

    /** Keep pinned chats first and preserve recency inside each group. */
    private static void sortPinnedFirst(List<Chat> chats) {
        if (chats == null) return;
        chats.sort((left, right) -> {
            if (left.isPinned() != right.isPinned()) return left.isPinned() ? -1 : 1;
            return Long.compare(right.getUpdatedAt(), left.getUpdatedAt());
        });
    }

    /** Archive every active chat in this store with one persistence write and cloud push. */
    public int archiveAllActive() {
        List<Chat> chats = loadChats();
        int archivedCount = 0;
        for (Chat chat : chats) {
            if (!chat.isArchived()) {
                chat.setArchived(true);
                archivedCount++;
            }
        }
        if (archivedCount > 0) {
            persistChatsJson(gson.toJson(chats));
            saveActiveChatId(null);
            syncToCloud();
        } else if (loadActiveChatId() != null) {
            saveActiveChatId(null);
        }
        return archivedCount;
    }

    /** Clear this store and its active-chat pointer. */
    public void clearAllChats() {
        long deletedAt = System.currentTimeMillis();
        List<Chat> all = loadChats();
        for (Chat chat : all) recordTombstone(chat.getId(), deletedAt);
        deleteAttachments(all);
        // Пишем пустой список и НЕ оставляем пред-снапшот в backup-ключе:
        // иначе restore-логика loadChats() при любом сбое парса resurrect-ит
        // только что удалённую историю.
        prefs.edit().putString(KEY_CHATS, "[]").putString(KEY_CHATS_BACKUP, "").apply();
        cachedChatsJson = "[]";
        cachedChats = new ArrayList<>();
        prefs.edit().remove(KEY_ACTIVE_CHAT_ID).apply();
        app.multiai.mobile.utils.ChatsClearedNotifier.notifyCleared();
        syncToCloud();
    }

    /** Add a chat to this store exactly once, preserving its model-generated title. */
    public void addChatIfAbsent(Chat chat) {
        if (chat == null || chat.getId() == null) return;
        // Пустые черновики не синкаем — в истории остаются только живые чаты.
        if (chat.getMessages() == null || chat.getMessages().isEmpty()) return;
        // A tombstoned UUID is permanently retired. Branch/import code must
        // create a new UUID instead of resurrecting a deleted chat.
        if (loadTombstones().containsKey(chat.getId())) return;
        List<Chat> chats = loadChats();
        chats.removeIf(existing -> chat.getId().equals(existing.getId()));
        chats.add(0, chat);
        sortPinnedFirst(chats);
        saveChats(chats);
    }

    private Map<String, Long> loadTombstones() {
        Map<String, Long> result = new HashMap<>();
        try {
            org.json.JSONObject json = new org.json.JSONObject(
                    prefs.getString(KEY_TOMBSTONES, "{}"));
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                long deletedAt = json.optLong(id, 0L);
                if (deletedAt > 0L) result.put(id, deletedAt);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void saveTombstones(Map<String, Long> tombstones) {
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            for (Map.Entry<String, Long> entry : tombstones.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_TOMBSTONES, json.toString()).apply();
    }

    private void recordTombstone(String chatId, long deletedAt) {
        if (chatId == null || chatId.isEmpty()) return;
        Map<String, Long> tombstones = loadTombstones();
        tombstones.put(chatId, Math.max(deletedAt, tombstones.getOrDefault(chatId, 0L)));
        saveTombstones(tombstones);
    }

    private String buildCloudEnvelope(String chatsJson, long revision,
                                      Map<String, Long> tombstones) {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("schema", 2);
            root.put("revision", revision);
            root.put("updatedAt", System.currentTimeMillis());
            root.put("chats", sanitizeCloudChats(new org.json.JSONArray(chatsJson)));
            org.json.JSONObject deleted = new org.json.JSONObject();
            for (Map.Entry<String, Long> entry : tombstones.entrySet()) {
                deleted.put(entry.getKey(), entry.getValue());
            }
            root.put("tombstones", deleted);
            return root.toString();
        } catch (Exception ignored) {
            return chatsJson;
        }
    }

    /** Replace device-local paths with portable inline data before cloud upload. */
    private org.json.JSONArray sanitizeCloudChats(org.json.JSONArray chats) {
        org.json.JSONArray safe = new org.json.JSONArray();
        if (chats == null) return safe;
        int remainingBytes = MAX_CLOUD_ATTACHMENTS_TOTAL_BYTES;
        for (int i = 0; i < chats.length(); i++) {
            org.json.JSONObject chat = chats.optJSONObject(i);
            if (chat == null) continue;
            org.json.JSONArray messages = chat.optJSONArray("messages");
            if (messages != null) {
                for (int j = 0; j < messages.length(); j++) {
                    org.json.JSONObject message = messages.optJSONObject(j);
                    if (message == null) continue;
                    String imagePath = message.optString("imagePath", "");
                    boolean imageInlined = false;
                    if (!imagePath.isEmpty() && remainingBytes > 0) {
                        byte[] image = readPrivateAttachment(imagePath,
                                Math.min(MAX_CLOUD_ATTACHMENT_BYTES, remainingBytes));
                        if (image != null) {
                            try {
                                message.put("imageBase64", android.util.Base64.encodeToString(
                                        image, android.util.Base64.NO_WRAP));
                                imageInlined = true;
                            } catch (Exception ignored) {
                                image = null;
                            }
                        }
                        if (image != null) {
                            remainingBytes -= image.length;
                        }
                    }
                    // If the picture did not fit the cloud budget, KEEP the
                    // device-local path: stripping it here would let the
                    // startup cloud-merge wipe the image from this device.
                    if (imageInlined) {
                        message.remove("imagePath");
                    }
                    String bundlePath = message.optString("imageBundlePath", "");
                    if (!bundlePath.isEmpty() && remainingBytes > 0) {
                        byte[] bundle = readPrivateAttachment(bundlePath, Math.min(MAX_CLOUD_ATTACHMENT_BYTES, remainingBytes));
                        if (bundle != null) {
                            try {
                                message.put("imageBase64", new String(bundle, java.nio.charset.StandardCharsets.UTF_8));
                                message.remove("imageBundlePath");
                                remainingBytes -= bundle.length;
                            } catch (Exception ignored) { }
                        }
                    }
                    String filePath = message.optString("attachedFilePath", "");
                    boolean fileInlined = false;
                    if (!filePath.isEmpty() && remainingBytes > 0) {
                        byte[] file = readPrivateAttachment(filePath,
                                Math.min(MAX_CLOUD_ATTACHMENT_BYTES, remainingBytes));
                        if (file != null) {
                            try {
                                message.put("attachedFileContent",
                                        new String(file, java.nio.charset.StandardCharsets.UTF_8));
                                fileInlined = true;
                            } catch (Exception ignored) {
                                file = null;
                            }
                        }
                        if (file != null) {
                            remainingBytes -= file.length;
                        }
                    }
                    // Same as images: keep the local path when inlining failed,
                    // so a cloud merge cannot erase the attachment locally.
                    if (fileInlined) {
                        message.remove("attachedFilePath");
                    }
                }
            }
            safe.put(chat);
        }
        return safe;
    }

    private byte[] readPrivateAttachment(String path, int maxBytes) {
        if (path == null || path.isEmpty() || maxBytes <= 0) return null;
        try {
            java.io.File root = new java.io.File(context.getFilesDir(), "attachments")
                    .getCanonicalFile();
            java.io.File file = new java.io.File(path).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + java.io.File.separator)
                    || !file.isFile() || file.length() > maxBytes) return null;
            try (java.io.FileInputStream input = new java.io.FileInputStream(file);
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(
                         (int) Math.min(file.length(), maxBytes))) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) return null;
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void deleteAttachments(List<Chat> chats) {
        if (chats == null) return;
        java.util.HashSet<String> paths = new java.util.HashSet<>();
        for (Chat chat : chats) {
            if (chat == null) continue;
            for (app.multiai.mobile.models.Message message : chat.getMessages()) {
                if (message.getImagePath() != null) paths.add(message.getImagePath());
                if (message.getAttachedFilePath() != null) paths.add(message.getAttachedFilePath());
            }
        }
        for (String path : paths) {
            try {
                java.io.File file = new java.io.File(path).getCanonicalFile();
                java.io.File root = new java.io.File(context.getFilesDir(), "attachments").getCanonicalFile();
                if (file.getPath().startsWith(root.getPath() + java.io.File.separator)) file.delete();
            } catch (Exception ignored) {
            }
        }
    }

    public String wrapCloudSnapshot(String chatsJson) {
        String safeChats = chatsJson == null ? "[]" : chatsJson.trim();
        try {
            if (safeChats.startsWith("{")) {
                org.json.JSONObject envelope = new org.json.JSONObject(safeChats);
                if (envelope.has("schema") && envelope.has("tombstones")) {
                    return envelope.toString();
                }
                org.json.JSONArray chats = envelope.optJSONArray("chats");
                return buildCloudEnvelope(chats != null ? chats.toString() : "[]",
                        envelope.optLong("revision", prefs.getLong(KEY_CLOUD_REVISION, 0L)),
                        loadTombstones());
            }
            return buildCloudEnvelope(new org.json.JSONArray(safeChats).toString(),
                    prefs.getLong(KEY_CLOUD_REVISION, 0L), loadTombstones());
        } catch (Exception ignored) {
            return buildCloudEnvelope("[]", prefs.getLong(KEY_CLOUD_REVISION, 0L), loadTombstones());
        }
    }

    public boolean mergeCloudSnapshot(String cloudData) {
        if (cloudData == null || cloudData.trim().isEmpty()) return false;
        try {
            long cloudRevision = 0L;
            Map<String, Long> cloudTombstones = new HashMap<>();
            String cloudChatsJson;
            String trimmed = cloudData.trim();
            boolean envelopeFormat = trimmed.startsWith("{");
            if (envelopeFormat) {
                org.json.JSONObject envelope = new org.json.JSONObject(trimmed);
                cloudRevision = envelope.optLong("revision", 0L);
                org.json.JSONArray chatsArray = envelope.optJSONArray("chats");
                if (chatsArray == null) return false;
                cloudChatsJson = chatsArray.toString();
                org.json.JSONObject deleted = envelope.optJSONObject("tombstones");
                if (deleted != null) {
                    java.util.Iterator<String> keys = deleted.keys();
                    while (keys.hasNext()) {
                        String id = keys.next();
                        cloudTombstones.put(id, deleted.optLong(id, 0L));
                    }
                }
            } else {
                cloudChatsJson = new org.json.JSONArray(trimmed).toString();
            }

            Type type = new TypeToken<List<Chat>>(){}.getType();
            List<Chat> cloudChats = gson.fromJson(cloudChatsJson, type);
            if (cloudChats == null) cloudChats = new ArrayList<>();
            List<Chat> localChats = loadChats();
            Map<String, Long> mergedTombstones = loadTombstones();
            boolean localWon = false;
            for (Map.Entry<String, Long> entry : cloudTombstones.entrySet()) {
                long local = mergedTombstones.getOrDefault(entry.getKey(), 0L);
                if (entry.getValue() > local) mergedTombstones.put(entry.getKey(), entry.getValue());
                if (local > entry.getValue()) localWon = true;
            }
            for (Map.Entry<String, Long> entry : mergedTombstones.entrySet()) {
                if (entry.getValue() > cloudTombstones.getOrDefault(entry.getKey(), 0L)) {
                    localWon = true;
                }
            }
            Map<String, Chat> merged = new java.util.LinkedHashMap<>();
            for (Chat chat : cloudChats) {
                if (chat != null && chat.getId() != null) merged.put(chat.getId(), chat);
            }
            for (Chat chat : localChats) {
                if (chat == null || chat.getId() == null) continue;
                Chat cloud = merged.get(chat.getId());
                if (cloud == null || chat.getUpdatedAt() > cloud.getUpdatedAt()) {
                    merged.put(chat.getId(), chat);
                    localWon = true;
                }
            }
            List<Chat> mergedChats = new ArrayList<>();
            for (Chat chat : merged.values()) {
                Long deletedAt = mergedTombstones.get(chat.getId());
                // A deletion only wins over a chat version that is not newer
                // than the deletion. Keep a genuinely newer update alive and
                // remove the stale tombstone, otherwise updateChat() would
                // immediately reject this surviving chat after the pull.
                if (deletedAt == null || chat.getUpdatedAt() > deletedAt) {
                    mergedChats.add(chat);
                    if (deletedAt != null) {
                        mergedTombstones.remove(chat.getId());
                        localWon = true;
                    }
                }
            }
            sortPinnedFirst(mergedChats);
            persistAttachmentsToFiles(mergedChats);
            prefs.edit()
                    .putString(KEY_CHATS, gson.toJson(mergedChats))
                    .putLong(KEY_CLOUD_REVISION,
                            Math.max(prefs.getLong(KEY_CLOUD_REVISION, 0L), cloudRevision))
                    .apply();
            saveTombstones(mergedTombstones);
            if (localWon || envelopeFormat) queueCloudSnapshot(gson.toJson(mergedChats));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Async push chats to cloud.
     *
     * <p>Один serial-executor + дебаунс: быстрые сейвы в стриминге больше не
     * плодят параллельные треды с гонками «кто последний запушил» — в облако
     * уезжает ровно последний снапшот, снятый в момент мутации (не перечитка
     * префсов в треде, которая могла поймать чужую промежуточную запись).
     */
    private java.util.concurrent.ScheduledFuture<?> pendingSync;

    private void syncToCloud() {
        String snapshot = prefs.getString(KEY_CHATS, null);
        if (snapshot != null) queueCloudSnapshot(snapshot);
    }

    private void queueCloudSnapshot(String chatsJson) {
        long revision = prefs.getLong(KEY_CLOUD_REVISION, 0L) + 1L;
        String payload = buildCloudEnvelope(chatsJson, revision, loadTombstones());
        prefs.edit()
                .putLong(KEY_CLOUD_REVISION, revision)
                .putString(KEY_CLOUD_OUTBOX, payload)
                .putInt(KEY_CLOUD_RETRY, 0)
                .putLong(KEY_CLOUD_RETRY_AT, 0L)
                .apply();
        scheduleCloudOutbox(600L);
    }

    private void resumeCloudOutbox() {
        if (prefs.getString(KEY_CLOUD_OUTBOX, null) == null) return;
        long delay = Math.max(0L,
                prefs.getLong(KEY_CLOUD_RETRY_AT, 0L) - System.currentTimeMillis());
        scheduleCloudOutbox(delay);
    }

    private synchronized void scheduleCloudOutbox(long delayMs) {
        if (pendingSync != null) pendingSync.cancel(false);
        pendingSync = SYNC_EXECUTOR.schedule(this::flushCloudOutbox,
                Math.max(0L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void flushCloudOutbox() {
        String payload = prefs.getString(KEY_CLOUD_OUTBOX, null);
        if (payload == null) return;
        boolean pushed = false;
        try {
            CloudSyncManager sync = new CloudSyncManager(context);
            if ("multiai_chatmode_chats".equals(prefsName)) {
                sync.pullChatModeChats();
                payload = prefs.getString(KEY_CLOUD_OUTBOX, payload);
                pushed = sync.pushChatModeChats(payload);
            } else {
                sync.pullChats();
                payload = prefs.getString(KEY_CLOUD_OUTBOX, payload);
                pushed = sync.pushChats(payload);
            }
        } catch (Exception ignored) {
        }
        if (pushed) {
            if (payload.equals(prefs.getString(KEY_CLOUD_OUTBOX, null))) {
                prefs.edit()
                        .remove(KEY_CLOUD_OUTBOX)
                        .remove(KEY_CLOUD_RETRY)
                        .remove(KEY_CLOUD_RETRY_AT)
                        .apply();
            }
            return;
        }
        int retry = Math.min(10, prefs.getInt(KEY_CLOUD_RETRY, 0) + 1);
        long delay = Math.min(60_000L, 1_000L << Math.min(6, retry - 1));
        prefs.edit()
                .putInt(KEY_CLOUD_RETRY, retry)
                .putLong(KEY_CLOUD_RETRY_AT, System.currentTimeMillis() + delay)
                .apply();
        scheduleCloudOutbox(delay);
    }
}
