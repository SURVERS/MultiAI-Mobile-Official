package app.multiai.mobile.ui.kit;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;

import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.utils.AccentColor;

/**
 * Centered "quick actions" card shown when a binary file (APK, image, audio,
 * PDF, …) is opened in the editor. Unlike {@link ModernMenu} (an anchored
 * dropdown list) or {@link ModernDialog} (text buttons), this renders a compact
 * card with a horizontal row of vertical icon-action tiles.
 *
 * <p>Visual style mirrors {@link ModernDialog}: dim+blur backdrop,
 * {@code @drawable/kit_dialog_bg} surface (rounded 24dp + 1dp stroke), centered,
 * scale-in enter animation (0.92 → 1, alpha 0 → 1, 200ms).
 *
 * <pre>{@code
 *   BinaryActionCard.show(ctx, "app.apk", "Android package", R.drawable.ic_android,
 *       Arrays.asList(
 *           new BinaryActionCard.Action(R.drawable.ic_android, "Установить", () -> installApk()),
 *           new BinaryActionCard.Action(R.drawable.ic_share, "Поделиться",   () -> shareApk())));
 * }</pre>
 */
public final class BinaryActionCard {

    private BinaryActionCard() {}

    /** A single icon-action tile in the card. */
    public static final class Action {
        public final int iconRes;
        public final CharSequence label;
        public final Runnable onClick;

        public Action(@DrawableRes int iconRes, CharSequence label, Runnable onClick) {
            this.iconRes = iconRes;
            this.label = label;
            this.onClick = onClick;
        }
    }

    /**
     * Show the binary action card.
     *
     * @param title     file name (e.g. "app.apk").
     * @param subtitle  human-readable type label (e.g. "Android package").
     * @param headerIconRes drawable shown in the header tile.
     * @param actions   1–N icon-action tiles. Each tile dismisses the card and
     *                  fires its {@link Action#onClick} when tapped.
     * @return the shown {@link Dialog} (dismiss to close programmatically).
     */
    public static Dialog show(Context ctx, CharSequence title, CharSequence subtitle,
                              @DrawableRes int headerIconRes, List<Action> actions) {
        AnimatedDialog d = new AnimatedDialog(ctx, AnimatedDialog.Mode.CENTER);
        d.setCancelable(true);

        FrameLayout root = new FrameLayout(ctx);
        View backdrop = new View(ctx);
        backdrop.setBackgroundColor(ContextCompat.getColor(ctx, R.color.overlay_dim));
        backdrop.setOnClickListener(v -> d.dismiss());
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ContextCompat.getDrawable(ctx, R.drawable.kit_dialog_bg));
        int pad = UiKit.dp(ctx, 24);
        card.setPadding(pad, pad, pad, UiKit.dp(ctx, 20));
        // Swallow taps on the card itself so they don't bubble up to the backdrop.
        card.setOnClickListener(v -> {});
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = UiKit.dp(ctx, 36);
        cardLp.rightMargin = UiKit.dp(ctx, 36);
        card.setLayoutParams(cardLp);
        card.setMinimumWidth(UiKit.dp(ctx, 280));

        // ── Header icon tile ───────────────────────────────────────────────
        int accent = AccentColor.resolve(ctx);
        int iconColor = UiKit.color(ctx, R.color.icon);

        LinearLayout headerTile = new LinearLayout(ctx);
        headerTile.setOrientation(LinearLayout.VERTICAL);
        headerTile.setGravity(Gravity.CENTER);
        int tile = UiKit.dp(ctx, 56);
        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(tile, tile);
        headerTile.setLayoutParams(tileLp);
        headerTile.setBackground(UiKit.pillRes(ctx, R.color.surface_tonal, UiKit.RADIUS_LG));
        ImageView headerIv = new ImageView(ctx);
        headerIv.setImageResource(headerIconRes);
        headerIv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        headerIv.setColorFilter(accent);
        LinearLayout.LayoutParams headerIvLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 30), UiKit.dp(ctx, 30));
        headerIvLp.gravity = Gravity.CENTER;
        headerIv.setLayoutParams(headerIvLp);
        headerTile.addView(headerIv);
        card.addView(headerTile);

        // ── File name ──────────────────────────────────────────────────────
        if (title != null && title.length() > 0) {
            TextView tvTitle = UiKit.text(ctx, title, 16, R.color.text_primary, true);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleLp.topMargin = UiKit.dp(ctx, 14);
            titleLp.gravity = Gravity.CENTER_HORIZONTAL;
            tvTitle.setLayoutParams(titleLp);
            tvTitle.setMaxLines(2);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            card.addView(tvTitle);
        }

        // ── Type label ─────────────────────────────────────────────────────
        if (subtitle != null && subtitle.length() > 0) {
            TextView tvSub = UiKit.text(ctx, subtitle, 12, R.color.text_hint, false);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = UiKit.dp(ctx, 2);
            subLp.gravity = Gravity.CENTER_HORIZONTAL;
            tvSub.setLayoutParams(subLp);
            card.addView(tvSub);
        }

        // ── Action row ─────────────────────────────────────────────────────
        if (actions != null && !actions.isEmpty()) {
            LinearLayout actionRow = new LinearLayout(ctx);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = UiKit.dp(ctx, 20);
            actionRow.setLayoutParams(rowLp);

            for (int i = 0; i < actions.size(); i++) {
                final Action a = actions.get(i);
                View tileView = makeActionTile(ctx, a, v -> {
                    d.dismiss();
                    if (a.onClick != null) a.onClick.run();
                });
                LinearLayout.LayoutParams tileViewLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) tileViewLp.setMarginStart(UiKit.dp(ctx, 10));
                tileView.setLayoutParams(tileViewLp);
                actionRow.addView(tileView);
            }
            card.addView(actionRow);
        }

        root.addView(card);
        d.setContentView(root);

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Frosted-glass blur of app content behind the window (Android 12+).
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    w.setBackgroundBlurRadius(UiKit.dp(ctx, 24));
                } catch (Throwable ignored) {}
            }
        }

        d.bind(backdrop, card);
        d.show();
        d.playEnter();
        return d;
    }

    /** A vertical icon-tile + label that the user taps to trigger an action. */
    private static View makeActionTile(Context ctx, Action action, View.OnClickListener click) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setOnClickListener(click);
        col.setBackground(UiKit.rippleRounded(ctx, UiKit.RADIUS_LG,
                ContextCompat.getColor(ctx, R.color.ripple)));
        int padV = UiKit.dp(ctx, 10);
        int padH = UiKit.dp(ctx, 6);
        col.setPadding(padH, padV, padH, padV);

        // Icon inside a soft rounded tile (keeps the icons visually grounded).
        LinearLayout iconTile = new LinearLayout(ctx);
        iconTile.setGravity(Gravity.CENTER);
        int tile = UiKit.dp(ctx, 52);
        LinearLayout.LayoutParams iconTileLp = new LinearLayout.LayoutParams(tile, tile);
        iconTile.setLayoutParams(iconTileLp);
        iconTile.setBackground(UiKit.pillRes(ctx, R.color.surface_tonal, UiKit.RADIUS_MD));

        ImageView iv = new ImageView(ctx);
        iv.setImageResource(action.iconRes);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setColorFilter(UiKit.color(ctx, R.color.icon));
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 28), UiKit.dp(ctx, 28));
        iv.setLayoutParams(ivLp);
        iconTile.addView(iv);
        col.addView(iconTile);

        // Label (11sp per spec).
        TextView label = new TextView(ctx);
        label.setText(action.label);
        label.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        label.setTextSize(11);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = UiKit.dp(ctx, 6);
        label.setLayoutParams(labelLp);
        col.addView(label);

        return col;
    }
}
