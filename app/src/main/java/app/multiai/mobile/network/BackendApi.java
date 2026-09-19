package app.multiai.mobile.network;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import app.multiai.mobile.BuildConfig;
import app.multiai.mobile.utils.DeviceIdentity;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Общий клиент к multiai-api.vercel.app: статус устройства (бан-гейт),
 * heartbeat и фидбэк.
 *
 * Аутентификация — OAuth access token юзера (Authorization: Bearer), который
 * бэкенд проверяет у multiai.store. Приложение НЕ содержит никаких вшитых
 * ключей: незалогиненный пользователь бан-гейтом не считается, а метрируемые
 * эндпоинты (whisper/TTS) требуют токен на стороне сервера.
 */
public final class BackendApi {

    private static final String TAG = "BackendApi";
    public static final String BASE_URL = "https://multiai-api.vercel.app";

    private static volatile BackendApi instance;

    private final OkHttpClient client;

    /** Последний известный статус бана — персистентный: переживает перезапуск
     *  процесса. Снимается только разбаном на сервере (banned=false в ответе). */
    private final java.util.List<StatusCallback> statusCallbacks = new java.util.ArrayList<>();
    private boolean statusInFlight;
    private boolean banStateRestored;
    private volatile boolean banned;
    private volatile String banMessage;
    private volatile long statusCheckedAtMs;
    private static final long STATUS_TTL_MS = 5 * 60_000L;
    private static final String PREFS = "multiai_device_ban";
    private static final String KEY_BANNED = "banned";
    private static final String KEY_MESSAGE = "message";

    public interface StatusCallback {
        void onStatus(boolean banned, String banMessage, boolean maintenance);
    }

    public interface Callback { void onDone(boolean ok); }

    private BackendApi() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public static BackendApi get() {
        if (instance == null) {
            synchronized (BackendApi.class) {
                if (instance == null) instance = new BackendApi();
            }
        }
        return instance;
    }

    /** Восстанавливает забаненное состояние из.SharedPreferences — вызывать
     *  один раз на старте процесса ДО сетевых проверок, чтобы перезапуск
     *  приложения не снимал бан. */
    public synchronized void restoreBanState(Context ctx) {
        if (banStateRestored) return;
        banStateRestored = true;
        android.content.SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_BANNED, false)) {
            banned = true;
            banMessage = prefs.getString(KEY_MESSAGE, null);
        }
    }

    private void persistBanState(Context ctx, boolean isBanned, String message) {
        android.content.SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_BANNED, isBanned)
                .putString(KEY_MESSAGE, isBanned ? message : null)
                .apply();
    }

    public boolean isBanned() { return banned; }
    public String getBanMessage() { return banMessage; }

    /** Свежий статус есть — не ходим в сеть. */
    public boolean hasFreshStatus() {
        return System.currentTimeMillis() - statusCheckedAtMs < STATUS_TTL_MS;
    }

    /**
     * Проверяет статус устройства на бэке (фоновый поток). Обновляет кэш бана.
     * Работает только для залогиненных (нужен OAuth-токен); иначе тихо пропускаем —
     * сервер всё равно ответит 401.
     *
     * Важно: на старте приложения токен ещё грузится из хранилища асинхронно,
     * поэтому первый checkStatus может прийти раньше токена. В этом случае
     * повторяем попытку с задержкой (до 3 раз), пока токен не появится.
     */
    public synchronized void checkStatus(Context ctx, StatusCallback cb) {
        restoreBanState(ctx);
        if (cb != null) statusCallbacks.add(cb);
        if (statusInFlight) return;
        statusInFlight = true;
        checkStatusWithRetry(ctx.getApplicationContext(), (blocked, message, maintenance) -> {
            java.util.List<StatusCallback> callbacks;
            synchronized (this) {
                callbacks = new java.util.ArrayList<>(statusCallbacks);
                statusCallbacks.clear();
                statusInFlight = false;
            }
            for (StatusCallback callback : callbacks) {
                try {
                    callback.onStatus(blocked, message, maintenance);
                } catch (Exception error) {
                    Log.w(TAG, "status callback failed", error);
                }
            }
        }, 0);
    }

    private void checkStatusWithRetry(Context ctx, StatusCallback cb, int attempt) {
        new Thread(() -> {
            String token = app.multiai.mobile.network.oauth.OAuthSession.get().getAccessToken();
            if (token == null || token.trim().isEmpty()) {
                if (attempt < 3) {
                    // Токен ещё не загружен из хранилища — ждём и пробуем снова.
                    try { Thread.sleep(3000L * (attempt + 1)); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        cb.onStatus(banned, banMessage, false);
                        return;
                    }
                    checkStatusWithRetry(ctx, cb, attempt + 1);
                } else if (cb != null) {
                    cb.onStatus(banned, banMessage, false);
                }
                return;
            }
            try {
                JSONObject resp = post("/api/app-status", devicePayload(ctx), "Bearer " + token);
                banned = DeviceBanStatus.parse(resp);
                banMessage = resp.optString("ban_message", null);
                if (banned && (banMessage == null || banMessage.isEmpty())) {
                    // Шаблон не пришёл — соберём фолбэк из причины, если она есть.
                    String reason = resp.optString("ban_reason", "");
                    banMessage = reason.isEmpty() ? null : reason;
                }
                persistBanState(ctx, banned, banMessage);
                boolean maintenance = resp.optBoolean("maintenance", false);
                statusCheckedAtMs = System.currentTimeMillis();
                if (cb != null) cb.onStatus(banned, banMessage, maintenance);
            } catch (Exception e) {
                Log.w(TAG, "status check failed", e);
                if (cb != null) cb.onStatus(banned, banMessage, false);
            }
        }).start();
    }

    /** Лёгкий пинг «я онлайн» — только для залогиненных. */
    public void sendHeartbeat(Context ctx) {
        String token = app.multiai.mobile.network.oauth.OAuthSession.get().getAccessToken();
        if (token == null || token.trim().isEmpty()) return;
        new Thread(() -> {
            try {
                post("/api/heartbeat", devicePayload(ctx), "Bearer " + token);
            } catch (Exception e) {
                Log.w(TAG, "heartbeat failed", e);
            }
        }).start();
    }

    /** Отправка репорта «Сообщить об ошибке». Токен не обязателен. */
    public void sendFeedback(Context ctx, String category, String details, Callback cb) {
        new Thread(() -> {
            try {
                JSONObject body = devicePayload(ctx);
                body.put("type", "bug_report");
                body.put("category", category);
                body.put("details", details);
                String token = app.multiai.mobile.network.oauth.OAuthSession.get().getAccessToken();
                Request req = new Request.Builder()
                        .url(BASE_URL + "/api/feedback")
                        .post(RequestBody.create(body.toString(),
                                MediaType.get("application/json; charset=utf-8")))
                        .build();
                if (token != null && !token.trim().isEmpty()) {
                    // Токен не проверяется на этом эндпоинте, но отправим девайс-контекст.
                }
                try (Response resp = client.newCall(req).execute()) {
                    String raw = resp.body() != null ? resp.body().string() : "";
                    JSONObject parsed = new JSONObject(raw.isEmpty() ? "{}" : raw);
                    if (cb != null) cb.onDone(parsed.optBoolean("ok", false));
                }
            } catch (Exception e) {
                Log.w(TAG, "feedback failed", e);
                if (cb != null) cb.onDone(false);
            }
        }).start();
    }

    /** Bearer-токен для метрируемых эндпоинтов (whisper/TTS) — без него сервер ответит 401. */
    public static String bearerToken() {
        String token = app.multiai.mobile.network.oauth.OAuthSession.get().getAccessToken();
        return token != null && !token.trim().isEmpty() ? "Bearer " + token : null;
    }

    private JSONObject devicePayload(Context ctx) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_id", DeviceIdentity.getDeviceId(ctx));
        body.put("platform", "android");
        body.put("device_name", DeviceIdentity.getDeviceName());
        body.put("app_version", BuildConfig.VERSION_NAME);
        return body;
    }

    private JSONObject post(String path, JSONObject body, String bearerToken) throws Exception {
        Request req = new Request.Builder()
                .url(BASE_URL + path)
                .header("Authorization", bearerToken)
                .post(RequestBody.create(body.toString(),
                        MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("Backend HTTP " + resp.code());
            }
            String raw = resp.body() != null ? resp.body().string() : "";
            return new JSONObject(raw.isEmpty() ? "{}" : raw);
        }
    }
}
