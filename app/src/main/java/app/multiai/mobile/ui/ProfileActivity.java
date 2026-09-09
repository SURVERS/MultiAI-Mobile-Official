package app.multiai.mobile.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.multiai.mobile.R;
import app.multiai.mobile.adapters.ChatsAdapter;
import app.multiai.mobile.models.Chat;
import app.multiai.mobile.network.oauth.OAuthLoginController;
import app.multiai.mobile.network.oauth.OAuthStore;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.ChatManager;
import app.multiai.mobile.utils.DrawerNavHelper;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.kit.MotionKit;
import app.multiai.mobile.views.DonutChartView;
import app.multiai.mobile.views.UsageChartView;

/**
 * Профиль пользователя MultiAI (OAuth Authorization Code + PKCE).
 *
 * <p>Экран построен вокруг четырёх вкладок — Главная / API-ключи / Расходы /
 * Подписка. Акцентный цвет берётся из настроек пользователя
 * ({@link AccentColor#resolve}), базовая палитра — «элегантный чёрный»
 * семантических цветов приложения.
 *
 * <p>Вкладка «Расходы» пока рисуется на демо-данных: серверный эндпоинт
 * {@code GET /api/oauth/v1/usage} описан в ТЗ биллинг-API и ещё не реализован.
 * Когда он появится — {@link #renderUsageDemo()} заменяется на загрузку
 * реальных значений, разметка и вьюхи останутся прежними.
 *
 * <p>Вход — внешний системный браузер открывает
 * {@code https://multiai.store/oauth/authorize}; обратный вызов приходит на
 * {@code multiai-mobile://oauth/callback}, активити {@code singleTask},
 * доходит через {@link #onNewIntent}.
 */
public class ProfileActivity extends AppCompatActivity {
    private static final String TAG = "ProfileActivity";

    private DrawerLayout drawerLayout;
    private app.multiai.mobile.ui.kit.ChatModeSidebar chatModeSidebar;
    private RecyclerView recyclerChats;
    private ChatsAdapter chatsAdapter;
    private ChatManager chatManager;

    // Header
    private ImageView imgAvatar;
    private TextView textUserName;
    private TextView textUserEmail;
    private TextView textMemberSince;
    private ImageButton btnOverflow;

    // Tabs + sections
    private LinearLayout profileTabs;
    private android.widget.HorizontalScrollView tabsScroller;
    private TextView[] tabViews = new TextView[5];
    private LinearLayout sectionHome;
    private LinearLayout sectionKeys;
    private LinearLayout sectionUsage;
    private LinearLayout sectionSubscription;
    private int currentTab = 0;

    // Home tab
    private TextView textTokensTotal;
    private TextView textTokensSubscription;
    private TextView textTokensBilling;

    // Subscription tab
    private View planInactive;
    private View planActive;
    private TextView textPlanName;
    private TextView textPlanExpires;
    private TextView textPlanDaysLeft;
    private ProgressBar planProgress;
    private ProgressBar limitFiveHour;
    private ProgressBar limitWeekly;
    private ProgressBar limitMonthly;
    private TextView textLimitFiveHour;
    private TextView textLimitWeekly;
    private TextView textLimitMonthly;
    private TextView pctFiveHour;
    private TextView pctWeekly;
    private TextView pctMonthly;
    private LinearLayout subscriptionPlans;
    private LinearLayout paymentHistoryList;
    /** Живой token_purchase из GET /billing/offers (min/max/цена для слайдера). */
    private JSONObject tokenPurchase;
    /** Последние транзакции из billing/history (для «Истории платежей»). */
    private JSONArray lastTransactions;
    /** Полный ответ billing/history (для «Вся история»). */
    private JSONObject lastHistoryFull;
    /** Ключи юзера для фильтра-чипов в шитах. */
    private JSONArray lastKeys;

    // Keys tab
    private LinearLayout keysList;
    private TextView keysEmpty;

    // Usage tab
    private UsageChartView usageChart;
    private DonutChartView usageDonut;
    private LinearLayout usageLegend;
    private LinearLayout recentList;
    private TextView textUsagePeriod;
    private int usagePeriodMode = 2; // 0 = день, 1 = неделя, 2 = месяц
    private int usageHighlight = -1; // выделенный сегмент доната

    /** Строки легенды «Структуры расходов» — подсвечиваются вместе с сегментом. */
    private final java.util.List<View> usageLegendRows = new ArrayList<>();
    private final java.util.List<String> usageSliceNames = new ArrayList<>();
    private final java.util.List<Float> usageSliceTokens = new ArrayList<>();
    private float usageTotalTokens;

    // Auth
    private Button btnSignIn;

    private OAuthLoginController oauth;
    private OAuthStore store;
    /** Ignores stale account responses after refresh, navigation or Activity teardown. */
    private final java.util.concurrent.atomic.AtomicInteger accountLoadGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final long NO_PENDING_SELECTED_KEY = Long.MIN_VALUE;
    /** Preserves an explicit local key choice until the server echoes the same id. */
    private long pendingSelectedKeyId = NO_PENDING_SELECTED_KEY;

    /**
     * Resolves the active key without treating a missing/zero server field as a reset.
     * The API can return an older snapshot directly after PUT/DELETE connection/key.
     */
    private long selectedKeyIdFor(JSONObject snap) {
        long storedId = store != null ? store.getSelectedKeyId() : -1L;
        long serverId = -1L;
        boolean hasServerSelection = false;
        if (snap != null) {
            JSONObject connection = snap.optJSONObject("connection");
            if (connection != null && connection.has("selected_key_id")) {
                serverId = connection.optLong("selected_key_id", -1L);
                hasServerSelection = true;
            }
        }

        if (pendingSelectedKeyId != NO_PENDING_SELECTED_KEY) {
            boolean serverConfirmed = hasServerSelection
                    && ((pendingSelectedKeyId > 0L && serverId == pendingSelectedKeyId)
                    || (pendingSelectedKeyId <= 0L && serverId <= 0L));
            if (serverConfirmed) {
                pendingSelectedKeyId = NO_PENDING_SELECTED_KEY;
                if (store != null) {
                    if (serverId > 0L) store.setSelectedKeyId(serverId);
                    else store.clearSelectedKeyId();
                }
            }
            return pendingSelectedKeyId == NO_PENDING_SELECTED_KEY
                    ? (serverId > 0L ? serverId : storedId)
                    : pendingSelectedKeyId;
        }

        if (serverId > 0L) {
            if (store != null && storedId != serverId) store.setSelectedKeyId(serverId);
            return serverId;
        }
        return storedId;
    }

    // Runtime accent
    private int accent;
    private int onAccent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        setContentView(R.layout.activity_profile);
        findViewById(R.id.profileOfficialWebsite).setOnClickListener(view -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://multiai.store")));
            } catch (android.content.ActivityNotFoundException unavailable) {
                ModernToast.show(this, getString(R.string.profile_website_unavailable));
            }
        });
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        chatManager = new ChatManager(this);
        oauth = new OAuthLoginController(this);
        store = oauth.getStore();

        initViews();
        applyAccent();
        setupDrawer();
        // В чат-режиме профиль открывает чат-мод сайдбар вместо ворк-дровера.
        boolean chatMode = new app.multiai.mobile.utils.AppSettings(this).isChatMode();
        if (chatMode) {
            chatModeSidebar = new app.multiai.mobile.ui.kit.ChatModeSidebar(this,
                    new app.multiai.mobile.ui.kit.ChatModeSidebar.Listener() {
                @Override public void onOpenChat(String chatId) {
                    startActivity(new Intent(ProfileActivity.this, ChatModeActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(app.multiai.mobile.utils.ChatActions.EXTRA_CHAT_ID, chatId));
                    finish();
                }
                @Override public void onNewChat() {
                    startActivity(new Intent(ProfileActivity.this, ChatModeActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    finish();
                }
                @Override public void onOpenMemories() {
                    startActivity(new Intent(ProfileActivity.this, SavedMemoriesActivity.class));
                }
                @Override public void onOpenSettings() {
                    startActivity(new Intent(ProfileActivity.this, ChatModeSettingsActivity.class));
                }
            });
        }
        loadProfile();

        // If we were launched from a fresh login redirect, process the callback now.
        Intent initial = getIntent();
        if (initial != null && initial.getData() != null) {
            onNewIntent(initial);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (app.multiai.mobile.utils.AppLockManager.get(this)
                .routeIfLocked(this, intent)) return;
        setIntent(intent);
        if (intent == null || intent.getData() == null) return;
        boolean handled = oauth.handleCallback(intent, new OAuthLoginController.Callback() {
            @Override public void onSuccess() {
                runOnUiThread(() -> {
                    ModernToast.show(ProfileActivity.this, getString(R.string.scr_welcome_name,
                            store.getName() != null ? store.getName() : getString(R.string.scr_user)));
                    loadProfile();
                });
            }
            @Override public void onError(String error, String detail) {
                String msg;
                if ("access_denied".equals(error)) {
                    msg = getString(R.string.scr_signin_cancelled);
                } else if ("insufficient_scope".equals(error)
                        && detail != null && !detail.trim().isEmpty()) {
                    msg = getString(R.string.oauth_missing_scopes, detail);
                } else {
                    msg = getString(R.string.profile_oauth_error, error);
                }
                runOnUiThread(() -> Toast.makeText(ProfileActivity.this, msg, Toast.LENGTH_LONG).show());
            }
        });
        if (handled) {
            // One-time use — clear the data off the intent so a re-launch doesn't reprocess.
            intent.setData(null);
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);

        // Header
        imgAvatar = findViewById(R.id.imgAvatar);
        textUserName = findViewById(R.id.textUserName);
        textUserEmail = findViewById(R.id.textUserEmail);
        textMemberSince = findViewById(R.id.textMemberSince);
        btnOverflow = findViewById(R.id.btnOverflow);

        // Tabs + sections
        profileTabs = findViewById(R.id.profileTabs);
        tabsScroller = findViewById(R.id.tabsScroller);
        tabViews[0] = findViewById(R.id.tabHome);
        tabViews[1] = findViewById(R.id.tabKeys);
        tabViews[2] = findViewById(R.id.tabUsage);
        tabViews[3] = findViewById(R.id.tabSubscription);
        tabViews[4] = findViewById(R.id.tabStore);
        sectionHome = findViewById(R.id.sectionHome);
        sectionKeys = findViewById(R.id.sectionKeys);
        sectionUsage = findViewById(R.id.sectionUsage);
        sectionSubscription = findViewById(R.id.sectionSubscription);

        // Home
        textTokensTotal = findViewById(R.id.textTokensTotal);
        textTokensSubscription = findViewById(R.id.textTokensSubscription);
        textTokensBilling = findViewById(R.id.textTokensBilling);

        // Subscription
        planInactive = findViewById(R.id.planInactive);
        planActive = findViewById(R.id.planActive);
        textPlanName = findViewById(R.id.textPlanName);
        textPlanExpires = findViewById(R.id.textPlanExpires);
        textPlanDaysLeft = findViewById(R.id.textPlanDaysLeft);
        planProgress = findViewById(R.id.planProgress);
        limitFiveHour = findViewById(R.id.limitFiveHour);
        limitWeekly = findViewById(R.id.limitWeekly);
        limitMonthly = findViewById(R.id.limitMonthly);
        textLimitFiveHour = findViewById(R.id.textLimitFiveHour);
        textLimitWeekly = findViewById(R.id.textLimitWeekly);
        textLimitMonthly = findViewById(R.id.textLimitMonthly);
        pctFiveHour = findViewById(R.id.pctFiveHour);
        pctWeekly = findViewById(R.id.pctWeekly);
        pctMonthly = findViewById(R.id.pctMonthly);
        subscriptionPlans = findViewById(R.id.subscriptionPlans);
        paymentHistoryList = findViewById(R.id.paymentHistoryList);

        View btnLimitDetails = findViewById(R.id.btnLimitDetails);
        if (btnLimitDetails != null) {
            btnLimitDetails.setOnClickListener(v -> selectTab(2));
        }

        // Keys
        keysList = findViewById(R.id.keysList);
        keysEmpty = findViewById(R.id.keysEmpty);

        // Usage
        usageChart = findViewById(R.id.usageChart);
        usageDonut = findViewById(R.id.usageDonut);
        usageDonut.setOnSegmentTapListener(index ->
                highlightSlice(usageHighlight == index ? -1 : index));
        usageLegend = findViewById(R.id.usageLegend);
        recentList = findViewById(R.id.recentList);
        textUsagePeriod = findViewById(R.id.textUsagePeriod);

        // Auth
        btnSignIn = findViewById(R.id.btnMultiAiSignIn);

        for (int i = 0; i < tabViews.length; i++) {
            final int idx = i;
            tabViews[i].setOnClickListener(v -> selectTab(idx));
        }

        // Иконки вкладок (слева от подписи, тонируются акцентом в restyleTabs).
        // «Магазин» — не вкладка-секция, а запуск отдельного экрана.
        int[] tabIcons = {R.drawable.ic_home, R.drawable.ic_key,
                R.drawable.ic_chart, R.drawable.ic_star, R.drawable.ic_cart};
        float density = getResources().getDisplayMetrics().density;
        int iconSize = (int) (14 * density);
        for (int i = 0; i < tabViews.length; i++) {
            Drawable d = ContextCompat.getDrawable(this, tabIcons[i]).mutate();
            d.setBounds(0, 0, iconSize, iconSize);
            tabViews[i].setCompoundDrawablePadding((int) (5 * density));
            tabViews[i].setCompoundDrawables(d, null, null, null);
        }

        btnOverflow.setOnClickListener(v -> showOverflowMenu(v));

        // Menu button
        ImageButton buttonMenu = findViewById(R.id.buttonMenu);
        buttonMenu.setOnClickListener(v -> {
            if (chatModeSidebar != null) {
                chatModeSidebar.toggle();
                return;
            }
            View drawerView = findViewById(R.id.drawerFrame);
            if (drawerLayout.isDrawerOpen(drawerView)) {
                drawerLayout.closeDrawer(drawerView);
            } else {
                drawerLayout.openDrawer(drawerView);
            }
        });

        // Свайп влево/вправо по контенту переключает вкладки
        // (вертикальный скролл не трогаем — жест только на явно горизонтальном движении).
        View profileScroll = findViewById(R.id.profileScroll);
        if (profileScroll != null) {
            profileScroll.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY;

                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    switch (event.getAction()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = event.getX();
                            downY = event.getY();
                            break;
                        case android.view.MotionEvent.ACTION_UP: {
                            float dx = event.getX() - downX;
                            float dy = event.getY() - downY;
                            float threshold = 110 * getResources().getDisplayMetrics().density;
                            if (Math.abs(dx) > threshold && Math.abs(dx) > 2.2f * Math.abs(dy)) {
                                if (dx < 0 && currentTab < 3) selectTab(currentTab + 1);
                                else if (dx > 0 && currentTab > 0) selectTab(currentTab - 1);
                            }
                            break;
                        }
                    }
                    return false; // не перехватываем — скролл работает как обычно
                }
            });
        }

        // Click handlers
        btnSignIn.setOnClickListener(v -> startLogin());

        View btnTopUp = findViewById(R.id.btnTopUp);
        if (btnTopUp != null) btnTopUp.setOnClickListener(v -> openStore());

        View btnCreateKey = findViewById(R.id.btnCreateKey);
        if (btnCreateKey != null) btnCreateKey.setOnClickListener(v -> createKeyLive());

        View btnClearSelect = findViewById(R.id.btnClearSelect);
        if (btnClearSelect != null) btnClearSelect.setOnClickListener(v -> clearSelectedKey());

        // Промо: POST /billing/promo (сервер жив с 17.08)
        View btnPromo = findViewById(R.id.btnActivatePromo);
        if (btnPromo != null) btnPromo.setOnClickListener(v -> {
            android.widget.EditText input = findViewById(R.id.promoInput);
            if (input != null && input.getText().toString().trim().isEmpty()) {
                ModernToast.show(this, getString(R.string.profile_promo_hint));
                return;
            }
            if (input != null) promoLive(input.getText().toString().trim());
        });

        View btnPeriod = findViewById(R.id.btnUsagePeriod);
        if (btnPeriod != null) btnPeriod.setOnClickListener(v -> showPeriodMenu(v));

        // «Все расходы» → шит AI-списаний; сам заголовок больше не кликабелен.
        View btnAllSpends = findViewById(R.id.btnAllSpends);
        if (btnAllSpends != null) btnAllSpends.setOnClickListener(v -> showRecentSpendsSheet());

        // «Вся история» → шит транзакций; заголовок больше не кликабелен.
        View btnAllPayments = findViewById(R.id.btnAllPayments);
        if (btnAllPayments != null) btnAllPayments.setOnClickListener(v -> showPaymentsSheet());

        renderUsageEmpty();
        renderSubscriptionPlans();
    }

    // ══════════════════ SUBSCRIPTION PLANS ══════════════════

    /**
     * Реальные планы придут с {@code GET /api/oauth/v1/billing/offers}
     * (ТЗ §5.1: title, price, period, recommended, tagline, features).
     * До сервера секция скрыта целиком.
     */
    private void renderSubscriptionPlans() {
        View title = findViewById(R.id.plansTitle);
        if (title != null) title.setVisibility(View.GONE);
        if (subscriptionPlans != null) {
            subscriptionPlans.removeAllViews();
            subscriptionPlans.setVisibility(View.GONE);
        }
    }

    /** Живые подписки из GET /billing/offers (ТЗ §5.1). Тап — чекаут без
     *  входа в браузере (POST /billing/checkout → одноразовая ссылка). */
    private void renderSubscriptionPlansLive(JSONArray subs) {
        if (subscriptionPlans == null || subs == null || subs.length() == 0) return;
        View title = findViewById(R.id.plansTitle);
        if (title != null) title.setVisibility(View.VISIBLE);
        subscriptionPlans.setVisibility(View.VISIBLE);
        subscriptionPlans.removeAllViews();
        for (int i = 0; i < subs.length(); i++) {
            JSONObject o = subs.optJSONObject(i);
            if (o == null) continue;
            View card = planCardLive(o);
            if (card != null) subscriptionPlans.addView(card);
        }
    }

    private View planCardLive(JSONObject o) {
        final String id = o.optString("id", "");
        String title = o.optString("title", id);
        boolean recommended = o.optBoolean("recommended", false);
        JSONObject price = o.optJSONObject("price");
        String priceStr = price != null ? price.optString("amount", "—") : "—";
        String tagline = o.optString("tagline", "");
        JSONArray features = o.optJSONArray("features");

        float dp = getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.profile_card_bg);
        card.setPadding((int) (18 * dp), (int) (18 * dp), (int) (18 * dp), (int) (18 * dp));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = (int) (12 * dp);
        card.setLayoutParams(cp);
        MotionKit.attachPress(card, 0.99f);

        // Заголовок + «Рекомендуем»
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        name.setTextSize(20f);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (recommended) {
            TextView badge = new TextView(this);
            badge.setText(R.string.store_recommended);
            badge.setTextSize(11f);
            badge.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            badge.setTextColor(accent);
            badge.setBackgroundResource(R.drawable.chat_mode_model_pill_bg);
            badge.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
            header.addView(badge);
        }
        card.addView(header);

        // Цена: сумма жирным + период серым меньшим кеглем («1 199 ₽ / месяц»).
        LinearLayout priceRow = new LinearLayout(this);
        priceRow.setOrientation(LinearLayout.HORIZONTAL);
        priceRow.setGravity(android.view.Gravity.BOTTOM);
        TextView priceNum = new TextView(this);
        priceNum.setText(fmtPrice(priceStr));
        priceNum.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        priceNum.setTextSize(22f);
        priceNum.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        priceRow.addView(priceNum);
        TextView pricePer = new TextView(this);
        pricePer.setText(" / " + prettyPeriod(o.optString("period", "")));
        pricePer.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        pricePer.setTextSize(13f);
        pricePer.setPadding(0, 0, 0, dp(3));
        priceRow.addView(pricePer);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = (int) (8 * dp);
        priceRow.setLayoutParams(plp);
        card.addView(priceRow);

        // Слоган
        if (!tagline.isEmpty()) {
            TextView tg = new TextView(this);
            tg.setText(tagline);
            tg.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            tg.setTextSize(13f);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = (int) (6 * dp);
            tg.setLayoutParams(tlp);
            card.addView(tg);
        }

        // Возможности
        if (features != null && features.length() > 0) {
            for (int i = 0; i < features.length(); i++) {
                String f = features.optString(i, "");
                if (f.isEmpty()) continue;
                View row = android.view.LayoutInflater.from(this)
                        .inflate(R.layout.item_store_feature, card, false);
                ((TextView) row.findViewById(R.id.featureText)).setText(f);
                ((android.widget.ImageView) row.findViewById(R.id.featureCheck)).setColorFilter(accent);
                card.addView(row);
            }
        }

        // Купить → чекаут без входа в браузере
        com.google.android.material.button.MaterialButton buy =
                new com.google.android.material.button.MaterialButton(this, null,
                        com.google.android.material.R.attr.materialButtonStyle);
        buy.setText(R.string.profile_buy);
        buy.setTextColor(onAccent);
        buy.setTextSize(14f);
        buy.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        buy.setBackgroundTintList(ColorStateList.valueOf(accent));
        buy.setCornerRadius((int) (12 * dp));
        buy.setOnClickListener(v -> ModernDialog.show(this, title,
                getString(R.string.profile_checkout_confirm, priceStr),
                getString(R.string.scr_cancel),
                getString(R.string.profile_buy),
                false,
                () -> checkoutPlanLive(id)));
        MotionKit.attachPress(buy, 0.97f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (46 * dp));
        blp.topMargin = (int) (14 * dp);
        card.addView(buy, blp);

        return card;
    }

    private void checkoutPlanLive(String planId) {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject co = oauth.getAccountApi().billingCheckout(null, planId);
                String url = co.optString("checkout_url", "");
                ui.post(() -> {
                    if (url.isEmpty()) {
                        ModernToast.show(this, getString(R.string.profile_checkout_fail));
                        return;
                    }
                    try {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } catch (Exception e) {
                        ModernToast.show(this, getString(R.string.profile_checkout_fail));
                    }
                });
            } catch (Exception e) {
                ui.post(() -> ModernToast.show(this,
                        getString(R.string.profile_checkout_fail) + ": " + briefError(e)));
            }
        }).start();
    }

    /** Меню периода динамики: За день / За неделю / За месяц. */
    private void showPeriodMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 1, getString(R.string.profile_period_day));
        menu.getMenu().add(0, 2, 2, getString(R.string.profile_usage_period_week));
        menu.getMenu().add(0, 3, 3, getString(R.string.profile_usage_period_month));
        menu.setOnMenuItemClickListener(item -> {
            usagePeriodMode = item.getItemId() == 1 ? 0 : item.getItemId() == 2 ? 1 : 2;
            textUsagePeriod.setText(getString(item.getItemId() == 1
                    ? R.string.profile_period_day
                    : item.getItemId() == 2
                            ? R.string.profile_usage_period_week
                            : R.string.profile_usage_period_month));
            loadUsageLive();
            return true;
        });
        menu.show();
    }

    /** Перезагрузка /usage: период определяет серверный days (1/7/30) для
     *  графика/кольца/недавних; обзор (день/нед/мес) всегда пересчитывается
     *  из полного 60-д кэша — периодами не искажается. */
    private void loadUsageLive() {
        int days = usagePeriodMode == 0 ? 2 : usagePeriodMode == 1 ? 7 : 60;
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject usage = oauth.getAccountApi().usage(days, null);
                if (usage != null && usage.has("by_model")) {
                    if (days == 60) usageCache = usage;
                    ui.post(() -> renderUsage(usage));
                    return;
                }
                ui.post(this::renderUsageEmpty);
            } catch (Exception e) {
                ui.post(this::renderUsageEmpty);
            }
        }).start();
    }

    /** История для полного списка загружается из /usage/history. Пока все страницы
     *  догружаются (или если history временно недоступен), показываем recent из /usage. */
    private static final int USAGE_HISTORY_PAGE_SIZE = 200;
    private static final int USAGE_HISTORY_MAX_REQUESTS = 500;
    private org.json.JSONArray allUsageHistory;

    private org.json.JSONArray recentForSheet() {
        return allUsageHistory;
    }

    private void loadAllUsageHistory() {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            // /usage уже содержит последние операции. Не держим экран пустым, пока
            // history последовательно загружает все страницы (их может быть много).
            JSONArray fallback = usageCache != null ? usageCache.optJSONArray("recent") : null;
            if (fallback != null) {
                allUsageHistory = fallback;
                ui.post(() -> {
                    if (usageCache != null) renderUsage(usageCache);
                });
            }

            JSONArray all = new JSONArray();
            java.util.Set<String> seenItems = new java.util.HashSet<>();
            java.util.Set<String> seenRequests = new java.util.HashSet<>();
            String cursor = null;
            Integer pageNumber = null;
            Integer offset = null;
            String mode = "initial";
            boolean pageProbeTried = false;
            boolean offsetProbeTried = false;
            int firstBatchSize = 0;
            int expectedTotal = usageHistoryExpectedTotal();

            try {
                for (int requestCount = 0;
                     requestCount < USAGE_HISTORY_MAX_REQUESTS;
                     requestCount++) {
                    String requestKey = mode + ":"
                            + (cursor != null ? cursor
                            : pageNumber != null ? pageNumber
                            : offset != null ? offset : "first");
                    if (!seenRequests.add(requestKey)) {
                        // Если сервер проигнорировал page, один раз пробуем offset.
                        if ("page".equals(mode) && !offsetProbeTried && firstBatchSize > 0) {
                            mode = "offset";
                            cursor = null;
                            pageNumber = null;
                            offset = firstBatchSize;
                            offsetProbeTried = true;
                            continue;
                        }
                        break;
                    }

                    JSONObject response;
                    try {
                        response = oauth.getAccountApi().usageHistory(
                                USAGE_HISTORY_PAGE_SIZE, cursor, pageNumber, offset);
                    } catch (Exception requestError) {
                        // Некоторые реализации отклоняют неизвестный page-параметр,
                        // но принимают offset. Fallback не должен обрывать всю загрузку.
                        if ("page".equals(mode) && !offsetProbeTried && firstBatchSize > 0) {
                            mode = "offset";
                            cursor = null;
                            pageNumber = null;
                            offset = firstBatchSize;
                            offsetProbeTried = true;
                            continue;
                        }
                        throw requestError;
                    }

                    JSONArray items = findUsageHistoryItems(response);
                    if (items == null) {
                        throw new IllegalStateException(
                                "usage/history response has no supported items array");
                    }
                    if (firstBatchSize == 0) firstBatchSize = items.length();

                    int added = 0;
                    for (int i = 0; i < items.length(); i++) {
                        Object item = items.opt(i);
                        if (item == null || item == JSONObject.NULL) continue;
                        if (seenItems.add(usageHistoryItemKey(item))) {
                            all.put(item);
                            added++;
                        }
                    }

                    if (items.length() == 0) break;
                    if (added == 0 && !"initial".equals(mode)) {
                        if ("page".equals(mode) && !offsetProbeTried && firstBatchSize > 0) {
                            mode = "offset";
                            cursor = null;
                            pageNumber = null;
                            offset = firstBatchSize;
                            offsetProbeTried = true;
                            continue;
                        }
                        break;
                    }

                    Boolean hasMore = firstUsageHistoryBoolean(response,
                            "has_more", "hasMore", "more");
                    String nextCursor = firstUsageHistoryString(response,
                            "next_cursor", "nextCursor", "continuation_token",
                            "continuationToken", "next_token", "nextToken");
                    Integer nextPage = firstUsageHistoryInt(response,
                            "next_page", "nextPage");
                    Integer currentPage = firstUsageHistoryInt(response,
                            "page", "current_page", "currentPage");
                    Integer totalPages = firstUsageHistoryInt(response,
                            "total_pages", "totalPages", "pages", "page_count", "pageCount");
                    Integer nextOffset = firstUsageHistoryInt(response,
                            "next_offset", "nextOffset");
                    Integer currentOffset = firstUsageHistoryInt(response,
                            "offset", "current_offset", "currentOffset");
                    Integer serverTotal = firstUsageHistoryInt(response,
                            "total", "total_count", "totalCount");

                    if (nextCursor != null && !nextCursor.isEmpty()) {
                        mode = "cursor";
                        cursor = nextCursor;
                        pageNumber = null;
                        offset = null;
                        continue;
                    }

                    if (nextPage == null && currentPage != null
                            && ((totalPages != null && currentPage < totalPages)
                            || Boolean.TRUE.equals(hasMore))) {
                        nextPage = currentPage + 1;
                    }
                    if (nextPage != null && nextPage >= 0) {
                        mode = "page";
                        cursor = null;
                        pageNumber = nextPage;
                        offset = null;
                        pageProbeTried = true;
                        continue;
                    }

                    if (nextOffset == null && currentOffset != null
                            && (Boolean.TRUE.equals(hasMore)
                            || (serverTotal != null
                            && currentOffset + items.length() < serverTotal))) {
                        nextOffset = currentOffset + items.length();
                    }
                    if (nextOffset != null && nextOffset >= 0) {
                        mode = "offset";
                        cursor = null;
                        pageNumber = null;
                        offset = nextOffset;
                        offsetProbeTried = true;
                        continue;
                    }

                    // Явное has_more=false важнее приблизительного total из /usage.
                    if (Boolean.FALSE.equals(hasMore)) break;
                    boolean likelyMore = Boolean.TRUE.equals(hasMore)
                            || (serverTotal != null && serverTotal > all.length())
                            || expectedTotal > all.length()
                            || items.length() >= USAGE_HISTORY_PAGE_SIZE;
                    if (!likelyMore) break;

                    // У старых серверов continuation metadata отсутствует. Сначала
                    // пробуем page=2, затем offset=<размер первой страницы>. Дедуп и
                    // seenRequests гарантируют, что игнорируемый параметр не зациклит нас.
                    if ("page".equals(mode)) {
                        pageNumber = pageNumber == null ? 2 : pageNumber + 1;
                        continue;
                    }
                    if ("offset".equals(mode)) {
                        offset = (offset == null ? 0 : offset) + items.length();
                        continue;
                    }
                    if (!pageProbeTried) {
                        mode = "page";
                        cursor = null;
                        pageNumber = 2;
                        offset = null;
                        pageProbeTried = true;
                    } else if (!offsetProbeTried && firstBatchSize > 0) {
                        mode = "offset";
                        cursor = null;
                        pageNumber = null;
                        offset = firstBatchSize;
                        offsetProbeTried = true;
                    } else {
                        break;
                    }
                }

                // Пустой успешный history не должен стирать непустой recent fallback.
                if (all.length() > 0) allUsageHistory = all;
                ui.post(() -> {
                    if (usageCache != null) renderUsage(usageCache);
                });
            } catch (Exception ignored) {
                android.util.Log.e("UsageHistory", "history_load_failed_using_recent");
                // Fallback уже показан; повторный рендер нужен на случай гонки первого UI-post.
                ui.post(() -> {
                    if (usageCache != null) renderUsage(usageCache);
                });
            }
        }).start();
    }

    private int usageHistoryExpectedTotal() {
        JSONObject total = usageCache != null ? usageCache.optJSONObject("total") : null;
        return total != null ? total.optInt("requests", -1) : -1;
    }

    /** Ищет items как на верхнем уровне, так и внутри data/result/payload. */
    private static JSONArray findUsageHistoryItems(JSONObject response) {
        return findUsageHistoryItems(response, 0);
    }

    private static JSONArray findUsageHistoryItems(JSONObject object, int depth) {
        if (object == null || depth > 3) return null;
        String[] arrayKeys = {"items", "requests", "history", "data", "results"};
        for (String key : arrayKeys) {
            JSONArray items = object.optJSONArray(key);
            if (items != null) return items;
        }
        String[] containerKeys = {"data", "result", "payload", "history"};
        for (String key : containerKeys) {
            JSONObject nested = object.optJSONObject(key);
            JSONArray items = findUsageHistoryItems(nested, depth + 1);
            if (items != null) return items;
        }
        return null;
    }

    private static java.util.List<JSONObject> usageHistoryScopes(JSONObject response) {
        java.util.List<JSONObject> scopes = new java.util.ArrayList<>();
        if (response == null) return scopes;
        scopes.add(response);
        addUsageHistoryScope(scopes, response.optJSONObject("pagination"));
        addUsageHistoryScope(scopes, response.optJSONObject("meta"));
        String[] containers = {"data", "result", "payload"};
        for (String key : containers) {
            JSONObject nested = response.optJSONObject(key);
            if (nested == null) continue;
            scopes.add(nested);
            addUsageHistoryScope(scopes, nested.optJSONObject("pagination"));
            addUsageHistoryScope(scopes, nested.optJSONObject("meta"));
        }
        return scopes;
    }

    private static void addUsageHistoryScope(java.util.List<JSONObject> scopes,
                                             JSONObject scope) {
        if (scope != null) scopes.add(scope);
    }

    private static String firstUsageHistoryString(JSONObject response, String... keys) {
        for (JSONObject scope : usageHistoryScopes(response)) {
            for (String key : keys) {
                if (!scope.has(key) || scope.isNull(key)) continue;
                Object value = scope.opt(key);
                if (value instanceof String || value instanceof Number) {
                    String text = String.valueOf(value);
                    if (!text.isEmpty()) return text;
                }
            }
        }
        return null;
    }

    private static Integer firstUsageHistoryInt(JSONObject response, String... keys) {
        for (JSONObject scope : usageHistoryScopes(response)) {
            for (String key : keys) {
                if (!scope.has(key) || scope.isNull(key)) continue;
                Object value = scope.opt(key);
                if (value instanceof Number) return ((Number) value).intValue();
                if (value instanceof String) {
                    try {
                        return Integer.parseInt((String) value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static Boolean firstUsageHistoryBoolean(JSONObject response, String... keys) {
        for (JSONObject scope : usageHistoryScopes(response)) {
            for (String key : keys) {
                if (!scope.has(key) || scope.isNull(key)) continue;
                Object value = scope.opt(key);
                if (value instanceof Boolean) return (Boolean) value;
                if (value instanceof Number) return ((Number) value).intValue() != 0;
                if (value instanceof String) {
                    String text = ((String) value).trim();
                    if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
                    if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
                }
            }
        }
        return null;
    }

    private static String usageHistoryItemKey(Object item) {
        if (item instanceof JSONObject) {
            JSONObject object = (JSONObject) item;
            String[] idKeys = {"id", "request_id", "requestId", "usage_id", "usageId"};
            for (String key : idKeys) {
                if (!object.has(key) || object.isNull(key)) continue;
                String value = String.valueOf(object.opt(key));
                if (!value.isEmpty()) return key + ":" + value;
            }
        }
        return String.valueOf(item);
    }

    /** Локальный срез usageCache под выбранный период: график, кольцо и
     *  недавние показывают ТОЛЬКО данные периода (by_day по датам, by_model
     *  по last_used_at, recent по created_at). */
    private org.json.JSONObject sliceUsageByPeriod() {
        if (usageCache == null) return null;
        try {
            org.json.JSONObject sliced = new org.json.JSONObject(usageCache.toString());
            int days = usagePeriodMode == 0 ? 1 : usagePeriodMode == 1 ? 7 : 30;
            long cutoff = System.currentTimeMillis() - days * 86_400_000L;
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.text.SimpleDateFormat dfFull = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", Locale.US);

            JSONArray byDay = sliced.optJSONArray("by_day");
            if (byDay != null) {
                JSONArray keep = new JSONArray();
                for (int i = 0; i < byDay.length(); i++) {
                    JSONObject d = byDay.optJSONObject(i);
                    if (d == null) continue;
                    try {
                        if (df.parse(d.optString("date", "")).getTime() >= cutoff) {
                            keep.put(d);
                        }
                    } catch (Exception ignored) {
                    }
                }
                sliced.put("by_day", keep);
            }
            // by_model: последняя работа модели должна быть в периоде.
            JSONArray byModel = sliced.optJSONArray("by_model");
            if (byModel != null) {
                JSONArray keepM = new JSONArray();
                for (int i = 0; i < byModel.length(); i++) {
                    JSONObject m = byModel.optJSONObject(i);
                    if (m == null) continue;
                    String last = m.optString("last_used_at", "");
                    try {
                        if (last.length() >= 19
                                && dfFull.parse(last.substring(0, 19)).getTime() >= cutoff) {
                            keepM.put(m);
                        }
                    } catch (Exception ignored) {
                    }
                }
                sliced.put("by_model", keepM);
            }
            // recent: только списания внутри периода.
            JSONArray recent = sliced.optJSONArray("recent");
            if (recent != null) {
                JSONArray keepR = new JSONArray();
                for (int i = 0; i < recent.length(); i++) {
                    JSONObject r = recent.optJSONObject(i);
                    if (r == null) continue;
                    String cr = r.optString("created_at", "");
                    try {
                        if (cr.length() >= 19
                                && dfFull.parse(cr.substring(0, 19)).getTime() >= cutoff) {
                            keepR.put(r);
                        }
                    } catch (Exception ignored) {
                    }
                }
                sliced.put("recent", keepR);
            }
            return sliced;
        } catch (Exception e) {
            return usageCache;
        }
    }

    /** Красит интерактивные элементы акцентом из настроек пользователя. */
    private void applyAccent() {
        accent = AccentColor.resolve(this);
        onAccent = AccentColor.contrastForeground(this);
        ColorStateList accentList = ColorStateList.valueOf(accent);
        ColorStateList onAccentList = ColorStateList.valueOf(onAccent);
        float dp = getResources().getDisplayMetrics().density;

        tintFilled(findViewById(R.id.btnMultiAiSignIn));
        tintFilled(findViewById(R.id.btnTopUp));
        tintFilled(findViewById(R.id.btnCreateKey));
        tintFilled(findViewById(R.id.btnActivatePromo));

        MaterialButton clear = findViewById(R.id.btnClearSelect);
        if (clear != null) {
            clear.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.surface_card)));
            clear.setStrokeColor(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.stroke)));
            clear.setStrokeWidth((int) dp);
            clear.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            clear.setElevation(0f);
            clear.setStateListAnimator(null);
        }

        TextView details = findViewById(R.id.btnLimitDetails);
        if (details != null) details.setTextColor(accentList);

        // Карточка баланса с акцентной лентой сверху — фон рисуется целиком
        // одним дравблом (карточка + лента одной геометрией), швов не бывает.
        View balanceCard = findViewById(R.id.balanceCard);
        if (balanceCard != null) {
            balanceCard.setBackground(new TopAccentCardDrawable(
                    accent,
                    ContextCompat.getColor(this, R.color.surface_card),
                    ContextCompat.getColor(this, R.color.stroke),
                    getResources().getDisplayMetrics().density));
        }
        for (ProgressBar bar : new ProgressBar[]{planProgress,
                limitFiveHour, limitWeekly, limitMonthly}) {
            if (bar != null) {
                bar.setProgressTintList(accentList);
                bar.setProgressBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.surface_tonal)));
            }
        }

        GradientDrawable planBadge = new GradientDrawable();
        planBadge.setCornerRadius(100 * getResources().getDisplayMetrics().density);
        planBadge.setColor(ContextCompat.getColor(this, R.color.success_bg));
        TextView badge = findViewById(R.id.badgePlanActive);
        if (badge != null) badge.setBackground(planBadge);

        usageChart.setAccent(accent);

        restyleTabs();
    }

    private void tintFilled(View v) {
        if (!(v instanceof MaterialButton)) return;
        MaterialButton b = (MaterialButton) v;
        b.setBackgroundTintList(ColorStateList.valueOf(accent));
        b.setTextColor(ColorStateList.valueOf(onAccent));
        b.setIconTint(ColorStateList.valueOf(onAccent));
    }

    private void restyleTabs() {
        float dp = getResources().getDisplayMetrics().density;
        int secondary = ContextCompat.getColor(this, R.color.text_secondary);
        for (int i = 0; i < tabViews.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(20 * dp);
            if (i == currentTab) {
                bg.setColor(accent);
                tabViews[i].setTextColor(onAccent);
            } else {
                bg.setColor(Color.TRANSPARENT);
                tabViews[i].setTextColor(secondary);
            }
            tabViews[i].setBackground(bg);
            Drawable icon = tabViews[i].getCompoundDrawables()[0];
            if (icon != null) icon.setTint(i == currentTab ? onAccent : secondary);
        }
    }

    private void selectTab(int idx) {
        if (idx == 4) {
            openStore();
            return;
        }
        if (idx == currentTab) return;
        currentTab = idx;
        restyleTabs();
        showTab(idx);
        centerSelectedTab();
    }

    /** Прокручивает ленту вкладок так, чтобы выбранная оказалась по центру. */
    private void centerSelectedTab() {
        TextView tab = tabViews[currentTab];
        if (tab == null || tabsScroller == null) return;
        tab.post(() -> tabsScroller.smoothScrollTo(
                Math.max(0, (tab.getLeft() + tab.getWidth() / 2) - tabsScroller.getWidth() / 2), 0));
    }

    private void showTab(int position) {
        LinearLayout[] sections = {sectionHome, sectionKeys, sectionUsage, sectionSubscription};
        for (int i = 0; i < sections.length; i++) {
            LinearLayout sec = sections[i];
            boolean show = i == position;
            sec.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) MotionKit.fadeIn(sec, 14f);
        }
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 1, getString(R.string.lay_sign_out));
        menu.setOnMenuItemClickListener(item -> {
            confirmSignOut();
            return true;
        });
        menu.show();
    }

    private void setupDrawer() {
        chatsAdapter = new ChatsAdapter();
        if (recyclerChats != null) {
            recyclerChats.setLayoutManager(new LinearLayoutManager(this));
            recyclerChats.setAdapter(chatsAdapter);
        }

        List<Chat> chats = chatManager.loadChats();
        chatsAdapter.setChats(chats);
        chatsAdapter.setActiveChatId(chatManager.loadActiveChatId());

        chatsAdapter.setOnChatClickListener(new ChatsAdapter.OnChatClickListener() {
            @Override
            public void onChatClick(Chat chat) {
                chatManager.saveActiveChatId(chat.getId());
                startActivity(new Intent(ProfileActivity.this, ChatActivity.class));
                finish();
            }

            @Override
            public void onDeleteChat(Chat chat) {
                chatManager.deleteChat(chat.getId());
                List<Chat> updated = chatManager.loadChats();
                chatsAdapter.setChats(updated);
                chatsAdapter.setActiveChatId(chatManager.loadActiveChatId());
            }

            @Override
            public void onRenameChat(Chat chat) { /* handled in ChatActivity */ }
        });

        DrawerNavHelper.setup(this, DrawerNavHelper.NavTarget.PROFILE,
                target -> {
                    switch (target) {
                        case CHAT:
                            // В чат-режиме «Чат» в дровере — это ChatModeActivity,
                            // иначе юзер из чат-режимного профиля попадал в ворк-чат.
                            if (new app.multiai.mobile.utils.AppSettings(this).isChatMode()) {
                                startActivity(new Intent(this, ChatModeActivity.class));
                            } else {
                                startActivity(new Intent(this, ChatActivity.class));
                            }
                            finish();
                            return true;
                        case TERMINAL:
                            startActivity(new Intent(this, TerminalActivity.class));
                            finish();
                            return true;
                        case FILES:
                            startActivity(new Intent(this, FilesActivity.class));
                            finish();
                            return true;
                        case EDITOR:
                            startActivity(new Intent(this, EditorActivity.class));
                            finish();
                            return true;
                        case PROFILE:
                        default:
                            return true;
                    }
                },
                v -> {
                    startActivity(new Intent(this, ChatModeSettingsActivity.class));
                    finish();
                },
                null);
    }

    // ══════════════════ AUTH (MultiAI OAuth) ══════════════════

    private void startLogin() {
        oauth.startLogin();
    }

    private void confirmSignOut() {
        ModernDialog.show(this, getString(R.string.scr_profile_signout_title),
                getString(R.string.scr_profile_signout_msg),
                getString(R.string.scr_cancel), getString(R.string.scr_exit), false, () -> signOut());
    }

    private void signOut() {
        // Revoke first, then report whether the fail-closed local wipe was durable.
        oauth.logout(wiped -> runOnUiThread(() -> {
            loadProfile();
            ModernToast.show(this, getString(wiped
                    ? R.string.scr_profile_signed_out
                    : R.string.scr_profile_signout_failed));
        }));
    }

    // ══════════════════ UI (profile restore from OAuth store) ══════════════════

    private void loadProfile() {
        // Обязательное обновление: профиль не показываем/не обновляем в блокированном состоянии.
        if (app.multiai.mobile.utils.UpdateManager.isUnsupportedBlocked(this)) {
            app.multiai.mobile.ui.kit.ModernToast.show(this,
                    app.multiai.mobile.utils.UpdateManager.unsupportedMessage(this));
            return;
        }
        updateUI(store != null && store.hasRefreshToken());
    }

    private void updateUI(boolean signedIn) {
        if (signedIn && store != null) {
            // ── Signed in ──
            String name = store.getName();
            textUserName.setText(name != null && !name.isEmpty()
                    ? name : getString(R.string.scr_user));

            // Telegram-эвристика: если email пустой, а preferred_username начинается с '@',
            // показываем @username с иконкой Telegram; иначе — email.
            boolean isTelegram = store.isTelegram();
            String email = store.getEmail();
            String username = store.getPreferredUsername();

            if (isTelegram) {
                textUserEmail.setText(username != null ? username : "");
                textUserEmail.setVisibility(View.VISIBLE);
                textUserEmail.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_telegram, 0, 0, 0);
                int pad = (int) (4 * getResources().getDisplayMetrics().density);
                textUserEmail.setCompoundDrawablePadding(pad);
            } else if (email != null && !email.isEmpty()) {
                textUserEmail.setText(email);
                textUserEmail.setVisibility(View.VISIBLE);
                textUserEmail.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            } else {
                textUserEmail.setVisibility(View.GONE);
            }

            String pic = store.getPicture();
            if (pic != null && !pic.isEmpty()) {
                loadAvatar(pic);
                imgAvatar.setImageTintList(null);
                imgAvatar.setPadding(0, 0, 0, 0);
            }

            long authTime = store.getAuthTime();
            if (authTime > 0) {
                String date = new SimpleDateFormat("dd MMM yyyy", new Locale("ru"))
                        .format(new Date(authTime));
                textMemberSince.setText(getString(R.string.scr_profile_member_since, date));
                textMemberSince.setVisibility(View.VISIBLE);
            }

            btnSignIn.setVisibility(View.GONE);
            btnOverflow.setVisibility(View.VISIBLE);
            tabsScroller.setVisibility(View.VISIBLE);
            showTab(currentTab);
            restyleTabs();

            // Приветствие по времени суток.
            TextView greeting = findViewById(R.id.textGreeting);
            if (greeting != null) {
                int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                int res = hour < 6 ? R.string.profile_greeting_night
                        : hour < 12 ? R.string.profile_greeting_morning
                        : hour < 18 ? R.string.profile_greeting_day
                        : R.string.profile_greeting_evening;
                String nm = store.getName();
                greeting.setText(getString(res,
                        nm == null || nm.isEmpty() ? getString(R.string.scr_user) : nm));
            }

            // Async-load snapshot + keys (AccountApi handles refresh + retry, §10).
            loadAccount();
        } else {
            // ── Guest ──
            textUserName.setText(getString(R.string.scr_profile_not_authorized));
            textUserEmail.setVisibility(View.GONE);
            textMemberSince.setVisibility(View.GONE);
            imgAvatar.setImageResource(R.drawable.ic_account);
            imgAvatar.setImageTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.icon)));
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            imgAvatar.setPadding(pad, pad, pad, pad);

            btnSignIn.setVisibility(View.VISIBLE);
            btnOverflow.setVisibility(View.GONE);
            tabsScroller.setVisibility(View.GONE);
            sectionHome.setVisibility(View.GONE);
            sectionKeys.setVisibility(View.GONE);
            sectionUsage.setVisibility(View.GONE);
            sectionSubscription.setVisibility(View.GONE);
        }
    }

    // ══════════════════ ACCOUNT SNAPSHOT / KEYS (§8) ══════════════════

    private boolean isCurrentAccountLoad(int generation) {
        return generation == accountLoadGeneration.get() && !isFinishing() && !isDestroyed();
    }

    private void postAccountLoadUpdate(android.os.Handler ui, int generation, Runnable update) {
        ui.post(() -> {
            if (isCurrentAccountLoad(generation)) {
                update.run();
            }
        });
    }

    /** Background fetch of /snapshot + /keys, then update the profile sections. */
    private void loadAccount() {
        final int generation = accountLoadGeneration.incrementAndGet();
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                oauth.ensureMetadataAsync(() -> {
                    new Thread(() -> {
                        if (!isCurrentAccountLoad(generation)) return;
                        try {
                            JSONObject snap = oauth.getAccountApi().snapshot();
                            // Snapshot также содержит keys; предпочитаем единый снимок.
                            postAccountLoadUpdate(ui, generation, () -> renderSnapshot(snap));
                            JSONArray keysArr = snap.optJSONArray("keys");
                            if (keysArr == null) keysArr = oauth.getAccountApi().keys();
                            final JSONArray keys = keysArr;
                            postAccountLoadUpdate(ui, generation, () -> renderKeys(keys, snap));
                            // Офферы и история — параллельно, не блокируют профиль.
                            try {
                                JSONObject offers = oauth.getAccountApi().billingOffers();
                                postAccountLoadUpdate(ui, generation, () -> renderOffers(offers));
                            } catch (Exception ignored) {
                            }
                            try {
                                JSONObject history = oauth.getAccountApi().billingHistory(10, null);
                                postAccountLoadUpdate(ui, generation, () -> renderHistory(history));
                            } catch (Exception ignored) {
                            }
                            // Usage: 60 дней = текущий месяц + предыдущий для дельт.
                            try {
                                JSONObject usage = oauth.getAccountApi().usage(60, null);
                                if (usage != null && usage.has("by_model")
                                        && generation == accountLoadGeneration.get()) {
                                    usageCache = usage; // шит «Все расходы» + обзор
                                    loadAllUsageHistory();
                                    postAccountLoadUpdate(ui, generation, () -> renderUsage(usage));
                                }
                            } catch (Exception ignored) {
                            }
                        } catch (Exception e) {
                            postAccountLoadUpdate(ui, generation, () ->
                                    ModernToast.show(this, getString(R.string.profile_load_error)));
                        }
                    }, "profile-account-load").start();
                }, err -> postAccountLoadUpdate(ui, generation, () ->
                        ModernToast.show(this, getString(R.string.profile_network_error))));
            } catch (Exception ignored) {
            }
        }, "profile-metadata-load").start();
    }

    // ══════════════════ LIVE BILLING ACTIONS ══════════════════

    /** Создание ключа через POST /keys: полный ключ показываем один раз. */
    private void createKeyLive() {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject created = oauth.getAccountApi().createKey("MultiAI Mobile");
                String fullKey = created.optString("key", "");
                ui.post(() -> {
                    if (fullKey.isEmpty()) {
                        ModernToast.show(this, getString(R.string.profile_key_create_fail));
                        return;
                    }
                    showFullKeyOnce(created.optString("name", "key"), fullKey);
                    loadAccount(); // обновить список
                });
            } catch (Exception e) {
                ui.post(() -> ModernToast.show(this,
                        getString(R.string.profile_key_create_fail) + ": " + briefError(e)));
            }
        }).start();
    }

    /** Полный ключ даём скопировать ровно один раз (дальше сервер отдаёт маску). */
    private void showFullKeyOnce(String name, String fullKey) {
        ModernDialog.show(this,
                getString(R.string.profile_key_created_title, name),
                fullKey,
                getString(R.string.action_close),
                getString(R.string.action_copy),
                false,
                () -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("key", fullKey));
                        ModernToast.show(this, getString(R.string.copied_ok));
                    }
                });
    }

    /** Чекаут покупки токенов: одноразовая ссылка без входа в браузере. */
    private void checkoutTokensLive(long tokensAmount) {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject co = oauth.getAccountApi().billingCheckout(tokensAmount, null);
                String url = co.optString("checkout_url", "");
                ui.post(() -> {
                    if (url.isEmpty()) {
                        ModernToast.show(this, getString(R.string.profile_checkout_fail));
                        return;
                    }
                    try {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } catch (Exception e) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                            ModernToast.show(this, getString(R.string.profile_checkout_copied));
                        }
                    }
                });
            } catch (Exception e) {
                ui.post(() -> ModernToast.show(this,
                        getString(R.string.profile_checkout_fail) + ": " + briefError(e)));
            }
        }).start();
    }

    /** Активация промо через POST /billing/promo. */
    private void promoLive(String code) {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject res = oauth.getAccountApi().billingPromo(code);
                ui.post(() -> {
                    if (res.optBoolean("ok", false)) {
                        long credited = res.optLong("tokens_credited", res.optLong("amount", 0));
                        String type = res.optString("reward_type",
                                res.optString("promo_type", res.optString("type", ""))).toLowerCase(java.util.Locale.ROOT);
                        String reward;
                        if ("tokens".equals(type) && credited > 0 && credited <= 10_000_000_000L) {
                            reward = getString(R.string.profile_promo_ok,
                                    String.format(java.util.Locale.US, "%,d", credited).replace(',', ' '));
                        } else if ("subscription".equals(type)) {
                            String plan = res.optString("plan_name", res.optString("plan", ""));
                            long days = res.optLong("days", res.optLong("duration_days", 0));
                            if (plan.trim().isEmpty() || days < 1 || days > 30) {
                                showPromoError(new Exception(getString(R.string.promo_reward_invalid)));
                                loadAccount();
                                return;
                            }
                            reward = getString(R.string.promo_reward_subscription, plan, days);
                        } else if ("discount".equals(type)) {
                            long percent = res.optLong("discount_percent", res.optLong("percent", 0));
                            if (percent < 1 || percent > 90) {
                                showPromoError(new Exception(getString(R.string.promo_reward_invalid)));
                                loadAccount();
                                return;
                            }
                            reward = getString(R.string.promo_reward_discount, percent);
                        } else {
                            showPromoError(new Exception(getString("tokens".equals(type)
                                    ? R.string.promo_reward_invalid : R.string.promo_reward_unsupported)));
                            loadAccount();
                            return;
                        }
                        ModernToast.showSuccess(ProfileActivity.this,
                                getString(R.string.profile_promo_success_title),
                                reward,
                                getString(R.string.profile_promo_awesome));
                        loadAccount();
                    } else {
                        showPromoError(new Exception(res.optString("error_description",
                                res.optString("message", res.optString("error", "unknown_error")))));
                    }
                });
            } catch (Exception e) {
                ui.post(() -> showPromoError(e));
            }
        }).start();
    }

    private void showPromoError(Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) detail = error.getClass().getSimpleName();
        detail = detail.replaceAll("(?i)Bearer\\s+[^\\s]+", "Bearer [redacted]")
                .replaceAll("https?://[^\\s]+", "[URL]");
        if (detail.length() > 2000) detail = detail.substring(0, 2000) + "…";
        if (error instanceof app.multiai.mobile.network.oauth.OAuthClient.OAuthException) {
            int status = ((app.multiai.mobile.network.oauth.OAuthClient.OAuthException) error).http;
            if (status > 0) detail = "HTTP " + status + "\n" + detail;
        }
        int reason = R.string.promo_error_unknown;
        String code = error.getMessage() == null ? "" : error.getMessage();
        if (error instanceof java.io.IOException) reason = R.string.promo_error_network;
        else if (code.contains("promo_already_used")) reason = R.string.profile_err_promo_used;
        else if (code.contains("invalid_promo_code")) reason = R.string.promo_error_invalid;
        else if (code.contains("promo_expired") || code.contains("promo_code_expired")) reason = R.string.promo_error_expired;
        else if (code.contains("promo_limit_reached") || code.contains("promo_exhausted")) reason = R.string.promo_error_limit;
        else if (code.contains("insufficient_scope")) reason = R.string.profile_err_scope;
        ModernDialog.show(this, getString(R.string.profile_promo_fail), getString(reason) + "\n\n" + detail,
                getString(R.string.chat_cancel), getString(android.R.string.ok), false, () -> {});
    }

    /** Человекочитаемая причина ошибки OAuth/сети без стека. */
    private String briefError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("insufficient_scope")) return getString(R.string.profile_err_scope);
        if (m.contains("key_limit_reached")) return getString(R.string.profile_err_key_limit);
        if (m.contains("promo_already_used")) return getString(R.string.profile_err_promo_used);
        if (m.contains("invalid_promo_code")) return getString(R.string.profile_promo_fail);
        if (m.length() > 80) m = m.substring(0, 80);
        return m.isEmpty() ? "error" : m;
    }

    /** Офферы: подписки + token_purchase (для «Пополнить» без входа в браузер). */
    private void renderOffers(JSONObject offers) {
        if (offers == null) return;
        JSONArray subs = offers.optJSONArray("offers");
        if (subs != null && subs.length() > 0) {
            renderSubscriptionPlansLive(subs);
        }
        JSONObject tp = offers.optJSONObject("token_purchase");
        if (tp != null && tp.optBoolean("enabled", false)) {
            tokenPurchase = tp; // слайдер возьмёт границы отсюда
        }
    }

    /** История платежей (billing/history): ТОЛЬКО в карточку «История
     *  платежей» на вкладке «Подписка». «Недавние расходы» (recentList) —
     *  исключительно AI-списания из usage.recent, пополнения туда не идут. */
    private void renderHistory(JSONObject history) {
        if (history == null) return;
        JSONArray tx = history.optJSONArray("transactions");
        if (tx == null) return;
        lastTransactions = tx;
        lastHistoryFull = history;
        renderPaymentHistory(); // карточка на вкладке «Подписка»
    }

    /** Умный формат транзакции: человекочитаемый заголовок по типу,
     *  сумма «—» вместо null, статусы с переводом. */
    private View inflateTransactionRow(JSONObject t) {
        String type = t.optString("type", "");
        String title;
        String icon = "card";
        long tokens = t.optLong("tokens_amount", -1);
        switch (type) {
            case "purchase":
                title = tokens > 0
                        ? getString(R.string.profile_tx_tokens, fmtTokens(tokens))
                        : t.optString("title", getString(R.string.profile_tx_purchase));
                icon = "coins";
                break;
            case "subscription":
                title = getString(R.string.profile_tx_subscription);
                icon = "crown";
                break;
            case "refund":
                title = getString(R.string.profile_tx_refund);
                icon = "card";
                break;
            case "bonus":
            default:
                // Промо и прочие бонусы: без сырых кодов в заголовке
                title = getString(R.string.profile_tx_bonus);
                icon = "gift";
                break;
        }

        // Сумма: null → «—» (промо/бонус бесплатно)
        Object amountObj = t.opt("amount");
        String amount;
        if (amountObj == null || amountObj == JSONObject.NULL) {
            amount = "—";
        } else {
            amount = String.valueOf(amountObj);
        }
        String cur = t.optString("currency", "");
        if (!cur.isEmpty() && !"—".equals(amount)) amount += " " + cur;

        // Статус по-русски + цвет
        String status = t.optString("status", "");
        int statusColor;
        String statusText;
        switch (status) {
            case "paid":
                statusText = getString(R.string.profile_tx_paid);
                statusColor = ContextCompat.getColor(this, R.color.success);
                break;
            case "pending":
                statusText = getString(R.string.profile_tx_pending);
                statusColor = ContextCompat.getColor(this, R.color.warning);
                break;
            case "failed":
                statusText = getString(R.string.profile_tx_failed);
                statusColor = ContextCompat.getColor(this, R.color.error);
                break;
            case "refunded":
                statusText = getString(R.string.profile_tx_refunded);
                statusColor = ContextCompat.getColor(this, R.color.text_hint);
                break;
            default:
                statusText = status;
                statusColor = ContextCompat.getColor(this, R.color.text_hint);
        }

        // Дата без секунд/миллисекунд
        String date = t.optString("created_at", "");
        if (date.length() >= 16) date = date.substring(0, 16).replace("T", " ");

        // Доначисленные токены бонусом — покажем справа вместо суммы
        long credited = t.optLong("tokens_credited", -1);
        String right = credited > 0 && "—".equals(amount)
                ? "+" + fmtTokens(credited)
                : amount;

        android.widget.TextView tv = new android.widget.TextView(this);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(title).append("  ·  ").append(right);
        int start = sb.length() - right.length();
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, sb.length(), 0);
        sb.append("\n").append(date).append("  ·  ");
        int stStart = sb.length();
        sb.append(statusText);
        sb.setSpan(new android.text.style.ForegroundColorSpan(statusColor),
                stStart, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                stStart, sb.length(), 0);
        tv.setText(sb);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tv.setTextSize(13);
        tv.setPadding(0, dp(6), 0, dp(6));
        return tv;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void renderSnapshot(JSONObject snap) {
        if (snap == null) return;

        // Desktop уже использует snapshot.user.avatar_url как fallback к userinfo.picture.
        // Mobile делает то же самое и сразу оповещает burger/settings об обновлении.
        JSONObject user = snap.optJSONObject("user");
        String snapshotAvatar = app.multiai.mobile.network.oauth.OAuthAvatar.fromProfileJson(user);
        if (snapshotAvatar != null && store != null) {
            String currentAvatar = store.getPicture();
            boolean changed = !snapshotAvatar.equals(currentAvatar);
            boolean persisted = !changed || store.updatePicture(snapshotAvatar);
            imgAvatar.setImageTintList(null);
            imgAvatar.setPadding(0, 0, 0, 0);
            loadAvatar(snapshotAvatar);
            if (changed && persisted) {
                sendBroadcast(new Intent(OAuthLoginController.ACTION_PROFILE_UPDATED)
                        .setPackage(getPackageName()));
            }
        }

        JSONObject account = snap.optJSONObject("account");
        if (account != null) {
            JSONObject wallet = account.optJSONObject("wallet");
            if (wallet != null) {
                long total = wallet.optLong("total", 0);
                animateCount(textTokensTotal, total);
                String mode = wallet.optString("billing_mode", "—");
                if ("tokens".equals(mode)) {
                    mode = getString(R.string.profile_mode_tokens);
                } else if ("subscription".equals(mode)) {
                    mode = getString(R.string.profile_mode_subscription);
                } else if (!mode.isEmpty() && !"—".equals(mode)) {
                    mode = mode.substring(0, 1).toUpperCase(Locale.ROOT) + mode.substring(1);
                }
                textTokensBilling.setText(mode);
            }
            JSONObject sub = account.optJSONObject("subscription");
            boolean active = sub != null && sub.optBoolean("active", false);
            String subLabel = getString(active
                    ? R.string.lay_tokens_active : R.string.lay_tokens_inactive);
            textTokensSubscription.setText(subLabel);
            textTokensSubscription.setTextColor(ContextCompat.getColor(this,
                    active ? R.color.success : R.color.text_hint));
            renderSubscription(sub);
        }
        JSONObject conn = snap.optJSONObject("connection");
        if (conn == null) conn = new JSONObject();
        renderStats(snap);
    }

    /** «Потрачено всего / Запросов / Активный ключ» — агрегаты по ключам из snapshot. */
    private void renderStats(JSONObject snap) {
        TextView statSpent = findViewById(R.id.textStatSpent);
        TextView statRequests = findViewById(R.id.textStatRequests);
        TextView statKey = findViewById(R.id.textStatKey);
        if (statSpent == null || statRequests == null || statKey == null) return;

        long selId = selectedKeyIdFor(snap);

        long spent = 0;
        long requests = 0;
        String masked = "—";
        JSONArray keys = snap.optJSONArray("keys");
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                JSONObject k = keys.optJSONObject(i);
                if (k == null) continue;
                spent += k.optLong("tokens_spent", 0);
                requests += k.optLong("usage_count", 0);
                if (selId > 0 && k.optLong("id", -1) == selId) {
                    masked = k.optString("key", "—");
                }
            }
        }
        statSpent.setText(String.format(Locale.getDefault(), "%,d", spent));
        statRequests.setText(String.format(Locale.getDefault(), "%,d", requests));
        statKey.setText(masked);
    }

    /** Счётчик: число набегает с нуля (формат с разделителями). */
    private void animateCount(TextView tv, long target) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, target);
        a.setDuration(700);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> tv.setText(String.format(
                Locale.getDefault(), "%,d", (long) (float) an.getAnimatedValue())));
        a.start();
    }

    private void renderKeys(JSONArray keys, JSONObject snap) {
        keysList.removeAllViews();
        lastKeys = keys; // фильтр-чипы в шитах расходов/платежей
        long selectedId = selectedKeyIdFor(snap);

        if (keys == null || keys.length() == 0) {
            keysEmpty.setVisibility(View.VISIBLE);
            return;
        }
        keysEmpty.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        float dp = getResources().getDisplayMetrics().density;
        int gap = (int) (10 * dp);
        String fmt = getString(R.string.profile_key_balance);
        String reqFmt = getString(R.string.profile_key_requests);

        for (int i = 0; i < keys.length(); i++) {
            JSONObject k = keys.optJSONObject(i);
            if (k == null) continue;
            long id = k.optLong("id", -1);
            String name = k.optString("name", "—");
            String masked = k.optString("key", "");
            String status = k.optString("status", "");
            long balance = k.optLong("token_balance", -1);
            long usage = k.optLong("usage_count", -1);
            boolean selected = id == selectedId;

            View item = inflater.inflate(R.layout.item_profile_key, keysList, false);

            TextView keyName = item.findViewById(R.id.keyName);
            TextView keyMasked = item.findViewById(R.id.keyMasked);
            TextView keyBadge = item.findViewById(R.id.keyBadgeStatus);
            TextView keyBalance = item.findViewById(R.id.keyBalance);
            ImageView tile = item.findViewById(R.id.keyIconTile);
            ImageView tileIcon = (ImageView) ((android.view.ViewGroup) tile.getParent())
                    .getChildAt(1);
            ImageButton keyMenu = item.findViewById(R.id.keyMenu);

            keyName.setText(name);
            keyName.setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.text_primary : R.color.text_primary));
            keyMasked.setText(masked);
            keyMasked.setVisibility(masked.isEmpty() ? View.GONE : View.VISIBLE);

            // Карточка: выбранная подсвечивается обводкой акцента.
            GradientDrawable card = new GradientDrawable();
            card.setColor(ContextCompat.getColor(this, R.color.surface_card));
            card.setCornerRadius(16 * dp);
            card.setStroke((int) Math.max(1, 1.5f * dp), selected
                    ? accent : ContextCompat.getColor(this, R.color.stroke));
            item.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ripple)),
                    card, null));

            // Плитка-иконка: у выбранного ключа — акцентная.
            GradientDrawable tileBg = new GradientDrawable();
            tileBg.setCornerRadius(10 * dp);
            if (selected) {
                tileBg.setColor(ColorUtils.setAlphaComponent(accent, 0x33));
                tileIcon.setColorFilter(onAccent);
            } else {
                tileBg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
                tileIcon.setColorFilter(ContextCompat.getColor(this, R.color.icon_muted));
            }
            tile.setBackground(tileBg);

            if (!status.isEmpty()) {
                boolean ok = "active".equals(status);
                String label = status.substring(0, 1).toUpperCase(Locale.ROOT) + status.substring(1);
                keyBadge.setText(label);
                GradientDrawable badge = new GradientDrawable();
                badge.setCornerRadius(100 * dp);
                badge.setColor(ContextCompat.getColor(this,
                        ok ? R.color.success_bg : R.color.surface_tonal));
                keyBadge.setBackground(badge);
                keyBadge.setTextColor(ContextCompat.getColor(this,
                        ok ? R.color.success : R.color.text_secondary));
                keyBadge.setVisibility(View.VISIBLE);
            }

            String meta = balance >= 0
                    ? String.format(fmt, String.format(Locale.getDefault(), "%,d", balance))
                    : "";
            if (usage >= 0) {
                meta += (meta.isEmpty() ? "" : "  ·  ")
                        + String.format(reqFmt, String.format(Locale.getDefault(), "%,d", usage));
            }
            keyBalance.setText(meta);
            keyBalance.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) item.getLayoutParams();
            lp.bottomMargin = gap;
            item.setLayoutParams(lp);
            MotionKit.attachPress(item, 0.98f);

            final long keyId = id;
            item.setOnClickListener(v -> {
                if (!selected) selectKey(keyId);
            });

            keyMenu.setOnClickListener(v -> showKeyMenu(v, name));
            keyMenu.setContentDescription(getString(R.string.profile_key_actions_for, name));
            keyMenu.setTag(id);

            keysList.addView(item);
        }
    }

    private void showKeyMenu(View anchor, String keyName) {
        // Реальные id ключа прилетают через tag — см. renderKeys.
        final long keyId = anchor.getTag() instanceof Long ? (Long) anchor.getTag() : -1L;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 1, getString(R.string.profile_key_rename));
        menu.getMenu().add(0, 2, 2, getString(R.string.profile_key_delete));
        menu.setOnMenuItemClickListener(item -> {
            if (keyId < 0) return true;
            if (item.getItemId() == 1) showRenameKeyDialog(keyId, keyName);
            else confirmDeleteKey(keyId, keyName);
            return true;
        });
        menu.show();
    }

    /** Переименование: PATCH /keys/{id} (ТЗ §4.3, опционален — при 404/405
     *  скажем, что сервер пока не поддерживает). */
    private void showRenameKeyDialog(long keyId, String currentName) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(currentName);
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        float dp = getResources().getDisplayMetrics().density;
        input.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (10 * dp));
        ModernDialog.show(this, getString(R.string.profile_key_rename),
                null, input,
                getString(R.string.scr_cancel),
                getString(R.string.save),
                false,
                () -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            oauth.getAccountApi().renameKey(keyId, newName);
                            runOnUiThread(this::loadAccount);
                        } catch (Exception e) {
                            runOnUiThread(() -> ModernToast.show(this,
                                    getString(R.string.profile_oauth_error, briefError(e))));
                        }
                    }).start();
                });
    }

    private void confirmDeleteKey(long keyId, String keyName) {
        ModernDialog.show(this,
                getString(R.string.profile_key_delete),
                getString(R.string.profile_key_delete_confirm, keyName),
                getString(R.string.scr_cancel),
                getString(R.string.profile_key_delete),
                true,
                () -> new Thread(() -> {
                    try {
                        oauth.getAccountApi().deleteKey(keyId);
                        runOnUiThread(() -> {
                            ModernToast.show(this, getString(R.string.profile_key_deleted));
                            loadAccount();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> ModernToast.show(this,
                                getString(R.string.profile_oauth_error, briefError(e))));
                    }
                }).start());
    }

    private void refreshSelectedKeyUi() {
        if (lastKeys != null) renderKeys(lastKeys, null);
    }

    private void selectKey(long keyId) {
        new Thread(() -> {
            try {
                oauth.getAccountApi().selectKey(keyId);
                runOnUiThread(() -> {
                    pendingSelectedKeyId = keyId;
                    if (store != null) store.setSelectedKeyId(keyId);
                    refreshSelectedKeyUi();
                    ModernToast.show(this, getString(R.string.lay_keys_selected));
                    loadAccount();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        ModernToast.show(this, getString(R.string.profile_oauth_error,
                                e.getMessage())));
            }
        }).start();
    }

    private void clearSelectedKey() {
        new Thread(() -> {
            try {
                oauth.getAccountApi().clearSelectedKey();
                runOnUiThread(() -> {
                    pendingSelectedKeyId = -1L;
                    if (store != null) store.clearSelectedKeyId();
                    refreshSelectedKeyUi();
                    ModernToast.show(this, getString(R.string.lay_keys_clear_select));
                    loadAccount();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        ModernToast.show(this, getString(R.string.profile_oauth_error,
                                e.getMessage())));
            }
        }).start();
    }

    private void loadAvatar(String url) {
        try {
            Glide.with(this)
                    .load(url)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_account)
                    .into(imgAvatar);
        } catch (Exception e) {
        }
    }

    // ══════════════════ SUBSCRIPTION TAB ══════════════════

    private void openStore() {
        startActivity(new Intent(this, StoreActivity.class));
    }

    /**
     * Карточка «Текущий план» + лимиты. Поля плана (plan_name, expires_at)
     * появятся с серверным обновлением (ТЗ §7.2) — пока активная подписка без
     * них показывается как неактивная. Проценты лимитов — реальные, из snapshot.
     */
    private void renderSubscription(JSONObject sub) {
        boolean active = sub != null && sub.optBoolean("active", false);
        String planName = sub != null ? sub.optString("plan_name", null) : null;
        String expires = sub != null ? sub.optString("expires_at", null) : null;

        boolean havePlan = active && planName != null && !planName.isEmpty();
        long daysLeft = 0;
        long periodDays = 30;

        if (havePlan) {
            try {
                Date end = null;
                if (expires != null && expires.length() >= 19) {
                    // «2026-08-18T17:28:55.34…» — парсим первые 19 символов.
                    java.text.SimpleDateFormat iso =
                            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                    end = iso.parse(expires.substring(0, 19));
                }
                if (end == null && expires != null && expires.length() >= 10) {
                    java.text.SimpleDateFormat d = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    end = d.parse(expires.substring(0, 10));
                }
                if (end != null) {
                    // Потолок: «до завтра» = 1 день, а не 0 (полные сутки ещё есть).
                    daysLeft = Math.max(0, (long) Math.ceil(
                            (end.getTime() - System.currentTimeMillis()) / 86_400_000d));
                }
            } catch (Exception ignored) {
            }
        }

        planInactive.setVisibility(havePlan ? View.GONE : View.VISIBLE);
        planActive.setVisibility(havePlan ? View.VISIBLE : View.GONE);

        if (havePlan) {
            textPlanName.setText(planName);
            // «Действует до» — из expires_at сервера, не от сегодня+N дней.
            try {
                java.text.SimpleDateFormat iso =
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                Date end = iso.parse(expires.substring(0, 19));
                textPlanExpires.setText(new SimpleDateFormat("d MMMM yyyy", new Locale("ru")).format(end));
            } catch (Exception e) {
                textPlanExpires.setText(expires != null ? expires : "—");
            }
            textPlanDaysLeft.setText(formatDaysLeft(daysLeft));
            ObjectAnimator.ofInt(planProgress, "progress", (int) Math.max(0, Math.min(100,
                    daysLeft * 100 / Math.max(1, periodDays))))
                    .setDuration(550)
                    .start();
        }

        // Лимиты: remaining_percent → использовано. Нет данных/лимит выключен
        // (план неактивен) — честные нули.
        JSONObject limits = sub != null ? sub.optJSONObject("limits") : null;
        bindLimit(limitWeekly, textLimitWeekly, pctWeekly, limits, "weekly");
        bindLimit(limitMonthly, textLimitMonthly, pctMonthly, limits, "monthly");
    }

    private String formatDaysLeft(long days) {
        long mod100 = days % 100;
        long mod10 = days % 10;
        String unit = mod100 >= 11 && mod100 <= 14 ? "дн."
                : mod10 == 1 ? "день"
                : mod10 >= 2 && mod10 <= 4 ? "дня" : "дн.";
        return "Остался " + days + " " + unit;
    }

    private void bindLimit(ProgressBar bar, TextView label, TextView pct, JSONObject limits, String key) {
        JSONObject l = limits != null ? limits.optJSONObject(key) : null;
        // remaining_percent=100 (ничего не юзано): бар ПОЛНЫЙ 100%, чип
        // сверху = остаток, подпись снизу = «0% использовано».
        int remaining = 0;
        if (l != null && l.optBoolean("enabled", false)) {
            remaining = Math.max(0, Math.min(100, l.optInt("remaining_percent", 0)));
        }
        ObjectAnimator.ofInt(bar, "progress", remaining)
                .setDuration(450)
                .start();
        // Остаток на исходе (<20%) — полоса краснеет.
        bar.setProgressTintList(ColorStateList.valueOf(
                remaining <= 20 ? ContextCompat.getColor(this, R.color.error) : accent));
        pct.setText(getString(R.string.profile_sub_remaining_percent_short, remaining));
        label.setText(getString(R.string.profile_sub_used_percent, 100 - remaining));
        pct.setTextColor(ContextCompat.getColor(this,
                remaining <= 20 ? R.color.error : R.color.text_primary));
    }

    /** Живая история платежей (billing/history) → красивые paymentRow
     *  с реальным статусом (pending — оранжевый) и иконкой по типу. */
    private void renderPaymentHistory() {
        if (paymentHistoryList == null) return;
        paymentHistoryList.removeAllViews();
        if (lastTransactions != null && lastTransactions.length() > 0) {
            int max = Math.min(lastTransactions.length(), 5);
            for (int i = 0; i < max; i++) {
                JSONObject t = lastTransactions.optJSONObject(i);
                if (t == null) continue;
                paymentHistoryList.addView(paymentRow(
                        txTitle(t), txDate(t), txAmount(t),
                        txStatusColor(t), txStatusText(t), txIconKind(t)));
                // Тонкий разделитель между строками (не после последней)
                if (i < max - 1) {
                    View div = new View(this);
                    android.widget.LinearLayout.LayoutParams dlp =
                            new android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1);
                    dlp.leftMargin = dp(60);
                    dlp.rightMargin = dp(14);
                    div.setLayoutParams(dlp);
                    div.setBackgroundColor(0x14FFFFFF);
                    paymentHistoryList.addView(div);
                }
            }
        } else {
            paymentHistoryList.addView(emptyHint());
        }
    }

    /** Иконка по типу транзакции. */
    private String txIconKind(JSONObject t) {
        switch (t.optString("type", "")) {
            case "subscription": return "crown";
            case "bonus": return "gift";
            case "purchase": return "coins";
            default: return "card";
        }
    }

    /** Шит «Все расходы»: ВСЕ AI-списания из полного кэша (не периодного). */
    private void showRecentSpendsSheet() {
        JSONArray recent = recentForSheet();
        if (recent == null) {
            ModernToast.show(this, getString(R.string.filter_empty));
            return;
        }
        java.util.Map<String, String> searchModelNames = new java.util.HashMap<>();
        RecentSpendsSheet.show(this, R.string.profile_usage_recent, recent, "ai", lastKeys,
                (ctx, item) -> inflateSpendRow(item),
                item -> {
                    String modelId = item.optString("model", "");
                    String displayName = searchModelNames.get(modelId);
                    if (displayName == null) {
                        displayName = prettyModelName(modelId);
                        searchModelNames.put(modelId, displayName);
                    }
                    return displayName;
                });
    }

    /** Шит «История платежей»: транзакции (токены/подписки/промокоды). */
    private void showPaymentsSheet() {
        if (lastTransactions == null || lastTransactions.length() == 0) {
            ModernToast.show(this, getString(R.string.filter_empty));
            return;
        }
        RecentSpendsSheet.show(this, R.string.profile_payments_title, lastTransactions, "tx", lastKeys,
                (ctx, t) -> paymentRow(txTitle(t), txDate(t), txAmount(t),
                        txStatusColor(t), txStatusText(t), txIconKind(t)));
    }

    private int txStatusColor(JSONObject t) {
        switch (t.optString("status", "")) {
            case "pending": return ContextCompat.getColor(this, R.color.warning);
            case "failed": return ContextCompat.getColor(this, R.color.error);
            case "refunded": return ContextCompat.getColor(this, R.color.text_hint);
            default: return ContextCompat.getColor(this, R.color.success);
        }
    }

    private String txStatusText(JSONObject t) {
        switch (t.optString("status", "")) {
            case "pending": return getString(R.string.profile_tx_pending);
            case "failed": return getString(R.string.profile_tx_failed);
            case "refunded": return getString(R.string.profile_tx_refunded);
            default: return getString(R.string.profile_tx_paid);
        }
    }

    /** Человекочитаемый заголовок транзакции по типу. */
    private String txTitle(JSONObject t) {
        String type = t.optString("type", "");
        long tokens = t.optLong("tokens_amount", -1);
        switch (type) {
            case "purchase":
                return tokens > 0
                        ? getString(R.string.profile_tx_tokens, fmtTokens(tokens))
                        : getString(R.string.profile_tx_purchase);
            case "subscription":
                return getString(R.string.profile_tx_subscription);
            case "refund":
                return getString(R.string.profile_tx_refund);
            case "bonus":
            default:
                return getString(R.string.profile_tx_bonus);
        }
    }

    /** Сумма: null → «—», бонусы — «+1M» (без копеек), рубли — «120 ₽». */
    private String txAmount(JSONObject t) {
        long credited = t.optLong("tokens_credited", -1);
        Object amountObj = t.opt("amount");
        boolean noAmount = amountObj == null || amountObj == JSONObject.NULL;
        if (noAmount && credited > 0) return "+" + fmtTokens(credited);
        if (noAmount) return "—";
        // «120.00» → «120 ₽» (копеек нет, валюта — знак).
        try {
            double v = Double.parseDouble(String.valueOf(amountObj));
            return String.format(Locale.getDefault(), "%,d", Math.round(v))
                    .replace(',', ' ') + "₽";
        } catch (NumberFormatException e) {
            return String.valueOf(amountObj);
        }
    }

    /** Дата без секунд: «2026-08-17 00:24». */
    private String txDate(JSONObject t) {
        String date = t.optString("created_at", "");
        return date.length() >= 16 ? date.substring(0, 16).replace("T", " ") : date;
    }

    /** Центрированная подсказка для пустых данных (не «в разработке» —
     *  разделы живые, пусто = нет записей за период). */
    private TextView emptyHint() {
        TextView hint = new TextView(this);
        hint.setText(R.string.profile_empty_period);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        hint.setTextSize(13.5f);
        hint.setGravity(android.view.Gravity.CENTER);
        float dp = getResources().getDisplayMetrics().density;
        hint.setPadding((int) (20 * dp), (int) (20 * dp), (int) (20 * dp), (int) (20 * dp));
        return hint;
    }

    private View paymentRow(String title, String time, String amount) {
        return paymentRow(title, time, amount,
                ContextCompat.getColor(this, R.color.success),
                getString(R.string.profile_tx_paid), "card");
    }

    /** Строка истории: иконка по типу, слева title+дата, справа сумма+статус
     *  (цвет из реального статуса транзакции, не вечный «Успешно»). */
    private View paymentRow(String title, String time, String amount,
                            int statusColor, String statusText, String iconKind) {
        return paymentRow(title, time, amount, statusColor, statusText, iconKind, null);
    }

    /** Для AI-списаний modelId включает реальный логотип модели поверх обычного fallback. */
    private View paymentRow(String title, String time, String amount,
                            int statusColor, String statusText, String iconKind,
                            String modelId) {
        if ("coins".equals(iconKind) && "−0".equals(amount)) {
            amount = getString(R.string.store_value_free);
        }
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));

        android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
        GradientDrawable tileBg = new GradientDrawable();
        tileBg.setShape(GradientDrawable.OVAL);
        tileBg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        tile.setBackground(tileBg);
        int size = (int) (34 * dp);

        ImageView icon = new ImageView(this);
        Integer modelIconRes = app.multiai.mobile.ui.kit.ModelCatalogUi.iconFor(modelId);
        boolean hasModelIcon = modelIconRes != null;
        int iconRes;
        if (hasModelIcon) {
            iconRes = modelIconRes;
        } else {
            switch (iconKind) {
                case "gift": iconRes = R.drawable.ic_gift; break;
                case "crown": iconRes = R.drawable.ic_crown; break;
                case "coins": iconRes = R.drawable.ic_coins; break;
                default: iconRes = R.drawable.ic_credit_card; break;
            }
        }
        icon.setImageResource(iconRes);
        // Брендовые model assets уже имеют правильные цвета — tint их портит.
        if (!hasModelIcon) {
            icon.setColorFilter(ContextCompat.getColor(this, R.color.icon_muted));
        } else {
            app.multiai.mobile.ui.kit.ModelCatalogUi.applyLogoContrast(this, icon, iconRes);
        }
        int iconSize = (int) ((hasModelIcon ? 22 : 16) * dp);
        android.widget.FrameLayout.LayoutParams iconLp = new android.widget.FrameLayout.LayoutParams(
                iconSize, iconSize, android.view.Gravity.CENTER);
        tile.addView(icon, iconLp);
        row.addView(tile, new LinearLayout.LayoutParams(size, size));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = (int) (12 * dp);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        name.setTextSize(13.5f);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        texts.addView(name);

        TextView when = new TextView(this);
        when.setText(time);
        when.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        when.setTextSize(11.5f);
        texts.addView(when);

        row.addView(texts, tp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(android.view.Gravity.END);

        TextView cost = new TextView(this);
        cost.setText(amount);
        cost.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        cost.setTextSize(14f);
        cost.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        right.addView(cost);

        TextView status = new TextView(this);
        status.setText(statusText);
        status.setTextColor(statusColor);
        status.setTextSize(10.5f);
        right.addView(status);

        row.addView(right);
        return row;
    }

    // ══════════════════ USAGE (пусто до появления GET /usage, ТЗ §6) ══════════════════

    /**
     * Вкладка «Расходы» без серверных данных: метрики «—», пустые график и
     * кольцо, заглушки в списках. Реальные значения придут с
     * {@code GET /api/oauth/v1/usage} (ТЗ §6): метрики и дельты — из
     * {@code total.by_day}, структура — из {@code by_model}, недавние — из
     * {@code recent}, история платежей — из {@code billing/history} (§5.4).
     */
    /** Живые «Расходы» из GET /usage (§6). Обзор (день/нед/мес) считается из
     *  ПЕРЕСЕЧЕНИЯ данных с реальными календарными периодами — от богатого
     *  кэша (60д), а переключатель периода обновляет только график/кольцо/
     *  недавние. Иначе при days=2 «месяц» съёживался до двух дней. */
    private org.json.JSONObject usageCache;
    /** Последний периодный usage нужен для повторного рендера после загрузки каталога. */
    private org.json.JSONObject lastRenderedUsage;
    private final java.util.Set<String> freeUsageModelIds = new java.util.HashSet<>();
    private boolean freeUsageModelsLoading;
    private boolean freeUsageModelsLoaded;

    private void loadFreeUsageModelsIfNeeded() {
        if (freeUsageModelsLoaded || freeUsageModelsLoading) return;
        freeUsageModelsLoading = true;
        String accessToken = app.multiai.mobile.network.oauth.OAuthSession.get().getAccessToken();
        new Thread(() -> {
            java.util.Set<String> loaded = new java.util.HashSet<>();
            try {
                java.util.List<app.multiai.mobile.models.ModelInfo> catalog =
                        new app.multiai.mobile.network.MultiAiClient()
                                .fetchModelCatalog(accessToken);
                for (app.multiai.mobile.models.ModelInfo model : catalog) {
                    if (model != null && model.free) {
                        loaded.add(normalizeUsageModelId(model.id));
                    }
                }
            } catch (Exception ignored) {
                android.util.Log.w(TAG, "free_model_catalog_load_failed");
            }
            runOnUiThread(() -> {
                freeUsageModelIds.clear();
                freeUsageModelIds.addAll(loaded);
                freeUsageModelsLoading = false;
                freeUsageModelsLoaded = true;
                JSONObject current = lastRenderedUsage;
                if (current != null && !isFinishing() && !isDestroyed()) {
                    renderUsage(current);
                }
            });
        }, "usage-free-models").start();
    }

    private static String normalizeUsageModelId(String modelId) {
        if (modelId == null) return "";
        String normalized = modelId.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("ma-") ? normalized.substring(3) : normalized;
    }

    private boolean isFreeUsageModel(JSONObject model) {
        if (model == null) return false;
        String id = normalizeUsageModelId(model.optString("model", ""));
        float tokens = (float) model.optDouble("tokens", 0);
        return model.optBoolean("billing_free", false)
                || freeUsageModelIds.contains(id)
                || (tokens == 0f && model.optLong("requests", 0) > 0);
    }

    private void renderUsage(JSONObject usage) {
        if (usage == null) return;
        lastRenderedUsage = usage;
        loadFreeUsageModelsIfNeeded();

        // Обзор (день/нед/мес) — ВСЕГДА из полного 60-д кэша, если он есть:
        // периодный ответ (days=2/7) не должен искажать «за месяц».
        JSONArray byDayOverview = null;
        if (usageCache != null && usageCache != usage) {
            byDayOverview = usageCache.optJSONArray("by_day");
        }
        if (byDayOverview == null) byDayOverview = usage.optJSONArray("by_day");

        long nowMs = System.currentTimeMillis();
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);

        float cur[] = {0f, 0f, 0f};   // день, неделя, месяц
        if (byDayOverview != null) {
            for (int i = 0; i < byDayOverview.length(); i++) {
                JSONObject d = byDayOverview.optJSONObject(i);
                if (d == null) continue;
                float tokens = (float) d.optDouble("tokens", 0);
                long t;
                try {
                    t = df.parse(d.optString("date", "")).getTime();
                } catch (Exception e) {
                    continue;
                }
                long age = nowMs - t;
                if (age < 1 * 86_400_000L) cur[0] += tokens;
                if (age < 7 * 86_400_000L) cur[1] += tokens;
                if (age < 30 * 86_400_000L) cur[2] += tokens;
            }
        }

        setTextOrHide(R.id.usageDayValue, fmtTokens(cur[0]));
        setTextOrHide(R.id.usageWeekValue, fmtTokens(cur[1]));
        setTextOrHide(R.id.usageMonthValue, fmtTokens(cur[2]));
        // Дельты-проценты в «Обзоре» не показываем (по фидбеку): плашки скрыты.
        hideIf(R.id.usageDayDelta);
        hideIf(R.id.usageWeekDelta);
        hideIf(R.id.usageMonthDelta);

        // График по дням — из ПЕРИОДНОГО ответа (usage), не из кэша обзора.
        JSONArray byDayPeriod = usage.optJSONArray("by_day");
        List<UsageChartView.Point> points = new ArrayList<>();
        if (byDayPeriod != null) {
            for (int i = 0; i < byDayPeriod.length(); i++) {
                JSONObject d = byDayPeriod.optJSONObject(i);
                if (d == null) continue;
                String label = d.optString("date", "");
                if (label.length() > 10) label = label.substring(0, 10);
                points.add(new UsageChartView.Point(label, (float) d.optDouble("tokens", 0)));
            }
        }
        usageChart.setData(points);

        // Кольцо по моделям + легенда. Топ-5 моделей отдельными сегментами,
        // остальной хвост — один сегмент «Другие» (не захламляем). «unknown»
        // на сервере — тоже в «Другие». Подсветка: тап по сегменту/строке.
        JSONArray byModel = usage.optJSONArray("by_model");
        List<DonutChartView.Segment> segments = new ArrayList<>();
        int[] palette = {0xFF7C5CFC, 0xFF4F8CFF, 0xFF2FBF8F, 0xFFF5A623, 0xFFEF5DA8, 0xFF8D9BB0};
        usageLegend.removeAllViews();
        usageLegendRows.clear();
        usageSliceNames.clear();
        usageSliceTokens.clear();
        float total = 0f;
        float othersTokens = 0f;
        // Бесплатные модели исключаются ДО total: они не влияют ни на проценты,
        // ни на «Другие», ни на центральную сумму. Недавняя история не меняется.
        if (byModel != null) {
            for (int i = 0; i < byModel.length(); i++) {
                JSONObject m = byModel.optJSONObject(i);
                if (m != null && !isFreeUsageModel(m)) {
                    total += (float) m.optDouble("tokens", 0);
                }
            }
        }
        if (byModel != null) {
            int shown = 0;
            for (int i = 0; i < byModel.length(); i++) {
                JSONObject m = byModel.optJSONObject(i);
                if (m == null || isFreeUsageModel(m)) continue;
                float tokens = (float) m.optDouble("tokens", 0);
                boolean isUnknown = "unknown".equalsIgnoreCase(m.optString("model", ""));
                // Заметность: ≥1% от total — иначе дуга-невидимка. Топ-5 или «Другие».
                boolean visible = tokens > 0 && total > 0 && tokens * 100f / total >= 1f;
                if (shown < 5 && !isUnknown && visible) {
                    int idx = shown;
                    segments.add(new DonutChartView.Segment(palette[idx], tokens));
                    View row = inflateLegendRow(prettyModelName(m.optString("model", "?")),
                            fmtTokens(tokens), palette[idx]);
                    bindLegendHighlight(row, idx);
                    usageLegendRows.add(row);
                    usageSliceNames.add(prettyModelName(m.optString("model", "?")));
                    usageSliceTokens.add(tokens);
                    usageLegend.addView(row);
                    shown++;
                } else {
                    othersTokens += tokens;
                }
            }
        }
        // «Другие» — только если заметны (≥1%), иначе совсем пустой сектор.
        if (othersTokens > 0f && total > 0f && othersTokens * 100f / total >= 1f) {
            int idx = segments.size();
            segments.add(new DonutChartView.Segment(palette[5], othersTokens));
            View row = inflateLegendRow(getString(R.string.profile_usage_other),
                    fmtTokens(othersTokens), palette[5]);
            bindLegendHighlight(row, idx);
            usageLegendRows.add(row);
            usageSliceNames.add(getString(R.string.profile_usage_other));
            usageSliceTokens.add(othersTokens);
            usageLegend.addView(row);
        }
        usageTotalTokens = total;
        usageDonut.setData(segments, fmtTokens(total), getString(R.string.profile_tokens_unit));

        // Недавние списания — только полная история из /usage/history.
        JSONArray recent = recentForSheet();
        recentList.removeAllViews();
        if (recent != null) {
            int shown = Math.min(recent.length(), 5);
            for (int i = 0; i < shown; i++) {
                JSONObject r = recent.optJSONObject(i);
                if (r == null) continue;
                recentList.addView(inflateSpendRow(r));
            }
        }
        if (recentList.getChildCount() == 0) recentList.addView(emptyHint());

        renderPaymentHistory();
    }

    /** Строка списания за AI-запрос: реальный логотип модели, имя и −N токенов. */
    private View inflateSpendRow(JSONObject r) {
        String modelId = r.optString("model", "");
        String model = prettyModelName(modelId);
        long tokens = r.optLong("tokens", 0);
        String when = r.optString("created_at", "");
        if (when.length() >= 16) when = when.substring(0, 16).replace("T", " ");
        return paymentRow(model, when, "−" + fmtTokens(tokens),
                ContextCompat.getColor(this, R.color.success),
                getString(R.string.profile_tx_paid), "coins", modelId);
    }

    private void setTextOrHide(int id, String value) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(value);
    }

    private void hideIf(int id) {
        View v = findViewById(id);
        if (v != null) v.setVisibility(View.GONE);
    }

    /** Локальная дельта cur vs prev: null = не с чем сравнивать. */
    private Double localDelta(float cur, float prev) {
        if (prev <= 0f) return null;
        return (cur - prev) / prev * 100.0;
    }

    /** Плашка «+x%»/«−x%»: null → скрыта. Рост — оранжевый, падение — зелёное. */
    private void bindDelta(int id, Double pct) {
        TextView tv = findViewById(id);
        if (tv == null) return;
        if (pct == null || pct.isNaN() || pct.isInfinite()) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setText(String.format(Locale.US, "%s%.1f%%",
                pct >= 0 ? "+" : "−", Math.abs(pct)));
        tv.setTextColor(ContextCompat.getColor(this,
                pct >= 0 ? R.color.warning : R.color.success));
        tv.setVisibility(View.VISIBLE);
    }

    /** 1 000 000 → «1M»; 1 230 000 → «1.23M»; 12 000 → «12K». */
    private String fmtTokens(float tokens) {
        if (tokens >= 1_000_000f) return compactNumber(tokens / 1_000_000f, "M");
        if (tokens >= 1_000f) return compactNumber(tokens / 1_000f, "K");
        return String.valueOf((long) tokens);
    }

    private String compactNumber(float value, String suffix) {
        String formatted = String.format(Locale.US, "%.2f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
        return formatted + suffix;
    }

    /** Красивое имя модели; «unknown» — это image-генерации
     *  (gpt-image-2 / nano-banana идут через /v1/images и не логируют модель). */
    private String prettyModelName(String id) {
        if (id == null || "unknown".equalsIgnoreCase(id)) {
            return getString(R.string.model_image_gen);
        }
        return app.multiai.mobile.models.ModelInfo.prettyName(id);
    }

    /** ISO-период → по-русски: P1M → «месяц» и т.д. */
    private String prettyPeriod(String iso) {
        if (iso == null) return "";
        switch (iso) {
            case "P1M": return "месяц";
            case "P3M": return "3 месяца";
            case "P6M": return "6 месяцев";
            case "P1Y": return "год";
            case "P7D": return "неделя";
            default: return iso;
        }
    }

    /** Цена без копеек с разделением и ₽: «1199.00» → «1 199 ₽». */
    private String fmtPrice(String raw) {
        try {
            double v = Double.parseDouble(raw);
            long rub = Math.round(v);
            return String.format(Locale.getDefault(), "%,d", rub).replace(',', ' ')
                    .replace('\u00A0', ' ') + "₽";
        } catch (Exception e) {
            return raw;
        }
    }

    private View inflateLegendRow(String model, String tokens, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.view.View dot = new android.view.View(this);
        android.widget.LinearLayout.LayoutParams dotLp =
                new android.widget.LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.rightMargin = dp(10);
        dot.setLayoutParams(dotLp);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        dot.setBackground(g);
        row.addView(dot);
        TextView name = new TextView(this);
        name.setText(model);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        name.setTextSize(13);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setText(tokens);
        val.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        val.setTextSize(13);
        val.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(val);
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }

    private void renderUsageEmpty() {
        // ── Метрики ──
        for (int[] pair : new int[][]{{R.id.usageMonthValue, R.id.usageMonthDelta},
                {R.id.usageWeekValue, R.id.usageWeekDelta},
                {R.id.usageDayValue, R.id.usageDayDelta}}) {
            TextView value = findViewById(pair[0]);
            TextView delta = findViewById(pair[1]);
            if (value != null) value.setText("—");
            if (delta != null) delta.setVisibility(View.GONE);
        }

        // ── График и структура ──
        usageChart.setData(new ArrayList<>());
        usageLegendRows.clear();
        usageSliceNames.clear();
        usageSliceTokens.clear();
        usageTotalTokens = 0f;
        usageLegend.removeAllViews();
        usageLegend.addView(emptyHint());
        usageDonut.setData(new ArrayList<>(), null, null);
        usageHighlight = -1;

        // ── Недавние расходы ──
        recentList.removeAllViews();
        recentList.addView(emptyHint());

        renderPaymentHistory();
    }

    /** Тап по строке легенды ИЛИ по сегменту кольца подсвечивает пару; повторный тап — сброс. */
    private void bindLegendHighlight(View row, int index) {
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> highlightSlice(usageHighlight == index ? -1 : index));
    }

    /** Общая подсветка: кольцо + центральный текст + акцент строки легенды. */
    private void highlightSlice(int index) {
        usageHighlight = index;
        usageDonut.setHighlight(index);
        applyUsageHighlight();
    }

    private void applyUsageHighlight() {
        if (usageHighlight >= 0 && usageHighlight < usageSliceNames.size()) {
            float tokens = usageSliceTokens.get(usageHighlight);
            String pct = String.format(Locale.US, "%.0f%%",
                    usageTotalTokens > 0f ? tokens * 100f / usageTotalTokens : 0f);
            usageDonut.setCenter(pct, UsageChartView.compact(tokens));
        } else {
            usageDonut.setCenter(UsageChartView.compact(usageTotalTokens),
                    getString(R.string.profile_usage_tokens_caps));
        }
        for (int i = 0; i < usageLegendRows.size(); i++) {
            View row = usageLegendRows.get(i);
            row.animate().alpha(usageHighlight < 0 || usageHighlight == i ? 1f : 0.4f)
                    .setDuration(160)
                    .start();
        }
    }

    private void bindMetric(int valueId, int deltaId, float tokens, float deltaPercent) {
        TextView value = findViewById(valueId);
        TextView delta = findViewById(deltaId);
        if (value != null) value.setText(UsageChartView.compact(tokens));
        if (delta != null) {
            delta.setText(String.format(Locale.US, "%s%.1f%%",
                    deltaPercent >= 0 ? "+" : "−", Math.abs(deltaPercent)));
            delta.setTextColor(accent);
        }
    }

    private View legendRow(int color, String name, float percent, float tokens, float dp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = (int) (8 * dp);
        row.setLayoutParams(rp);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(color);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dp_ = new LinearLayout.LayoutParams(
                (int) (9 * dp), (int) (9 * dp));
        row.addView(dot, dp_);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        label.setTextSize(12.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = (int) (10 * dp);
        row.addView(label, lp);

        TextView pct = new TextView(this);
        pct.setText(String.format(Locale.US, "%.0f%%", percent));
        pct.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        pct.setTextSize(12f);
        row.addView(pct);

        TextView amt = new TextView(this);
        amt.setText(UsageChartView.compact(tokens));
        amt.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        amt.setTextSize(12f);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.leftMargin = (int) (12 * dp);
        row.addView(amt, ap);

        return row;
    }

    private View recentRow(String model, String time, int tokens, int colorRes, float dp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));

        int color = ContextCompat.getColor(this, colorRes);

        android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
        GradientDrawable tileBg = new GradientDrawable();
        tileBg.setShape(GradientDrawable.OVAL);
        tileBg.setColor(ColorUtils.setAlphaComponent(color, 0x2E));
        tile.setBackground(tileBg);
        int size = (int) (34 * dp);
        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(size, size);

        ImageView icon = new ImageView(this);
        icon.setImageResource(modelIcon(model));
        icon.setColorFilter(color);
        android.widget.FrameLayout.LayoutParams iconLp = new android.widget.FrameLayout.LayoutParams(
                (int) (17 * dp), (int) (17 * dp), android.view.Gravity.CENTER);
        tile.addView(icon, iconLp);
        row.addView(tile, tileLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = (int) (12 * dp);
        texts.setLayoutParams(tp);

        TextView name = new TextView(this);
        name.setText(model);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        name.setTextSize(13.5f);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        texts.addView(name);

        TextView when = new TextView(this);
        when.setText(time);
        when.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        when.setTextSize(11.5f);
        texts.addView(when);

        row.addView(texts);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(android.view.Gravity.END);

        TextView spent = new TextView(this);
        spent.setText(String.format(Locale.US, "−%d", tokens));
        spent.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        spent.setTextSize(14f);
        spent.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        right.addView(spent);

        TextView unit = new TextView(this);
        unit.setText(getString(R.string.profile_usage_tokens_caps));
        unit.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        unit.setTextSize(9f);
        right.addView(unit);

        row.addView(right);

        return row;
    }

    private int modelIcon(String model) {
        Integer icon = app.multiai.mobile.ui.kit.ModelCatalogUi.iconFor(model);
        return icon != null ? icon : R.drawable.ic_s_sparkle;
    }

    /**
     * Фон карточки с акцентной градиентной лентой на верхней кромке.
     * Карточка и лента рисуются одним проходом по одной и той же траектории
     * скругления — расхождение геометрий исключено по построению.
     */
    private static final class TopAccentCardDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fillPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint stripPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint strokePaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final float radius;
        private final float stripHeight;
        private final int accent;
        private final int surface;
        private final int stroke;

        TopAccentCardDrawable(int accent, int surface, int stroke, float density) {
            this.accent = accent;
            this.surface = surface;
            this.stroke = stroke;
            this.radius = 17f * density;   // под карточки 18dp (1dp обводка)
            this.stripHeight = 4f * density;
            this.strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
            this.strokePaint.setStrokeWidth(density);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            rect.set(getBounds());
            if (rect.isEmpty()) return;

            path.reset();
            path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);

            // Тело карточки.
            fillPaint.setColor(surface);
            canvas.drawPath(path, fillPaint);

            // Акцентная лента: клип по тому же пути, заливка сверху на 4dp.
            int save = canvas.save();
            canvas.clipPath(path);
            stripPaint.setShader(new android.graphics.LinearGradient(
                    rect.left, 0, rect.right, 0,
                    new int[]{accent,
                            (accent & 0x00FFFFFF) | 0x66000000,
                            (accent & 0x00FFFFFF)},
                    null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + stripHeight, stripPaint);
            canvas.restoreToCount(save);

            // Обводка поверх — контур читается и через ленту.
            strokePaint.setColor(stroke);
            canvas.drawPath(path, strokePaint);
        }

        @Override public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            stripPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
        }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            fillPaint.setColorFilter(cf);
            stripPaint.setColorFilter(cf);
            strokePaint.setColorFilter(cf);
        }
        @SuppressWarnings("deprecation")
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    @Override
    public void onBackPressed() {
        // Сначала закрываем чат-мод сайдбар (поиск → сама шторка), потом обычный back.
        if (chatModeSidebar != null && chatModeSidebar.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        // Edge-свайп открывает/закрывает чат-мод сайдбар — как в ChatModeActivity.
        if (chatModeSidebar != null && chatModeSidebar.handleSwipe(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
}
