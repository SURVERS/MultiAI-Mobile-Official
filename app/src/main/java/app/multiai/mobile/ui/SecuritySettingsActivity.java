package app.multiai.mobile.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AppLockManager;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.SecurityHelper;

public class SecuritySettingsActivity extends AppCompatActivity {

    private AppSettings settings;
    /** Suppresses listener-driven persistence while reverting the switch. */
    private boolean revertingBio;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AppLockManager.get(this).routeIfLocked(this)) return;
        SettingsUI.Scaffold sc = SettingsUI.createScaffold(
                this, getString(R.string.scr_security_title), false);
        LinearLayout root = sc.content;

        settings = new AppSettings(this);
        LinearLayout card = SettingsUI.card(this);

        final View[] bioRowHolder = new View[1];
        View bioRow = SettingsUI.rowToggle(this, 0,
                getString(R.string.scr_sec_biometric_title),
                getString(R.string.scr_sec_biometric_desc),
                settings.isBiometricEnabled(),
                checked -> {
                    if (revertingBio) {
                        revertingBio = false;
                        return;
                    }
                    if (checked) {
                        enableBiometric(bioRowHolder[0]);
                    } else {
                        settings.setBiometricEnabled(false);
                        AppLockManager.get(this).onBiometricDisabled();
                        ModernToast.show(this, getString(R.string.scr_disabled));
                    }
                });
        bioRowHolder[0] = bioRow;
        card.addView(bioRow);
        card.addView(SettingsUI.divider(this));

        card.addView(SettingsUI.rowToggle(this, 0,
                getString(R.string.scr_sec_screenshot_title),
                getString(R.string.scr_sec_screenshot_desc),
                settings.isScreenshotBlockEnabled(),
                checked -> {
                    settings.setScreenshotBlockEnabled(checked);
                    SecurityHelper.setSecure(this, checked);
                    ModernToast.show(this, checked
                            ? getString(R.string.scr_enabled)
                            : getString(R.string.scr_disabled));
                }));
        root.addView(card);
    }

    /** Persist biometric locking only after successful authentication. */
    private void enableBiometric(View bioRow) {
        if (!SecurityHelper.isBiometricAvailable(this)) {
            revertingBio = true;
            setSwitchChecked(bioRow, false);
            ModernToast.show(this, getString(R.string.scr_biometric_unavailable), true);
            return;
        }
        final View row = bioRow;
        SecurityHelper.showBiometricPrompt(this, new SecurityHelper.Callback() {
            @Override
            public void onSuccess() {
                settings.setBiometricEnabled(true);
                AppLockManager.get(SecuritySettingsActivity.this)
                        .onBiometricEnabledAfterAuthentication();
                ModernToast.show(SecuritySettingsActivity.this,
                        getString(R.string.scr_enabled));
            }

            @Override
            public void onError(int errorCode, String errString) {
                revertingBio = true;
                setSwitchChecked(row, false);
                String message = SecurityHelper.isCancellationError(errorCode)
                        ? getString(R.string.scr_bio_cancelled_short)
                        : SecurityHelper.isLockoutError(errorCode)
                                ? getString(R.string.scr_bio_too_many_later)
                                : getString(R.string.scr_biometric_unavailable);
                ModernToast.show(SecuritySettingsActivity.this, message, true);
            }
        });
    }

    private static void setSwitchChecked(View row, boolean checked) {
        CompoundButton sw = findSwitch(row);
        if (sw != null) sw.setChecked(checked);
    }

    private static CompoundButton findSwitch(View view) {
        if (view instanceof app.multiai.mobile.ui.settings.ChatGptSwitchView) {
            return (CompoundButton) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CompoundButton found = findSwitch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
