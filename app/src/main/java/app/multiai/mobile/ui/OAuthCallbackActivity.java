package app.multiai.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import app.multiai.mobile.network.oauth.OAuthConfig;
import app.multiai.mobile.utils.AppLockManager;
import app.multiai.mobile.utils.SecurityHelper;

/**
 * Non-sensitive OAuth redirect trampoline.
 *
 * <p>The exported component never exchanges a code or reads session state. It accepts
 * only the configured redirect shape, strips caller-controlled flags/extras, and sends
 * the callback to ProfileActivity only after any required biometric unlock.</p>
 */
public final class OAuthCallbackActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SecurityHelper.setSecure(this, true);

        Intent incoming = getIntent();
        Uri callback = incoming == null ? null : incoming.getData();
        if (incoming == null || !Intent.ACTION_VIEW.equals(incoming.getAction())
                || !isExactRedirect(callback)) {
            finish();
            return;
        }

        Intent safeReplay = new Intent(this, OAuthCallbackActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(callback)
                .setPackage(getPackageName());
        setIntent(safeReplay);
        if (AppLockManager.get(this).routeIfLocked(this, safeReplay)) return;

        boolean onboardingCompleted = getSharedPreferences("multiai_profile", MODE_PRIVATE)
                .getBoolean("onboarding_completed", false);
        Class<?> destination = onboardingCompleted ? ProfileActivity.class : OnboardingActivity.class;
        Intent target = new Intent(this, destination)
                .setAction(Intent.ACTION_VIEW)
                .setData(callback)
                .setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(target);
        finish();
        overridePendingTransition(0, 0);
    }

    private static boolean isExactRedirect(Uri actual) {
        if (actual == null || actual.isOpaque()
                || actual.getUserInfo() != null || actual.getFragment() != null) {
            return false;
        }
        Uri expected = Uri.parse(OAuthConfig.getRedirectUri());
        return equalsIgnoreCase(expected.getScheme(), actual.getScheme())
                && equalsIgnoreCase(expected.getHost(), actual.getHost())
                && expected.getPort() == actual.getPort()
                && safeEquals(expected.getEncodedPath(), actual.getEncodedPath());
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private static boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
