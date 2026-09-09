package app.multiai.mobile.network.oauth;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Fail-closed OAuth storage backed only by EncryptedSharedPreferences. */
public final class OAuthStore {

    private static final String PREFS_FILE = "multiai_oauth_secure";
    private static final String DISABLED_PREFS_FILE = PREFS_FILE + "_DISABLED";
    private static final String GUARD_PREFS_FILE = "multiai_oauth_guard";
    private static final String K_INVALIDATED = "token_family_invalidated";
    private static final Object STORE_LOCK = new Object();

    private static final String K_REFRESH = "refresh_token";
    private static final String K_REFRESH_VER = "refresh_version";
    private static final String K_REFRESH_IN_FLIGHT = "refresh_in_flight";
    private static final String K_SUB = "sub";
    private static final String K_NAME = "name";
    private static final String K_PICTURE = "picture";
    private static final String K_EMAIL = "email";
    private static final String K_USERNAME = "preferred_username";
    private static final String K_TELEGRAM = "is_telegram";
    private static final String K_AUTH_TIME = "auth_time";
    private static final String K_SCOPE = "scope";
    private static final String K_SELECTED_KEY = "selected_key_id";

    @NonNull private final Context appContext;
    @NonNull private final SharedPreferences guardPrefs;
    @Nullable private final SharedPreferences prefs;
    private final boolean available;

    public static final class ProfileClaims {
        @NonNull public final String sub;
        @Nullable public final String name;
        @Nullable public final String picture;
        @Nullable public final String email;
        @Nullable public final String preferredUsername;
        public final boolean telegram;
        public final long authTime;
        @Nullable public final String scope;

        public ProfileClaims(@NonNull String sub,
                             @Nullable String name,
                             @Nullable String picture,
                             @Nullable String email,
                             @Nullable String preferredUsername,
                             boolean telegram,
                             long authTime,
                             @Nullable String scope) {
            this.sub = sub;
            this.name = name;
            this.picture = picture;
            this.email = email;
            this.preferredUsername = preferredUsername;
            this.telegram = telegram;
            this.authTime = authTime;
            this.scope = scope;
        }
    }

    public OAuthStore(@NonNull Context context) {
        appContext = context.getApplicationContext();
        guardPrefs = appContext.getSharedPreferences(GUARD_PREFS_FILE, Context.MODE_PRIVATE);
        SharedPreferences encrypted = null;
        try {
            androidx.security.crypto.MasterKey masterKey =
                    new androidx.security.crypto.MasterKey.Builder(appContext)
                            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                            .build();
            encrypted = androidx.security.crypto.EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_FILE,
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception ignored) {
            try {
                appContext.getSharedPreferences(DISABLED_PREFS_FILE, Context.MODE_PRIVATE)
                        .edit().clear().commit();
            } catch (Exception ignoredToo) {
            }
        }
        prefs = encrypted;
        available = encrypted != null;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isInvalidated() {
        try {
            return guardPrefs.getBoolean(K_INVALIDATED, false);
        } catch (RuntimeException corruptGuard) {
            return true;
        }
    }

    /**
     * Fast durable logout barrier that does not initialize encrypted storage.
     * A tombstone or deletion is enough to prevent restoration after process death.
     */
    public static boolean invalidateTokenFamily(@NonNull Context context) {
        Context app = context.getApplicationContext();
        boolean tombstoned = false;
        try {
            tombstoned = app.getSharedPreferences(GUARD_PREFS_FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(K_INVALIDATED, true).commit();
        } catch (RuntimeException ignored) {
        }
        if (tombstoned) return true;
        try {
            return app.deleteSharedPreferences(PREFS_FILE);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public boolean hasRefreshToken() {
        return getRefreshToken() != null;
    }

    @Nullable
    public String getRefreshToken() {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated()) return null;
        try {
            if (encrypted.getBoolean(K_REFRESH_IN_FLIGHT, false)) return null;
            String token = sane(encrypted.getString(K_REFRESH, null));
            return token != null && token.startsWith("ma-oauth-rt-") ? token : null;
        } catch (RuntimeException corruptStore) {
            return null;
        }
    }

    /** Raw token is exposed only for best-effort revocation after tombstoning. */
    @Nullable
    String getRefreshTokenForRevocation() {
        SharedPreferences encrypted = prefs;
        if (encrypted == null) return null;
        try {
            return sane(encrypted.getString(K_REFRESH, null));
        } catch (RuntimeException corruptStore) {
            return null;
        }
    }

    public int getRefreshVersion() {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated()) return 0;
        try {
            return encrypted.getBoolean(K_REFRESH_IN_FLIGHT, false)
                    ? 0 : encrypted.getInt(K_REFRESH_VER, 0);
        } catch (RuntimeException corruptStore) {
            return 0;
        }
    }

    public boolean isRefreshInFlight() {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated()) return false;
        try {
            return encrypted.getBoolean(K_REFRESH_IN_FLIGHT, false);
        } catch (RuntimeException corruptStore) {
            return true;
        }
    }

    /** Durably marks a rotating token as consumed before any network request. */
    public boolean beginRefresh(int expectedVersion) {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated() || expectedVersion < 1) return false;
        synchronized (STORE_LOCK) {
            try {
                if (isInvalidated()
                        || encrypted.getBoolean(K_REFRESH_IN_FLIGHT, false)
                        || encrypted.getInt(K_REFRESH_VER, 0) != expectedVersion
                        || sane(encrypted.getString(K_REFRESH, null)) == null) {
                    return false;
                }
                Map<String, ?> before = new HashMap<>(encrypted.getAll());
                boolean committed = encrypted.edit()
                        .putBoolean(K_REFRESH_IN_FLIGHT, true)
                        .commit();
                if (!committed) restoreSnapshot(encrypted, before);
                return committed;
            } catch (RuntimeException corruptStore) {
                return false;
            }
        }
    }

    /** Removes the marker only when discovery failed before refresh was sent. */
    public boolean cancelRefresh(int expectedVersion) {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated() || expectedVersion < 1) return false;
        synchronized (STORE_LOCK) {
            try {
                if (isInvalidated()
                        || !encrypted.getBoolean(K_REFRESH_IN_FLIGHT, false)
                        || encrypted.getInt(K_REFRESH_VER, 0) != expectedVersion) {
                    return false;
                }
                Map<String, ?> before = new HashMap<>(encrypted.getAll());
                boolean committed = encrypted.edit()
                        .remove(K_REFRESH_IN_FLIGHT)
                        .commit();
                if (!committed) restoreSnapshot(encrypted, before);
                return committed;
            } catch (RuntimeException corruptStore) {
                return false;
            }
        }
    }

    /** Durable compare-and-swap that completes a pre-marked token rotation. */
    public boolean storeRefreshTokenCas(@Nullable String newRefresh, int expectedVersion,
                                        @Nullable String grantedScope) {
        SharedPreferences encrypted = prefs;
        String safeRefresh = sane(newRefresh);
        String safeScope = sane(grantedScope);
        if (encrypted == null || isInvalidated() || safeRefresh == null
                || !safeRefresh.startsWith("ma-oauth-rt-")
                || safeScope == null || !OAuthConfig.hasRequiredScopes(safeScope)
                || expectedVersion < 1) return false;
        synchronized (STORE_LOCK) {
            try {
                if (isInvalidated()
                        || !encrypted.getBoolean(K_REFRESH_IN_FLIGHT, false)
                        || encrypted.getInt(K_REFRESH_VER, 0) != expectedVersion) {
                    return false;
                }
                Map<String, ?> before = new HashMap<>(encrypted.getAll());
                boolean committed = encrypted.edit()
                        .putString(K_REFRESH, safeRefresh)
                        .putString(K_SCOPE, safeScope)
                        .putInt(K_REFRESH_VER, expectedVersion + 1)
                        .remove(K_REFRESH_IN_FLIGHT)
                        .commit();
                if (!committed) restoreSnapshot(encrypted, before);
                return committed;
            } catch (RuntimeException corruptStore) {
                return false;
            }
        }
    }

    /** Atomically replaces the active encrypted identity after full validation. */
    public boolean commitLogin(@Nullable String refresh, @Nullable ProfileClaims profile) {
        SharedPreferences encrypted = prefs;
        String safeRefresh = sane(refresh);
        if (encrypted == null || safeRefresh == null
                || !safeRefresh.startsWith("ma-oauth-rt-") || profile == null
                || sane(profile.sub) == null
                || !OAuthConfig.hasRequiredScopes(profile.scope)) return false;
        synchronized (STORE_LOCK) {
            try {
                Map<String, ?> before = new HashMap<>(encrypted.getAll());
                boolean wasInvalidated = isInvalidated();
                boolean committed = putProfile(encrypted.edit().clear()
                        .putString(K_REFRESH, safeRefresh)
                        .putInt(K_REFRESH_VER, 1), profile).commit();
                if (!committed) {
                    restoreSnapshot(encrypted, before);
                    return false;
                }
                if (wasInvalidated
                        && !guardPrefs.edit().remove(K_INVALIDATED).commit()) {
                    // commit() may mutate memory despite a disk failure. Restore the
                    // fail-closed guard before restoring the previous encrypted family.
                    guardPrefs.edit().putBoolean(K_INVALIDATED, true).commit();
                    restoreSnapshot(encrypted, before);
                    return false;
                }
                return true;
            } catch (RuntimeException corruptStore) {
                return false;
            }
        }
    }

    public boolean updateProfile(@Nullable ProfileClaims profile) {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated() || profile == null) return false;
        synchronized (STORE_LOCK) {
            try {
                String storedSub = sane(encrypted.getString(K_SUB, null));
                String incomingSub = sane(profile.sub);
                if (storedSub == null || incomingSub == null || !storedSub.equals(incomingSub)) {
                    return false;
                }
                SharedPreferences.Editor editor = encrypted.edit();
                if (sane(profile.name) != null) editor.putString(K_NAME, sane(profile.name));
                String picture = OAuthAvatar.normalizeRemote(profile.picture);
                if (picture != null) editor.putString(K_PICTURE, picture);
                if (sane(profile.email) != null) editor.putString(K_EMAIL, sane(profile.email));
                if (sane(profile.preferredUsername) != null) {
                    editor.putString(K_USERNAME, sane(profile.preferredUsername));
                }
                editor.putBoolean(K_TELEGRAM, profile.telegram);
                if (profile.authTime > 0L) editor.putLong(K_AUTH_TIME, profile.authTime);
                if (sane(profile.scope) != null) editor.putString(K_SCOPE, sane(profile.scope));
                return editor.commit();
            } catch (RuntimeException corruptStore) {
                return false;
            }
        }
    }

    /** Persists the canonical picture supplied by an authenticated account snapshot. */
    public boolean updatePicture(@Nullable String picture) {
        SharedPreferences encrypted = prefs;
        String normalized = OAuthAvatar.normalizeRemote(picture);
        if (encrypted == null || isInvalidated() || normalized == null) return false;
        synchronized (STORE_LOCK) {
            try {
                return encrypted.edit().putString(K_PICTURE, normalized).commit();
            } catch (RuntimeException corruptStore) {
                return false;
            }
        }
    }

    @Nullable public String getSub() { return text(K_SUB); }
    @Nullable public String getName() { return text(K_NAME); }
    @Nullable public String getPicture() { return OAuthAvatar.normalizeRemote(text(K_PICTURE)); }
    @Nullable public String getEmail() { return text(K_EMAIL); }
    @Nullable public String getPreferredUsername() { return text(K_USERNAME); }
    public boolean isTelegram() {
        try {
            return prefs != null && !isInvalidated() && prefs.getBoolean(K_TELEGRAM, false);
        } catch (RuntimeException corruptStore) {
            return false;
        }
    }
    public long getAuthTime() {
        try {
            return prefs != null && !isInvalidated() ? prefs.getLong(K_AUTH_TIME, 0L) : 0L;
        } catch (RuntimeException corruptStore) {
            return 0L;
        }
    }
    @Nullable public String getScope() { return text(K_SCOPE); }
    public long getSelectedKeyId() {
        try {
            return prefs != null && !isInvalidated() ? prefs.getLong(K_SELECTED_KEY, -1L) : -1L;
        } catch (RuntimeException corruptStore) {
            return -1L;
        }
    }

    public boolean setSelectedKeyId(long id) {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated()) return false;
        synchronized (STORE_LOCK) {
            try {
                return !isInvalidated()
                        && encrypted.edit().putLong(K_SELECTED_KEY, id).commit();
            } catch (RuntimeException corruptStore) {
                return false;
            }
        }
    }

    public boolean clearSelectedKeyId() {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated()) return false;
        synchronized (STORE_LOCK) {
            try {
                return !isInvalidated()
                        && encrypted.edit().remove(K_SELECTED_KEY).commit();
            } catch (RuntimeException corruptStore) {
                return false;
            }
        }
    }

    /**
     * Durably blocks the current token family. Either the non-secret tombstone or
     * deletion/clearing of the encrypted file is sufficient to prevent restoration.
     */
    public boolean clearAll() {
        synchronized (STORE_LOCK) {
            boolean tombstoned = false;
            try {
                tombstoned = guardPrefs.edit()
                        .putBoolean(K_INVALIDATED, true)
                        .commit();
            } catch (RuntimeException ignored) {
            }
            boolean cleared = false;
            SharedPreferences encrypted = prefs;
            if (encrypted != null) {
                try {
                    cleared = encrypted.edit().clear().commit();
                } catch (RuntimeException ignored) {
                }
            }
            boolean deleted = false;
            if (!cleared) {
                try {
                    deleted = appContext.deleteSharedPreferences(PREFS_FILE);
                } catch (RuntimeException ignored) {
                }
            }
            return tombstoned || cleared || deleted;
        }
    }

    private static SharedPreferences.Editor putProfile(SharedPreferences.Editor editor,
                                                        ProfileClaims profile) {
        return editor
                .putString(K_SUB, sane(profile.sub))
                .putString(K_NAME, sane(profile.name))
                .putString(K_PICTURE, sane(profile.picture))
                .putString(K_EMAIL, sane(profile.email))
                .putString(K_USERNAME, sane(profile.preferredUsername))
                .putBoolean(K_TELEGRAM, profile.telegram)
                .putLong(K_AUTH_TIME, profile.authTime)
                .putString(K_SCOPE, sane(profile.scope));
    }

    private static void restoreSnapshot(@NonNull SharedPreferences target,
                                        @NonNull Map<String, ?> snapshot) {
        try {
            SharedPreferences.Editor editor = target.edit().clear();
            for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(key, (String) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Long) editor.putLong(key, (Long) value);
                else if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Set) {
                    @SuppressWarnings("unchecked")
                    Set<String> strings = (Set<String>) value;
                    editor.putStringSet(key, new HashSet<>(strings));
                }
            }
            // Even if disk is unavailable, commit updates the in-process map back to
            // the previous identity, preventing split access/profile state.
            editor.commit();
        } catch (RuntimeException ignored) {
        }
    }

    @Nullable
    private String text(String key) {
        SharedPreferences encrypted = prefs;
        if (encrypted == null || isInvalidated()) return null;
        try {
            return sane(encrypted.getString(key, null));
        } catch (RuntimeException corruptStore) {
            return null;
        }
    }

    @Nullable
    private static String sane(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }
}
