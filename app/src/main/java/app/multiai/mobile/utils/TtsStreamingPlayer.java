package app.multiai.mobile.utils;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Batch TTS player.
 * <p>
 * Логика (согласно запросу):
 * 1) Первый запрос с текстом → бэк возвращает {audio_1, chunks[], total}.
 * 2) Параллельно с прогрессом в UI качаем остальные чанки серийно (cooldown у Free Tier).
 * 3) Когда все чанки в файлах — цепляем их через MediaPlayer.setNextMediaPlayer()
 *    для gapless-воспроизведения.
 * 4) Играем как один бесшовный аудиопоток.
 */
public class TtsStreamingPlayer {
    private static final String TAG = "TtsStream";

    public interface Listener {
        void onStart();
        /** Прогресс загрузки. loaded = сколько чанков уже готово, total = всего. */
        void onProgress(int loaded, int total);
        /** Начало проигрывания (все чанки загружены). */
        void onPlaybackStart(int total);
        /** Текущий играющий чанк (для прогресса во время плейбека). */
        void onChunk(int index, int total);
        void onComplete();
        void onError(String error);
    }

    private final Context context;
    private final String backendUrl;
    private final OkHttpClient http;
    private final Listener listener;

    private volatile boolean cancelled = false;
    private final List<MediaPlayer> chain = new ArrayList<>();
    private final List<File> tempFiles = new ArrayList<>();

    public TtsStreamingPlayer(Context context, String backendUrl, Listener listener) {
        this.context = context.getApplicationContext();
        this.backendUrl = backendUrl;
        this.listener = listener;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    /** Запустить озвучку. Вызывается из UI-потока. */
    public void start(String text, String voice, String emotion) {
        if (listener != null) listener.onStart();
        new Thread(() -> runPipeline(text, voice, emotion)).start();
    }

    /** Остановить. Вызывать из UI-потока. */
    public void stop() {
        cancelled = true;
        for (MediaPlayer mp : chain) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }
        chain.clear();
        cleanup();
    }

    public boolean isPlaying() {
        if (chain.isEmpty()) return false;
        for (MediaPlayer mp : chain) {
            try { if (mp.isPlaying()) return true; } catch (Exception ignored) {}
        }
        return false;
    }

    private void cleanup() {
        for (File f : tempFiles) {
            try { f.delete(); } catch (Exception ignored) {}
        }
        tempFiles.clear();
    }

    private void runPipeline(String text, String voice, String emotion) {
        try {
            // 1) Получаем первый чанк + список всех чанков + seed
            ChunkResult first = fetchChunk(text, null, -1, voice, emotion, -1);
            if (cancelled || first == null) { cleanup(); return; }

            int total = first.allChunks != null ? first.allChunks.length() : 1;
            long sessionSeed = first.seed; // Один seed на всю сессию — голос не «плавает»
            List<File> audioFiles = new ArrayList<>();
            File f0 = writeAudio(first.audioBytes, first.format);
            tempFiles.add(f0);
            audioFiles.add(f0);
            postMain(() -> { if (listener != null) listener.onProgress(1, total); });

            // 2) Последовательно качаем остальные с тем же seed
            for (int i = 1; i < total && !cancelled; i++) {
                ChunkResult r = fetchChunk(null, first.allChunks, i, voice, emotion, sessionSeed);
                if (cancelled || r == null) { cleanup(); return; }
                File fi = writeAudio(r.audioBytes, r.format);
                tempFiles.add(fi);
                audioFiles.add(fi);
                final int loaded = i + 1;
                postMain(() -> { if (listener != null) listener.onProgress(loaded, total); });
            }

            if (cancelled) { cleanup(); return; }

            // 3) Gapless-воспроизведение через setNextMediaPlayer
            playGapless(audioFiles, total);
        } catch (Throwable t) {
            notifyError(t.getMessage() != null ? t.getMessage() : "unknown error");
            cleanup();
        }
    }

    /** Gapless-воспроизведение цепочки файлов. Ожидает вызова из фонового потока. */
    private void playGapless(List<File> files, int total) {
        final Object playLock = new Object();
        final boolean[] finishedAll = { false };

        postMain(() -> {
            try {
                // Создаём все MediaPlayer заранее
                for (File f : files) {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(f.getAbsolutePath());
                    mp.prepare();
                    chain.add(mp);
                }

                // Цепляем через setNextMediaPlayer
                for (int i = 0; i < chain.size() - 1; i++) {
                    chain.get(i).setNextMediaPlayer(chain.get(i + 1));
                }

                // Слушаем onCompletion для каждого
                for (int i = 0; i < chain.size(); i++) {
                    final int idx = i;
                    chain.get(i).setOnCompletionListener(mp -> {
                        // Релизим завершившийся
                        try { mp.release(); } catch (Exception ignored) {}
                        int next = idx + 1;
                        if (next < chain.size()) {
                            // next уже стартует сам благодаря setNextMediaPlayer
                            if (listener != null) listener.onChunk(next, total);
                        } else {
                            finishedAll[0] = true;
                            synchronized (playLock) { playLock.notifyAll(); }
                        }
                    });
                    chain.get(i).setOnErrorListener((mp, what, extra) -> {
                        try { mp.release(); } catch (Exception ignored) {}
                        finishedAll[0] = true;
                        synchronized (playLock) { playLock.notifyAll(); }
                        return true;
                    });
                }

                if (listener != null) {
                    listener.onPlaybackStart(total);
                    listener.onChunk(0, total);
                }
                chain.get(0).start();
            } catch (Exception e) {
                notifyError("playback: " + e.getMessage());
                finishedAll[0] = true;
                synchronized (playLock) { playLock.notifyAll(); }
            }
        });

        // Ждём пока плейбек завершится (чтобы потом почистить tempFiles)
        synchronized (playLock) {
            while (!finishedAll[0] && !cancelled) {
                try { playLock.wait(500); } catch (InterruptedException ignored) {}
            }
        }

        // Финальная уборка
        chain.clear();
        if (!cancelled) {
            postMain(() -> { if (listener != null) listener.onComplete(); });
        }
        cleanup();
    }

    private File writeAudio(byte[] audio, String format) throws Exception {
        String ext = (format != null && !format.isEmpty()) ? format : "mp3";
        File f = new File(context.getCacheDir(), "multiai_tts_" + System.nanoTime() + "." + ext);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(audio);
        }
        return f;
    }

    private ChunkResult fetchChunk(String text, JSONArray chunks, int chunkIndex, String voice, String emotion, long seed) throws Exception {
        JSONObject body = new JSONObject();
        if (chunks != null) {
            body.put("chunks", chunks);
            body.put("chunk_index", chunkIndex);
        } else {
            body.put("text", text);
        }
        body.put("voice", voice);
        if (emotion != null && !emotion.isEmpty()) body.put("emotion", emotion);
        if (seed >= 0) body.put("seed", seed);

        RequestBody rb = RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request req = new Request.Builder()
                .url(backendUrl + "/api/nihalgazi-tts")
                .post(rb)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("HTTP " + resp.code());
            }
            JSONObject json = new JSONObject(resp.body().string());
            if (!json.optBoolean("success", false)) {
                throw new Exception(json.optString("error", "unknown"));
            }
            ChunkResult r = new ChunkResult();
            r.audioBytes = Base64.decode(json.getString("audio"), Base64.DEFAULT);
            r.format = json.optString("format", "mp3");
            r.allChunks = json.optJSONArray("chunks");
            r.total = json.optInt("total_chunks", 1);
            r.hasMore = json.optBoolean("has_more", false);
            r.seed = json.optLong("seed", -1);
            return r;
        }
    }

    private void notifyError(String msg) {
        postMain(() -> { if (listener != null) listener.onError(msg); });
    }

    private void postMain(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static class ChunkResult {
        byte[] audioBytes;
        String format;
        JSONArray allChunks;
        int total;
        boolean hasMore;
        long seed = -1;
    }
}
