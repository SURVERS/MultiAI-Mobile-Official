package app.multiai.mobile.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.DropdownMenu;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AppSettings;

/**
 * Персонализация — 1-в-1 копия ChatGPT.
 *  • Базовый стиль и тон (dropdown справа)
 *  • Характеристики — 4 карточки + кнопка "Добавить характеристики" → TraitsActivity
 *  • Пользовательские инструкции (textarea)
 *  • Расширенные настройки → Веб-поиск toggle
 */
public class PersonalizationActivity extends AppCompatActivity {

    private AppSettings s;
    private TextView toneSub;
    private EditText instructionsInput;
    private LinearLayout advancedCard;
    private TextView advancedToggle;
    private boolean advancedOpen = false;
    private LinearLayout traitsCard;
    private TextView traitsLabel;

    /** Tones: [key, titleResId, descResId]. Resolve with resolveTones(ctx). */
    public static final Object[][] TONES = {
            {"default",      R.string.scr_tone_default_title,      R.string.scr_tone_default_desc},
            {"professional", R.string.scr_tone_professional_title, R.string.scr_tone_professional_desc},
            {"friendly",     R.string.scr_tone_friendly_title,     R.string.scr_tone_friendly_desc},
            {"candid",       R.string.scr_tone_candid_title,       R.string.scr_tone_candid_desc},
            {"whimsical",    R.string.scr_tone_whimsical_title,    R.string.scr_tone_whimsical_desc},
            {"efficient",    R.string.scr_tone_efficient_title,    R.string.scr_tone_efficient_desc},
            {"sarcastic",    R.string.scr_tone_sarcastic_title,    R.string.scr_tone_sarcastic_desc},
    };

    /** Resolve TONES into localized [key, title, desc] tuples. */
    public static String[][] resolveTones(Context ctx) {
        String[][] out = new String[TONES.length][];
        for (int i = 0; i < TONES.length; i++) {
            out[i] = new String[]{
                    (String) TONES[i][0],
                    ctx.getString((Integer) TONES[i][1]),
                    ctx.getString((Integer) TONES[i][2])
            };
        }
        return out;
    }

    /**
     * Traits: [trait_key, moreTitleRes, moreDescRes, lessTitleRes, lessDescRes].
     * Resolve with resolveTraits(ctx).
     */
    public static final Object[][] TRAITS = {
            {"warmth",     R.string.scr_trait_warmth_more_t,     R.string.scr_trait_warmth_more_d,
                           R.string.scr_trait_warmth_less_t,     R.string.scr_trait_warmth_less_d},
            {"excitement", R.string.scr_trait_excitement_more_t, R.string.scr_trait_excitement_more_d,
                           R.string.scr_trait_excitement_less_t, R.string.scr_trait_excitement_less_d},
            {"headers",    R.string.scr_trait_headers_more_t,    R.string.scr_trait_headers_more_d,
                           R.string.scr_trait_headers_less_t,    R.string.scr_trait_headers_less_d},
            {"emoji",      R.string.scr_trait_emoji_more_t,      R.string.scr_trait_emoji_more_d,
                           R.string.scr_trait_emoji_less_t,      R.string.scr_trait_emoji_less_d},
    };

    /** Resolve TRAITS into localized [key, more_title, more_desc, less_title, less_desc] tuples. */
    public static String[][] resolveTraits(Context ctx) {
        String[][] out = new String[TRAITS.length][];
        for (int i = 0; i < TRAITS.length; i++) {
            out[i] = new String[]{
                    (String) TRAITS[i][0],
                    ctx.getString((Integer) TRAITS[i][1]),
                    ctx.getString((Integer) TRAITS[i][2]),
                    ctx.getString((Integer) TRAITS[i][3]),
                    ctx.getString((Integer) TRAITS[i][4])
            };
        }
        return out;
    }

    /** Short trait names for TraitsActivity: [key, shortNameRes]. Resolve with resolveTraitShortNames(ctx). */
    public static final Object[][] TRAIT_SHORT_NAMES = {
            {"warmth",     R.string.scr_trait_warmth_short},
            {"excitement", R.string.scr_trait_excitement_short},
            {"headers",    R.string.scr_trait_headers_short},
            {"emoji",      R.string.scr_trait_emoji_short},
    };

    /** Resolve TRAIT_SHORT_NAMES into localized [key, shortName] tuples. */
    public static String[][] resolveTraitShortNames(Context ctx) {
        String[][] out = new String[TRAIT_SHORT_NAMES.length][];
        for (int i = 0; i < TRAIT_SHORT_NAMES.length; i++) {
            out[i] = new String[]{
                    (String) TRAIT_SHORT_NAMES[i][0],
                    ctx.getString((Integer) TRAIT_SHORT_NAMES[i][1])
            };
        }
        return out;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        s = new AppSettings(this);

        SettingsUI.Scaffold sc = SettingsUI.createScaffold(this, getString(R.string.scr_personalization_title), true);
        if (sc.saveButton != null) sc.saveButton.setOnClickListener(v -> {
            saveAll();
            finish();
        });

        LinearLayout root = sc.content;

        // ── Базовый стиль и тон ──
        root.addView(buildToneCard());

        TextView toneDesc = new TextView(this);
        toneDesc.setText(getString(R.string.scr_tone_desc));
        toneDesc.setTextColor(SettingsUI.colorSubtext(this));
        toneDesc.setTextSize(13);
        toneDesc.setLineSpacing(SettingsUI.dp(this, 2), 1f);
        LinearLayout.LayoutParams tdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tdLp.leftMargin = SettingsUI.dp(this, 20);
        tdLp.rightMargin = SettingsUI.dp(this, 20);
        tdLp.topMargin = SettingsUI.dp(this, 12);
        toneDesc.setLayoutParams(tdLp);
        root.addView(toneDesc);

        // ── Характеристики ──
        traitsLabel = SettingsUI.sectionLabel(this, getString(R.string.scr_traits_section));
        root.addView(traitsLabel);
        traitsCard = new LinearLayout(this);
        traitsCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        traitsCard.setLayoutParams(tcLp);
        root.addView(traitsCard);
        rebuildTraitCards();

        // ── Добавить характеристики ──
        root.addView(SettingsUI.spacer(this, 14));
        LinearLayout addCard = SettingsUI.card(this);
        addCard.addView(buildAddRow());
        root.addView(addCard);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.scr_traits_hint));
        hint.setTextColor(SettingsUI.colorSubtext(this));
        hint.setTextSize(13);
        hint.setLineSpacing(SettingsUI.dp(this, 2), 1f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.leftMargin = SettingsUI.dp(this, 20);
        hLp.rightMargin = SettingsUI.dp(this, 20);
        hLp.topMargin = SettingsUI.dp(this, 10);
        hint.setLayoutParams(hLp);
        root.addView(hint);

        // ── Пользовательские инструкции ──
        TextView usL = SettingsUI.sectionLabel(this, getString(R.string.scr_custom_instructions));
        root.addView(usL);

        instructionsInput = new EditText(this);
        instructionsInput.setText(s.getPersonalizationInstructions());
        instructionsInput.setHint(getString(R.string.scr_custom_instructions_hint));
        instructionsInput.setHintTextColor(SettingsUI.colorSubtext(this));
        instructionsInput.setTextColor(SettingsUI.colorText(this));
        instructionsInput.setTextSize(15);
        instructionsInput.setGravity(Gravity.TOP | Gravity.START);
        instructionsInput.setMinLines(3);
        instructionsInput.setLineSpacing(SettingsUI.dp(this, 3), 1f);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setColor(SettingsUI.colorCard(this));
        inBg.setCornerRadius(SettingsUI.dp(this, 14));
        instructionsInput.setBackground(inBg);
        instructionsInput.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
        LinearLayout.LayoutParams iiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iiLp.leftMargin = SettingsUI.dp(this, 16);
        iiLp.rightMargin = SettingsUI.dp(this, 16);
        instructionsInput.setLayoutParams(iiLp);
        root.addView(instructionsInput);

        // ── Расширенные настройки ──
        root.addView(SettingsUI.spacer(this, 24));
        root.addView(buildAdvancedHeader());
        advancedCard = SettingsUI.card(this);
        advancedCard.setVisibility(View.GONE);
        advancedCard.setAlpha(0f);
        advancedCard.addView(SettingsUI.rowToggle(this, R.drawable.ic_magnify,
                getString(R.string.scr_web_search), getString(R.string.scr_web_search_desc),
                s.isWebSearchEnabled(),
                checked -> s.setWebSearchEnabled(checked)));
        root.addView(advancedCard);
    }

    // ─────────── TONE CARD ───────────

    private View buildToneCard() {
        LinearLayout card = SettingsUI.card(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(getDrawable(tv.resourceId));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        col.setLayoutParams(colLp);

        TextView title = new TextView(this);
        title.setText(getString(R.string.scr_tone_card_title));
        title.setTextColor(SettingsUI.colorText(this));
        title.setTextSize(16);
        col.addView(title);

        toneSub = new TextView(this);
        toneSub.setText(findToneById(s.getPersonalizationTone())[1]);
        toneSub.setTextColor(SettingsUI.colorSubtext(this));
        toneSub.setTextSize(13);
        col.addView(toneSub);
        row.addView(col);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_down);
        chev.setColorFilter(SettingsUI.colorSubtext(this));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 18), SettingsUI.dp(this, 18));
        chLp.leftMargin = SettingsUI.dp(this, 8);
        chev.setLayoutParams(chLp);
        row.addView(chev);

        row.setOnClickListener(v -> {
            String[][] tones = resolveTones(this);
            DropdownMenu.Item[] items = new DropdownMenu.Item[tones.length];
            for (int i = 0; i < tones.length; i++) {
                items[i] = new DropdownMenu.Item(tones[i][0], tones[i][1], tones[i][2]);
            }
            DropdownMenu.show(row, items, s.getPersonalizationTone(), id -> {
                s.setPersonalizationTone(id);
                toneSub.setText(findToneById(id)[1]);
            });
        });

        card.addView(row);
        return card;
    }

    private String[] findToneById(String id) {
        String[][] tones = resolveTones(this);
        for (String[] t : tones) if (t[0].equals(id)) return t;
        return tones[0];
    }

    // ─────────── TRAIT CARDS (4 отдельные) ───────────

    private void rebuildTraitCards() {
        traitsCard.removeAllViews();

        // Показываем ТОЛЬКО выбранные (не default) — как в ChatGPT
        int shown = 0;
        String[][] traits = resolveTraits(this);
        for (String[] trait : traits) {
            String level = s.getTraitLevel(trait[0]);
            if ("default".equals(level)) continue;
            if (shown > 0) traitsCard.addView(SettingsUI.spacer(this, 8));
            String title = "more".equals(level) ? trait[1] : trait[3];
            String desc  = "more".equals(level) ? trait[2] : trait[4];
            traitsCard.addView(buildTraitCard(trait[0], title, desc));
            shown++;
        }

        // Кастомные traits (они всегда показываются — это юзер их добавил)
        String custom = s.getPersonalizationCustomTraits();
        if (!custom.isEmpty()) {
            for (String c : custom.split(",")) {
                if (c.trim().isEmpty()) continue;
                if (shown > 0) traitsCard.addView(SettingsUI.spacer(this, 8));
                traitsCard.addView(buildTraitCard("custom:" + c.trim(),
                        c.trim(), getString(R.string.scr_custom_trait)));
                shown++;
            }
        }

        // Если ничего не выбрано — скрываем заголовок "Характеристики" и не показываем секцию
        // (наружный код сам решает показать ли label, здесь — просто пустой список)
        traitsCard.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
        if (traitsLabel != null) {
            traitsLabel.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private View buildTraitCard(String key, String title, String desc) {
        LinearLayout card = SettingsUI.card(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(getDrawable(tv.resourceId));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        col.setLayoutParams(colLp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(SettingsUI.colorText(this));
        t.setTextSize(16);
        col.addView(t);

        TextView sub = new TextView(this);
        sub.setText(desc);
        sub.setTextColor(SettingsUI.colorSubtext(this));
        sub.setTextSize(13);
        col.addView(sub);
        row.addView(col);

        // Тап ведёт на экран Характеристик
        row.setOnClickListener(v ->
                startActivity(new Intent(this, TraitsActivity.class)));

        card.addView(row);
        return card;
    }

    private String levelLabel(String level) {
        switch (level) {
            case "more": return getString(R.string.scr_level_more);
            case "less": return getString(R.string.scr_level_less);
            default: return getString(R.string.scr_level_default);
        }
    }

    // ─────────── ADD ROW ───────────

    private View buildAddRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(getDrawable(tv.resourceId));
        row.setOnClickListener(v ->
                startActivity(new Intent(this, TraitsActivity.class)));

        TextView t = new TextView(this);
        t.setText(getString(R.string.scr_add_traits));
        t.setTextColor(SettingsUI.colorText(this));
        t.setTextSize(16);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        t.setLayoutParams(tLp);
        row.addView(t);
        return row;
    }

    // ─────────── ADVANCED HEADER ───────────

    private View buildAdvancedHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(SettingsUI.dp(this, 20), SettingsUI.dp(this, 8),
                SettingsUI.dp(this, 20), SettingsUI.dp(this, 8));
        h.setClickable(true);
        h.setFocusable(true);

        advancedToggle = new TextView(this);
        advancedToggle.setText(getString(R.string.scr_advanced_settings));
        advancedToggle.setTextColor(SettingsUI.colorSubtext(this));
        advancedToggle.setTextSize(13);
        h.addView(advancedToggle);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_down);
        chev.setColorFilter(SettingsUI.colorSubtext(this));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 14));
        chLp.leftMargin = SettingsUI.dp(this, 6);
        chev.setLayoutParams(chLp);
        chev.setRotation(advancedOpen ? 180f : 0f);
        h.addView(chev);

        h.setOnClickListener(v -> {
            advancedOpen = !advancedOpen;
            if (advancedOpen) expandAdvanced();
            else collapseAdvanced();
            chev.animate().rotation(advancedOpen ? 180f : 0f)
                    .setDuration(260)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0, 0, 1))
                    .start();
        });
        return h;
    }

    /** Плавное раскрытие с анимацией высоты + fade-in */
    private void expandAdvanced() {
        // Делаем видимой с начальной нулевой высотой
        advancedCard.setVisibility(View.VISIBLE);
        advancedCard.setAlpha(0f);

        // Измеряем целевую высоту (wrap_content → конкретный px)
        int widthMeasure = View.MeasureSpec.makeMeasureSpec(
                ((View) advancedCard.getParent()).getWidth(), View.MeasureSpec.EXACTLY);
        int heightMeasure = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        advancedCard.measure(widthMeasure, heightMeasure);
        final int targetH = advancedCard.getMeasuredHeight();

        ViewGroup.LayoutParams lp = advancedCard.getLayoutParams();
        lp.height = 0;
        advancedCard.setLayoutParams(lp);
        advancedCard.setTranslationY(-SettingsUI.dp(this, 8));

        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(0, targetH);
        va.setDuration(320);
        va.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0, 0, 1));
        va.addUpdateListener(anim -> {
            ViewGroup.LayoutParams p = advancedCard.getLayoutParams();
            p.height = (int) anim.getAnimatedValue();
            advancedCard.setLayoutParams(p);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                // После анимации возвращаем wrap_content чтобы контент мог расти (например при изменении toggle)
                ViewGroup.LayoutParams p = advancedCard.getLayoutParams();
                p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                advancedCard.setLayoutParams(p);
            }
        });
        va.start();

        advancedCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0, 0, 1))
                .start();
    }

    /** Плавное сворачивание с анимацией высоты + fade-out */
    private void collapseAdvanced() {
        final int startH = advancedCard.getHeight();

        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(startH, 0);
        va.setDuration(260);
        va.setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0, 1, 1));
        va.addUpdateListener(anim -> {
            ViewGroup.LayoutParams p = advancedCard.getLayoutParams();
            p.height = (int) anim.getAnimatedValue();
            advancedCard.setLayoutParams(p);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                advancedCard.setVisibility(View.GONE);
                // Возвращаем wrap_content на будущее
                ViewGroup.LayoutParams p = advancedCard.getLayoutParams();
                p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                advancedCard.setLayoutParams(p);
            }
        });
        va.start();

        advancedCard.animate()
                .alpha(0f)
                .translationY(-SettingsUI.dp(this, 8))
                .setDuration(200)
                .setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0, 1, 1))
                .start();
    }

    private void saveAll() {
        s.setPersonalizationInstructions(instructionsInput.getText().toString());
        ModernToast.show(this, getString(R.string.scr_saved));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Обновляем traits (могли быть изменения на подэкране)
        if (traitsCard != null) rebuildTraitCards();
    }
}
