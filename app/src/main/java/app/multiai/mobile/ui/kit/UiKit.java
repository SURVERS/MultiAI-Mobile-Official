package app.multiai.mobile.ui.kit;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;

/**
 * Central design tokens + tiny view-builder helpers shared across the whole app.
 *
 * Replaces the previously duplicated {@code dp}/{@code txt}/{@code pill}/{@code spacer}
 * helpers that were copy-pasted across OnboardingActivity, TerminalActivity,
 * MessagesAdapter, ChatModeActivity and others.
 *
 * All colours come exclusively from {@code @color/*} resources, never hardcoded.
 */
public final class UiKit {

    private UiKit() {}

    // ── Design tokens ──────────────────────────────────────────────────────
    /** Corner radius for big surfaces: cards, sheets, dialogs. */
    public static final int RADIUS_XL = 24;
    /** Corner radius for standard cards and bubbles. */
    public static final int RADIUS_LG = 18;
    /** Corner radius for small controls, chips, toasts. */
    public static final int RADIUS_MD = 14;
    /** Corner radius for tiny elements, icon tiles. */
    public static final int RADIUS_SM = 10;
    /** Pill / fully-rounded shape marker (clamped at draw time). */
    public static final int RADIUS_PILL = 999;

    /** Standard card padding (left/right). */
    public static final int PAD_CARD = 16;
    /** Standard card padding (top/bottom). */
    public static final int PAD_CARD_V = 14;

    /** Minimum touch target used by tappable controls. */
    public static final int TOUCH_TARGET = 48;

    // ── Density & colour helpers ───────────────────────────────────────────

    /** Convert dp to px. */
    public static int dp(Context ctx, float value) {
        if (ctx == null) return (int) value;
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    /** Resolve a {@code @color} resource to an int. */
    @ColorInt
    public static int color(Context ctx, @ColorRes int res) {
        return ContextCompat.getColor(ctx, res);
    }

    // ── Drawable builders ──────────────────────────────────────────────────

    /** Solid rounded-rectangle drawable using a raw colour int. */
    public static GradientDrawable pill(@ColorInt int color, int radiusDp, Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(ctx, radiusDp));
        return gd;
    }

    /** Solid rounded-rectangle drawable using a {@code @color} resource. */
    public static GradientDrawable pillRes(Context ctx, @ColorRes int colorRes, int radiusDp) {
        return pill(color(ctx, colorRes), radiusDp, ctx);
    }

    /** Rounded rectangle with a hairline stroke. */
    public static GradientDrawable cardWithStroke(Context ctx, @ColorRes int fillRes,
                                                  @ColorRes int strokeRes, int radiusDp) {
        GradientDrawable gd = pillRes(ctx, fillRes, radiusDp);
        gd.setStroke(dp(ctx, 1), color(ctx, strokeRes));
        return gd;
    }

    /** Ripple on a rounded mask — for tappable rows/buttons without a background. */
    public static Drawable rippleRounded(Context ctx, int radiusDp, @ColorInt int rippleColor) {
        ColorStateList csl = ColorStateList.valueOf(rippleColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(ctx, radiusDp));
        return new RippleDrawable(csl, null, mask);
    }

    /** Ripple on a rounded mask driven by the theme {@code @color/ripple}. */
    public static Drawable rippleThemed(Context ctx, int radiusDp) {
        return rippleRounded(ctx, radiusDp, color(ctx, app.multiai.mobile.R.color.ripple));
    }

    // ── View builders ──────────────────────────────────────────────────────

    /** A styled {@link TextView}. {@code colorRes=0} keeps the default text colour. */
    public static TextView text(Context ctx, CharSequence text, float sizeSp,
                                @ColorRes int colorRes, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        if (colorRes != 0) tv.setTextColor(color(ctx, colorRes));
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    /** Vertical spacer of {@code heightDp}. */
    public static View spacer(Context ctx, int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
        return v;
    }

    /** An {@link ImageView} of a given dp size with an optional tint ({@code 0}=no tint). */
    public static ImageView icon(Context ctx, @DrawableRes int resId, int sizeDp,
                                 @ColorInt int tint) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(resId);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp));
        iv.setLayoutParams(lp);
        if (tint != 0) iv.setColorFilter(tint);
        return iv;
    }
}
