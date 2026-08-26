package app.multiai.mobile.ui;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import app.multiai.mobile.R;
import app.multiai.mobile.network.MultiAiClient;
import app.multiai.mobile.network.oauth.OAuthSession;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppLockManager;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.SecurityHelper;

public class SecuritySettingsActivity extends AppCompatActivity {

    private AppSettings settings;
    /** Suppresses listener-driven persistence while reverting the switch. */
    private boolean revertingBio;

    /** ZXing scanner launcher (registered before onCreate completes). */
    private final ActivityResultLauncher<ScanOptions> qrLauncher =
            registerForActivityResult(new ScanContract(), this::onQrScanResult);

    /** Holds the last parsed QR payload between scan → confirm dialog. */
    private String pendingSessionId;
    private String pendingNonce;
    private String pendingHmac;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AppLockManager.get(this).routeIfLocked(this)) return;
        SettingsUI.Scaffold sc = SettingsUI.createScaffold(
                this, getString(R.string.scr_security_title), false);
        LinearLayout root = sc.content;

        settings = new AppSettings(this);

        // ─── Device linking (QR) ───────────────────────────────────────────
        LinearLayout linkCard = SettingsUI.card(this);
        linkCard.addView(SettingsUI.row(this, R.drawable.ic_qr_link,
                getString(R.string.scr_sec_link_device_title),
                getString(R.string.scr_sec_link_device_desc),
                this::startQrScan));
        root.addView(linkCard);
        root.addView(SettingsUI.spacer(this, 12));

        // ─── Biometric + screenshot block ──────────────────────────────────
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

    // ═══════════════════════════════════════════════════════════════
    // QR-линковка устройства
    // ═══════════════════════════════════════════════════════════════

    /** Launches the ZXing QR scanner with the MultiAI prompt. */
    private void startQrScan() {
        if (!OAuthSession.get().isAuthorized()) {
            ModernToast.show(this, getString(R.string.scr_sec_link_err_unauthorized), true);
            return;
        }
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.scr_sec_link_scan_hint));
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        qrLauncher.launch(options);
    }

    /** Called by the ZXing launcher when the scan completes or is cancelled. */
    private void onQrScanResult(ScanIntentResult result) {
        if (result == null) {
            ModernToast.show(this, getString(R.string.scr_sec_link_cancelled));
            return;
        }
        String raw = result.getContents();
        if (raw == null || raw.isEmpty()) {
            ModernToast.show(this, getString(R.string.scr_sec_link_cancelled));
            return;
        }
        if (raw == null) {
            ModernToast.show(this, getString(R.string.scr_sec_link_err_invalid), true);
            return;
        }
        // Expected: multiai://link?session=lnk_xxx&nonce=n_xxx&hmac=...
        if (!raw.startsWith("multiai://link?")) {
            ModernToast.show(this, getString(R.string.scr_sec_link_err_invalid), true);
            return;
        }
        Uri uri = Uri.parse(raw);
        String session = uri.getQueryParameter("session");
        String nonce = uri.getQueryParameter("nonce");
        String hmac = uri.getQueryParameter("hmac");
        if (session == null || session.isEmpty()
                || nonce == null || nonce.isEmpty()
                || hmac == null || hmac.isEmpty()) {
            ModernToast.show(this, getString(R.string.scr_sec_link_err_invalid), true);
            return;
        }
        pendingSessionId = session;
        pendingNonce = nonce;
        pendingHmac = hmac;
        showLinkConfirmSheet();
    }

    /** Confirmation sheet before sending the mobile token to the backend. */
    private void showLinkConfirmSheet() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUI.dp(this, 20);
        body.setPadding(pad, pad, pad, pad);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText(R.string.scr_sec_link_confirm_title);
        title.setTextColor(SettingsUI.colorText(this));
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        body.addView(title);

        android.widget.TextView desc = new android.widget.TextView(this);
        desc.setText(R.string.scr_sec_link_confirm_desc);
        desc.setTextColor(SettingsUI.colorSubtext(this));
        desc.setTextSize(14);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = SettingsUI.dp(this, 8);
        body.addView(desc, descLp);

        com.google.android.material.button.MaterialButton confirm =
                new com.google.android.material.button.MaterialButton(this);
        int accent = AccentColor.resolve(this);
        confirm.setText(R.string.scr_sec_link_confirm_action);
        confirm.setAllCaps(false);
        confirm.setTextColor(AccentColor.contrastForeground(accent));
        confirm.setTextSize(15);
        confirm.setTypeface(confirm.getTypeface(), android.graphics.Typeface.BOLD);
        confirm.setCornerRadius(SettingsUI.dp(this, 14));
        confirm.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        confirm.setOnClickListener(v -> {
            String sid = pendingSessionId;
            String n = pendingNonce;
            String h = pendingHmac;
            pendingSessionId = null;
            pendingNonce = null;
            pendingHmac = null;
            sendLinkConfirm(sid, n, h);
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = SettingsUI.dp(this, 18);
        body.addView(confirm, btnLp);

        ModernSheet.custom(this, getString(R.string.scr_sec_link_confirm_title), body, true);
    }

    /** Sends the confirm request to the backend via MultiAiClient. */
    private void sendLinkConfirm(String sessionId, String nonce, String hmac) {
        ModernToast.show(this, "…");
        String deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        new MultiAiClient().confirmDeviceLink(sessionId, nonce, hmac, deviceName,
                result -> runOnUiThread(() -> {
                    if (result.ok) {
                        String name = result.linkedDevice != null && !result.linkedDevice.isEmpty()
                                ? result.linkedDevice : "Desktop";
                        ModernToast.show(this, getString(R.string.scr_sec_link_success, name));
                    } else {
                        String msg;
                        switch (result.error == null ? "" : result.error) {
                            case "invalid_qr":
                                msg = getString(R.string.scr_sec_link_err_invalid); break;
                            case "session_expired":
                                msg = getString(R.string.scr_sec_link_err_expired); break;
                            case "unauthorized":
                                msg = getString(R.string.scr_sec_link_err_unauthorized); break;
                            case "network_error":
                                msg = getString(R.string.scr_sec_link_err_network); break;
                            default:
                                msg = getString(R.string.scr_sec_link_err_unknown); break;
                        }
                        ModernToast.show(this, msg, true);
                    }
                }));
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
