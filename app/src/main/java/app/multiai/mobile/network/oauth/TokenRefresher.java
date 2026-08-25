package app.multiai.mobile.network.oauth;

import android.content.Context;

import androidx.annotation.Nullable;

/** Compatibility facade for the process-wide OAuth refresh coordinator. */
public final class TokenRefresher {

    private TokenRefresher() {
    }

    public static boolean refreshBlocking(Context context) {
        return OAuthRefreshCoordinator.refreshBlocking(context);
    }

    public static boolean refreshBlocking(Context context,
                                          @Nullable String rejectedAccessToken) {
        return OAuthRefreshCoordinator.refreshBlocking(context, rejectedAccessToken);
    }
}
