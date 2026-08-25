package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.multiai.mobile.MultiAIApp;
import app.multiai.mobile.ui.BiometricGateActivity;
import app.multiai.mobile.ui.OnboardingActivity;

/**
 * Process-local biometric lock state and routing gate.
 *
 * <p>Unlock state is intentionally never persisted: a new process starts locked
 * whenever biometrics are enabled. Sensitive Application warm-up begins only after
 * an authenticated unlock. Protected Activities call {@link #routeIfLocked(Activity)}
 * before touching layout/data and repeat the check for warm {@code onNewIntent} routes.</p>
 */
public final class AppLockManager {

    private static final long BACKGROUND_RELOCK_DELAY_MS = 1_000L;
    private static volatile AppLockManager instance;

    @NonNull private final Context appContext;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable relockTask = this::lockAfterBackground;

    private boolean unlocked;
    private boolean gateActive;
    private int startedActivities;
    @Nullable private Intent pendingRoute;

    private AppLockManager(@NonNull Context context) {
        appContext = context.getApplicationContext();
        unlocked = !new AppSettings(appContext).isBiometricEnabled();
    }

    @NonNull
    public static AppLockManager get(@NonNull Context context) {
        AppLockManager manager = instance;
        if (manager == null) {
            synchronized (AppLockManager.class) {
                manager = instance;
                if (manager == null) {
                    manager = new AppLockManager(context);
                    instance = manager;
                }
            }
        }
        return manager;
    }

    /** Called once from Application.onCreate so process state exists before Activities. */
    @NonNull
    public static AppLockManager initialize(@NonNull Context context) {
        return get(context);
    }

    public synchronized boolean isUnlockRequired() {
        if (!new AppSettings(appContext).isBiometricEnabled()) {
            unlocked = true;
            return false;
        }
        return !unlocked;
    }

    /** Route the Activity's current Intent through the biometric gate when locked. */
    public boolean routeIfLocked(@NonNull Activity activity) {
        return routeIfLocked(activity, activity.getIntent());
    }

    /**
     * Route a specific incoming Intent through the biometric gate. This overload is
     * required for warm-task {@code onNewIntent} delivery, where Activity.getIntent()
     * may still be the stale route.
     */
    public boolean routeIfLocked(@NonNull Activity activity, @Nullable Intent incomingIntent) {
        if (activity instanceof BiometricGateActivity
                || activity instanceof OnboardingActivity
                || !isUnlockRequired()) {
            return false;
        }

        SecurityHelper.setSecure(activity, true);
        final boolean launchGate;
        synchronized (this) {
            Intent route = incomingIntent == null ? new Intent() : new Intent(incomingIntent);
            route.setComponent(new ComponentName(activity, activity.getClass()));
            route.setPackage(activity.getPackageName());
            route.setSelector(null);
            pendingRoute = route;
            launchGate = !gateActive;
            gateActive = true;
        }

        if (launchGate) {
            try {
                activity.startActivity(new Intent(activity, BiometricGateActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (RuntimeException failedToLaunch) {
                synchronized (this) {
                    gateActive = false;
                }
            }
        }
        activity.finish();
        activity.overridePendingTransition(0, 0);
        return true;
    }

    /** Mark this process authenticated when no pending route needs replay. */
    public void unlockCurrentProcess() {
        synchronized (this) {
            unlocked = true;
            gateActive = false;
            pendingRoute = null;
            main.removeCallbacks(relockTask);
        }
        startDeferredInitialization();
    }

    /** Used by a biometric host to atomically unlock and consume its target route. */
    @Nullable
    public Intent unlockAndTakePendingRoute() {
        Intent route;
        synchronized (this) {
            unlocked = true;
            gateActive = false;
            main.removeCallbacks(relockTask);
            route = pendingRoute == null ? null : new Intent(pendingRoute);
            pendingRoute = null;
        }
        startDeferredInitialization();
        return route;
    }

    /** The enabling prompt itself authenticated the current user. */
    public void onBiometricEnabledAfterAuthentication() {
        unlockCurrentProcess();
    }

    /** Disabling the preference removes the gate immediately for this process. */
    public void onBiometricDisabled() {
        synchronized (this) {
            unlocked = true;
            gateActive = false;
            pendingRoute = null;
            main.removeCallbacks(relockTask);
        }
        startDeferredInitialization();
    }

    /** Global lifecycle hook; also catches warm-task/Recents returns before onResume. */
    public void onActivityStarted(@NonNull Activity activity) {
        synchronized (this) {
            startedActivities++;
            main.removeCallbacks(relockTask);
        }
        // The gate is exempt inside routeIfLocked, but still counts as foreground.
        routeIfLocked(activity);
    }

    /** Relock shortly after the last Activity, including the gate, leaves foreground. */
    public void onActivityStopped(@NonNull Activity activity) {
        synchronized (this) {
            if (startedActivities > 0) startedActivities--;
            if (startedActivities == 0
                    && new AppSettings(appContext).isBiometricEnabled()) {
                main.removeCallbacks(relockTask);
                main.postDelayed(relockTask, BACKGROUND_RELOCK_DELAY_MS);
            }
        }
    }

    /** Allows a fresh gate launch if Android destroyed the gate without unlocking. */
    public synchronized void onGateDestroyed(boolean changingConfigurations) {
        if (!changingConfigurations && !unlocked) gateActive = false;
    }

    private void lockAfterBackground() {
        synchronized (this) {
            if (startedActivities != 0
                    || !new AppSettings(appContext).isBiometricEnabled()) {
                return;
            }
            // Keep an active in-memory route while its gate is backgrounded (for
            // example while system Security Settings are open). It is consumed once
            // after authentication or replaced by a newer locked entry route.
            unlocked = false;
        }
    }

    private void startDeferredInitialization() {
        if (appContext instanceof MultiAIApp) {
            ((MultiAIApp) appContext).startSensitiveInitializationAfterUnlock();
        }
    }
}
