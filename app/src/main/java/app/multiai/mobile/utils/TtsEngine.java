package app.multiai.mobile.utils;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

import app.multiai.mobile.network.oauth.OAuthSession;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Синтез речи для озвучки ответов ИИ. Два провайдера:
 *  - Edge TTS — через выделенный Edge API (JSON, base64 mp3).
 *  - Qwen3 TTS — напрямую через HF Gradio Space (call/tts_interface + SSE poll).
 *
 * Все методы блокирующие — вызывать из фонового потока. Возвращают локальный
 * файл в cacheDir, который затем проигрывается через MediaPlayer.
 */
public final class TtsEngine {

    private static final String TAG = "TtsEngine";
    private static final String EDGE_URL = "https://backend-tau-ten-80.vercel.app/api/edge-tts";
    private static final String QWEN_URL = "https://qwen-qwen3-tts-demo.hf.space";

    private static final java.util.Set<Character> RU_LETTERS = new java.util.HashSet<>();
    static {
        for (char c : "абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґ".toCharArray()) RU_LETTERS.add(c);
    }

    /** Результат TTS: позволяет интерфейсу честно показать fallback провайдера. */
    public static final class SynthesisResult {
        public final File audioFile;
        public final String provider;
        public final boolean fallbackUsed;

        private SynthesisResult(File audioFile, String provider, boolean fallbackUsed) {
            this.audioFile = audioFile;
            this.provider = provider;
            this.fallbackUsed = fallbackUsed;
        }
    }

    /**
     * Совместимый shortcut для существующих вызовов озвучки ответа.
     * При временной проблеме Edge автоматически применяется Qwen3, чтобы
     * озвучка никогда не превращалась в мёртвую кнопку.
     */
    public static File synthesizeBlocking(Context ctx, String provider, String voice,
                                          String text, String rate) throws Exception {
        return synthesizeWithFallback(ctx, provider, voice, text, rate, "+0Hz", "+0%").audioFile;
    }

    /** Backward-compatible overload: pitch and volume stay neutral. */
    public static SynthesisResult synthesizeWithFallback(Context ctx, String provider, String voice,
                                                         String text, String rate) throws Exception {
        return synthesizeWithFallback(ctx, provider, voice, text, rate, "+0Hz", "+0%");
    }

    /**
     * Synthesizes with every parameter supported by the Edge endpoint. Qwen3
     * only accepts text, voice and language, so rate/pitch/volume are applied
     * by its caller where possible (for rate, through MediaPlayer playback).
     */
    public static SynthesisResult synthesizeWithFallback(Context ctx, String provider, String voice,
                                                         String text, String rate, String pitch,
                                                         String volume) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new Exception("empty_text");
        if (VoiceCatalog.PROVIDER_QWEN.equals(provider)) {
            return new SynthesisResult(synthesizeQwen(ctx, voice, text),
                    VoiceCatalog.PROVIDER_QWEN, false);
        }
        try {
            return new SynthesisResult(synthesizeEdge(ctx, voice, text, rate, pitch, volume),
                    VoiceCatalog.PROVIDER_EDGE, false);
        } catch (Exception edgeError) {
            // If the selected Edge API is temporarily unavailable, Qwen keeps
            // the app audible instead of leaving the user with a dead button.
            Log.w(TAG, "Edge TTS unavailable; falling back to Qwen", edgeError);
            try {
                return new SynthesisResult(synthesizeQwen(ctx, VoiceCatalog.DEFAULT_QWEN_VOICE, text),
                        VoiceCatalog.PROVIDER_QWEN, true);
            } catch (Exception qwenError) {
                edgeError.addSuppressed(qwenError);
                throw edgeError;
            }
        }
    }

    // ─────────────────────────── EDGE ───────────────────────────

    private static File synthesizeEdge(Context ctx, String voice, String text, String rate,
                                       String pitch, String volume) throws Exception {
        String voiceId = (voice == null || voice.trim().isEmpty())
                ? VoiceCatalog.DEFAULT_EDGE_VOICE : voice;
        Log.i(TAG, "Edge synthesis requested for voice=" + voiceId);
        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("voice", voiceId);
        body.put("rate", rate == null || rate.trim().isEmpty() ? "+0%" : rate);
        body.put("pitch", pitch == null || pitch.trim().isEmpty() ? "+0Hz" : pitch);
        body.put("volume", volume == null || volume.trim().isEmpty() ? "+0%" : volume);

        RequestBody reqBody = RequestBody.create(
                body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(EDGE_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "MultiAI-Mobile/1.0 (Android)")
                .post(reqBody);
        String accessToken = OAuthSession.get().getAccessToken();
        if (accessToken != null && !accessToken.trim().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }
        Request req = requestBuilder.build();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("edge_http_" + resp.code());
            }
            JSONObject result = new JSONObject(resp.body().string());
            if (!result.optBoolean("success", false)) {
                throw new Exception(result.optString("error", "edge_failed"));
            }
            String echoedVoice = result.optString("voice", "");
            if (!echoedVoice.isEmpty() && !voiceId.equals(echoedVoice)) {
                throw new Exception("edge_voice_mismatch");
            }
            Log.i(TAG, "Edge synthesis returned voice="
                    + (echoedVoice.isEmpty() ? "(not echoed)" : echoedVoice));
            String base64Audio = result.getString("audio").trim();
            int marker = base64Audio.indexOf("base64,");
            if (marker >= 0) base64Audio = base64Audio.substring(marker + 7);
            // Some reverse proxies wrap base64 onto multiple lines.
            base64Audio = base64Audio.replaceAll("\\s", "");
            String format = result.optString("format", "mp3");
            byte[] audioBytes = Base64.decode(base64Audio, Base64.DEFAULT);
            validateAudio(audioBytes, format, "edge");
            return writeCache(ctx, audioBytes, format);
        }
    }

    // ─────────────────────────── QWEN3 ───────────────────────────

    private static File synthesizeQwen(Context ctx, String voice, String text) throws Exception {
        String voiceId = (voice == null || voice.trim().isEmpty())
                ? VoiceCatalog.DEFAULT_QWEN_VOICE : voice;
        String lang = detectQwenLang(text);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();

        // 1) POST — получаем event_id.
        JSONArray data = new JSONArray();
        data.put(text);
        data.put(voiceId);
        data.put(lang);
        JSONObject payload = new JSONObject();
        payload.put("data", data);

        Request postReq = new Request.Builder()
                .url(QWEN_URL + "/gradio_api/call/tts_interface")
                .post(RequestBody.create(payload.toString(),
                        MediaType.get("application/json; charset=utf-8")))
                .build();

        String eventId;
        try (Response resp = client.newCall(postReq).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("qwen_http_" + resp.code());
            }
            eventId = new JSONObject(resp.body().string()).optString("event_id", "");
        }
        if (eventId.isEmpty()) throw new Exception("qwen_no_event_id");

        // 2) GET SSE stream — ждём event: complete + data: [{url}].
        Request streamReq = new Request.Builder()
                .url(QWEN_URL + "/gradio_api/call/tts_interface/" + eventId)
                .build();

        String audioUrl = null;
        try (Response resp = client.newCall(streamReq).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("qwen_stream_http_" + resp.code());
            }
            ResponseBody rb = resp.body();
            BufferedSource source = rb.source();
            String lastEvent = null;
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (line.isEmpty()) continue;
                if (line.startsWith("event: ")) {
                    lastEvent = line.substring(7).trim();
                    continue;
                }
                if (line.startsWith("data: ")) {
                    String ds = line.substring(6);
                    if ("error".equals(lastEvent)) {
                        throw new Exception("qwen_server_error: " + ds);
                    }
                    if (!ds.isEmpty() && !"null".equals(ds)) {
                        try {
                            JSONArray arr = new JSONArray(ds);
                            if (arr.length() > 0) {
                                JSONObject first = arr.optJSONObject(0);
                                if (first != null && !first.optString("url", "").isEmpty()) {
                                    audioUrl = first.getString("url");
                                    break;
                                }
                            }
                        } catch (Exception ignored) {
                            // не тот data-кадр — продолжаем читать поток.
                        }
                    }
                    lastEvent = null;
                }
            }
        }
        if (audioUrl == null) throw new Exception("qwen_no_audio_url");
        if (audioUrl.startsWith("/")) audioUrl = QWEN_URL + audioUrl;

        // 3) Скачиваем аудио (wav).
        Request audioReq = new Request.Builder().url(audioUrl).build();
        try (Response resp = client.newCall(audioReq).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("qwen_download_http_" + resp.code());
            }
            byte[] audioBytes = resp.body().bytes();
            validateAudio(audioBytes, "wav", "qwen");
            return writeCache(ctx, audioBytes, "wav");
        }
    }

    /** Как в боте: наличие кириллицы/украинских букв => русский, иначе английский. */
    private static String detectQwenLang(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (RU_LETTERS.contains(Character.toLowerCase(text.charAt(i)))) {
                return "Russian / 俄语";
            }
        }
        return "English / 英文";
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    private static void validateAudio(byte[] audioBytes, String format, String source) throws Exception {
        if (audioBytes == null || audioBytes.length < 12) {
            throw new Exception(source + "_empty_audio");
        }
        boolean wav = "wav".equalsIgnoreCase(format);
        boolean valid = wav
                ? audioBytes[0] == 'R' && audioBytes[1] == 'I'
                    && audioBytes[2] == 'F' && audioBytes[3] == 'F'
                : (audioBytes[0] == 'I' && audioBytes[1] == 'D' && audioBytes[2] == '3')
                    || ((audioBytes[0] & 0xFF) == 0xFF && (audioBytes[1] & 0xE0) == 0xE0);
        if (!valid) throw new Exception(source + "_invalid_audio");
    }

    private static File writeCache(Context ctx, byte[] audioBytes, String format) throws Exception {
        if (audioBytes == null || audioBytes.length == 0) throw new Exception("empty_audio");
        String ext = (format == null || format.trim().isEmpty()) ? "mp3" : format.trim();
        File tempFile = new File(ctx.getCacheDir(),
                "multiai_tts_" + System.currentTimeMillis() + "." + ext);
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(audioBytes);
        }
        return tempFile;
    }

    private TtsEngine() {}
}
