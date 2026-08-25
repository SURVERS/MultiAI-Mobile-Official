package app.multiai.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;
import app.multiai.mobile.utils.AppLockManager;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.SecurityHelper;

/** Opaque, non-exported process unlock gate for every non-launcher entry path. */
public final class BiometricGateActivity extends AppCompatActivity {

    private TextView status;
    private TextView retry;
    private TextView settings;
    private boolean promptVisible;
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable retryUnlock = () -> {
        if (retry == null) return;
        retry.setEnabled(true);
        retry.setAlpha(1f);
        retry.setBackgroundResource(R.drawable.biometric_retry_button);
        retry.setText(getString(R.string.scr_retry));
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SecurityHelper.setSecure(this, true);
        setContentView(R.layout.activity_biometric_gate);
        int bg = ContextCompat.getColor(this, R.color.bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        status = findViewById(R.id.biometricStatus);
        retry = findViewById(R.id.biometricRetry);
        settings = findViewById(R.id.biometricSettings);
        retry.setOnClickListener(v -> requestBiometric());
        settings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
            } catch (RuntimeException ignored) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
        });
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        moveTaskToBack(true);
                    }
                });
        getWindow().getDecorView().post(this::requestBiometric);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!new AppSettings(this).isBiometricEnabled()) completeUnlock();
    }

    private void requestBiometric() {
        if (promptVisible || isFinishing()) return;
        if (!new AppSettings(this).isBiometricEnabled()) {
            completeUnlock();
            return;
        }
        handler.removeCallbacks(retryUnlock);
        retry.setVisibility(View.GONE);
        settings.setVisibility(View.GONE);
        status.setText(getString(R.string.scr_bio_use_fingerprint));
        status.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        if (!SecurityHelper.isBiometricAvailable(this)) {
            showUnavailable();
            return;
        }

        promptVisible = true;
        SecurityHelper.showBiometricPrompt(this, new SecurityHelper.Callback() {
            @Override
            public void onSuccess() {
                promptVisible = false;
                completeUnlock();
            }

            @Override
            public void onError(int errorCode, String errString) {
                promptVisible = false;
                handleError(errorCode);
            }

            @Override
            public void onFailure() {
                status.setText(getString(R.string.scr_bio_not_recognized));
                status.setTextColor(ContextCompat.getColor(
                        BiometricGateActivity.this, R.color.error));
            }
        });
    }

    private void handleError(int errorCode) {
        retry.setVisibility(View.VISIBLE);
        retry.setEnabled(true);
        retry.setAlpha(1f);
        retry.setBackgroundResource(R.drawable.biometric_retry_button);
        retry.setText(getString(R.string.scr_retry));
        settings.setVisibility(View.GONE);
        if (SecurityHelper.isLockoutError(errorCode)) {
            status.setText(getString(R.string.scr_bio_lockout));
            status.setTextColor(ContextCompat.getColor(this, R.color.error));
            retry.setEnabled(false);
            retry.setAlpha(0.7f);
            retry.setBackgroundResource(R.drawable.biometric_retry_button_disabled);
            retry.setText(getString(R.string.scr_bio_wait));
            handler.postDelayed(retryUnlock, 30_000L);
        } else if (SecurityHelper.isUnavailableError(errorCode)) {
            showUnavailable();
        } else {
            status.setText(getString(R.string.scr_bio_cancelled));
            status.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    private void showUnavailable() {
        status.setText(getString(R.string.scr_bio_unavailable));
        status.setTextColor(ContextCompat.getColor(this, R.color.error));
        retry.setVisibility(View.GONE);
        settings.setVisibility(View.VISIBLE);
    }

    private void completeUnlock() {
        if (isFinishing()) return;
        Intent target = AppLockManager.get(this).unlockAndTakePendingRoute();
        if (target == null) target = new Intent(this, OnboardingActivity.class);
        startActivity(target);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        AppLockManager.get(this).onGateDestroyed(isChangingConfigurations());
        super.onDestroy();
    }
}
