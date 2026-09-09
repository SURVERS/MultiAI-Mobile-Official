package app.multiai.mobile.network.oauth;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * MultiAI Account/Keys API client over the OAuth access token (§8 of the
 * integration document). Endpoints all live under {@code /api/oauth/v1} on the
 * trusted issuer origin.
 *
 * <p>Implements the §10 retry contract: each request carries
 * {@code Authorization: Bearer <access_token>}; on {@code 401 invalid_oauth_token}
 * the client performs <b>one</b> process-wide refresh through
 * {@link OAuthRefreshCoordinator} and <b>one</b> retry of the original request;
 * any further failure aborts to a signed-out state (no infinite retry).
 *
 * <p>The encrypted refresh-token family is owned by {@link OAuthStore};
 * {@link OAuthSession} holds only the volatile access token. Rotation, login
 * publication and logout are serialized and generation-checked by
 * {@link OAuthRefreshCoordinator}.
 */
public class AccountApi {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Context context;
    private final OAuthStore store;
    private volatile OAuthClient.Metadata metadata;

    public AccountApi(@NonNull Context context, @NonNull OAuthStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /** Caller provides metadata once (fetched + issuer-checked in {@link OAuthClient}). */
    public void setMetadata(@NonNull OAuthClient.Metadata m) { this.metadata = m; }

    // ─────────────────────────── ENDPOINTS (§8) ───────────────────────────

    /** {@code GET /api/oauth/v1/me} — needs {@code profile}; email added when granted. */
    @NonNull public JSONObject me() throws IOException, OAuthClient.OAuthException {
        return get("me");
    }

    /** {@code GET /api/oauth/v1/account} — needs {@code account:read}. */
    @NonNull public JSONObject account() throws IOException, OAuthClient.OAuthException {
        return get("account");
    }

    /** {@code GET /api/oauth/v1/keys} — needs {@code keys:read}; masked key only. */
    @NonNull public JSONArray keys() throws IOException, OAuthClient.OAuthException {
        return get("keys").optJSONArray("keys");
    }

    /** {@code POST /api/oauth/v1/keys} — needs {@code keys:write}. Полный ключ
     *  отдаётся только здесь (один раз). */
    @NonNull public JSONObject createKey(String name) throws IOException, OAuthClient.OAuthException {
        JSONObject body = new JSONObject();
        try { body.put("name", name == null ? "" : name); } catch (Exception ignored) {}
        Request.Builder b = authorizedRequest("keys")
                .post(RequestBody.create(body.toString(), JSON));
        return execOnceAsJson(b, true);
    }

    /** {@code DELETE /api/oauth/v1/keys/{id}} — needs {@code keys:write}. 204. */
    public void deleteKey(long keyId) throws IOException, OAuthClient.OAuthException {
        Request.Builder b = authorizedRequest("keys/" + keyId).delete();
        execOnce(b, /*json*/false, /*retry*/true);
    }

    /** {@code PATCH /api/oauth/v1/keys/{id}} — needs {@code keys:write}
     *  (опциональный эндпоинт ТЗ §4.3; 404/405 — не реализован, ок). */
    @NonNull public JSONObject renameKey(long keyId, String name)
            throws IOException, OAuthClient.OAuthException {
        JSONObject body = new JSONObject();
        try { body.put("name", name == null ? "" : name); } catch (Exception ignored) {}
        Request.Builder b = authorizedRequest("keys/" + keyId)
                .method("PATCH", RequestBody.create(body.toString(), JSON));
        return execOnceAsJson(b, true);
    }

    /** {@code GET /api/oauth/v1/billing/offers} — needs {@code billing:read}. */
    @NonNull public JSONObject billingOffers() throws IOException, OAuthClient.OAuthException {
        return get("billing/offers");
    }

    /** {@code POST /api/oauth/v1/billing/checkout} — needs {@code billing:read}.
     *  tokensAmount ≠ null → покупка токенов (любое целое 1M..10B);
     *  иначе planId → покупка подписки. Возвращает checkout_url + expires_at. */
    @NonNull public JSONObject billingCheckout(Long tokensAmount, String planId)
            throws IOException, OAuthClient.OAuthException {
        JSONObject body = new JSONObject();
        try {
            if (tokensAmount != null) body.put("tokens_amount", tokensAmount);
            else body.put("plan_id", planId == null ? "" : planId);
        } catch (Exception ignored) {}
        Request.Builder b = authorizedRequest("billing/checkout")
                .post(RequestBody.create(body.toString(), JSON));
        return execOnceAsJson(b, true);
    }

    /** {@code GET /api/oauth/v1/billing/history} — needs {@code billing:read}. */
    @NonNull public JSONObject billingHistory(int limit, String cursor)
            throws IOException, OAuthClient.OAuthException {
        String path = "billing/history?limit=" + Math.max(1, Math.min(200, limit));
        if (cursor != null && !cursor.isEmpty()) path += "&cursor=" + cursor;
        return get(path);
    }

    /** {@code POST /api/oauth/v1/billing/promo} — needs {@code billing:read}. */
    @NonNull public JSONObject billingPromo(String code) throws IOException, OAuthClient.OAuthException {
        JSONObject body = new JSONObject();
        try { body.put("code", code == null ? "" : code); } catch (Exception ignored) {}
        Request.Builder b = authorizedRequest("billing/promo")
                .post(RequestBody.create(body.toString(), JSON));
        return execOnceAsJson(b, true);
    }

    /** Пагинируемая история всех AI-запросов. */
    @NonNull public JSONObject usageHistory(int limit, String cursor)
            throws IOException, OAuthClient.OAuthException {
        return usageHistory(limit, cursor, null, null);
    }

    /**
     * Пагинируемая история всех AI-запросов. Серверные версии встречаются с
     * cursor-, page- и offset-контрактами, поэтому клиент умеет передать любой
     * из continuation-параметров. Неиспользуемые параметры должны быть null.
     */
    @NonNull public JSONObject usageHistory(int limit, String cursor,
                                             Integer page, Integer offset)
            throws IOException, OAuthClient.OAuthException {
        StringBuilder path = new StringBuilder("usage/history?limit=")
                .append(Math.max(1, Math.min(500, limit)));
        if (cursor != null && !cursor.isEmpty()) {
            path.append("&cursor=")
                    .append(java.net.URLEncoder.encode(cursor, "UTF-8"));
        }
        if (page != null && page >= 0) {
            path.append("&page=").append(page);
        }
        if (offset != null && offset >= 0) {
            path.append("&offset=").append(offset);
        }
        return get(path.toString());
    }

    /** {@code GET /api/oauth/v1/usage} — needs {@code account:read}.
     *  days=0 → без параметра (серверный дефолт 30). */
    @NonNull public JSONObject usage(int days, Long keyId) throws IOException, OAuthClient.OAuthException {
        StringBuilder p = new StringBuilder("usage");
        boolean first = true;
        if (days > 0) { p.append("?days=").append(days); first = false; }
        if (keyId != null) p.append(first ? "?" : "&").append("key_id=").append(keyId);
        return get(p.toString());
    }

    /** {@code GET /api/oauth/v1/snapshot} — needs {@code account:read} + {@code keys:read}. */
    @NonNull public JSONObject snapshot() throws IOException, OAuthClient.OAuthException {
        return get("snapshot");
    }

    /** {@code PUT /api/oauth/v1/connection/key} — needs {@code keys:read} + {@code ai:invoke}. */
    public void selectKey(long keyId) throws IOException, OAuthClient.OAuthException {
        JSONObject body = new JSONObject();
        try { body.put("key_id", keyId); } catch (Exception ignored) {}
        putJson("connection/key", body.toString());
        store.setSelectedKeyId(keyId);
    }

    /** {@code DELETE /api/oauth/v1/connection/key} (idempotent). */
    public void clearSelectedKey() throws IOException, OAuthClient.OAuthException {
        Request.Builder b = authorizedRequest("connection/key").delete();
        execOnce(b, /*json*/false, /*retry*/true);
        store.clearSelectedKeyId();
    }

    /** {@code GET /api/oauth/v1/devices} — all active OAuth sessions for this user. */
    @NonNull public JSONObject devices() throws IOException, OAuthClient.OAuthException {
        return get("devices");
    }

    /** {@code DELETE /api/oauth/v1/devices/{id}} — revoke one OAuth token family. */
    public void revokeDevice(@Nullable String deviceId)
            throws IOException, OAuthClient.OAuthException {
        String id = deviceId == null ? "" : deviceId.trim();
        if (id.isEmpty() || !id.matches("[A-Za-z0-9._~-]+")) {
            throw new OAuthClient.OAuthException("invalid_request");
        }
        Request.Builder b = authorizedRequest("devices/" + id).delete();
        execOnce(b, /*json*/false, /*retry*/true);
    }

    /** {@code DELETE /api/oauth/v1/devices/others} — revoke every family except current. */
    @NonNull public JSONObject revokeOtherDevices()
            throws IOException, OAuthClient.OAuthException {
        Request.Builder b = authorizedRequest("devices/others").delete();
        return execOnceAsJson(b, true);
    }

    /** {@code POST /api/oauth/v1/devices/current/heartbeat} — throttled server activity. */
    public void heartbeatCurrentDevice() throws IOException, OAuthClient.OAuthException {
        Request.Builder b = authorizedRequest("devices/current/heartbeat")
                .post(RequestBody.create("{}", JSON));
        execOnce(b, /*json*/false, /*retry*/true);
    }

    /** Optional v2 policy endpoint; 404/501 means the backend has not rolled it out yet. */
    @NonNull public JSONObject deviceSettings()
            throws IOException, OAuthClient.OAuthException {
        return get("devices/settings");
    }

    /** {@code PUT /api/oauth/v1/devices/settings}. */
    @NonNull public JSONObject updateInactiveDeviceTtl(int days)
            throws IOException, OAuthClient.OAuthException {
        JSONObject body = new JSONObject();
        try {
            body.put("inactive_ttl_days", days);
        } catch (Exception ignored) {
            throw new OAuthClient.OAuthException("invalid_inactive_ttl");
        }
        Request.Builder b = authorizedRequest("devices/settings")
                .put(RequestBody.create(body.toString(), JSON));
        return execOnceAsJson(b, true);
    }

    // ─────────────────────────── HTTP + §10 retry ───────────────────────────

    @NonNull
    private JSONObject get(@NonNull String path) throws IOException, OAuthClient.OAuthException {
        Request.Builder b = authorizedRequest(path).get();
        return execOnceAsJson(b, true);
    }

    private void putJson(@NonNull String path, @NonNull String body)
            throws IOException, OAuthClient.OAuthException {
        Request.Builder b = authorizedRequest(path)
                .put(RequestBody.create(body, JSON));
        execOnce(b, true, true);
    }

    @NonNull
    private Request.Builder authorizedRequest(@NonNull String path) {
        if (metadata == null) throw new IllegalStateException("metadata not set");
        String url = OAuthConfig.getIssuer() + "/api/oauth/v1/" + path;
        Request.Builder b = new Request.Builder().url(url)
                .header("Accept", "application/json");
        String at = OAuthSession.get().getAccessToken();
        if (at != null) b.header("Authorization", "Bearer " + at);
        return b;
    }

    /** Single retry path: one process-wide rotation, then one retry. */
    @NonNull
    private HttpResp executeWithOneRetry(@NonNull Request.Builder initial)
            throws IOException, OAuthClient.OAuthException {
        Request firstRequest = initial.build();
        String rejectedAccessToken = bearerToken(firstRequest.header("Authorization"));
        if (rejectedAccessToken == null) {
            if (!OAuthRefreshCoordinator.refreshBlocking(context)) {
                throw new OAuthClient.OAuthException("invalid_grant");
            }
            firstRequest = reAuth(initial).build();
            rejectedAccessToken = bearerToken(firstRequest.header("Authorization"));
        }
        try (Response first = http.newCall(firstRequest).execute()) {
            int code = first.code();
            String body = first.body() != null ? first.body().string() : "";
            if (code != 401) return new HttpResp(code, body);
            if (!looksInvalidToken(body)) return new HttpResp(401, body);
        }

        if (!OAuthRefreshCoordinator.refreshBlocking(context, rejectedAccessToken)) {
            throw new OAuthClient.OAuthException("invalid_grant");
        }

        try (Response second = http.newCall(reAuth(initial).build()).execute()) {
            String body = second.body() != null ? second.body().string() : "";
            return new HttpResp(second.code(), body);
        }
    }

    private void execOnce(@NonNull Request.Builder b, boolean json, boolean retry)
            throws IOException, OAuthClient.OAuthException {
        HttpResp r = retry ? executeWithOneRetry(b) : executeRaw(b);
        if (r.code >= 200 && r.code < 300) return;
        throw makeError(r.body, r.code);
    }

    @NonNull
    private JSONObject execOnceAsJson(@NonNull Request.Builder b, boolean retry)
            throws IOException, OAuthClient.OAuthException {
        HttpResp r = retry ? executeWithOneRetry(b) : executeRaw(b);
        if (!(r.code >= 200 && r.code < 300)) throw makeError(r.body, r.code);
        return parseJson(r.body);
    }

    private HttpResp executeRaw(@NonNull Request.Builder b) throws IOException {
        try (Response r = http.newCall(b.build()).execute()) {
            String rb = r.body() != null ? r.body().string() : "";
            return new HttpResp(r.code(), rb);
        }
    }

    /** Tiny (code, body) pair so we can drain Response bodies before closing them. */
    private static final class HttpResp {
        final int code;
        final String body;
        HttpResp(int c, String b) { code = c; body = b; }
    }

    @NonNull
    private static OAuthClient.OAuthException makeError(@NonNull String rb, int code) {
        String err = "invalid_request";
        String detail = null;
        try {
            JSONObject body = new JSONObject(rb);
            JSONObject nested = body.optJSONObject("error");
            if (nested != null) {
                err = nested.optString("code", nested.optString("type", err));
                detail = nested.optString("message", null);
            } else {
                err = body.optString("error", err);
            }
            if (detail == null) detail = body.optString("error_description",
                    body.optString("message", body.optString("detail", null)));
        } catch (Exception ignored) {}
        return new OAuthClient.OAuthException(err, code, detail);
    }

    @NonNull
    private static JSONObject parseJson(@NonNull String rb) throws OAuthClient.OAuthException {
        try { return new JSONObject(rb); }
        catch (Exception ignored) {
            throw new OAuthClient.OAuthException("server_error", 0, null);
        }
    }

    @Nullable
    private static String bearerToken(@Nullable String authorization) {
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static boolean looksInvalidToken(@Nullable String body) {
        return body != null
                && (body.contains("invalid_oauth_token") || body.contains("invalid_token"));
    }

    private Request.Builder reAuth(@NonNull Request.Builder template) {
        Request copy = template.build();
        Request.Builder b = copy.newBuilder();
        String at = OAuthSession.get().getAccessToken();
        if (at != null) b.header("Authorization", "Bearer " + at);
        return b;
    }
}
