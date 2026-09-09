package app.multiai.mobile.network.oauth;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsIntent;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Authorization Code + PKCE orchestration for the native public client. */
public class OAuthLoginController {

    private static final String TAG = "OAuthLogin";

    public static final String ACTION_PROFILE_UPDATED =
            "app.multiai.mobile.action.OAUTH_PROFILE_UPDATED";

    private static final long ATTEMPT_TTL_MS = 10L * 60L * 1000L;
    private static final long MAX_TOKEN_LIFETIME_SECONDS = 31L * 24L * 60L * 60L;

    public interface Callback {
        @MainThread void onSuccess();
        @MainThread void onError(@NonNull String error, @Nullable String detail);
    }

    private final Context context;
    private final OAuthClient client;
    private final OAuthStore store;
    private final AccountApi accountApi;
    private static final Executor IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "oauth-login");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile OAuthClient.Metadata metadata;

    public OAuthLoginController(@NonNull Context context) {
        this.context = context.getApplicationContext();
        store = new OAuthStore(this.context);
        client = new OAuthClient();
        accountApi = new AccountApi(this.context, store);
    }

    public OAuthStore getStore() { return store; }
    public AccountApi getAccountApi() { return accountApi; }

    public void ensureMetadataAsync(@NonNull Runnable onReady,
                                    @NonNull java.util.function.Consumer<String> onError) {
        if (metadata != null) {
            onReady.run();
            return;
        }
        IO.execute(() -> {
            try {
                OAuthClient.Metadata loaded = client.fetchMetadata();
                metadata = loaded;
                accountApi.setMetadata(loaded);
                main.post(onReady);
            } catch (Exception error) {
                String code = error instanceof OAuthClient.OAuthException
                        ? ((OAuthClient.OAuthException) error).oauthError : "server_error";
                main.post(() -> onError.accept(code));
            }
        });
    }

    /** Creates a fresh one-shot PKCE attempt and opens the trusted system browser. */
    public void startLogin() {
        if (!store.isAvailable()) {
            Toast.makeText(context,
                    context.getString(app.multiai.mobile.R.string.oauth_secure_storage_unavailable),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ensureMetadataAsync(this::launchBrowser, error ->
                Toast.makeText(context,
                        context.getString(app.multiai.mobile.R.string.profile_oauth_error, error),
                        Toast.LENGTH_SHORT).show());
    }

    private void launchBrowser() {
        String verifier = PkceUtil.generateVerifier();
        String challenge = PkceUtil.computeChallenge(verifier);
        String state = PkceUtil.generateToken();
        String nonce = PkceUtil.generateToken();
        String redirect = OAuthConfig.getRedirectUri();

        OAuthSession.Attempt attempt = OAuthRefreshCoordinator.beginLoginAttempt(
                verifier, state, nonce, redirect, System.currentTimeMillis());
        if (attempt == null) return;

        final String url;
        try {
            url = client.buildAuthorizeUrl(metadata, challenge, state, nonce,
                    redirect, OAuthConfig.getScopes());
        } catch (Exception error) {
            OAuthRefreshCoordinator.cancelLoginAttempt(attempt.loginGeneration);
            return;
        }

        try {
            CustomTabsIntent tabs = new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(false)
                    .build();
            tabs.intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tabs.launchUrl(context, Uri.parse(url));
        } catch (Exception customTabsError) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception browserError) {
                OAuthRefreshCoordinator.cancelLoginAttempt(attempt.loginGeneration);
            }
        }
    }

    /**
     * Validates and atomically consumes an exact OAuth callback before exchange.
     * Returns false only when the intent is unrelated to the configured callback.
     */
    public boolean handleCallback(@NonNull Intent intent, @NonNull Callback callback) {
        Uri data = intent.getData();
        if (data == null || !isExactRedirect(data)) return false;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) {
            callback.onError("invalid_callback_intent", null);
            return true;
        }
        if (hasDuplicateCriticalParameter(data)
                || (hasValue(data, "code") && hasValue(data, "error"))) {
            callback.onError("invalid_request", null);
            return true;
        }

        OAuthSession session = OAuthSession.get();
        OAuthSession.Attempt snapshot = session.getAttempt();
        if (snapshot == null) {
            callback.onError("no_attempt", null);
            return true;
        }

        long now = System.currentTimeMillis();
        if (now < snapshot.createdAtMs || now - snapshot.createdAtMs > ATTEMPT_TTL_MS) {
            session.clearAttempt(snapshot.loginGeneration);
            callback.onError("stale_attempt", null);
            return true;
        }

        String state = data.getQueryParameter("state");
        if (!PkceUtil.constantTimeEquals(snapshot.state, state == null ? "" : state)) {
            // Do not clear a valid attempt for a foreign callback: that would enable DoS.
            callback.onError("bad_state", null);
            return true;
        }

        String issuer = data.getQueryParameter("iss");
        if (issuer == null || !PkceUtil.constantTimeEquals(issuer, OAuthConfig.getIssuer())) {
            callback.onError("bad_iss", null);
            return true;
        }

        OAuthSession.Attempt attempt = session.consumeAttempt(state, now, ATTEMPT_TTL_MS);
        if (attempt == null) {
            callback.onError("no_attempt", null);
            return true;
        }

        String oauthError = data.getQueryParameter("error");
        if (!isBlank(oauthError)) {
            callback.onError(oauthError, null);
            return true;
        }

        String code = data.getQueryParameter("code");
        if (isBlank(code)) {
            callback.onError("invalid_request", null);
            return true;
        }

        IO.execute(() -> exchangeAfterMetadata(code, attempt, callback));
        return true;
    }

    private void exchangeAfterMetadata(@NonNull String code,
                                       @NonNull OAuthSession.Attempt attempt,
                                       @NonNull Callback callback) {
        try {
            OAuthClient.Metadata current = metadata;
            if (current == null) {
                current = client.fetchMetadata();
                metadata = current;
                accountApi.setMetadata(current);
            }
            doExchange(current, code, attempt, callback);
        } catch (Exception error) {
            postOAuthError(callback, error);
        }
    }

    /** Validate everything first, then atomically publish through the coordinator. */
    private void doExchange(@NonNull OAuthClient.Metadata current,
                            @NonNull String code,
                            @NonNull OAuthSession.Attempt attempt,
                            @NonNull Callback callback) {
        try {
            OAuthClient.TokenResponse tokens = client.exchangeCode(current, code,
                    attempt.redirectUri, attempt.verifier);
            // Diagnostics per integration contract §6: scopes only — never tokens,
            // code, verifier, nonce or HMAC.
            Log.i(TAG, "scope requested: " + OAuthConfig.getScopes());
            Log.i(TAG, "scope granted:   " + (tokens.scope == null ? "" : tokens.scope));
            validateTokenResponse(tokens);

            long now = System.currentTimeMillis();
            OAuthStore.ProfileClaims idProfile = null;
            if (!isBlank(tokens.idToken)) {
                ensureJwks(current);
                IdTokenValidator.Result validation = IdTokenValidator.validate(
                        tokens.idToken,
                        JwksCache.get(),
                        OAuthConfig.getIssuer(),
                        OAuthConfig.getClientId(),
                        attempt.nonce,
                        now / 1000L);
                if (!validation.valid || validation.claims == null) {
                    throw new OAuthClient.OAuthException("bad_id_token", 0,
                            validation.error);
                }
                idProfile = parseProfileClaims(validation.claims, tokens.scope);
            }

            OAuthStore.ProfileClaims userProfile = parseProfileClaims(
                    client.fetchUserinfo(current, tokens.accessToken), tokens.scope);
            if (idProfile != null && !idProfile.sub.equals(userProfile.sub)) {
                throw new OAuthClient.OAuthException("userinfo_sub_mismatch");
            }
            OAuthStore.ProfileClaims profile = mergeProfiles(userProfile, idProfile, tokens.scope);
            OAuthRefreshCoordinator.LoginPublishResult result =
                    OAuthRefreshCoordinator.publishLogin(context, attempt.sessionEpoch,
                            attempt.loginGeneration, tokens, profile, now);
            if (result != OAuthRefreshCoordinator.LoginPublishResult.SUCCESS) {
                final String errorCode;
                switch (result) {
                    case STALE_ATTEMPT:
                        errorCode = "stale_attempt";
                        break;
                    case INVALID_RESPONSE:
                        errorCode = "invalid_token_response";
                        break;
                    case SECURE_STORAGE_UNAVAILABLE:
                        errorCode = "secure_storage_unavailable";
                        break;
                    case SECURE_STORAGE_WRITE_FAILED:
                    default:
                        errorCode = "secure_storage_write_failed";
                        break;
                }
                throw new OAuthClient.OAuthException(errorCode);
            }

            notifyProfileUpdated();
            main.post(callback::onSuccess);
        } catch (Exception error) {
            // Stale attempts never mutate state; durable storage rollback protects
            // the previous valid identity when publication fails.
            postOAuthError(callback, error);
        }
    }

    public boolean refreshStoredProfile() throws java.io.IOException, OAuthClient.OAuthException {
        OAuthClient.Metadata current = metadata;
        String accessToken = OAuthSession.get().getAccessToken();
        if (current == null || isBlank(accessToken)) return false;
        OAuthStore.ProfileClaims profile = parseProfileClaims(
                client.fetchUserinfo(current, accessToken), OAuthSession.get().getScope());
        if (!store.updateProfile(profile)) return false;
        notifyProfileUpdated();
        return true;
    }

    private void notifyProfileUpdated() {
        context.sendBroadcast(new Intent(ACTION_PROFILE_UPDATED).setPackage(context.getPackageName()));
    }

    @NonNull
    private static OAuthStore.ProfileClaims mergeProfiles(
            @NonNull OAuthStore.ProfileClaims primary,
            @Nullable OAuthStore.ProfileClaims fallback,
            @Nullable String scope) {
        if (fallback == null) return primary;
        return new OAuthStore.ProfileClaims(
                primary.sub,
                firstPresent(primary.name, fallback.name),
                firstPresent(primary.picture, fallback.picture),
                firstPresent(primary.email, fallback.email),
                firstPresent(primary.preferredUsername, fallback.preferredUsername),
                primary.telegram,
                primary.authTime > 0L ? primary.authTime : fallback.authTime,
                firstPresent(scope, fallback.scope));
    }

    @Nullable
    private static String firstPresent(@Nullable String primary, @Nullable String fallback) {
        return isBlank(primary) ? fallback : primary;
    }

    private static final class MissingScopesException extends Exception {
        @NonNull final String missingScopes;

        MissingScopesException(@NonNull List<String> missingScopes) {
            this.missingScopes = String.join(", ", missingScopes);
        }
    }

    private void validateTokenResponse(@NonNull OAuthClient.TokenResponse tokens)
            throws OAuthClient.OAuthException, MissingScopesException {
        if (isBlank(tokens.accessToken) || isBlank(tokens.refreshToken)
                || !tokens.accessToken.startsWith("ma-oauth-at-")
                || !tokens.refreshToken.startsWith("ma-oauth-rt-")) {
            throw new OAuthClient.OAuthException("invalid_token_response");
        }
        if (!"Bearer".equalsIgnoreCase(tokens.tokenType)) {
            throw new OAuthClient.OAuthException("unsupported_token_type");
        }
        if (tokens.expiresIn <= 0L || tokens.expiresIn > MAX_TOKEN_LIFETIME_SECONDS) {
            throw new OAuthClient.OAuthException("invalid_token_expiry");
        }
        List<String> missingScopes = OAuthConfig.getMissingScopes(tokens.scope);
        if (!missingScopes.isEmpty()) throw new MissingScopesException(missingScopes);
    }

    @NonNull
    private OAuthStore.ProfileClaims parseProfileClaims(@NonNull JSONObject claims,
                                                        @Nullable String scope)
            throws OAuthClient.OAuthException {
        String sub = claimText(claims, "sub");
        if (isBlank(sub)) throw new OAuthClient.OAuthException("missing_sub");

        String email = claimText(claims, "email");
        String name = claimText(claims, "name");
        String username = claimText(claims, "preferred_username");
        String picture = OAuthAvatar.fromProfileJson(claims);
        long authTime = claims.optLong("auth_time", 0L) * 1000L;
        boolean telegram = isBlank(email) && !isBlank(username) && username.startsWith("@");
        if (telegram && isBlank(name)) name = username;
        return new OAuthStore.ProfileClaims(sub, name, picture, email, username,
                telegram, authTime, scope);
    }

    private void ensureJwks(@NonNull OAuthClient.Metadata current)
            throws java.io.IOException, OAuthClient.OAuthException {
        if (!JwksCache.isFresh(System.currentTimeMillis())) {
            JwksCache.set(client.fetchJwks(current), System.currentTimeMillis());
        }
    }

    private void postOAuthError(@NonNull Callback callback, @NonNull Exception error) {
        final String code;
        final String safeDetail;
        if (error instanceof MissingScopesException) {
            code = "insufficient_scope";
            safeDetail = ((MissingScopesException) error).missingScopes;
        } else {
            code = error instanceof OAuthClient.OAuthException
                    ? ((OAuthClient.OAuthException) error).oauthError : "server_error";
            // Never forward provider response bodies or token-adjacent exception details.
            safeDetail = null;
        }
        main.post(() -> callback.onError(code, safeDetail));
    }

    private static boolean isExactRedirect(@NonNull Uri actual) {
        Uri expected = Uri.parse(OAuthConfig.getRedirectUri());
        if (actual.isOpaque() || actual.getUserInfo() != null || actual.getFragment() != null) {
            return false;
        }
        return equalsIgnoreCase(expected.getScheme(), actual.getScheme())
                && equalsIgnoreCase(expected.getHost(), actual.getHost())
                && effectivePort(expected) == effectivePort(actual)
                && safeEquals(expected.getEncodedPath(), actual.getEncodedPath());
    }

    private static int effectivePort(@NonNull Uri uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        if ("https".equalsIgnoreCase(uri.getScheme())) return 443;
        if ("http".equalsIgnoreCase(uri.getScheme())) return 80;
        return -1;
    }

    private static boolean hasDuplicateCriticalParameter(@NonNull Uri uri) {
        String[] critical = {"code", "state", "iss", "error"};
        for (String name : critical) {
            List<String> values = uri.getQueryParameters(name);
            if (values.size() > 1) return true;
        }
        return false;
    }

    private static boolean hasValue(@NonNull Uri uri, @NonNull String name) {
        String value = uri.getQueryParameter(name);
        return value != null && !value.isEmpty();
    }

    @Nullable
    private static String claimText(@NonNull JSONObject claims, @NonNull String key) {
        if (!claims.has(key) || claims.isNull(key)) return null;
        Object value = claims.opt(key);
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return isBlank(text) ? null : text;
    }

    private static boolean isBlank(@Nullable String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed);
    }

    private static boolean equalsIgnoreCase(@Nullable String left, @Nullable String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private static boolean safeEquals(@Nullable String left, @Nullable String right) {
        return left == null ? right == null : left.equals(right);
    }

    public void logout(@NonNull Consumer<Boolean> onFinished) {
        OAuthRefreshCoordinator.logout(context, onFinished);
    }
}
