package app.multiai.mobile.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Set;

import app.multiai.mobile.R;
import app.multiai.mobile.network.MultiAiClient;
import app.multiai.mobile.network.oauth.OAuthConfig;
import app.multiai.mobile.network.oauth.OAuthRefreshCoordinator;
import app.multiai.mobile.network.oauth.OAuthSession;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppLockManager;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.SecurityHelper;

public class SecuritySettingsActivity extends AppCompatActivity {

    /** Internal action used by the Devices hero to reuse this exact secure QR flow. */
    public static final String EXTRA_START_DEVICE_LINK =
            "app.multiai.mobile.extra.START_DEVICE_LINK";

    private AppSettings settings;
    /** Suppresses listener-driven persistence while reverting the switch. */
    private boolean revertingBio;

    /** Custom portrait QR scanner launcher. */
    private final ActivityResultLauncher<Intent> qrLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onQrScanResult);

    /** Sensitive QR proof and one OAuth token snapshot, kept only while the sheet is open. */
    private String pendingSessionId;
    private String pendingNonce;
    private String pendingHmac;
    private String pendingAccessToken;
    private boolean cancelPendingOnDismiss;
    private android.app.Dialog pendingConfirmSheet;
    private MultiAiClient deviceLinkClient;
    /** One forced token rotation + retry per step, mirroring AccountApi's §10 contract. */
    private boolean scannedAuthRetried;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AppLockManager.get(this).routeIfLocked(this)) return;
        SettingsUI.Scaffold sc = SettingsUI.createScaffold(
                this, getString(R.string.scr_security_title), false);
        LinearLayout root = sc.content;

        settings = new AppSettings(this);
        deviceLinkClient = new MultiAiClient();

        // ─── Устройства ─────────────────────────────────────────────────────
        // «Подключить устройство» убрано отсюда — привязка по QR уже доступна
        // прямо внутри экрана «Устройства» (кнопка hero).
        LinearLayout linkCard = SettingsUI.card(this);
        linkCard.addView(SettingsUI.row(this, R.drawable.ic_devices_nav,
                getString(R.string.scr_sec_devices_title),
                getString(R.string.scr_sec_devices_desc),
                () -> startActivity(new Intent(this, DevicesActivity.class))));
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

        if (getIntent().getBooleanExtra(EXTRA_START_DEVICE_LINK, false)) {
            // Post after scaffold creation so camera return/confirm sheet has a valid host window.
            root.post(this::startQrScan);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // QR-линковка устройства
    // ═══════════════════════════════════════════════════════════════

    /** Launches the custom portrait QR scanner only for a complete OAuth grant. */
    private void startQrScan() {
        ensureAuthorizedThen(() -> {
            clearPendingLinkState();
            qrLauncher.launch(new Intent(this, CameraScanActivity.class));
        });
    }

    /**
     * Гарантирует валидную OAuth-сессию перед линковкой. Если access-token в памяти
     * протух (но refresh-token есть) — молча обновляет его в фоне, как это делает
     * AccountApi, а не просит повторный вход. Только при реальном отсутствии сессии
     * показываем ошибку авторизации.
     */
    private void ensureAuthorizedThen(Runnable onReady) {
        OAuthSession session = OAuthSession.get();
        if (session.isAuthorized() && !isBlank(session.getAccessToken())) {
            onReady.run();
            return;
        }
        new Thread(() -> {
            boolean refreshed = false;
            try {
                refreshed = OAuthRefreshCoordinator.refreshBlocking(getApplicationContext());
            } catch (Throwable ignored) {
                // Ниже примем решение по фактическому состоянию сессии.
            }
            final boolean ok = refreshed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                OAuthSession fresh = OAuthSession.get();
                if (ok && fresh.isAuthorized() && !isBlank(fresh.getAccessToken())) {
                    onReady.run();
                    return;
                }
                List<String> missing = OAuthConfig.getMissingScopes(fresh.getScope());
                if (!missing.isEmpty()) {
                    ModernToast.show(this, getString(
                            R.string.oauth_missing_scopes, String.join(", ", missing)), true);
                } else {
                    ModernToast.show(this, getString(
                            R.string.scr_sec_link_err_unauthorized), true);
                }
                finishDirectDeviceLink(false);
            });
        }, "device-link-auth").start();
    }

    /** Called when the custom QR scanner returns. */
    private void onQrScanResult(androidx.activity.result.ActivityResult result) {
        if (result == null || result.getResultCode() != Activity.RESULT_OK) {
            ModernToast.show(this, getString(R.string.scr_sec_link_cancelled));
            finishDirectDeviceLink(false);
            return;
        }
        Intent data = result.getData();
        String raw = data != null ? data.getStringExtra(CameraScanActivity.EXTRA_RESULT) : null;
        if (raw == null || raw.isEmpty()) {
            ModernToast.show(this, getString(R.string.scr_sec_link_cancelled));
            finishDirectDeviceLink(false);
            return;
        }

        Uri uri = Uri.parse(raw);
        boolean exactRoute = uri.isHierarchical()
                && "multiai".equalsIgnoreCase(uri.getScheme())
                && "link".equalsIgnoreCase(uri.getHost())
                && (uri.getPath() == null || uri.getPath().isEmpty())
                && uri.getUserInfo() == null
                && uri.getPort() == -1
                && uri.getFragment() == null;
        if (!exactRoute) {
            showDeviceLinkError("invalid_qr");
            return;
        }
        Set<String> parameters = uri.getQueryParameterNames();
        boolean hasDeviceLabel = parameters.contains("device");
        boolean exactParameters = (parameters.size() == 3
                || (parameters.size() == 4 && hasDeviceLabel))
                && parameters.contains("session")
                && parameters.contains("nonce")
                && parameters.contains("hmac")
                && uri.getQueryParameters("session").size() == 1
                && uri.getQueryParameters("nonce").size() == 1
                && uri.getQueryParameters("hmac").size() == 1
                && (!hasDeviceLabel || uri.getQueryParameters("device").size() == 1);
        if (!exactParameters) {
            showDeviceLinkError("invalid_qr");
            return;
        }

        String session = uri.getQueryParameter("session");
        String nonce = uri.getQueryParameter("nonce");
        String hmac = uri.getQueryParameter("hmac");
        if (isBlank(session) || isBlank(nonce) || isBlank(hmac)) {
            showDeviceLinkError("invalid_qr");
            return;
        }

        String deviceLabel = hasDeviceLabel ? uri.getQueryParameter("device") : null;
        if (!isBlank(deviceLabel)) {
            deviceLabel = deviceLabel.trim().replaceAll("[\\p{Cntrl}\\s]+", " ");
            if (deviceLabel.length() > 64) deviceLabel = deviceLabel.substring(0, 64).trim();
        } else {
            deviceLabel = null;
        }

        final String linkSession = session;
        final String linkNonce = nonce;
        final String linkHmac = hmac;
        final String linkLabel = deviceLabel;
        // Свежий токен ПЕРЕД линковкой: access-token в памяти мог протухнуть за время
        // сканирования (или он near-expiry). refreshBlocking переиспользует ещё живой
        // токен и ротирует только протухший — тот же токен пойдёт в /scanned и /confirm.
        captureFreshAccessToken(accessToken -> {
            if (accessToken == null) {
                finishDirectDeviceLink(false);
                return;
            }
            pendingSessionId = linkSession;
            pendingNonce = linkNonce;
            pendingHmac = linkHmac;
            pendingAccessToken = accessToken;
            showLinkConfirmSheet(linkLabel);
        });
    }

    /**
     * Ensures a server-valid access token before capturing it for the link proof.
     * {@code refreshBlocking} reuses a still-fresh token and only rotates an expired
     * one, so a long scan that outlived the token no longer misfires «Войдите».
     */
    private void captureFreshAccessToken(java.util.function.Consumer<String> onToken) {
        new Thread(() -> {
            try {
                OAuthRefreshCoordinator.refreshBlocking(getApplicationContext());
            } catch (Throwable ignored) {
                // Fall through: snapshot decides on the actual session state.
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                onToken.accept(snapshotAuthorizedAccessToken());
            });
        }, "device-link-token").start();
    }

    /**
     * Forces exactly one rotating refresh (like AccountApi on 401), then re-runs the
     * step with the freshly minted token. A genuine sign-out aborts with an auth error.
     */
    private void rotateTokenThenRetry(String rejectedToken, Runnable onRotated) {
        new Thread(() -> {
            boolean rotated = false;
            try {
                rotated = OAuthRefreshCoordinator.refreshBlocking(
                        getApplicationContext(), rejectedToken);
            } catch (Throwable ignored) {
                // Ниже примем решение по фактическому состоянию сессии.
            }
            final boolean ok = rotated;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                OAuthSession fresh = OAuthSession.get();
                String token = ok && fresh.isAuthorized() ? fresh.getAccessToken() : null;
                if (isBlank(token)) {
                    dismissPendingConfirmSheet(true);
                    showDeviceLinkError("unauthorized");
                    return;
                }
                pendingAccessToken = token;
                onRotated.run();
            });
        }, "device-link-rotate").start();
    }

    /** Confirmation sheet appears first; only then is /scanned sent. */
    private void showLinkConfirmSheet(String deviceLabel) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        android.widget.TextView desc = new android.widget.TextView(this);
        desc.setText(R.string.scr_sec_link_confirm_desc);
        desc.setTextColor(SettingsUI.colorSubtext(this));
        desc.setTextSize(14);
        body.addView(desc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        confirm.setEnabled(false);
        confirm.setOnClickListener(v -> {
            if (!confirm.isEnabled()) return;
            confirm.setEnabled(false);
            cancelPendingOnDismiss = false;
            sendLinkConfirm(pendingAccessToken, pendingSessionId, pendingNonce, pendingHmac);
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = SettingsUI.dp(this, 18);
        body.addView(confirm, btnLp);

        String title = isBlank(deviceLabel)
                ? getString(R.string.scr_sec_link_confirm_title)
                : getString(R.string.scr_sec_link_confirm_named_title, deviceLabel);
        android.app.Dialog sheet = ModernSheet.custom(this, title, body, true);
        pendingConfirmSheet = sheet;
        cancelPendingOnDismiss = true;
        sheet.setOnDismissListener(dialog -> {
            if (pendingConfirmSheet == dialog) pendingConfirmSheet = null;
            finishPendingLinkDismiss();
        });
        sendLinkScanned(confirm);
    }

    /** Sends /scanned after the sheet is visible and unlocks confirm only on success. */
    private void sendLinkScanned(com.google.android.material.button.MaterialButton confirm) {
        final String accessToken = pendingAccessToken;
        final String sessionId = pendingSessionId;
        final String nonce = pendingNonce;
        final String hmac = pendingHmac;
        if (isBlank(accessToken) || isBlank(sessionId) || isBlank(nonce) || isBlank(hmac)) {
            dismissPendingConfirmSheet(false);
            showDeviceLinkError("invalid_qr");
            return;
        }
        deviceLinkClient.markDeviceLinkScanned(accessToken, sessionId, nonce, hmac, result -> {
            if (!isCurrentPendingLink(accessToken, sessionId, nonce, hmac)) return;
            if (result.ok) {
                confirm.setEnabled(true);
            } else if ("unauthorized".equals(result.error) && !scannedAuthRetried) {
                // Токен отвергнут сервером, хотя локально выглядел свежим: форсим одну
                // ротацию и повторяем /scanned новым токеном (его же затем шлём в /confirm).
                scannedAuthRetried = true;
                rotateTokenThenRetry(accessToken, () -> sendLinkScanned(confirm));
            } else {
                dismissPendingConfirmSheet(true);
                showDeviceLinkError(result.error);
            }
        });
    }

    /** Sends /confirm with the exact token snapshot already used for /scanned. */
    private void sendLinkConfirm(String accessToken, String sessionId, String nonce, String hmac) {
        if (isBlank(accessToken) || isBlank(sessionId) || isBlank(nonce) || isBlank(hmac)) {
            dismissPendingConfirmSheet(true);
            showDeviceLinkError("invalid_qr");
            return;
        }
        ModernToast.show(this, "…");
        deviceLinkClient.confirmDeviceLink(accessToken, sessionId, nonce, hmac, result -> {
            if (!isCurrentPendingLink(accessToken, sessionId, nonce, hmac)) return;
            if (result.ok) {
                dismissPendingConfirmSheet(false);
                String name = !isBlank(result.linkedDevice) ? result.linkedDevice : "Desktop";
                ModernToast.show(this, getString(R.string.scr_sec_link_success, name));
                finishDirectDeviceLink(true);
            } else {
                dismissPendingConfirmSheet(true);
                showDeviceLinkError(result.error);
            }
        });
    }

    private boolean ensureCompleteOAuthGrant() {
        OAuthSession session = OAuthSession.get();
        List<String> missing = OAuthConfig.getMissingScopes(session.getScope());
        if (!missing.isEmpty()) {
            ModernToast.show(this, getString(
                    R.string.oauth_missing_scopes, String.join(", ", missing)), true);
            return false;
        }
        if (!session.isAuthorized() || isBlank(session.getAccessToken())) {
            ModernToast.show(this, getString(R.string.scr_sec_link_err_unauthorized), true);
            return false;
        }
        return true;
    }

    private String snapshotAuthorizedAccessToken() {
        if (!ensureCompleteOAuthGrant()) return null;
        OAuthSession session = OAuthSession.get();
        String accessToken = session.getAccessToken();
        return isBlank(accessToken) ? null : accessToken;
    }

    private boolean isCurrentPendingLink(String accessToken, String sessionId,
                                         String nonce, String hmac) {
        return accessToken.equals(pendingAccessToken)
                && sessionId.equals(pendingSessionId)
                && nonce.equals(pendingNonce)
                && hmac.equals(pendingHmac);
    }

    private void dismissPendingConfirmSheet(boolean cancelSession) {
        cancelPendingOnDismiss = cancelSession;
        android.app.Dialog sheet = pendingConfirmSheet;
        pendingConfirmSheet = null;
        if (sheet != null && sheet.isShowing()) {
            sheet.dismiss();
        } else {
            finishPendingLinkDismiss();
        }
    }

    /** Clears proof/token first, then performs a best-effort authenticated cancel. */
    private void finishPendingLinkDismiss() {
        String accessToken = pendingAccessToken;
        String sessionId = pendingSessionId;
        String nonce = pendingNonce;
        String hmac = pendingHmac;
        boolean shouldCancel = cancelPendingOnDismiss;
        clearPendingLinkState();
        if (shouldCancel && !isBlank(accessToken) && !isBlank(sessionId)
                && !isBlank(nonce) && !isBlank(hmac)) {
            deviceLinkClient.cancelDeviceLink(accessToken, sessionId, nonce, hmac, ignored -> { });
        }
        if (shouldCancel) finishDirectDeviceLink(false);
    }

    private void clearPendingLinkState() {
        pendingSessionId = null;
        pendingNonce = null;
        pendingHmac = null;
        pendingAccessToken = null;
        cancelPendingOnDismiss = false;
        scannedAuthRetried = false;
    }

    private void showDeviceLinkError(String error) {
        final String message;
        switch (error == null ? "" : error) {
            case "invalid_qr":
                message = getString(R.string.scr_sec_link_err_invalid);
                break;
            case "session_expired":
                message = getString(R.string.scr_sec_link_err_expired);
                break;
            case "unauthorized":
                message = getString(R.string.scr_sec_link_err_unauthorized);
                break;
            case "network_error":
                message = getString(R.string.scr_sec_link_err_network);
                break;
            default:
                message = getString(R.string.scr_sec_link_err_unknown);
                break;
        }
        ModernToast.show(this, message, true);
        finishDirectDeviceLink(false);
    }

    private void finishDirectDeviceLink(boolean success) {
        if (!getIntent().getBooleanExtra(EXTRA_START_DEVICE_LINK, false)) return;
        setResult(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
        finish();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    protected void onDestroy() {
        dismissPendingConfirmSheet(true);
        super.onDestroy();
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
