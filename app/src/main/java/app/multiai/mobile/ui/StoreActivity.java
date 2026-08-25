package app.multiai.mobile.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

import app.multiai.mobile.R;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.views.LogSliderView;
import app.multiai.mobile.views.UsageChartView;

/**
 * Магазин: покупка токенов (слайдер 1M–10B с живым пересчётом цены) и подписок.
 *
 * <p>Пока работает на демо-тарифе (цена за 1M, множители «ценности по
 * моделям», карточки подписок) — конфигурация приедет с
 * {@code GET /api/oauth/v1/billing/offers} (см. ТЗ биллинг-API). Разметка и
 * логика пересчёта при этом не меняются: offers просто подставит значения.
 *
 * <p>Слайдер логарифмический: четыре декады 1M…10B на шкале 0…1000,
 * значения «прилипают» к красивым числам (2 значащие цифры).
 */
public class StoreActivity extends AppCompatActivity {

    /**
     * Границы объёма — из ТЗ (token_purchase.min/max). Цена за 1M, конфиг
     * «ценности по моделям» и карточки подписок придут с сервера
     * (GET /billing/offers + ценовые множители /v1/models) — до тех пор
     * соответствующие секции скрыты, цена не показывается.
     */
    private static final long MIN_TOKENS = 1_000_000L;
    private static final long MAX_TOKENS = 10_000_000_000L;
    private static final int SLIDER_MAX = 1000;

    private TextView textStorePrice;
    private TextView textStorePerUnit;
    private EditText storeAmountInput;
    private LogSliderView storeSlider;
    private TextView[] quickChips = new TextView[5];

    private long tokens = MIN_TOKENS;
    private int accent;
    private int onAccent;
    private boolean selfUpdate;
    /** Живой token_purchase из GET /billing/offers (base_price_per_1m). */
    private double pricePer1m = -1;
    private app.multiai.mobile.network.oauth.OAuthLoginController oauth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        setContentView(R.layout.activity_store);
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        accent = AccentColor.resolve(this);
        onAccent = AccentColor.contrastForeground(this);

        textStorePrice = findViewById(R.id.textStorePrice);
        textStorePerUnit = findViewById(R.id.textStorePerUnit);
        storeAmountInput = findViewById(R.id.storeAmountInput);
        storeSlider = findViewById(R.id.storeSlider);

        quickChips[0] = findViewById(R.id.chip1M);
        quickChips[1] = findViewById(R.id.chip10M);
        quickChips[2] = findViewById(R.id.chip100M);
        quickChips[3] = findViewById(R.id.chip1B);
        quickChips[4] = findViewById(R.id.chip10B);

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Скрыты до серверных данных: «ценность по моделям» (нужны ценовые
        // множители) и статичные подписки-заглушки (заменит живой рендер).
        for (int id : new int[]{R.id.storeValueTitle, R.id.storeValueCard,
                R.id.storeProCard, R.id.storePremiumCard}) {
            View section = findViewById(id);
            if (section != null) section.setVisibility(View.GONE);
        }
        // Заголовок «Подписки» и контейнер — показываем, рендер оживит их.
        findViewById(R.id.storeSubsTitle).setVisibility(View.VISIBLE);
        findViewById(R.id.storeSubsList).setVisibility(View.VISIBLE);

        tintAccent();
        bindInputs();

        // AccountApi требует проверенную OAuth metadata до первого запроса.
        oauth = new app.multiai.mobile.network.oauth.OAuthLoginController(this);
        oauth.ensureMetadataAsync(this::loadOffers,
                ignored -> android.util.Log.e("StoreOffers", "metadata_failed"));
        fetchMultipliersRaw(); // «Ценность по моделям»

        setTokens(MIN_TOKENS);
    }

    /** Цена и подписки загружаются независимо: сбой одной части не гасит вторую. */
    private void loadOffers() {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

        new Thread(() -> {
            try {
                org.json.JSONObject offers = oauth.getAccountApi().billingOffers();
                org.json.JSONObject tp = offers.optJSONObject("token_purchase");
                if (tp == null) tp = offers.optJSONObject("tokens");
                double parsed = tp != null ? tp.optDouble("base_price_per_1m", -1) : -1;
                if (parsed <= 0 && tp != null) parsed = tp.optDouble("price_per_1m", -1);
                if (parsed > 0) {
                    final double validPrice = parsed;
                    ui.post(() -> {
                        pricePer1m = validPrice;
                        setTokens(tokens, true);
                    });
                }
            } catch (Exception ignored) {
                android.util.Log.e("StoreOffers", "price_load_failed");
            }
        }).start();

        new Thread(() -> {
            try {
                org.json.JSONObject offers = oauth.getAccountApi().billingOffers();
                org.json.JSONArray subs = offers.optJSONArray("offers");
                if (subs == null) subs = offers.optJSONArray("subscriptions");
                if (subs == null) subs = offers.optJSONArray("plans");
                final org.json.JSONArray plans = subs;
                ui.post(() -> renderSubscriptions(plans));
            } catch (Exception ignored) {
                android.util.Log.e("StoreOffers", "subscriptions_load_failed");
            }
        }).start();
    }

    /** Живые подписки в storeSubsList (XML-контейнер под заголовком). */
    private void renderSubscriptions(org.json.JSONArray subs) {
        LinearLayout box = findViewById(R.id.storeSubsList);
        if (box == null) return;
        box.removeAllViews();
        if (subs == null || subs.length() == 0) {
            findViewById(R.id.storeSubsTitle).setVisibility(View.GONE);
            box.setVisibility(View.GONE);
            return;
        }
        findViewById(R.id.storeSubsTitle).setVisibility(View.VISIBLE);
        box.setVisibility(View.VISIBLE);
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < subs.length(); i++) {
            org.json.JSONObject o = subs.optJSONObject(i);
            if (o == null) continue;
            final String id = o.optString("id", "");
            String name = o.optString("title", id);
            org.json.JSONObject price = o.optJSONObject("price");
            String priceStr = price != null ? price.optString("amount", "—") : "—";
            String period = prettyPeriod(o.optString("period", ""));
            String tagline = o.optString("tagline", "");
            boolean recommended = o.optBoolean("recommended", false);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.profile_card_bg);
            int pad = (int) (16 * dp);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = (int) (10 * dp);
            card.setLayoutParams(cp);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView nameTv = new TextView(this);
            nameTv.setText(name);
            nameTv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            nameTv.setTextSize(18);
            nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            header.addView(nameTv, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (recommended) {
                TextView badge = new TextView(this);
                badge.setText(R.string.store_recommended);
                badge.setTextSize(11);
                badge.setTextColor(accent);
                badge.setBackgroundResource(R.drawable.chat_mode_model_pill_bg);
                badge.setPadding((int) (10 * dp), (int) (4 * dp), (int) (10 * dp), (int) (4 * dp));
                header.addView(badge);
            }
            card.addView(header);

            // Цена: сумма жирным без копеек + период серым («1 199 ₽ / месяц»).
            LinearLayout priceRow = new LinearLayout(this);
            priceRow.setOrientation(LinearLayout.HORIZONTAL);
            priceRow.setGravity(android.view.Gravity.BOTTOM);
            TextView priceNum = new TextView(this);
            try {
                double v = Double.parseDouble(priceStr);
                priceNum.setText(String.format(Locale.getDefault(), "%,d", Math.round(v))
                        .replace(',', ' ') + "₽");
            } catch (NumberFormatException e) {
                priceNum.setText(priceStr);
            }
            priceNum.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            priceNum.setTextSize(22);
            priceNum.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            priceRow.addView(priceNum);
            TextView pricePer = new TextView(this);
            pricePer.setText(" / " + period);
            pricePer.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
            pricePer.setTextSize(13);
            pricePer.setPadding(0, 0, 0, (int) (3 * dp));
            priceRow.addView(pricePer);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.topMargin = (int) (6 * dp);
            priceRow.setLayoutParams(plp);
            card.addView(priceRow);

            if (!tagline.isEmpty()) {
                TextView tg = new TextView(this);
                tg.setText(tagline);
                tg.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tg.setTextSize(13);
                card.addView(tg);
            }

            org.json.JSONArray feats = o.optJSONArray("features");
            if (feats != null) {
                for (int f = 0; f < feats.length(); f++) {
                    String ft = feats.optString(f, "");
                    if (ft.isEmpty()) continue;
                    View row = LayoutInflater.from(this)
                            .inflate(R.layout.item_store_feature, card, false);
                    ((TextView) row.findViewById(R.id.featureText)).setText(ft);
                    ((ImageView) row.findViewById(R.id.featureCheck)).setColorFilter(accent);
                    card.addView(row);
                }
            }

            MaterialButton buy = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonStyle);
            buy.setText(R.string.profile_buy);
            buy.setTextColor(onAccent);
            buy.setTextSize(14);
            buy.setBackgroundTintList(ColorStateList.valueOf(accent));
            buy.setCornerRadius((int) (12 * dp));
            buy.setOnClickListener(v -> checkoutPlan(id, name, priceStr));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (44 * dp));
            blp.topMargin = (int) (12 * dp);
            card.addView(buy, blp);

            box.addView(card);
        }
    }

    /** Чекаут подписки по plan_id. */
    private void checkoutPlan(String planId, String name, String priceStr) {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                org.json.JSONObject co = oauth.getAccountApi().billingCheckout(null, planId);
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
                ui.post(() -> ModernToast.show(this, getString(R.string.profile_checkout_fail)));
            }
        }).start();
    }

    /** «Ценность по моделям»: для выбранного объёма — сколько эффективных
     *  токенов даст каждая модель (множители из /v1/models, min = база). */
    private final java.util.Map<String, Double> multipliers = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Double> valueRows = new java.util.LinkedHashMap<>();
    private boolean valueLoaded = false;
    private boolean hasFreeModels = false;

    private void fetchMultipliersRaw() {
        new Thread(() -> {
            try {
                okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();
                String token = app.multiai.mobile.network.oauth.OAuthSession.get().getAccessToken();
                okhttp3.Response r = http.newCall(new okhttp3.Request.Builder()
                        .url("https://multiai.store/v1/models")
                        .header("Authorization", "Bearer " + token)
                        .build()).execute();
                org.json.JSONObject body = new org.json.JSONObject(r.body().string());
                r.close();
                org.json.JSONArray data = body.optJSONArray("data");
                if (data == null) return;
                synchronized (multipliers) {
                    multipliers.clear();
                    hasFreeModels = false;
                    for (int i = 0; i < data.length(); i++) {
                        org.json.JSONObject m = data.optJSONObject(i);
                        if (m == null) continue;
                        double k = m.optDouble("input_multiplier", 1);
                        boolean free = m.optBoolean("billing_free", false);
                        if (free) {
                            hasFreeModels = true;
                            continue;
                        }
                        if (k <= 0) continue;
                        multipliers.put(m.optString("id", "?"), k);
                    }
                }
                valueLoaded = true;
                runOnUiThread(this::refreshValueByModels);
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** Группа вендора для «Ценности по моделям»: GPT / Claude / Китай / Другие. */
    private static String vendorGroup(String id) {
        String s = id.toLowerCase();
        if (s.contains("gpt") || s.contains("o1") || s.contains("o3") || s.contains("o4")) return "GPT";
        if (s.contains("claude")) return "Claude";
        if (s.contains("deepseek") || s.contains("glm") || s.contains("qwen")
                || s.contains("mimo") || s.contains("nemotron") || s.contains("hy3")
                || s.contains("laguna") || s.contains("kimi") || s.contains("ernie")) return "Китай";
        if (s.contains("gemini")) return "Gemini";
        return "Другие";
    }

    private void refreshValueByModels() {
        LinearLayout box = findViewById(R.id.storeValueCard);
        View title = findViewById(R.id.storeValueTitle);
        if (box == null || title == null || !valueLoaded) return;

        double min = Double.MAX_VALUE;
        synchronized (multipliers) {
            for (double k : multipliers.values()) min = Math.min(min, k);
            if (multipliers.isEmpty() || min <= 0) return;
            valueRows.clear();
            for (java.util.Map.Entry<String, Double> e : multipliers.entrySet()) {
                String group = vendorGroup(e.getKey());
                if (!"Claude".equals(group) && !"GPT".equals(group) && !"Китай".equals(group)) {
                    continue;
                }
                double effectiveTokens = tokens * (min / e.getValue());
                valueRows.merge(group, effectiveTokens, Math::max);
            }
        }

        double claude = valueRows.getOrDefault("Claude", 0d);
        double gpt = valueRows.getOrDefault("GPT", 0d);
        double china = valueRows.getOrDefault("Китай", 0d);
        if (claude + gpt + china <= 0) return;

        title.setVisibility(View.VISIBLE);
        box.setVisibility(View.VISIBLE);
        bindValueSegment(R.id.barClaude, claude);
        bindValueSegment(R.id.barGpt, gpt);
        bindValueSegment(R.id.barCn, china);
        ((TextView) findViewById(R.id.valueClaude)).setText(
                getString(R.string.store_value_tokens, fmtTokens(Math.round(claude))));
        ((TextView) findViewById(R.id.valueGpt)).setText(
                getString(R.string.store_value_tokens, fmtTokens(Math.round(gpt))));
        ((TextView) findViewById(R.id.valueCn)).setText(
                getString(R.string.store_value_tokens, fmtTokens(Math.round(china))));
        bindFreeModelsRow(box);
    }

    private void bindFreeModelsRow(LinearLayout box) {
        View old = box.findViewWithTag("free_models");
        if (old != null) box.removeView(old);
        if (!hasFreeModels) return;
        LinearLayout row = new LinearLayout(this);
        row.setTag("free_models");
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(10 * getResources().getDisplayMetrics().density), 0, 0);
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.RECTANGLE);
        dotBg.setColor(0xFF2FBF8F);
        dot.setBackground(dotBg);
        row.addView(dot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        TextView label = new TextView(this);
        label.setText(R.string.store_free_models);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        label.setTextSize(12.5f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = dp(10);
        row.addView(label, labelLp);
        TextView value = new TextView(this);
        value.setText(R.string.store_value_free);
        value.setTextColor(ContextCompat.getColor(this, R.color.success));
        value.setTextSize(12.5f);
        row.addView(value);
        box.addView(row);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bindValueSegment(int viewId, double value) {
        View segment = findViewById(viewId);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) segment.getLayoutParams();
        lp.weight = (float) Math.max(0.001d, value);
        segment.setLayoutParams(lp);
    }

    /** ISO-период по-русски. */
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

    private void tintAccent() {
        ColorStateList accentList = ColorStateList.valueOf(accent);
        ColorStateList onAccentList = ColorStateList.valueOf(onAccent);

        storeSlider.setAccentColor(accent, onAccent);

        TextView badge = findViewById(R.id.badgeRecommended);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(100 * getResources().getDisplayMetrics().density);
        badgeBg.setColor(ColorUtils.setAlphaComponent(accent, 0x26));
        badge.setBackground(badgeBg);
        badge.setTextColor(accentList);

        for (int id : new int[]{R.id.btnBuyTokens, R.id.btnBuyPro, R.id.btnBuyPremium}) {
            MaterialButton b = findViewById(id);
            if (b != null) {
                b.setBackgroundTintList(accentList);
                b.setTextColor(onAccentList);
                app.multiai.mobile.ui.kit.MotionKit.attachPress(b, 0.97f);
            }
        }

        for (TextView chip : quickChips) {
            app.multiai.mobile.ui.kit.MotionKit.attachPress(chip, 0.94f);
        }
    }

    private void bindInputs() {
        storeSlider.setListener((value, fromUser) -> {
            if (fromUser) setTokens(value);
        });

        storeAmountInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (selfUpdate) return;
                String raw = s.toString().replaceAll("[^0-9]", "");
                if (raw.isEmpty()) return;
                try {
                    // Во время набора не трогаем поле: превью пересчитывается по ходу,
                    // а нормализация (точки + минимум 1M) — при уходе с поля.
                    setTokens(Long.parseLong(raw), false);
                } catch (NumberFormatException ignored) {
                }
            }
        });

        // Уход с поля: форматируем точками и возвращаем значение в диапазон 1M…10B.
        storeAmountInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) setTokens(tokens, true);
        });
        storeAmountInput.setOnEditorActionListener((v, actionId, event) -> {
            setTokens(tokens, true);
            storeAmountInput.clearFocus();
            return false;
        });

        long[] presets = {1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L, 10_000_000_000L};
        for (int i = 0; i < quickChips.length; i++) {
            final long value = presets[i];
            quickChips[i].setOnClickListener(v -> setTokens(value));
        }

        findViewById(R.id.btnBuyTokens).setOnClickListener(v -> checkoutTokens());
        View pro = findViewById(R.id.btnBuyPro);
        View premium = findViewById(R.id.btnBuyPremium);
        if (pro != null) pro.setVisibility(View.GONE);
        if (premium != null) premium.setVisibility(View.GONE);
    }

    /** Покупка токенов: POST /billing/checkout → одноразовая ссылка. */
    private void checkoutTokens() {
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try {
                org.json.JSONObject co = oauth.getAccountApi().billingCheckout(tokens, null);
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
                        getString(R.string.profile_checkout_fail)));
            }
        }).start();
    }

    /** 1 000 000 → «1M»; 3 460 000 → «3.46M». */
    private String fmtTokens(long tokens) {
        if (tokens >= 1_000_000_000L) return compactNumber(tokens / 1e9, "B");
        if (tokens >= 1_000_000L) return compactNumber(tokens / 1e6, "M");
        if (tokens >= 1_000L) return compactNumber(tokens / 1e3, "K");
        return String.valueOf(tokens);
    }

    private String compactNumber(double value, String suffix) {
        return String.format(Locale.US, "%.2f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "") + suffix;
    }

    private String formatRubles(double value) {
        return String.format(Locale.US, "%,d", Math.round(value))
                .replace(',', ' ') + "₽";
    }

    /** Единая точка обновления: чипы и поле; цена — после token_purchase с сервера. */
    private void setTokens(long value) {
        setTokens(value, true);
    }

    private void setTokens(long value, boolean rewriteInput) {
        tokens = Math.max(MIN_TOKENS, Math.min(MAX_TOKENS, snap(value)));

        selfUpdate = true;
        storeSlider.setTokens(tokens);
        if (rewriteInput) {
            String raw = String.format(Locale.US, "%d", tokens);
            StringBuilder dotted = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                if (i > 0 && (raw.length() - i) % 3 == 0) dotted.append('.');
                dotted.append(raw.charAt(i));
            }
            storeAmountInput.setText(dotted);
            storeAmountInput.setSelection(dotted.length());
        }
        selfUpdate = false;

        // Кнопка с живым объёмом: «Купить 3.46M токенов»
        MaterialButton buy = findViewById(R.id.btnBuyTokens);
        if (buy != null) {
            buy.setText(getString(R.string.store_buy_tokens_amount, fmtTokens(tokens)));
        }

        // «Ценность по моделям» пересчитывается под новый объём.
        if (valueLoaded) refreshValueByModels();

        // Превью цены: tokens / 1M × base_price_per_1m (ТЗ §5.1). Итоговую
        // цену подтверждает страница оплаты (сходится ±1%).
        if (pricePer1m > 0) {
            double price = tokens / 1_000_000d * pricePer1m;
            textStorePrice.setText(formatRubles(price));
            textStorePerUnit.setText(formatRubles(pricePer1m) + " / 1M токенов");
        } else {
            textStorePrice.setText("—");
            textStorePerUnit.setText(getString(R.string.store_per_unit, "—"));
        }

        restyleChips();
    }

    private void restyleChips() {
        float dp = getResources().getDisplayMetrics().density;
        long[] presets = {1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L, 10_000_000_000L};
        for (int i = 0; i < quickChips.length; i++) {
            boolean active = tokens == presets[i];
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(9 * dp);
            bg.setColor(active
                    ? accent
                    : ContextCompat.getColor(this, R.color.surface_tonal));
            bg.setStroke((int) dp, active
                    ? accent
                    : ContextCompat.getColor(this, R.color.stroke));
            quickChips[i].setBackground(bg);
            quickChips[i].setTextColor(active
                    ? onAccent
                    : ContextCompat.getColor(this, R.color.text_secondary));
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) quickChips[i].getLayoutParams();
            lp.rightMargin = i < quickChips.length - 1 ? (int) (4 * dp) : 0;
            quickChips[i].setLayoutParams(lp);
        }
    }

    private static long snap(long v) {
        if (v <= 0) return MIN_TOKENS;
        int digits = (int) Math.floor(Math.log10(v)) - 1; // 2 значащие цифры
        long pow = (long) Math.pow(10, Math.max(0, digits));
        long sig = Math.round(v / (double) pow);
        if (sig <= 10) sig = Math.max(1, Math.min(10, sig));
        else if (sig <= 25) sig = (sig + 4) / 5 * 5;
        else if (sig <= 50) sig = (sig + 4) / 5 * 5;
        else sig = (sig + 24) / 50 * 50;
        return sig * pow;
    }
}
