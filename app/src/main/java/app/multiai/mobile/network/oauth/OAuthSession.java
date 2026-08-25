package app.multiai.mobile.network.oauth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Process-memory OAuth access state and one-shot PKCE attempt. */
public final class OAuthSession {

    public static final class Attempt {
        public final String verifier;
        public final String state;
        public final String nonce;
        public final String redirectUri;
        public final long createdAtMs;
        public final long sessionEpoch;
        public final long loginGeneration;

        public Attempt(String verifier, String state, String nonce, String redirectUri,
                       long createdAtMs, long sessionEpoch, long loginGeneration) {
            this.verifier = verifier;
            this.state = state;
            this.nonce = nonce;
            this.redirectUri = redirectUri;
            this.createdAtMs = createdAtMs;
            this.sessionEpoch = sessionEpoch;
            this.loginGeneration = loginGeneration;
        }
    }

    private static volatile OAuthSession instance;

    private volatile String accessToken;
    private volatile String tokenType = "Bearer";
    private volatile long expiresAtMs;
    private volatile String scope;
    private volatile long generation;
    private Attempt currentAttempt;

    private OAuthSession() {
    }

    @NonNull
    public static OAuthSession get() {
        OAuthSession session = instance;
        if (session == null) {
            synchronized (OAuthSession.class) {
                session = instance;
                if (session == null) {
                    session = new OAuthSession();
                    instance = session;
                }
            }
        }
        return session;
    }

    public synchronized void setAccessToken(@Nullable String token, @Nullable String type,
                                            long expiresInSec, @Nullable String scope,
                                            long nowMs) {
        accessToken = token;
        tokenType = type != null && !type.isEmpty() ? type : "Bearer";
        expiresAtMs = expiresInSec > 0 ? nowMs + expiresInSec * 1000L : 0L;
        this.scope = scope;
        generation++;
    }

    @Nullable public String getAccessToken() { return accessToken; }
    @NonNull public String getTokenType() { return tokenType; }
    @Nullable public String getScope() { return scope; }
    public long getGeneration() { return generation; }

    public boolean isAccessTokenFresh(long nowMs) {
        return accessToken != null
                && (expiresAtMs == 0L || nowMs + 10_000L < expiresAtMs);
    }

    public boolean isAuthorized() {
        return isAccessTokenFresh(System.currentTimeMillis());
    }

    public synchronized void startAttempt(@NonNull Attempt attempt) {
        currentAttempt = attempt;
    }

    @Nullable
    public synchronized Attempt getAttempt() {
        return currentAttempt;
    }

    /**
     * Atomically consumes an unexpired attempt only when the callback state matches.
     * A foreign bad-state callback cannot erase the user's active login attempt.
     */
    @Nullable
    public synchronized Attempt consumeAttempt(@Nullable String state, long nowMs,
                                               long maxAgeMs) {
        Attempt attempt = currentAttempt;
        if (attempt == null) return null;
        if (maxAgeMs <= 0L || nowMs < attempt.createdAtMs
                || nowMs - attempt.createdAtMs > maxAgeMs) {
            currentAttempt = null;
            return null;
        }
        if (!PkceUtil.constantTimeEquals(attempt.state, state == null ? "" : state)) {
            return null;
        }
        currentAttempt = null;
        return attempt;
    }

    public synchronized void clearAttempt() {
        currentAttempt = null;
    }

    /** Does not let an older controller erase a newer browser attempt. */
    public synchronized void clearAttempt(long expectedLoginGeneration) {
        if (currentAttempt != null
                && currentAttempt.loginGeneration == expectedLoginGeneration) {
            currentAttempt = null;
        }
    }

    public synchronized void clearTokens() {
        accessToken = null;
        tokenType = "Bearer";
        scope = null;
        expiresAtMs = 0L;
        generation++;
    }
}
