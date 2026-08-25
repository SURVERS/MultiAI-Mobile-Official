package app.multiai.mobile.network.oauth;

import android.net.Uri;

import androidx.annotation.NonNull;

import app.multiai.mobile.BuildConfig;

/**
 * MultiAI OAuth client configuration.
 *
 * <p>All values come from {@link BuildConfig} (defined per build variant in
 * {@code app/build.gradle}). These are <b>not secrets</b>: a native public client
 * has no client_secret per RFC 8252 / the MultiAI integration contract
 * (§2 & §5.4 — native token requests carry only {@code client_id} in the form body).
 *
 * <p>The production issuer is always {@code https://multiai.store}; the client must
 * compare metadata's {@code issuer} against it (§1, §3) and never substitute a
 * local address.
 */
public final class OAuthConfig {

    private OAuthConfig() {}

    @NonNull public static String getIssuer()      { return BuildConfig.OAUTH_ISSUER; }
    @NonNull public static String getClientId()     { return BuildConfig.OAUTH_CLIENT_ID; }
    @NonNull public static String getRedirectUri()  { return BuildConfig.OAUTH_REDIRECT_URI; }
    @NonNull public static String getScopes()      { return BuildConfig.OAUTH_SCOPES; }

    /** Short human-readable label for the user's connected-devices list (§5.2):
     *  марка + модель телефона (например «Tecno KL7»), как в системных настройках. */
    @NonNull public static String getDeviceName() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        // У многих вендоров MODEL уже включает бренд («Tecno KL7»),
        // тогда не дублируем производителя.
        if (model != null && manufacturer != null
                && model.toLowerCase().contains(manufacturer.toLowerCase())) {
            return model;
        }
        String man = manufacturer == null ? "" : manufacturer.trim();
        String mod = model == null ? "" : model.trim();
        if (man.isEmpty()) return mod.isEmpty() ? "Android" : mod;
        if (mod.isEmpty()) return man;
        return man + " " + mod;
    }

    /**
     * Strict origin check (§3): every endpoint in the authorization-server metadata
     * must use the same trusted HTTPS origin as the configured issuer.
     */
    public static boolean isIssuerOrigin(@NonNull String url) {
        Uri configured = Uri.parse(getIssuer());
        Uri candidate  = Uri.parse(url);
        return "https".equals(candidate.getScheme())
                && eq(configured.getHost(), candidate.getHost())
                && configured.getPort() == candidate.getPort();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
