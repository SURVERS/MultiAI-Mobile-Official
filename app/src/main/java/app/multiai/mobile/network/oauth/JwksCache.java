package app.multiai.mobile.network.oauth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * In-memory JWKS cache for RS256 ID-token validation (integration doc §7).
 *
 * <p>Stores the parsed {@code {"keys":[...]}} document fetched from
 * {@code jwks_uri}. {@link IdTokenValidator} rejects any token whose {@code kid}
 * is not present; when that happens the call-site should fetch JWKS again
 * (keys rotate) and retry once — that logic lives in {@code OAuthClient} since it
 * needs network access, which this pure-data class does not.
 *
 * <p>Thread-confined to the login/token thread (no synchronization needed); kept
 * simple on purpose.
 */
public final class JwksCache {

    private static volatile JSONObject current;
    private static volatile long fetchedAtMillis;
    private static volatile long ttlMillis = 60 * 60 * 1000L; // 1h default

    private JwksCache() {}

    public static void setTtlMillis(long ttl) { ttlMillis = ttl; }

    public static boolean isFresh(long nowMillis) {
        return current != null && (nowMillis - fetchedAtMillis) < ttlMillis;
    }

    @Nullable public static JSONObject get() { return current; }

    public static void set(@NonNull JSONObject jwks, long nowMillis) {
        current = jwks;
        fetchedAtMillis = nowMillis;
    }

    public static void clear() {
        current = null;
        fetchedAtMillis = 0;
    }
}
