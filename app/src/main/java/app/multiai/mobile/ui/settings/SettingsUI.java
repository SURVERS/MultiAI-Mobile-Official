package app.multiai.mobile.ui.settings;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;

import app.multiai.mobile.ui.kit.MotionKit;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.SecurityHelper;

/**
 * Helper-билдеры для ChatGPT-подобных экранов настроек.
 * Чтобы не копипастить row/card/divider в каждом SettingsActivity.
 *
 * Все цвета тянутся из ресурсов (@color/*), поэтому экраны настроек
 * автоматически следуют системной теме (light/dark) — см. values/ и values-night/.
 */
public final class SettingsUI {

    public static final int RADIUS_CARD = 24;
    public static final int GAP_SECTION = 28;
    public static final int SCREEN_PADDING = 16;

    private SettingsUI() {}

    // ─────────── theme-aware color resolvers ───────────
    public static int colorBg(Context c)       { return ContextCompat.getColor(c, R.color.bg); }
    public static int colorCard(Context c)     { return ContextCompat.getColor(c, R.color.surface_card); }
    public static int colorRipple(Context c)   { return ContextCompat.getColor(c, R.color.ripple); }
    public static int colorDivider(Context c)  { return ContextCompat.getColor(c, R.color.divider); }
    public static int colorText(Context c)     { return ContextCompat.getColor(c, R.color.text_primary); }
    public static int colorSubtext(Context c)  { return ContextCompat.getColor(c, R.color.text_secondary); }
    public static int colorHint(Context c)     { return ContextCompat.getColor(c, R.color.text_hint); }
    public static int colorDisabledText(Context c) { return ContextCompat.getColor(c, R.color.text_disabled); }
    public static int colorIcon(Context c)     { return ContextCompat.getColor(c, R.color.icon); }
    public static int colorMutedIcon(Context c) { return ContextCompat.getColor(c, R.color.icon_muted); }
    public static int colorStroke(Context c)   { return ContextCompat.getColor(c, R.color.stroke); }
    public static int colorShadowAmbient(Context c) { return ContextCompat.getColor(c, R.color.shadow_ambient); }
    public static int colorShadowSpot(Context c) { return ContextCompat.getColor(c, R.color.shadow_spot); }
    public static int colorDanger(Context c)   { return ContextCompat.getColor(c, R.color.error); }
    public static int colorAccent(Context c)   { return AccentColor.resolve(c); }

    // ─────────── screen scaffold ───────────

    /**
     * Базовый scaffold: toolbar (← title [✓]) + scrollable column.
     * Возвращает корневой LinearLayout в который нужно добавлять контент.
     */
    public static class Scaffold {
        public final ScrollView scrollRoot;
        public final LinearLayout content;
        public final ImageView backButton;
        public final ImageView saveButton;
        public final TextView titleView;

        Scaffold(ScrollView scrollRoot, LinearLayout content,
                 ImageView backButton, ImageView saveButton, TextView titleView) {
            this.scrollRoot = scrollRoot;
            this.content = content;
            this.backButton = backButton;
            this.saveButton = saveButton;
            this.titleView = titleView;
        }
    }

    public static Scaffold createScaffold(Activity act, String title, boolean showSaveButton) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(colorBg(act));
        // Apply system window insets to the root view: top padding pushes
        // toolbar below status bar, bottom padding avoids nav bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(wrap,
                (view, insets) -> {
                    int st = insets.getSystemWindowInsetTop();
                    int sb = insets.getSystemWindowInsetBottom();
                    int sl = insets.getSystemWindowInsetLeft();
                    int sr = insets.getSystemWindowInsetRight();
                    view.setPadding(sl, st, sr, sb);
                    return androidx.core.view.WindowInsetsCompat.CONSUMED;
                });

        // Toolbar
        FrameLayout bar = new FrameLayout(act);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 60)));

        // Back button — круглая с мягким stroke
        ImageView back = new ImageView(act);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setColorFilter(colorText(act));
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        GradientDrawable backBg = new GradientDrawable();
        backBg.setShape(GradientDrawable.OVAL);
        backBg.setColor(colorSurfaceTonal(act));
        back.setBackground(backBg);
        back.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(act, 42), dp(act, 42));
        backLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        backLp.leftMargin = dp(act, 16);
        back.setLayoutParams(backLp);
        back.setOnClickListener(v -> act.finish());
        // Spring press feedback on the round back button.
        attachPressAnimation(back, 0.9f);
        bar.addView(back);

        // Title
        TextView titleTv = new TextView(act);
        titleTv.setText(title);
        titleTv.setTextColor(colorText(act));
        titleTv.setTextSize(18);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setLetterSpacing(-0.01f);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.gravity = Gravity.CENTER;
        titleTv.setLayoutParams(titleLp);
        bar.addView(titleTv);

        // Save button (optional)
        ImageView save = null;
        if (showSaveButton) {
            save = new ImageView(act);
            save.setImageResource(R.drawable.ic_check);
            save.setColorFilter(colorText(act));
            save.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            GradientDrawable saveBg = new GradientDrawable();
            saveBg.setShape(GradientDrawable.OVAL);
            saveBg.setColor(colorSurfaceTonal(act));
            save.setBackground(saveBg);
            save.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
            FrameLayout.LayoutParams saveLp = new FrameLayout.LayoutParams(dp(act, 42), dp(act, 42));
            saveLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            saveLp.rightMargin = dp(act, 16);
            save.setLayoutParams(saveLp);
            // Spring press feedback on the round save button (click set by caller via Scaffold).
            attachPressAnimation(save, 0.9f);
            bar.addView(save);
        }

        wrap.addView(bar);

        // Scrollable content
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        sv.setLayoutParams(svLp);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(act, 4), 0, dp(act, 40));
        sv.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wrap.addView(sv);
        act.setContentView(wrap);
        // Let our inset listener apply padding; decor fits must be false
        // so the system dispatches real insets to our view.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(act.getWindow(), false);
        act.getWindow().setStatusBarColor(colorBg(act));
        act.getWindow().setNavigationBarColor(colorBg(act));
        // Request insets dispatch so our listener receives real values.
        androidx.core.view.ViewCompat.requestApplyInsets(wrap);
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        SecurityHelper.applySecure(act);

        // Staggered fade-in for the content cards — each direct child of the scroll
        // column slides up 20dp and fades in with a 50ms step. The first run happens
        // on the next frame (after measure/layout) so child views actually exist.
        playContentEnterAnimation(col);

        return new Scaffold(sv, col, back, save, titleTv);
    }

    // ─────────── sections ───────────

    public static TextView sectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(colorSubtext(ctx));
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        tv.setAllCaps(false);
        tv.setLetterSpacing(0.02f);
        tv.setPadding(dp(ctx, 20), dp(ctx, 22), dp(ctx, 16), dp(ctx, 8));
        return tv;
    }

    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorCard(ctx));
        bg.setCornerRadius(dp(ctx, RADIUS_CARD));
        card.setBackground(bg);
        card.setElevation(dp(ctx, 6));
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(ctx, RADIUS_CARD));
            }
        });
        card.setClipToOutline(true);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            card.setOutlineAmbientShadowColor(colorShadowAmbient(ctx));
            card.setOutlineSpotShadowColor(colorShadowSpot(ctx));
        }
        // Card container is NOT interactive — only individual rows handle clicks,
        // so the ripple/press state never spans the whole card.
        card.setClickable(false);
        card.setFocusable(false);
        card.setLongClickable(false);
        card.setAddStatesFromChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, SCREEN_PADDING);
        lp.rightMargin = dp(ctx, SCREEN_PADDING);
        card.setLayoutParams(lp);
        return card;
    }

    public static View divider(Context ctx) {
        // Ультратонкий divider с отступом слева равным началу текста
        // (18 row padding + 36 icon wrap + 14 gap = 68dp)
        View div = new View(ctx);
        div.setBackgroundColor(colorDivider(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(ctx, 68);
        div.setLayoutParams(lp);
        return div;
    }

    public static View spacer(Context ctx, int heightDp) {
        View s = new View(ctx);
        s.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
        return s;
    }

    /** 24dp gap между карточками-группами (по ТЗ) */
    public static View sectionGap(Context ctx) {
        return spacer(ctx, GAP_SECTION);
    }

    // ─────────── rows ───────────

    /**
     * Строка: иконка + title + (subtitle?) + chevron>
     */
    public static View row(Activity act, int iconRes, String title, String subtitle,
                           Runnable onClick) {
        return buildRow(act, iconRes, title, subtitle, true, false, null, onClick);
    }

    /** Строка без chevron (для danger-кнопок в конце группы или простых rows) */
    public static View rowNoChevron(Activity act, int iconRes, String title, String subtitle,
                                    Runnable onClick) {
        return buildRow(act, iconRes, title, subtitle, false, false, null, onClick);
    }

    /** Строка с трейлингом (например, цветной кружок акцента) */
    public static View rowWithTrailing(Activity act, int iconRes, String title, String subtitle,
                                       View trailing, Runnable onClick) {
        return buildRow(act, iconRes, title, subtitle, true, false, trailing, onClick);
    }

    /** Toggle-строка */
    public static View rowToggle(Activity act, int iconRes, String title, String subtitle,
                                 boolean checked, ToggleListener listener) {
        ChatGptSwitchView sw = createChatGptSwitch(act, title, checked);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                dp(act, 64), dp(act, 48));
        sw.setLayoutParams(swLp);

        View row = buildRow(act, iconRes, title, subtitle, false, false, sw,
                () -> sw.setChecked(!sw.isChecked()));
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            if (listener != null) listener.onChanged(isChecked);
            // Subtle row highlight: briefly flash to surface_tonal then back, so
            // the toggle feels acknowledged. Does not replace the existing background.
            flashRowHighlight(row, act);
        });
        return row;
    }

    /** Deterministic pill switch matching ChatGPT without theme-dependent drawables. */
    public static ChatGptSwitchView createChatGptSwitch(
            Context ctx, CharSequence label, boolean checked) {
        ChatGptSwitchView sw = new ChatGptSwitchView(ctx);
        sw.setContentDescription(label);
        sw.setChecked(checked);
        return sw;
    }

    public interface ToggleListener {
        void onChanged(boolean checked);
    }

    /** Danger-кнопка (красный текст) во всю ширину карточки */
    public static View rowDanger(Activity act, String title, Runnable onClick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(act, 18), dp(act, 16), dp(act, 18), dp(act, 16));
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue tv = new TypedValue();
        act.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(act.getDrawable(tv.resourceId));
        row.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        // Gentle press feedback consistent with the regular rows.
        attachPressAnimation(row, 0.98f);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(colorDanger(act));
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        row.addView(t);
        return row;
    }

    private static View buildRow(Activity act, int iconRes, String title, String subtitle,
                                 boolean showChevron, boolean ignored, View trailing,
                                 Runnable onClick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 18), dp(act, 16), dp(act, 18), dp(act, 16));
        row.setClickable(true);
        row.setFocusable(true);
        // Keep this row's press/ripple state local — do not propagate to the card.
        row.setAddStatesFromChildren(false);
        row.setDuplicateParentStateEnabled(false);
        // Theme-aware ripple masked to this row's bounds.
        row.setBackground(createRipple(act));
        row.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        // Gentle press feedback: scale down on ACTION_DOWN, spring back on UP/CANCEL.
        attachPressAnimation(row, 0.98f);

        if (iconRes != 0) {
            // Иконка в мягком tonal-кружке для элитарного вида
            FrameLayout iconWrap = new FrameLayout(act);
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setShape(GradientDrawable.OVAL);
            iconBg.setColor(colorSurfaceTonal(act));
            iconWrap.setBackground(iconBg);
            LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(dp(act, 36), dp(act, 36));
            iconWrap.setLayoutParams(wrapLp);
            ImageView icon = new ImageView(act);
            icon.setImageResource(iconRes);
            icon.setColorFilter(colorIcon(act));
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(act, 20), dp(act, 20));
            iconLp.gravity = Gravity.CENTER;
            icon.setLayoutParams(iconLp);
            iconWrap.addView(icon);
            row.addView(iconWrap);
        }

        LinearLayout textCol = new LinearLayout(act);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (iconRes != 0) tlp.leftMargin = dp(act, 14);
        textCol.setLayoutParams(tlp);

        TextView titleTv = new TextView(act);
        titleTv.setText(title);
        titleTv.setTextColor(colorText(act));
        titleTv.setTextSize(16);
        titleTv.setLetterSpacing(-0.01f);
        textCol.addView(titleTv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(act);
            subTv.setText(subtitle);
            subTv.setTextColor(colorSubtext(act));
            subTv.setTextSize(13);
            subTv.setPadding(0, dp(act, 2), 0, 0);
            textCol.addView(subTv);
        }

        row.addView(textCol);

        if (trailing != null) {
            row.addView(trailing);
        } else if (showChevron) {
            ImageView chevron = new ImageView(act);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setColorFilter(colorSubtext(act));
            chevron.setAlpha(0.6f);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(act, 16), dp(act, 16));
            cLp.leftMargin = dp(act, 8);
            chevron.setLayoutParams(cLp);
            row.addView(chevron);
        }

        return row;
    }

    /**
     * Theme-aware ripple clipped to the row bounds.
     * A solid mask ColorDrawable clips the ripple strictly to the row bounds,
     * so the press highlight never spans the whole card.
     */
    private static android.graphics.drawable.Drawable createRipple(android.content.Context ctx) {
        android.content.res.ColorStateList csl = new android.content.res.ColorStateList(
                new int[][]{new int[]{}}, new int[]{colorRipple(ctx)});
        android.graphics.drawable.ColorDrawable mask =
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE);
        return new android.graphics.drawable.RippleDrawable(csl, null, mask);
    }

    // ─────────── utils ───────────

    public static int colorSurfaceTonal(Context c) { return ContextCompat.getColor(c, R.color.surface_tonal); }

    /** Restrained shared press feedback; touch is not consumed. */
    public static void attachPressAnimation(View view, float pressed) {
        MotionKit.attachPress(view, pressed);
    }

    /**
     * Staggered fade-in for the content column. Each direct child of {@code col}
     * (cards, labels, gaps…) starts at alpha 0 + 20dp translationY and animates to
     * alpha 1 / 0 over 300ms with a 50ms step and AccelerateDecelerateInterpolator.
     * The animation is scheduled on the next frame so children added later in the
     * activity's onCreate are already present.
     */
    private static void playContentEnterAnimation(final LinearLayout col) {
        col.post(() -> {
            final int count = col.getChildCount();
            final float ty = dp(col.getContext(), 28);
            for (int i = 0; i < count; i++) {
                View child = col.getChildAt(i);
                child.setAlpha(0f);
                child.setTranslationY(ty);
            }
            for (int i = 0; i < count; i++) {
                View child = col.getChildAt(i);
                child.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setStartDelay(i * 60L)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();
            }
        });
    }

    /**
     * Brief highlight flash on a toggle row when the switch changes. Cross-fades a
     * surface_tonal tint over the existing background for ~180ms then restores it.
     * Uses a foreground overlay so the row's ripple background is untouched.
     */
    private static void flashRowHighlight(final View row, Context ctx) {
        final int tonal = colorSurfaceTonal(ctx);
        final android.graphics.drawable.GradientDrawable flash =
                new android.graphics.drawable.GradientDrawable();
        flash.setColor(tonal);
        flash.setAlpha(0);
        row.setForeground(flash);

        final android.animation.ValueAnimator a =
                android.animation.ValueAnimator.ofInt(0, 70, 0);
        a.setDuration(280);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(anim -> {
            int val = (int) anim.getAnimatedValue();
            flash.setAlpha(val);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                row.setForeground(null);
            }
        });
        a.start();
    }

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
