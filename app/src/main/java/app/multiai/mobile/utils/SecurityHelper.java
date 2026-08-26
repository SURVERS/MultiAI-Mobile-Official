package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

/** Shared biometric and secure-window helpers. */
public final class SecurityHelper {

    private SecurityHelper() {}

    /** Apply the persisted screenshot policy to an Activity window. */
    public static void applySecure(Activity activity) {
        if (activity == null) return;
        applySecure(activity, activity.getWindow());
    }

    /** Apply the persisted screenshot policy to Dialog/Sheet windows too. */
    public static void applySecure(Context context, Window window) {
        if (context == null || window == null) return;
        setSecure(window, new AppSettings(context).isScreenshotBlockEnabled());
    }

    /** Force a state on the current Activity window. */
    public static void setSecure(Activity activity, boolean secure) {
        if (activity == null) return;
        setSecure(activity.getWindow(), secure);
    }

    private static void setSecure(Window window, boolean secure) {
        if (window == null) return;
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** True if the device can authenticate with strong or weak biometrics. */
    public static boolean isBiometricAvailable(Context context) {
        try {
            BiometricManager manager = BiometricManager.from(context);
            int result = manager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.BIOMETRIC_WEAK);
            return result == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Show the trusted system biometric prompt. */
    public static void showBiometricPrompt(FragmentActivity host, Callback callback) {
        BiometricPrompt.AuthenticationCallback adapter =
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        if (callback != null) callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      CharSequence errString) {
                        if (callback != null) {
                            callback.onError(errorCode,
                                    errString == null ? "" : errString.toString());
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        if (callback != null) callback.onFailure();
                    }
                };

        BiometricPrompt prompt = new BiometricPrompt(
                host, ContextCompat.getMainExecutor(host), adapter);
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(host.getString(app.multiai.mobile.R.string.security_biometric_prompt_title))
                .setSubtitle(host.getString(app.multiai.mobile.R.string.security_biometric_prompt_subtitle))
                .setNegativeButtonText(host.getString(app.multiai.mobile.R.string.scr_cancel))
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build();
        prompt.authenticate(info);
    }

    public static boolean isCancellationError(int errorCode) {
        return errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                || errorCode == BiometricPrompt.ERROR_USER_CANCELED
                || errorCode == BiometricPrompt.ERROR_CANCELED;
    }

    public static boolean isLockoutError(int errorCode) {
        return errorCode == BiometricPrompt.ERROR_LOCKOUT
                || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT;
    }

    public static boolean isUnavailableError(int errorCode) {
        return errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE
                || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT
                || errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS
                || errorCode == BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED;
    }

    public interface Callback {
        void onSuccess();
        void onError(int errorCode, String errString);
        default void onFailure() {}
    }
}
