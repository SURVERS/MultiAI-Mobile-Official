package app.multiai.mobile;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.concurrent.atomic.AtomicBoolean;

import app.multiai.mobile.network.oauth.OAuthLoginController;
import app.multiai.mobile.network.oauth.OAuthRefreshCoordinator;
import app.multiai.mobile.network.oauth.OAuthStore;
import app.multiai.mobile.utils.AppLockManager;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.SecurityHelper;

/** Application entry point for locale/theme, process lock and deferred session warm-up. */
public class MultiAIApp extends Application {

    /** Application context для фоновых хелперов (TokenRefresher и др.). */
    public static volatile android.content.Context APP;

    public static android.content.Context getAppContext() {
        return APP;
    }

    private static final String TAG = "MultiAIWarmUp";

    private final AtomicBoolean sensitiveInitializationInFlight = new AtomicBoolean(false);
    private final AtomicBoolean sensitiveInitializationCompleted = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        APP = this;
        AppSettings settings = new AppSettings(this);
        applyLanguage(settings.getLanguage());
        applyTheme(settings.getTheme());

        // Reset session-scoped Work Mode approval on fresh app start
        app.multiai.mobile.utils.WorkModeAccess.resetSession();

        AppLockManager lockManager = AppLockManager.initialize(this);
        if (!lockManager.isUnlockRequired()) {
            startSensitiveInitializationAfterUnlock();
        }

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
                applyWindowSecurity(activity, lockManager);
                lockManager.onActivityStarted(activity);
                startSensitiveInitializationAfterUnlock();
            }

            @Override
            public void onActivityResumed(Activity activity) {
                applyWindowSecurity(activity, lockManager);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                // onPause completes before Android captures the task snapshot. Force a
                // secure window whenever app lock is enabled, even during the 1s grace.
                if (new AppSettings(MultiAIApp.this).isBiometricEnabled()) {
                    SecurityHelper.setSecure(activity, true);
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                lockManager.onActivityStopped(activity);
            }

            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private static void applyWindowSecurity(Activity activity, AppLockManager lockManager) {
        if (activity instanceof app.multiai.mobile.ui.BiometricGateActivity
                || lockManager.isUnlockRequired()) {
            SecurityHelper.setSecure(activity, true);
        } else {
            SecurityHelper.applySecure(activity);
        }
    }

    /** Map the stored preference to AppCompatDelegate night mode. */
    public static void applyTheme(String pref) {
        int mode;
        if ("light".equals(pref)) {
            mode = AppCompatDelegate.MODE_NIGHT_NO;
        } else if ("dark".equals(pref)) {
            mode = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    /**
     * Starts OAuth/cloud/model initialization once, and only after process unlock.
     * Provider/service-only process starts remain locked and perform no token reads.
     */
    public void startSensitiveInitializationAfterUnlock() {
        if (AppLockManager.get(this).isUnlockRequired()
                || sensitiveInitializationCompleted.get()
                || !sensitiveInitializationInFlight.compareAndSet(false, true)) {
            return;
        }
        warmUpSessionAsync();
    }

    private void warmUpSessionAsync() {
        final OAuthStore store = new OAuthStore(this);
        if (!store.hasRefreshToken()) {
            Log.d(TAG, "Skipped: no stored session");
            sensitiveInitializationInFlight.set(false);
            return;
        }
        Log.i(TAG, "Started");
        new Thread(() -> {
            try {
                OAuthLoginController oauth = new OAuthLoginController(this);
                oauth.ensureMetadataAsync(() -> new Thread(() -> {
                    boolean snapshotLoaded = false;
                    try {
                        Log.i(TAG, "Metadata ready");
                        if (!OAuthRefreshCoordinator.refreshBlocking(this)) {
                            Log.w(TAG, "Access token restore failed");
                            return;
                        }
                        Log.i(TAG, "Access token restored");
                        oauth.getAccountApi().snapshot();
                        snapshotLoaded = true;
                        Log.i(TAG, "Account snapshot loaded");
                        try {
                            Log.i(TAG, "Profile refreshed: updated=" + oauth.refreshStoredProfile());
                        } catch (Exception error) {
                            logFailure("Profile refresh", error);
                        }
                        try {
                            app.multiai.mobile.utils.CloudSyncManager sync =
                                    new app.multiai.mobile.utils.CloudSyncManager(this);
                            boolean allPulled = sync.pullAll();
                            boolean personalizationPulled = sync.pullPersonalization();
                            Log.i(TAG, "Cloud sync finished: all=" + allPulled
                                    + ", personalization=" + personalizationPulled);
                        } catch (Exception error) {
                            logFailure("Cloud sync", error);
                        }
                        try {
                            int models = new app.multiai.mobile.network.MultiAiClient()
                                    .fetchModelCatalog(app.multiai.mobile.network.oauth.OAuthSession
                                            .get().getAccessToken()).size();
                            Log.i(TAG, "Model catalog loaded: count=" + models);
                        } catch (Exception error) {
                            logFailure("Model catalog", error);
                        }
                    } catch (Exception error) {
                        logFailure("Session restore", error);
                    } finally {
                        sensitiveInitializationCompleted.set(snapshotLoaded);
                        sensitiveInitializationInFlight.set(false);
                        Log.i(TAG, snapshotLoaded ? "Completed" : "Failed; retry allowed");
                    }
                }, "oauth-session-warmup").start(), error -> {
                    Log.w(TAG, "Metadata failed: code=" + safeLogValue(error));
                    sensitiveInitializationInFlight.set(false);
                });
            } catch (Exception error) {
                logFailure("Bootstrap", error);
                sensitiveInitializationInFlight.set(false);
            }
        }, "oauth-warmup-bootstrap").start();
    }

    private static void logFailure(String step, Exception error) {
        Log.w(TAG, step + " failed: type=" + error.getClass().getSimpleName());
    }

    private static String safeLogValue(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_.-]{1,64}")) return "unknown";
        return value;
    }

    /** Apply the persisted app locale before Activities are created. */
    public static void applyLanguage(String pref) {
        LocaleListCompat locales = "en".equals(pref)
                ? LocaleListCompat.forLanguageTags("en")
                : LocaleListCompat.forLanguageTags("ru");
        AppCompatDelegate.setApplicationLocales(locales);
    }
}
