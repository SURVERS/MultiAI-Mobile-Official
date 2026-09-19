package app.multiai.mobile.network.oauth;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/** Нормализует канонический avatar из OAuth userinfo и account snapshot. */
public final class OAuthAvatar {

    private static final int MAX_URL_LENGTH = 4096;

    private OAuthAvatar() {}

    /**
     * Backend-контракт допускает {@code picture} в userinfo и {@code avatar_url}
     * в account snapshot. Старые/переходные aliases читаются только как fallback.
     * Вложенный объект {@code avatar} тоже проверяется (например {@code avatar.url}).
     */
    @Nullable
    public static String fromProfileJson(@Nullable JSONObject profile) {
        if (profile == null) return null;
        String[] keys = {"picture", "avatar_url", "avatarUrl", "photo_url", "photoUrl", "avatar"};
        for (String key : keys) {
            String value = text(profile, key);
            String normalized = normalizeRemote(value);
            if (normalized != null) return normalized;
        }

        JSONObject avatar = profile.optJSONObject("avatar");
        if (avatar != null) {
            String[] nestedKeys = {"url", "src", "href", "picture"};
            for (String key : nestedKeys) {
                String normalized = normalizeRemote(text(avatar, key));
                if (normalized != null) return normalized;
            }
        }

        // Fallback: snapshot может хранить avatar_url вне объекта user (редкий legacy-формат)
        String direct = normalizeRemote(text(profile, "avatar_url"));
        if (direct != null) return direct;

        return null;
    }

    /** Resolves relative canonical paths against the trusted production issuer. */
    @Nullable
    public static String normalizeRemote(@Nullable String raw) {
        String value = clean(raw);
        if (value == null) return null;
        if (value.startsWith("//")) value = "https:" + value;

        Uri parsed = Uri.parse(value);
        if (parsed.getScheme() == null) {
            String issuer = OAuthConfig.getIssuer();
            while (issuer.endsWith("/")) issuer = issuer.substring(0, issuer.length() - 1);
            value = issuer + (value.startsWith("/") ? value : "/" + value);
            parsed = Uri.parse(value);
        }

        if (!parsed.isHierarchical()
                || !"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getHost() == null
                || parsed.getHost().trim().isEmpty()
                || parsed.getUserInfo() != null) {
            return null;
        }
        return parsed.toString();
    }

    /** Keeps local gallery URIs working while applying strict rules to remote values. */
    @Nullable
    public static String resolveForGlide(@Nullable String raw) {
        String value = clean(raw);
        if (value == null) return null;
        Uri parsed = Uri.parse(value);
        String scheme = parsed.getScheme();
        if ("content".equalsIgnoreCase(scheme)
                || "file".equalsIgnoreCase(scheme)
                || "android.resource".equalsIgnoreCase(scheme)) {
            return value;
        }
        return normalizeRemote(value);
    }

    @Nullable
    private static String text(@NonNull JSONObject object, @NonNull String key) {
        if (!object.has(key) || object.isNull(key)) return null;
        Object value = object.opt(key);
        return value instanceof String ? clean((String) value) : null;
    }

    @Nullable
    private static String clean(@Nullable String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)
                || value.length() > MAX_URL_LENGTH) return null;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return null;
        }
        return value;
    }
}
