package app.multiai.mobile.network.oauth;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    private static final List<String> REQUIRED_SCOPES = buildRequiredScopes();

    private OAuthConfig() {}

    @NonNull public static String getIssuer()      { return BuildConfig.OAUTH_ISSUER; }
    @NonNull public static String getClientId()     { return BuildConfig.OAUTH_CLIENT_ID; }
    @NonNull public static String getRedirectUri()  { return BuildConfig.OAUTH_REDIRECT_URI; }
    @NonNull public static String getScopes()       { return BuildConfig.OAUTH_SCOPES; }

    /** Required scopes in authorization-request order, without duplicates. */
    @NonNull
    public static List<String> getRequiredScopes() {
        return REQUIRED_SCOPES;
    }

    /** Missing required scopes in the same stable order shown to the user. */
    @NonNull
    public static List<String> getMissingScopes(@Nullable String grantedScopes) {
        Set<String> granted = new HashSet<>();
        if (grantedScopes != null) {
            String trimmed = grantedScopes.trim();
            if (!trimmed.isEmpty()) {
                Collections.addAll(granted, trimmed.split("\\s+"));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_SCOPES) {
            if (!granted.contains(required)) missing.add(required);
        }
        return missing;
    }

    public static boolean hasRequiredScopes(@Nullable String grantedScopes) {
        return getMissingScopes(grantedScopes).isEmpty();
    }

    @NonNull
    private static List<String> buildRequiredScopes() {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        String configured = BuildConfig.OAUTH_SCOPES == null ? "" : BuildConfig.OAUTH_SCOPES.trim();
        if (!configured.isEmpty()) Collections.addAll(scopes, configured.split("\\s+"));
        return Collections.unmodifiableList(new ArrayList<>(scopes));
    }

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
