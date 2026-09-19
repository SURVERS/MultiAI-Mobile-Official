package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

import app.multiai.mobile.network.BackendApi;
import app.multiai.mobile.ui.BanActivity;

public final class DeviceBanManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static boolean launchPending;

    private DeviceBanManager() {}

    public static void onResumed(Activity activity) {
        foreground = new WeakReference<>(activity);
        if (activity instanceof BanActivity) launchPending = false;
        applyCurrentStatus();
    }

    public static void onPaused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }

    public static void onBanDestroyed() {
        launchPending = false;
    }

    public static void checkNow(Context ctx) {
        BackendApi.get().checkStatus(ctx.getApplicationContext(),
                (banned, message, maintenance) -> MAIN.post(DeviceBanManager::applyCurrentStatus));
    }

    public static boolean isBanned() {
        return BackendApi.get().isBanned();
    }

    public static void applyIfBanned(Context ctx) {
        MAIN.post(DeviceBanManager::applyCurrentStatus);
    }

    private static void applyCurrentStatus() {
        Activity activity = foreground.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (activity instanceof BanActivity) {
            ((BanActivity) activity).showCurrentStatus();
            return;
        }
        if (!isBanned() || launchPending) return;
        try {
            launchPending = true;
            Intent intent = new Intent(activity, BanActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            activity.startActivity(intent);
        } catch (Exception error) {
            launchPending = false;
            android.util.Log.w("DeviceBanManager", "Cannot open ban screen", error);
        }
    }
}
