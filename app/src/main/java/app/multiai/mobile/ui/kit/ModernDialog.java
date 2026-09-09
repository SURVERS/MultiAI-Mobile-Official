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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;

/**
 * Modern centered card dialog used everywhere in the app.
 *
 * <p>Replaces the older {@code ModalDialog} and the per-activity
 * {@code showStyledDialog/showStyledInputDialog} helpers with one consistent look:
 * dim+blur backdrop, rounded {@code surface_card} card, scale-in animation,
 * text-style action buttons (destructive action shown in {@code error} red).
 *
 * <p>Supports an optional custom body view for input forms.
 */
public final class ModernDialog {

    private ModernDialog() {}

    /** Click callback for an action button. */
    public interface Action { void run(); }

    /**
     * Show a confirm dialog with two buttons (cancel / action).
     *
     * @param destructive when true, the action button text is rendered in {@code @color/error}.
     */
    public static Dialog show(Context ctx, String title, String message,
                              String cancelText, String actionText, boolean destructive,
                              Action onAction) {
        return show(ctx, title, message, null, cancelText, actionText, destructive, onAction);
    }

    /**
     * Show a single-button info dialog.
     */
    public static Dialog show(Context ctx, String title, String message, String okText) {
        return show(ctx, title, message, null, null, okText, false, null);
    }

    /**
     * Full show with an optional custom body view.
     *
     * @param bodyView optional view rendered between the message and the button row (forms, inputs…).
     */
    public static Dialog show(Context ctx, String title, String message, View bodyView,
                              String cancelText, String actionText, boolean destructive,
                              Action onAction) {
        return show(ctx, title, message, bodyView, cancelText, actionText, destructive, onAction, true);
    }

    public static Dialog show(Context ctx, String title, String message, View bodyView,
                              String cancelText, String actionText, boolean destructive,
                              Action onAction, boolean dismissible) {
        AnimatedDialog d = new AnimatedDialog(ctx, AnimatedDialog.Mode.CENTER);
        d.setCancelable(dismissible);
        d.setCanceledOnTouchOutside(dismissible);

        FrameLayout root = new FrameLayout(ctx);
        View backdrop = new View(ctx);
        backdrop.setBackgroundColor(ContextCompat.getColor(ctx, R.color.overlay_dim));
        backdrop.setOnClickListener(v -> { if (dismissible) d.dismiss(); });
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ContextCompat.getDrawable(ctx, R.drawable.kit_dialog_bg));
        int pad = UiKit.dp(ctx, 24);
        card.setPadding(pad, pad, pad, UiKit.dp(ctx, 14));
        card.setOnClickListener(v -> {}); // swallow taps on the card so they don't close.

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = UiKit.dp(ctx, 36);
        cardLp.rightMargin = UiKit.dp(ctx, 36);
        card.setLayoutParams(cardLp);
        card.setMinimumWidth(UiKit.dp(ctx, 280));

        // Title
        if (title != null && !title.isEmpty()) {
            TextView tvTitle = UiKit.text(ctx, title, 17, R.color.text_primary, true);
            tvTitle.setLineSpacing(UiKit.dp(ctx, 1), 1f);
            card.addView(tvTitle);
        }

        // Message
        if (message != null && !message.isEmpty()) {
            TextView tvMsg = UiKit.text(ctx, message, 13.5f, R.color.text_secondary, false);
            LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            msgLp.topMargin = UiKit.dp(ctx, 8);
            tvMsg.setLayoutParams(msgLp);
            tvMsg.setLineSpacing(UiKit.dp(ctx, 2), 1f);
            card.addView(tvMsg);
        }

        // Custom body (input fields etc.)
        if (bodyView != null) {
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = UiKit.dp(ctx, 14);
            bodyView.setLayoutParams(bodyLp);
            card.addView(bodyView);
        }

        // Button row
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = UiKit.dp(ctx, 12);
        btnRow.setLayoutParams(btnLp);

        if (cancelText != null && !cancelText.isEmpty()) {
            TextView cancel = makeTextButton(ctx, cancelText, R.color.text_secondary, false);
            cancel.setOnClickListener(v -> d.dismiss());
            btnRow.addView(cancel);
        }

        final Action action = onAction;
        TextView actionBtn = makeTextButton(ctx,
                actionText != null ? actionText : "OK",
                destructive ? R.color.error : R.color.text_primary,
                true);
        actionBtn.setOnClickListener(v -> {
            d.dismiss();
            if (action != null) action.run();
        });
        if (dismissible || actionText != null) btnRow.addView(actionBtn);

        card.addView(btnRow);
        root.addView(card);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            app.multiai.mobile.utils.SecurityHelper.applySecure(ctx, w);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Real backdrop blur (frosted glass) on Android 12+: blurs the app content
            // BEHIND the window. Unlike RenderEffect (which only blurs the view's own
            // pixels/descendants), this blurs what's underneath the dialog.
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

    private static TextView makeTextButton(Context ctx, String text, int colorRes, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(ctx, colorRes));
        tv.setTextSize(15);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        int padH = UiKit.dp(ctx, 14);
        int padV = UiKit.dp(ctx, 10);
        tv.setPadding(padH, padV, padH, padV);
        tv.setBackground(UiKit.rippleRounded(ctx, UiKit.RADIUS_SM,
                ContextCompat.getColor(ctx, R.color.ripple)));
        return tv;
    }
}
