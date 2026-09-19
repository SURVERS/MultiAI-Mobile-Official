package app.multiai.mobile.ui;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.multiai.mobile.R;
import app.multiai.mobile.network.oauth.AccountApi;
import app.multiai.mobile.network.oauth.OAuthClient;
import app.multiai.mobile.network.oauth.OAuthConfig;
import app.multiai.mobile.network.oauth.OAuthLoginController;
import app.multiai.mobile.network.oauth.OAuthSession;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppLockManager;

/** Telegram-style OAuth device sessions without exposing raw IP to the client. */
public class DevicesActivity extends AppCompatActivity {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "oauth-devices");
        thread.setDaemon(true);
        return thread;
    });

    private LinearLayout content;
    private ImageView refreshButton;
    private OAuthLoginController oauth;
    private AccountApi accountApi;
    private int loadGeneration;
    private boolean resumedOnce;
    /** Первая загрузка: не показываем промежуточный loading, чтобы не мигал hero. */
    private boolean firstLoad = true;
    /** Не-текущие сессии из последнего рендера — для надёжного завершения всех. */
    private final List<DeviceSession> lastOthers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AppLockManager.get(this).routeIfLocked(this)) return;

        // Плоский фон окна вместо темового ambient_background: иначе при входе
        // на миллисекунду мелькает старый градиент-подложка до отрисовки контента.
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                SettingsUI.colorBg(this)));

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(
                this, getString(R.string.scr_sec_devices_title), true);
        content = scaffold.content;
        refreshButton = scaffold.saveButton;
        refreshButton.setImageResource(R.drawable.ic_retry_arrow);
        refreshButton.setContentDescription(getString(R.string.scr_sec_devices_refresh));
        refreshButton.setOnClickListener(view -> loadDevices());

        oauth = new OAuthLoginController(this);
        accountApi = oauth.getAccountApi();
        loadDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content == null) return;
        if (resumedOnce) {
            loadDevices();
        } else {
            resumedOnce = true;
        }
    }

    private void loadDevices() {
        final int generation = ++loadGeneration;
        String grantedScope = OAuthSession.get().getScope();
        if (isBlank(grantedScope)) grantedScope = oauth.getStore().getScope();
        List<String> missing = OAuthConfig.getMissingScopes(grantedScope);
        if (!missing.isEmpty()) {
            renderMessage(getString(R.string.oauth_missing_scopes, String.join(", ", missing)), true);
            return;
        }
        if (!OAuthSession.get().isAuthorized() && !oauth.getStore().hasRefreshToken()) {
            renderMessage(getString(R.string.scr_sec_link_err_unauthorized), true);
            return;
        }

        setRefreshBusy(true);
        // На первом входе не рисуем промежуточный loading: hero пересоздавался бы
        // дважды (loading → devices) и мигал. Ждём данные и строим экран один раз.
        if (!firstLoad) {
            renderMessage(getString(R.string.scr_sec_devices_loading), false);
        }
        oauth.ensureMetadataAsync(() -> IO.execute(() -> {
            try {
                JSONObject response = accountApi.devices();
                JSONArray rows = response.optJSONArray("devices");
                if (rows == null) throw new OAuthClient.OAuthException("invalid_response");

                List<DeviceSession> devices = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    DeviceSession device = DeviceSession.from(rows.optJSONObject(i));
                    if (device != null) devices.add(device);
                }
                devices.sort(DeviceSession.ORDER);

                JSONObject capabilities = response.optJSONObject("capabilities");
                boolean revokeOthersAvailable = capabilities != null
                        && capabilities.optBoolean("revoke_others", false);
                DevicePolicy policy = null;
                if (capabilities != null && capabilities.optBoolean("inactive_ttl", false)) {
                    try {
                        policy = DevicePolicy.from(accountApi.deviceSettings());
                    } catch (Exception ignored) {
                        // Optional rollout: the rest of the sessions screen remains usable.
                    }
                }

                DevicePolicy loadedPolicy = policy;
                runOnUiThread(() -> {
                    if (generation != loadGeneration || !isAlive()) return;
                    setRefreshBusy(false);
                    renderDevices(devices, revokeOthersAvailable, loadedPolicy);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || !isAlive()) return;
                    setRefreshBusy(false);
                    renderMessage(getString(R.string.scr_sec_devices_load_error), true);
                });
            }
        }), error -> {
            if (generation != loadGeneration || !isAlive()) return;
            setRefreshBusy(false);
            renderMessage(getString(R.string.scr_sec_devices_load_error), true);
        });
    }

    private void renderDevices(List<DeviceSession> devices, boolean revokeOthersAvailable,
                               DevicePolicy policy) {
        resetContent();
        if (devices.isEmpty()) {
            addLinkEntry();
            addMessageCard(getString(R.string.scr_sec_devices_empty), false);
            return;
        }

        DeviceSession current = null;
        int currentCount = 0;
        for (DeviceSession device : devices) {
            if (device.current) {
                currentCount++;
                if (current == null) current = device;
            }
        }
        boolean actionsSafe = currentCount == 1;

        List<DeviceSession> others = new ArrayList<>();
        for (DeviceSession device : devices) {
            if (device != current) others.add(device);
        }
        lastOthers.clear();
        lastOthers.addAll(others);

        addLinkEntry();

        if (current != null) {
            content.addView(SettingsUI.sectionLabel(this,
                    getString(R.string.scr_sec_devices_current_section)));
            LinearLayout currentCard = SettingsUI.card(this);
            currentCard.addView(deviceRow(current, actionsSafe));
            content.addView(currentCard);
        }

        // Массовый отзыв идёт сразу под текущим устройством (как в Telegram):
        // красная строка с иконкой выхода + короткое пояснение под ней.
        if (actionsSafe && revokeOthersAvailable && !others.isEmpty()) {
            content.addView(SettingsUI.spacer(this, 12));
            LinearLayout terminateCard = SettingsUI.card(this);
            terminateCard.addView(dangerRowWithIcon(
                    getString(R.string.scr_sec_devices_revoke_others),
                    this::confirmRevokeOthers));
            content.addView(terminateCard);
            content.addView(sectionFootnote(
                    getString(R.string.scr_sec_devices_revoke_others_desc), false));
        }

        if (!actionsSafe) {
            content.addView(SettingsUI.spacer(this, 12));
            LinearLayout warningCard = SettingsUI.card(this);
            warningCard.addView(compactNote(
                    getString(R.string.scr_sec_devices_current_unknown), true));
            content.addView(warningCard);
        }

        content.addView(SettingsUI.sectionLabel(this,
                getString(R.string.scr_sec_devices_active_section)));
        if (others.isEmpty()) {
            addMessageCard(getString(R.string.scr_sec_devices_no_other_sessions), false);
        } else {
            LinearLayout activeCard = SettingsUI.card(this);
            for (int i = 0; i < others.size(); i++) {
                activeCard.addView(deviceRow(others.get(i), actionsSafe));
                if (i < others.size() - 1) activeCard.addView(SettingsUI.divider(this));
            }
            content.addView(activeCard);
        }
        content.addView(sectionFootnote(
                getString(R.string.scr_sec_devices_platforms_hint), false));

        if (policy != null) {
            content.addView(SettingsUI.sectionLabel(this,
                    getString(R.string.scr_sec_devices_auto_terminate_section)));
            LinearLayout policyCard = SettingsUI.card(this);
            policyCard.addView(SettingsUI.rowWithTrailing(this, R.drawable.ic_history,
                    getString(R.string.scr_sec_devices_auto_terminate), null,
                    trailingValue(formatTtl(policy.inactiveTtlDays)),
                    () -> showTtlPicker(policy)));
            content.addView(policyCard);
        }
    }

    /** Красная строка «Завершить все другие сеансы» с иконкой выхода (как в Telegram). */
    private View dangerRowWithIcon(String title, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 18), SettingsUI.dp(this, 16),
                SettingsUI.dp(this, 18), SettingsUI.dp(this, 16));
        row.setClickable(true);
        row.setFocusable(true);
        SettingsUI.attachPressAnimation(row, 0.98f);
        row.setOnClickListener(v -> { if (onClick != null) onClick.run(); });

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_s_logout);
        icon.setColorFilter(SettingsUI.colorDanger(this));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 22), SettingsUI.dp(this, 22));
        iconLp.setMarginEnd(SettingsUI.dp(this, 14));
        row.addView(icon, iconLp);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(SettingsUI.colorDanger(this));
        label.setTextSize(16);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        row.addView(label);
        return row;
    }

    /** Серое пояснение под секцией в отступах экрана (Telegram-style footnote). */
    private TextView sectionFootnote(String text, boolean error) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(13);
        note.setTextColor(error ? SettingsUI.colorDanger(this) : SettingsUI.colorSubtext(this));
        note.setLineSpacing(SettingsUI.dp(this, 2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = SettingsUI.dp(this, 20);
        lp.rightMargin = SettingsUI.dp(this, 20);
        lp.topMargin = SettingsUI.dp(this, 8);
        note.setLayoutParams(lp);
        return note;
    }

    /** Значение справа для строки-настройки (trailing value, как в Telegram). */
    private TextView trailingValue(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(SettingsUI.colorSubtext(this));
        text.setTextSize(15);
        text.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        return text;
    }

    /**
     * Человекочитаемый срок неактивности: «1 неделя», «3 месяца», «6 месяцев»,
     * «1 год» вместо сырого «Через N дн.». Округляет к ближайшей крупной единице.
     * Строки — через plurals, так что локализуются.
     */
    private String formatTtl(int days) {
        if (days >= 330) {
            int n = Math.max(1, Math.round(days / 365f));
            return getResources().getQuantityString(R.plurals.ttl_years, n, n);
        }
        if (days >= 25) {
            int n = Math.round(days / 30.4f);
            return getResources().getQuantityString(R.plurals.ttl_months, n, n);
        }
        if (days >= 7) {
            int n = Math.round(days / 7f);
            return getResources().getQuantityString(R.plurals.ttl_weeks, n, n);
        }
        int n = Math.max(1, days);
        return getResources().getQuantityString(R.plurals.ttl_days, n, n);
    }

    private void renderMessage(String message, boolean error) {
        resetContent();
        addLinkEntry();
        addMessageCard(message, error);
    }

    private void resetContent() {
        content.removeAllViews();
        firstLoad = false;
    }

    /**
     * Единая точка входа в привязку устройства — всегда крупный hero-блок.
     *
     * <p>Раньше при наличии других сеансов показывалась старая компактная строка,
     * и «новая» кнопка подключения из hero пропадала. Теперь UI единый вне
     * зависимости от количества активных сеансов.
     */
    private void addLinkEntry() {
        content.addView(createLinkHero());
        content.addView(SettingsUI.spacer(this, 4));
    }

    /** Индикатор присутствия: зелёная точка в сети, приглушённая — офлайн. */
    private View statusDot(DeviceSession device) {
        boolean online = device.onlineKnown && device.online;
        View dot = new View(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(online
                ? ContextCompat.getColor(this, R.color.success)
                : SettingsUI.colorMutedIcon(this));
        dot.setBackground(shape);
        int size = SettingsUI.dp(this, online ? 9 : 7);
        dot.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return dot;
    }

    /** Строка сеанса: цветной градиентный кружок платформы, имя, метаданные и индикатор присутствия. */
    private View deviceRow(DeviceSession device, boolean actionsSafe) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 12),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 12));
        row.setClickable(true);
        row.setFocusable(true);
        SettingsUI.attachPressAnimation(row, 0.98f);
        row.setOnClickListener(v -> showDeviceDetails(device, actionsSafe));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(platformBadge(device, 44));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 44), SettingsUI.dp(this, 44));
        iconLp.setMarginEnd(SettingsUI.dp(this, 14));
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(device.displayName(this));
        title.setTextColor(SettingsUI.colorText(this));
        title.setTextSize(16);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(device.rowSubtitle(this));
        subtitle.setTextColor(SettingsUI.colorSubtext(this));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, SettingsUI.dp(this, 2), 0, 0);
        textCol.addView(subtitle);
        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View dot = statusDot(device);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, device.onlineKnown && device.online ? 9 : 7),
                SettingsUI.dp(this, device.onlineKnown && device.online ? 9 : 7));
        dotLp.setMarginStart(SettingsUI.dp(this, 8));
        dot.setLayoutParams(dotLp);
        row.addView(dot);
        return row;
    }

    // ═══════════════════════════════════════════════════════════════
    // Иконки платформ (перенос механики Telegram SessionCell.createDrawable):
    // белая иконка платформы поверх вертикального градиентного круга.
    // ═══════════════════════════════════════════════════════════════

    /** Белая иконка платформы на градиентном круге размером sizeDp. */
    private Drawable platformBadge(DeviceSession device, int sizeDp) {
        int[] colors = platformColors(device);
        GradientDrawable circle = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colors);
        circle.setShape(GradientDrawable.OVAL);

        Drawable icon = ContextCompat.getDrawable(this, platformIconRes(device));
        if (icon != null) {
            icon = icon.mutate();
            icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        }
        LayerDrawable layers = new LayerDrawable(new Drawable[]{circle, icon});
        int inset = SettingsUI.dp(this, (int) Math.round(sizeDp * 0.24));
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    /** PNG-иконка платформы для списка (перенос из Telegram, drawable-*dpi/device_*.png). */
    private int platformIconRes(DeviceSession device) {
        String s = (device.platform + " " + device.kind + " " + device.name
                + " " + device.appName).toLowerCase(Locale.ROOT);
        if (s.contains("safari")) return R.drawable.device_web_safari;
        if (s.contains("edge")) return R.drawable.device_web_edge;
        if (s.contains("chrome")) return R.drawable.device_web_chrome;
        if (s.contains("opera")) return R.drawable.device_web_opera;
        if (s.contains("firefox")) return R.drawable.device_web_firefox;
        if (s.contains("android")) {
            return s.contains("tab") ? R.drawable.device_tablet_android
                    : R.drawable.device_phone_android;
        }
        // MultiAI Desktop поддерживается только на Windows.
        if (s.contains("windows") || s.contains("win32")
                || s.contains("desktop") || s.contains("pc")) return R.drawable.device_desktop_win;
        return R.drawable.device_web_other;
    }

    /** Пара цветов градиента круга по платформе (cyan/green/blue/pink, как в Telegram). */
    private int[] platformColors(DeviceSession device) {
        String s = (device.platform + " " + device.kind + " " + device.name
                + " " + device.appName).toLowerCase(Locale.ROOT);
        if (s.contains("windows") || s.contains("win32")
                || s.contains("desktop") || s.contains("pc")) {
            return new int[]{0xFF4EC2F7, 0xFF2A9EF1}; // cyan (Windows / ПК)
        }
        if (s.contains("android")) {
            return new int[]{0xFF77CE5B, 0xFF4FAF3A}; // green
        }
        return new int[]{0xFFE873B0, 0xFFCB4E93}; // pink (web/browsers)
    }

    /** Lottie-анимация платформы для большой иконки в шите (res/raw/*_30.json). */
    private int platformLottieRes(DeviceSession device) {
        String s = (device.platform + " " + device.kind + " " + device.name
                + " " + device.appName).toLowerCase(Locale.ROOT);
        if (s.contains("safari")) return R.raw.safari_30;
        if (s.contains("edge")) return R.raw.edge_30;
        if (s.contains("firefox")) return R.raw.firefox_30;
        if (s.contains("chrome") || s.contains("opera")) return R.raw.chrome_30;
        if (s.contains("android")) return R.raw.android_30;
        // MultiAI Desktop поддерживается только на Windows.
        if (s.contains("windows") || s.contains("win32")
                || s.contains("desktop") || s.contains("pc")) return R.raw.windows_30;
        return R.raw.chrome_30;
    }

    private View createLinkHero() {
        LinearLayout card = SettingsUI.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int horizontal = SettingsUI.dp(this, 24);
        card.setPadding(horizontal, SettingsUI.dp(this, 22),
                horizontal, SettingsUI.dp(this, 24));

        // Зацикленная Lottie-иллюстрация компьютера над кнопкой подключения.
        LottieAnimationView hero = new LottieAnimationView(this);
        hero.setAnimation(R.raw.programming_computer);
        hero.setRepeatCount(com.airbnb.lottie.LottieDrawable.INFINITE);
        hero.playAnimation();
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 220), SettingsUI.dp(this, 220));
        heroLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(hero, heroLp);

        TextView title = new TextView(this);
        title.setText(R.string.scr_sec_devices_link_title);
        title.setTextColor(SettingsUI.colorText(this));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = SettingsUI.dp(this, 16);
        card.addView(title, titleLp);

        TextView description = new TextView(this);
        description.setText(R.string.scr_sec_devices_link_desc);
        description.setTextColor(SettingsUI.colorSubtext(this));
        description.setTextSize(14);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(SettingsUI.dp(this, 2), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = SettingsUI.dp(this, 6);
        card.addView(description, descLp);

        View action = linkPillButton();
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUI.dp(this, 54));
        actionLp.topMargin = SettingsUI.dp(this, 20);
        card.addView(action, actionLp);
        return card;
    }

    /**
     * Широкая pill-кнопка «Подключить устройство» в стиле Telegram: полностью
     * скруглённая, tonal-фон, QR-иконка слева от текста. QR-иконку берём ту же,
     * что и раздел безопасности (header_qr_24), чтобы флоу линковки был единым.
     */
    private View linkPillButton() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setClickable(true);
        pill.setFocusable(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.BLACK);
        background.setCornerRadius(SettingsUI.dp(this, 27));
        pill.setBackground(background);
        SettingsUI.attachPressAnimation(pill, 0.97f);
        pill.setOnClickListener(view -> startActivity(new Intent(
                this, SecuritySettingsActivity.class)
                .putExtra(SecuritySettingsActivity.EXTRA_START_DEVICE_LINK, true)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.header_qr_24);
        icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 30), SettingsUI.dp(this, 30));
        iconLp.setMarginEnd(SettingsUI.dp(this, 12));
        pill.addView(icon, iconLp);

        TextView label = new TextView(this);
        label.setText(R.string.scr_sec_devices_link_action);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        pill.addView(label);
        return pill;
    }

    private void addMessageCard(String message, boolean error) {
        LinearLayout card = SettingsUI.card(this);
        card.addView(messageView(message, error));
        content.addView(card);
    }

    private TextView messageView(String text, boolean error) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(error ? SettingsUI.colorDanger(this) : SettingsUI.colorSubtext(this));
        int pad = SettingsUI.dp(this, 18);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    private TextView compactNote(String text, boolean error) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(13);
        note.setTextColor(error ? SettingsUI.colorDanger(this) : SettingsUI.colorSubtext(this));
        note.setPadding(SettingsUI.dp(this, 16), 0,
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
        return note;
    }

    private void showDeviceDetails(DeviceSession device, boolean actionsSafe) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        // Большая иконка платформы на чёрном круге + Lottie поверх (1:1 Telegram
        // SessionBottomSheet): играет при открытии, тап перезапускает анимацию.
        FrameLayout iconWrap = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.BLACK);
        iconWrap.setBackground(circle);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 84), SettingsUI.dp(this, 84));
        wrapLp.gravity = Gravity.CENTER_HORIZONTAL;
        wrapLp.topMargin = SettingsUI.dp(this, 8);
        body.addView(iconWrap, wrapLp);

        LottieAnimationView icon = new LottieAnimationView(this);
        icon.setAnimation(platformLottieRes(device));
        icon.setRepeatCount(0);
        icon.playAnimation();
        icon.setOnClickListener(v -> {
            if (!icon.isAnimating()) {
                icon.setFrame(0);
                icon.playAnimation();
            }
        });
        FrameLayout.LayoutParams animLp = new FrameLayout.LayoutParams(
                SettingsUI.dp(this, 52), SettingsUI.dp(this, 52));
        animLp.gravity = Gravity.CENTER;
        iconWrap.addView(icon, animLp);

        TextView nameView = new TextView(this);
        nameView.setText(device.displayName(this));
        nameView.setTextSize(20);
        nameView.setTypeface(nameView.getTypeface(), Typeface.BOLD);
        nameView.setTextColor(SettingsUI.colorText(this));
        nameView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = SettingsUI.dp(this, 12);
        nameLp.leftMargin = SettingsUI.dp(this, 16);
        nameLp.rightMargin = SettingsUI.dp(this, 16);
        body.addView(nameView, nameLp);

        TextView timeView = new TextView(this);
        timeView.setText(device.statusText(this));
        timeView.setTextSize(13);
        timeView.setGravity(Gravity.CENTER);
        timeView.setTextColor(device.onlineKnown && device.online
                ? ContextCompat.getColor(this, R.color.success)
                : SettingsUI.colorSubtext(this));
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.topMargin = SettingsUI.dp(this, 4);
        timeLp.bottomMargin = SettingsUI.dp(this, 18);
        body.addView(timeView, timeLp);

        // Детали в стиле Telegram: строка с иконкой слева, значение сверху,
        // подпись снизу. Поля без данных пропускаем — «Неизвестно» тут ни к чему.
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailsLp.topMargin = SettingsUI.dp(this, 6);
        body.addView(details, detailsLp);

        String unknown = getString(R.string.scr_sec_device_unknown_value);
        String application = device.applicationDisplay(this);
        if (!isBlank(application) && !application.equals(unknown)) {
            addDetailRow(details, R.drawable.menu_devices, application,
                    getString(R.string.scr_sec_device_detail_app_hint));
        }
        String location = device.locationDisplay(this);
        if (!isBlank(location) && !location.equals(unknown)) {
            addDetailRow(details, R.drawable.msg_location, location,
                    getString(R.string.scr_sec_device_detail_location_hint));
        }
        if (!device.createdAt.isEmpty()) {
            addDetailRow(details, R.drawable.ic_calendar,
                    DeviceSession.relativeTime(this, device.createdAt).toString(),
                    getString(R.string.scr_sec_device_detail_created));
        }

        final Dialog[] sheet = new Dialog[1];
        if (!device.current && actionsSafe) {
            TextView terminate = new TextView(this);
            terminate.setText(R.string.scr_sec_device_revoke);
            terminate.setGravity(Gravity.CENTER);
            terminate.setTextSize(15);
            terminate.setTypeface(terminate.getTypeface(), Typeface.BOLD);
            terminate.setTextColor(SettingsUI.colorText(this));
            GradientDrawable terminateBg = new GradientDrawable();
            terminateBg.setColor(SettingsUI.colorSurfaceTonal(this));
            terminateBg.setCornerRadius(SettingsUI.dp(this, 24));
            terminate.setBackground(terminateBg);
            terminate.setClickable(true);
            terminate.setFocusable(true);
            SettingsUI.attachPressAnimation(terminate, 0.97f);
            terminate.setOnClickListener(view -> {
                if (sheet[0] != null) sheet[0].dismiss();
                confirmRevoke(device);
            });
            LinearLayout.LayoutParams terminateLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, SettingsUI.dp(this, 48));
            terminateLp.topMargin = SettingsUI.dp(this, 16);
            terminateLp.leftMargin = SettingsUI.dp(this, 4);
            terminateLp.rightMargin = SettingsUI.dp(this, 4);
            body.addView(terminate, terminateLp);
        }

        // Заголовок не передаём — имя устройства уже показано в теле по центру (как в ТГ).
        sheet[0] = ModernSheet.custom(this, null, body, true);
    }

    /**
     * Строка деталей в стиле Telegram: иконка слева, значение крупно сверху,
     * подпись мелко снизу. Разделитель добавляется перед строкой, если она не первая.
     */
    private void addDetailRow(LinearLayout parent, int iconRes, String value, String hint) {
        if (parent.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(SettingsUI.colorDivider(this));
            LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerLp.leftMargin = SettingsUI.dp(this, 50);
            parent.addView(divider, dividerLp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 4), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 4), SettingsUI.dp(this, 10));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(SettingsUI.colorMutedIcon(this));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 26), SettingsUI.dp(this, 26));
        iconLp.setMarginEnd(SettingsUI.dp(this, 20));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(16);
        valueView.setTextColor(SettingsUI.colorText(this));
        textCol.addView(valueView);

        TextView hintView = new TextView(this);
        hintView.setText(hint);
        hintView.setTextSize(12);
        hintView.setTextColor(SettingsUI.colorSubtext(this));
        hintView.setPadding(0, SettingsUI.dp(this, 1), 0, 0);
        textCol.addView(hintView);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void confirmRevoke(DeviceSession device) {
        if (device.current) return;
        ModernDialog.show(this,
                getString(R.string.scr_sec_device_revoke_title),
                getString(R.string.scr_sec_device_revoke_message, device.displayName(this)),
                getString(android.R.string.cancel),
                getString(R.string.scr_sec_device_revoke),
                true,
                () -> revoke(device));
    }

    private void revoke(DeviceSession device) {
        if (device.current) return;
        final int generation = ++loadGeneration;
        setRefreshBusy(true);
        renderMessage(getString(R.string.scr_sec_devices_loading), false);
        IO.execute(() -> {
            try {
                accountApi.revokeDevice(device.id);
                runOnUiThread(() -> {
                    if (generation != loadGeneration || !isAlive()) return;
                    ModernToast.show(this, getString(R.string.scr_sec_device_revoked));
                    loadDevices();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || !isAlive()) return;
                    ModernToast.show(this, getString(R.string.scr_sec_device_revoke_error), true);
                    loadDevices();
                });
            }
        });
    }

    private void confirmRevokeOthers() {
        ModernDialog.show(this,
                getString(R.string.scr_sec_devices_revoke_others_title),
                getString(R.string.scr_sec_devices_revoke_others_message),
                getString(android.R.string.cancel),
                getString(R.string.scr_sec_devices_revoke_others),
                true,
                this::revokeOtherDevices);
    }

    private void revokeOtherDevices() {
        final int generation = ++loadGeneration;
        setRefreshBusy(true);
        renderMessage(getString(R.string.scr_sec_devices_loading), false);
        final List<DeviceSession> targets = new ArrayList<>(lastOthers);
        IO.execute(() -> {
            boolean hadError = false;
            // Сначала пробуем массовый отзыв (если бэкенд умеет)…
            try {
                accountApi.revokeOtherDevices();
            } catch (Exception error) {
                if (!isFeatureUnavailable(error)) hadError = true;
            }
            // …затем гарантированно добиваем каждую оставшуюся чужую сессию
            // поштучно — bulk-отзыв на бэке завершал не всех.
            for (DeviceSession target : targets) {
                if (target.id == null || target.id.isEmpty()) continue;
                try {
                    accountApi.revokeDevice(target.id);
                } catch (Exception error) {
                    if (!isFeatureUnavailable(error)) hadError = true;
                }
            }
            final boolean errored = hadError;
            runOnUiThread(() -> {
                if (generation != loadGeneration || !isAlive()) return;
                if (errored) {
                    ModernToast.show(this,
                            getString(R.string.scr_sec_devices_revoke_others_error), true);
                } else {
                    ModernToast.show(this,
                            getString(R.string.scr_sec_devices_revoked_others, targets.size()));
                }
                loadDevices();
            });
        });
    }

    private void showTtlPicker(DevicePolicy policy) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(SettingsUI.dp(this, 4), 0, SettingsUI.dp(this, 4), SettingsUI.dp(this, 6));

        final Dialog[] sheet = new Dialog[1];
        for (int days : policy.allowedDays) {
            boolean checked = days == policy.inactiveTtlDays;
            body.addView(ttlRadioRow(formatTtl(days), checked, () -> {
                        if (sheet[0] != null) sheet[0].dismiss();
                        if (!checked) updateInactiveTtl(days);
                    }));
        }
        sheet[0] = ModernSheet.custom(this,
                getString(R.string.scr_sec_devices_auto_terminate_picker), body, true);
    }

    /** Строка выбора с radio-индикатором (заполненный кружок у текущего значения). */
    private View ttlRadioRow(String label, boolean checked, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 14));
        row.setClickable(true);
        row.setFocusable(true);
        SettingsUI.attachPressAnimation(row, 0.98f);
        row.setOnClickListener(v -> { if (onClick != null) onClick.run(); });

        int accent = AccentColor.resolve(this);
        View radio = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(android.graphics.Color.TRANSPARENT);
        ring.setStroke(SettingsUI.dp(this, 2),
                checked ? accent : SettingsUI.colorMutedIcon(this));
        if (checked) {
            // Внутренняя заполненная точка через inset accent-круга.
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            android.graphics.drawable.InsetDrawable inset =
                    new android.graphics.drawable.InsetDrawable(dot, SettingsUI.dp(this, 5));
            android.graphics.drawable.LayerDrawable layers =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{ring, inset});
            radio.setBackground(layers);
        } else {
            radio.setBackground(ring);
        }
        LinearLayout.LayoutParams radioLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 22), SettingsUI.dp(this, 22));
        radioLp.setMarginEnd(SettingsUI.dp(this, 16));
        row.addView(radio, radioLp);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(16);
        text.setTextColor(SettingsUI.colorText(this));
        row.addView(text);
        return row;
    }

    private void updateInactiveTtl(int days) {
        final int generation = ++loadGeneration;
        setRefreshBusy(true);
        IO.execute(() -> {
            try {
                accountApi.updateInactiveDeviceTtl(days);
                runOnUiThread(() -> {
                    if (generation != loadGeneration || !isAlive()) return;
                    ModernToast.show(this, getString(
                            R.string.scr_sec_devices_auto_terminate_saved, days));
                    loadDevices();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || !isAlive()) return;
                    int message = isFeatureUnavailable(error)
                            ? R.string.scr_sec_devices_feature_unavailable
                            : R.string.scr_sec_devices_auto_terminate_error;
                    ModernToast.show(this, getString(message), true);
                    loadDevices();
                });
            }
        });
    }

    private void setRefreshBusy(boolean busy) {
        if (refreshButton == null) return;
        refreshButton.setEnabled(!busy);
        refreshButton.setAlpha(busy ? 0.45f : 1f);
    }

    private boolean isAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private static boolean isFeatureUnavailable(Exception error) {
        if (!(error instanceof OAuthClient.OAuthException)) return false;
        OAuthClient.OAuthException oauthError = (OAuthClient.OAuthException) error;
        return oauthError.http == 404 || oauthError.http == 405 || oauthError.http == 501
                || "feature_unavailable".equals(oauthError.oauthError)
                || "device_not_found".equals(oauthError.oauthError);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class DevicePolicy {
        final int inactiveTtlDays;
        final List<Integer> allowedDays;

        private DevicePolicy(int inactiveTtlDays, List<Integer> allowedDays) {
            this.inactiveTtlDays = inactiveTtlDays;
            this.allowedDays = allowedDays;
        }

        static DevicePolicy from(JSONObject json) {
            if (json == null) return null;
            int current = json.optInt("inactive_ttl_days", 0);
            if (current <= 0) return null;
            List<Integer> allowed = new ArrayList<>();
            JSONArray values = json.optJSONArray("allowed_inactive_ttl_days");
            if (values != null) {
                for (int i = 0; i < values.length(); i++) {
                    int days = values.optInt(i, 0);
                    if (days > 0 && days <= 3650 && !allowed.contains(days)) allowed.add(days);
                }
            }
            if (allowed.isEmpty()) {
                int[] defaults = {7, 30, 90, 180, 365};
                for (int days : defaults) allowed.add(days);
            }
            if (!allowed.contains(current)) allowed.add(current);
            allowed.sort(Integer::compareTo);
            return new DevicePolicy(current, allowed);
        }
    }

    private static final class DeviceSession {
        static final Comparator<DeviceSession> ORDER = (left, right) -> {
            if (left.current != right.current) return left.current ? -1 : 1;
            if (left.online != right.online) return left.online ? -1 : 1;
            return right.lastSeenAt.compareTo(left.lastSeenAt);
        };

        final String id;
        final String kind;
        final String name;
        final String platform;
        final String appName;
        final String appVersion;
        final String countryCode;
        final String countryName;
        final String createdAt;
        final String lastSeenAt;
        final boolean online;
        final boolean onlineKnown;
        final boolean current;

        private DeviceSession(String id, String kind, String name, String platform,
                              String appName, String appVersion, String countryCode,
                              String countryName, String createdAt, String lastSeenAt,
                              boolean online, boolean onlineKnown, boolean current) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.platform = platform;
            this.appName = appName;
            this.appVersion = appVersion;
            this.countryCode = countryCode;
            this.countryName = countryName;
            this.createdAt = createdAt;
            this.lastSeenAt = lastSeenAt;
            this.online = online;
            this.onlineKnown = onlineKnown;
            this.current = current;
        }

        static DeviceSession from(JSONObject row) {
            if (row == null) return null;
            String id = first(row, "id", "device_id", "session_id");
            if (id.isEmpty()) return null;
            String countryCode = first(row, "country_code").toUpperCase(Locale.ROOT);
            if (!countryCode.matches("[A-Z]{2}")) countryCode = "";
            boolean onlineKnown = row.has("online") && !row.isNull("online");
            return new DeviceSession(
                    id,
                    first(row, "kind", "device_kind", "type"),
                    first(row, "device_name", "name", "device_model"),
                    first(row, "device_platform", "platform", "os_name"),
                    first(row, "app_name", "application_name", "application"),
                    first(row, "app_version", "version"),
                    countryCode,
                    first(row, "country_name", "country"),
                    first(row, "created_at", "authorized_at"),
                    first(row, "last_seen_at", "last_used_at", "updated_at"),
                    row.optBoolean("online", false),
                    onlineKnown,
                    row.optBoolean("is_current", row.optBoolean("current", false)));
        }

        String displayName(DevicesActivity activity) {
            if (!name.isEmpty()) return name;
            String inferred = platformDisplay(activity);
            return inferred.isEmpty()
                    ? activity.getString(R.string.scr_sec_device_unknown) : inferred;
        }

        String rowSubtitle(DevicesActivity activity) {
            List<String> metadata = new ArrayList<>();
            String application = applicationDisplay(activity);
            if (!application.equals(activity.getString(R.string.scr_sec_device_unknown_value))) {
                metadata.add(application);
            }
            String location = locationDisplay(activity);
            if (!location.equals(activity.getString(R.string.scr_sec_device_unknown_value))) {
                metadata.add(location);
            }
            String status = statusText(activity);
            if (metadata.isEmpty()) return status;
            return String.join(" · ", metadata) + (status.isEmpty() ? "" : "\n" + status);
        }

        String statusText(DevicesActivity activity) {
            if (onlineKnown && online) return activity.getString(R.string.scr_sec_device_online);
            if (!lastSeenAt.isEmpty()) {
                return activity.getString(R.string.scr_sec_device_last_used,
                        relativeTime(activity, lastSeenAt));
            }
            if (current) return activity.getString(R.string.scr_sec_device_current);
            return activity.getString(R.string.scr_sec_device_activity_unknown);
        }

        /** Веб-сессия (вход через браузер), а не десктоп-приложение MultiAI. */
        boolean looksWebBrowser() {
            String s = (platform + " " + kind + " " + name + " " + appName)
                    .toLowerCase(Locale.ROOT);
            return s.contains("chrome") || s.contains("firefox") || s.contains("safari")
                    || s.contains("edge") || s.contains("opera") || s.contains("browser")
                    || s.contains("web");
        }

        String applicationDisplay(DevicesActivity activity) {
            // Браузерный вход — это «MultiAI Web», а не десктоп-приложение, даже если
            // сервер прислал app_name «MultiAI Desktop» (общий origin для веба и ПК).
            if (looksWebBrowser()) {
                return "MultiAI Web";
            }
            String resolvedName = appName;
            if (resolvedName.isEmpty()) {
                String lowerKind = kind.toLowerCase(Locale.ROOT);
                String lowerPlatform = platform.toLowerCase(Locale.ROOT);
                if (lowerKind.contains("mobile") || lowerPlatform.contains("android")
                        || lowerPlatform.contains("ios")) {
                    resolvedName = "MultiAI Mobile";
                } else if (lowerKind.contains("desktop") || lowerPlatform.contains("windows")
                        || lowerPlatform.contains("linux") || lowerPlatform.contains("mac")) {
                    resolvedName = "MultiAI Desktop";
                }
            }
            if (resolvedName.isEmpty()) {
                return activity.getString(R.string.scr_sec_device_unknown_value);
            }
            return appVersion.isEmpty() ? resolvedName : resolvedName + " " + appVersion;
        }

        String platformDisplay(DevicesActivity activity) {
            String source = !platform.isEmpty() ? platform : kind;
            String lower = source.toLowerCase(Locale.ROOT);
            if (lower.contains("android")) return "Android";
            if (lower.contains("windows") || lower.equals("win32")) return "Windows";
            if (lower.contains("mac") || lower.contains("darwin")) return "macOS";
            if (lower.contains("linux")) return "Linux";
            if (lower.contains("ios") || lower.contains("iphone") || lower.contains("ipad")) {
                return "iOS";
            }
            if (lower.contains("web") || lower.contains("browser")) return "Web";
            return source.isEmpty()
                    ? activity.getString(R.string.scr_sec_device_unknown_value) : source;
        }

        String locationDisplay(DevicesActivity activity) {
            if (!countryName.isEmpty() && !countryCode.isEmpty()
                    && !countryName.equalsIgnoreCase(countryCode)) {
                return countryName + " (" + countryCode + ")";
            }
            if (!countryName.isEmpty()) return countryName;
            if (!countryCode.isEmpty()) return countryCode;
            return activity.getString(R.string.scr_sec_device_unknown_value);
        }

        int iconRes() {
            String source = (platform + " " + kind).toLowerCase(Locale.ROOT);
            if (source.contains("android") || source.contains("ios")
                    || source.contains("mobile") || source.contains("phone")
                    || source.contains("tablet")) {
                return R.drawable.ic_device_mobile;
            }
            if (source.contains("windows") || source.contains("win32")) {
                return R.drawable.ic_device_windows;
            }
            if (source.contains("web") || source.contains("browser")) {
                return R.drawable.ic_device_web;
            }
            if (source.contains("desktop") || source.contains("linux")
                    || source.contains("mac") || source.contains("darwin")
                    || source.contains("pc")) {
                return R.drawable.ic_device_desktop;
            }
            return R.drawable.ic_device_unknown;
        }

        /**
         * Человекочитаемая давность события.
         *
         * <p>Раньше отдавали сырой {@link DateUtils#getRelativeTimeSpanString}: когда
         * серверная метка оказывалась на пару секунд впереди часов устройства (а для
         * текущего сеанса это норма), выходило «Через 0 мин.» — время в будущем.
         * Поэтому будущее подтягиваем к «сейчас», а всё свежее в пределах минуты
         * показываем словом, а не нулём.
         */
        static CharSequence relativeTime(android.content.Context ctx, String value) {
            try {
                long timestamp = Instant.parse(value).toEpochMilli();
                long now = System.currentTimeMillis();
                if (timestamp > now) timestamp = now;
                if (now - timestamp < DateUtils.MINUTE_IN_MILLIS) {
                    return ctx.getString(R.string.scr_sec_device_just_now);
                }
                return DateUtils.getRelativeTimeSpanString(timestamp, now,
                        DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
            } catch (Exception ignored) {
                return value;
            }
        }

        private static String first(JSONObject row, String... names) {
            for (String name : names) {
                String value = sanitize(row.optString(name, ""));
                if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
            }
            return "";
        }

        private static String sanitize(String value) {
            if (value == null) return "";
            String clean = value.replaceAll("[\\p{Cntrl}]+", " ").trim();
            return clean.length() <= 160 ? clean : clean.substring(0, 160).trim();
        }
    }
}
