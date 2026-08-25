package app.multiai.mobile.network.oauth;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Process-wide OAuth token-family coordinator.
 *
 * <p>The monitor protects only short state/storage transitions; network I/O always
 * runs outside it. Refresh is single-flight and durably marked before a rotating
 * token can reach the server. Login publication, refresh completion and logout
 * invalidation are generation-checked so stale operations cannot replace a newer
 * account or revive an older token family.
 */
public final class OAuthRefreshCoordinator {

    public enum LoginPublishResult {
        SUCCESS,
        STALE_ATTEMPT,
        INVALID_RESPONSE,
        SECURE_STORAGE_UNAVAILABLE,
        SECURE_STORAGE_WRITE_FAILED
    }

    private static final long MAX_TOKEN_LIFETIME_SECONDS = 31L * 24L * 60L * 60L;
    private static final Object TOKEN_FAMILY_LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile OAuthClient.Metadata cachedMetadata;

    // Guarded by TOKEN_FAMILY_LOCK.
    private static long sessionEpoch;
    private static long loginGeneration;
    private static long tokenFamilyGeneration;
    private static boolean refreshInFlight;
    private static int pendingLogoutCount;

    private OAuthRefreshCoordinator() {
    }

    /** Atomically supersedes every older browser/exchange attempt. */
    @Nullable
    public static OAuthSession.Attempt beginLoginAttempt(@NonNull String verifier,
                                                         @NonNull String state,
                                                         @NonNull String nonce,
                                                         @NonNull String redirectUri,
                                                         long createdAtMs) {
        synchronized (TOKEN_FAMILY_LOCK) {
            if (pendingLogoutCount > 0) return null;
            long generation = ++loginGeneration;
            OAuthSession.Attempt attempt = new OAuthSession.Attempt(
                    verifier, state, nonce, redirectUri, createdAtMs,
                    sessionEpoch, generation);
            OAuthSession.get().startAttempt(attempt);
            return attempt;
        }
    }

    /** An older controller cannot cancel a newer login attempt. */
    public static void cancelLoginAttempt(long expectedLoginGeneration) {
        synchronized (TOKEN_FAMILY_LOCK) {
            OAuthSession.get().clearAttempt(expectedLoginGeneration);
        }
    }

    /**
     * Publish a fully validated login under the same transition lock as refresh.
     * Encrypted identity is committed durably before the access token is exposed.
     */
    @NonNull
    public static LoginPublishResult publishLogin(@Nullable Context context,
                                                   long expectedEpoch,
                                                   long expectedLoginGeneration,
                                                   @Nullable OAuthClient.TokenResponse tokens,
                                                   @Nullable OAuthStore.ProfileClaims profile,
                                                   long nowMs) {
        if (context == null) return LoginPublishResult.SECURE_STORAGE_UNAVAILABLE;
        if (!validLoginResponse(tokens, profile, nowMs)) {
            return LoginPublishResult.INVALID_RESPONSE;
        }

        synchronized (TOKEN_FAMILY_LOCK) {
            if (pendingLogoutCount > 0
                    || expectedEpoch != sessionEpoch
                    || expectedLoginGeneration != loginGeneration) {
                return LoginPublishResult.STALE_ATTEMPT;
            }

            OAuthStore store = new OAuthStore(context.getApplicationContext());
            if (!store.isAvailable()) {
                return LoginPublishResult.SECURE_STORAGE_UNAVAILABLE;
            }
            if (!store.commitLogin(tokens.refreshToken, profile)) {
                return LoginPublishResult.SECURE_STORAGE_WRITE_FAILED;
            }

            tokenFamilyGeneration++;
            OAuthSession.get().setAccessToken(tokens.accessToken, tokens.tokenType,
                    tokens.expiresIn, tokens.scope, nowMs);
            return LoginPublishResult.SUCCESS;
        }
    }

    /** Restore an access token after process start, or reuse an already fresh one. */
    public static boolean refreshBlocking(@Nullable Context context) {
        return refreshBlocking(context, null);
    }

    /**
     * Single-flight rotating refresh. Only durable state transitions use the monitor;
     * metadata and token HTTP calls never block login/logout on the main thread.
     */
    public static boolean refreshBlocking(@Nullable Context context,
                                          @Nullable String rejectedAccessToken) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        OAuthSession session = OAuthSession.get();

        if (canReuseCurrent(session, rejectedAccessToken)) return true;

        final OAuthStore operationStore;
        final String currentRefresh;
        final int expectedVersion;
        final long operationEpoch;
        final long operationFamilyGeneration;

        synchronized (TOKEN_FAMILY_LOCK) {
            if (pendingLogoutCount > 0) return false;
            if (canReuseCurrent(session, rejectedAccessToken)) return true;

            while (refreshInFlight) {
                try {
                    TOKEN_FAMILY_LOCK.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (pendingLogoutCount > 0) return false;
                if (canReuseCurrent(session, rejectedAccessToken)) return true;
            }

            operationStore = new OAuthStore(app);
            if (!operationStore.isAvailable()) {
                session.clearTokens();
                return false;
            }
            if (operationStore.isRefreshInFlight()) {
                invalidateCurrentFamilyLocked(operationStore, session);
                return false;
            }

            currentRefresh = operationStore.getRefreshToken();
            expectedVersion = operationStore.getRefreshVersion();
            if (isBlank(currentRefresh) || expectedVersion < 1) {
                session.clearTokens();
                return false;
            }
            if (!operationStore.beginRefresh(expectedVersion)) {
                invalidateCurrentFamilyLocked(operationStore, session);
                return false;
            }

            refreshInFlight = true;
            operationEpoch = sessionEpoch;
            operationFamilyGeneration = tokenFamilyGeneration;
        }

        OAuthClient client = new OAuthClient();
        OAuthClient.Metadata operationMetadata = cachedMetadata;
        OAuthClient.TokenResponse refreshedTokens = null;
        Exception networkFailure = null;
        boolean requestAttempted = false;
        try {
            if (operationMetadata == null) {
                operationMetadata = client.fetchMetadata();
                cachedMetadata = operationMetadata;
            }
            requestAttempted = true;
            refreshedTokens = client.refresh(
                    operationMetadata, currentRefresh, expectedVersion).first;
        } catch (Exception failure) {
            networkFailure = failure;
        }

        boolean success = false;
        String staleRefreshToRevoke = null;
        synchronized (TOKEN_FAMILY_LOCK) {
            try {
                boolean staleFamily = pendingLogoutCount > 0
                        || operationEpoch != sessionEpoch
                        || operationFamilyGeneration != tokenFamilyGeneration;
                if (staleFamily) {
                    // A newer login/logout owns storage now. Never clear or CAS it.
                    if (requestAttempted && networkFailure == null
                            && validRotatedResponse(refreshedTokens)) {
                        staleRefreshToRevoke = refreshedTokens.refreshToken;
                    }
                } else if (networkFailure != null) {
                    if (!requestAttempted) {
                        // Discovery failed before the rotating token was presented.
                        if (!operationStore.cancelRefresh(expectedVersion)) {
                            invalidateCurrentFamilyLocked(operationStore, session);
                        }
                    } else {
                        // The server may have consumed the old token: fail closed.
                        invalidateCurrentFamilyLocked(operationStore, session);
                    }
                } else if (!validRotatedResponse(refreshedTokens)
                        || !operationStore.storeRefreshTokenCas(
                                refreshedTokens.refreshToken, expectedVersion)) {
                    invalidateCurrentFamilyLocked(operationStore, session);
                } else {
                    tokenFamilyGeneration++;
                    session.setAccessToken(refreshedTokens.accessToken,
                            refreshedTokens.tokenType, refreshedTokens.expiresIn,
                            refreshedTokens.scope, System.currentTimeMillis());
                    success = true;
                }
            } finally {
                refreshInFlight = false;
                TOKEN_FAMILY_LOCK.notifyAll();
            }
        }

        if (!isBlank(staleRefreshToRevoke)) {
            revokeBestEffort(operationMetadata, staleRefreshToRevoke);
        }
        return success;
    }

    /**
     * Durably invalidates local auth before best-effort remote revoke. Queueing is
     * ordered under the monitor, so an older logout can never run after a newer one.
     */
    public static void logout(@NonNull Context context,
                              @NonNull Consumer<Boolean> onFinished) {
        Context app = context.getApplicationContext();
        OAuthSession session = OAuthSession.get();

        synchronized (TOKEN_FAMILY_LOCK) {
            sessionEpoch++;
            loginGeneration++;
            tokenFamilyGeneration++;
            pendingLogoutCount++;
            session.clearTokens();
            session.clearAttempt();

            final boolean initiallyInvalidated = OAuthStore.invalidateTokenFamily(app);
            try {
                // Submit while still holding the monitor: queue order == epoch order.
                IO.execute(() -> performLogout(app, initiallyInvalidated, onFinished));
            } catch (RuntimeException rejected) {
                pendingLogoutCount--;
                TOKEN_FAMILY_LOCK.notifyAll();
                MAIN.post(() -> onFinished.accept(initiallyInvalidated));
            }
        }
    }

    private static void performLogout(@NonNull Context app,
                                      boolean initiallyInvalidated,
                                      @NonNull Consumer<Boolean> onFinished) {
        boolean durableWipe = initiallyInvalidated;
        String refreshToRevoke = null;
        try {
            OAuthStore store = new OAuthStore(app);
            refreshToRevoke = store.getRefreshTokenForRevocation();
            durableWipe = store.clearAll() || durableWipe;
        } catch (RuntimeException ignored) {
        }

        if (!isBlank(refreshToRevoke)) {
            revokeBestEffort(cachedMetadata, refreshToRevoke);
        }

        synchronized (TOKEN_FAMILY_LOCK) {
            OAuthSession.get().clearTokens();
            OAuthSession.get().clearAttempt();
            if (pendingLogoutCount > 0) pendingLogoutCount--;
            TOKEN_FAMILY_LOCK.notifyAll();
        }

        boolean result = durableWipe;
        MAIN.post(() -> onFinished.accept(result));
    }

    private static void revokeBestEffort(@Nullable OAuthClient.Metadata knownMetadata,
                                         @NonNull String refreshToken) {
        try {
            OAuthClient client = new OAuthClient();
            OAuthClient.Metadata metadata = knownMetadata;
            if (metadata == null) {
                metadata = client.fetchMetadata();
                cachedMetadata = metadata;
            }
            client.revoke(metadata, refreshToken);
        } catch (Exception ignored) {
            // Local invalidation is authoritative; remote revoke is best effort.
        }
    }

    private static boolean canReuseCurrent(@NonNull OAuthSession session,
                                           @Nullable String rejectedAccessToken) {
        if (!session.isAccessTokenFresh(System.currentTimeMillis())) return false;
        if (rejectedAccessToken == null) return true;
        String current = session.getAccessToken();
        return current != null && !current.equals(rejectedAccessToken);
    }

    private static boolean validLoginResponse(@Nullable OAuthClient.TokenResponse tokens,
                                              @Nullable OAuthStore.ProfileClaims profile,
                                              long nowMs) {
        return tokens != null
                && profile != null
                && !isBlank(profile.sub)
                && !isBlank(tokens.accessToken)
                && !isBlank(tokens.refreshToken)
                && "Bearer".equalsIgnoreCase(tokens.tokenType)
                && tokens.expiresIn > 0L
                && tokens.expiresIn <= MAX_TOKEN_LIFETIME_SECONDS
                && nowMs > 0L;
    }

    private static boolean validRotatedResponse(@Nullable OAuthClient.TokenResponse tokens) {
        return tokens != null
                && !isBlank(tokens.accessToken)
                && !isBlank(tokens.refreshToken)
                && "Bearer".equalsIgnoreCase(tokens.tokenType)
                && tokens.expiresIn > 0L
                && tokens.expiresIn <= MAX_TOKEN_LIFETIME_SECONDS;
    }

    private static boolean invalidateCurrentFamilyLocked(@NonNull OAuthStore store,
                                                         @NonNull OAuthSession session) {
        boolean durable = store.clearAll();
        tokenFamilyGeneration++;
        session.clearTokens();
        return durable;
    }

    private static boolean isBlank(@Nullable String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed);
    }
}
