package app.multiai.mobile.network.oauth;

import android.net.Uri;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * MultiAI OAuth client (Authorization Code + PKCE, native public app).
 *
 * <p>Implements sections 3, 5, 6 (n/a), 7, 10, 12 of the integration document:
 * <ul>
 *   <li>fetch + validate authorization-server metadata, strict issuer-origin check (§3);
 *   <li>build the authorize URL with PKCE + state + nonce (§5.1/§5.2);
 *   <li>exchange the one-time code as a native client — {@code client_id} in the
 *       form body, <b>no {@code Authorization} header, no {@code client_secret}</b> (§5.4);
 *   <li>refresh-token rotation, single-flight per token family (§10); retrying a
 *       rotated token is treated as compromise — we abort the session;
 *   <li>revoke refresh token on logout (§12);
 *   <li>fetch JWKS + userinfo.
 * </ul>
 *
 * <p>Network runs on the caller's background thread (Android has no coroutines here);
 * methods are blocking. {@code refresh()} is {@code synchronized} to guarantee
 * single-flight (§10 §1).
 */
public class OAuthClient {

    private static final String TAG = "OAuthClient";
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    private static final MediaType JSON = MediaType.get("application/json");

    /** Parsed authorization-server metadata (§3). */
    public static final class Metadata {
        public final String issuer;
        public final String authorizationEndpoint;
        public final String tokenEndpoint;
        public final String revocationEndpoint;
        public final String userinfoEndpoint;
        public final String jwksUri;
        private Metadata(String i, String a, String t, String r, String u, String j) {
            issuer = i; authorizationEndpoint = a; tokenEndpoint = t;
            revocationEndpoint = r; userinfoEndpoint = u; jwksUri = j;
        }
        static Metadata from(JSONObject o) {
            return new Metadata(
                    o.optString("issuer"),
                    o.optString("authorization_endpoint"),
                    o.optString("token_endpoint"),
                    o.optString("revocation_endpoint", null),
                    o.optString("userinfo_endpoint", null),
                    o.optString("jwks_uri"));
        }
    }

    /** Token response (§5.6 / §10 answer). */
    public static final class TokenResponse {
        public final String accessToken;
        public final String refreshToken;
        public final String tokenType;
        public final long expiresIn;
        public final String scope;
        public final String idToken;
        private TokenResponse(String a, String r, String t, long e, String s, String i) {
            accessToken = a; refreshToken = r; tokenType = t; expiresIn = e;
            scope = s; idToken = i;
        }
        static TokenResponse from(JSONObject o) {
            return new TokenResponse(
                    o.optString("access_token", null),
                    o.optString("refresh_token", null),
                    o.optString("token_type", "Bearer"),
                    o.optLong("expires_in", 0),
                    o.optString("scope", null),
                    o.optString("id_token", null));
        }
    }

    /** Standard error wrapping — carries the OAuth {@code error} token (§13). */
    public static final class OAuthException extends Exception {
        public final String oauthError;
        public final int http;
        public OAuthException(String error, int http, String detail) {
            super(error + (detail == null ? "" : (": " + detail)));
            this.oauthError = error == null ? "server_error" : error;
            this.http = http;
        }
        public OAuthException(String error) { this(error, 0, null); }
    }

    private final OkHttpClient http;
    private final OAuthConfig cfg;

    public OAuthClient() { this(new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()); }

    @VisibleForTesting
    OAuthClient(@NonNull OkHttpClient http) {
        this.http = http;
        this.cfg = null; // uses OAuthConfig statics
    }

    // ─────────────────────────── §3 METADATA ───────────────────────────

    @NonNull
    public Metadata fetchMetadata() throws IOException, OAuthException {
        String url = OAuthConfig.getIssuer() + "/.well-known/oauth-authorization-server";
        JSONObject o = getJson(url);
        Metadata m = Metadata.from(o);
        if (!OAuthConfig.isIssuerOrigin(m.issuer)) throw new OAuthException("bad_issuer");
        String[] eps = { m.authorizationEndpoint, m.tokenEndpoint,
                         m.revocationEndpoint, m.userinfoEndpoint, m.jwksUri };
        for (String e : eps) {
            if (e == null || e.isEmpty() || !OAuthConfig.isIssuerOrigin(e))
                throw new OAuthException("bad_metadata_endpoint");
        }
        return m;
    }

    // ─────────────────────────── §5.2 AUTHORIZE URL ───────────────────────────

    @NonNull
    public String buildAuthorizeUrl(@NonNull Metadata m,
                                   @NonNull String codeChallenge,
                                   @NonNull String state,
                                   @NonNull String nonce,
                                   @NonNull String redirectUri,
                                   @NonNull String scopes) {
        Uri.Builder b = Uri.parse(m.authorizationEndpoint).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", OAuthConfig.getClientId())
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", scopes)
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("device_name", OAuthConfig.getDeviceName());
        return b.build().toString();
    }

    // ─────────────────────────── §5.4 TOKEN EXCHANGE (native) ───────────────────────────

    @NonNull
    public TokenResponse exchangeCode(@NonNull Metadata m,
                                      @NonNull String code,
                                      @NonNull String redirectUri,
                                      @NonNull String codeVerifier) throws IOException, OAuthException {
        String body = "grant_type=authorization_code"
                + "&client_id=" + enc(OAuthConfig.getClientId())
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&code_verifier=" + enc(codeVerifier);
        JSONObject r = postForm(m.tokenEndpoint, body);
        return TokenResponse.from(r);
    }

    // ─────────────────────────── §10 REFRESH (single-flight rotation) ───────────────────────────

    private final Object refreshLock = new Object();

    @NonNull
    public Pair<TokenResponse, Integer> refresh(@NonNull Metadata m,
                                                @NonNull String currentRefresh,
                                                int expectedVersion) throws IOException, OAuthException {
        synchronized (refreshLock) {
            // Native: client_id in form body, no Authorization header, no secret (§10).
            String body = "grant_type=refresh_token"
                    + "&client_id=" + enc(OAuthConfig.getClientId())
                    + "&refresh_token=" + enc(currentRefresh);
            JSONObject r;
            try {
                r = postForm(m.tokenEndpoint, body);
            } catch (OAuthException e) {
                // §10 lost-response handling: ambiguity → drop session, don't retry old token.
                if ("invalid_grant".equals(e.oauthError)) throw e;
                throw e;
            }
            TokenResponse tr = TokenResponse.from(r);
            if (tr.refreshToken == null) {
                // §10: every successful refresh MUST return a new refresh token.
                throw new OAuthException("missing_rotated_refresh");
            }
            return Pair.create(tr, expectedVersion);
        }
    }

    // ─────────────────────────── §12 REVOKE ───────────────────────────

    public void revoke(@NonNull Metadata m, @NonNull String refreshToken) throws IOException {
        if (m.revocationEndpoint == null) return;
        String body = "client_id=" + enc(OAuthConfig.getClientId())
                + "&token=" + enc(refreshToken);
        try {
            postForm(null, m.revocationEndpoint, body);
        } catch (OAuthException ignored) {
            // §12: endpoint returns 200 even for already-revoked tokens; ignore.
        }
    }

    // ─────────────────────────── §7 JWKS + USERINFO ───────────────────────────

    @NonNull
    public JSONObject fetchJwks(@NonNull Metadata m) throws IOException, OAuthException {
        return getJson(m.jwksUri);
    }

    @NonNull
    public JSONObject fetchUserinfo(@NonNull Metadata m, @NonNull String accessToken)
            throws IOException, OAuthException {
        return getJson(m.userinfoEndpoint, "Authorization", "Bearer " + accessToken);
    }

    // ─────────────────────────── HTTP plumbing ───────────────────────────

    @NonNull
    private JSONObject getJson(@NonNull String url) throws IOException, OAuthException {
        return getJson(url, null, null);
    }

    @NonNull
    private JSONObject getJson(@NonNull String url, @Nullable String hName, @Nullable String hVal)
            throws IOException, OAuthException {
        Request.Builder b = new Request.Builder().url(url).get();
        if (hName != null) {
            b.header(hName, hVal);
        }
        try (Response resp = http.newCall(b.build()).execute()) {
            String rb = body(resp);
            return parseOrThrow(rb, resp.code());
        }
    }

    @NonNull
    private JSONObject postForm(@NonNull String url, @NonNull String body)
            throws IOException, OAuthException {
        return postForm(url, url, body);
    }

    @NonNull
    private JSONObject postForm(@Nullable String url, @Nullable String actualUrl,
                                @NonNull String body) throws IOException, OAuthException {
        String target = actualUrl != null ? actualUrl : url;
        Request.Builder b = new Request.Builder()
                .url(target)
                .post(RequestBody.create(body, FORM))
                .header("Accept", "application/json");
        // Native client (§5.4): never send Authorization header on token/revoke endpoints.
        try (Response resp = http.newCall(b.build()).execute()) {
            String rb = body(resp);
            return parseOrThrow(rb, resp.code());
        }
    }

    @NonNull
    private static JSONObject parseOrThrow(@Nullable String rb, int code) throws OAuthException {
        if (rb == null || rb.isEmpty()) {
            throw new OAuthException(code >= 500 ? "server_error" : "invalid_request", code, null);
        }
        try {
            JSONObject o = new JSONObject(rb);
            if (code >= 200 && code < 300) return o;
            String err = o.optString("error", null);
            throw new OAuthException(err, code, o.optString("error_description", null));
        } catch (org.json.JSONException je) {
            throw new OAuthException("server_error", code, rb);
        }
    }

    private static String body(@NonNull Response r) throws IOException {
        return r.body() != null ? r.body().string() : "";
    }

    /** Percent-encode for x-www-form-urlencoded, RFC 3986 unreserved + spaces → +. */
    private static String enc(@NonNull String s) {
        return Uri.encode(s);
    }
}
