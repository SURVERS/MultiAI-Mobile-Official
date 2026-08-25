package app.multiai.mobile.ui.kit;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;

/**
 * Consistently styled in-app feedback rendered only inside the current Activity decor.
 * It inherits that Activity's screenshot policy and never uses overlay windows.
 */
public final class ModernToast {

    private ModernToast() {}

    public enum Position { TOP, BOTTOM }

    private static final long DURATION_SHORT = 1800L;
    private static final long DURATION_LONG  = 3200L;
    private static WeakReference<View> currentView; // no static Activity/View ownership
    private static WeakReference<View> currentCard;

    public static void show(Activity activity, CharSequence message) {
        show(activity, message, Position.TOP, false);
    }

    public static void show(Activity activity, CharSequence message, boolean longDuration) {
        show(activity, message, Position.TOP, longDuration);
    }

    public static void show(Activity activity, CharSequence message, Position position, boolean longDuration) {
        if (activity == null) return;
        new Handler(Looper.getMainLooper()).post(() -> showInternal(activity, message, position, longDuration));
    }

    /**
     * Успешный тост-карточка с заголовком, сообщением и кнопкой действия
     * («Круто!»). Живёт дольше обычного тоста (4.5с), кнопка закрывает сразу.
     */
    public static void showSuccess(Activity activity, CharSequence title,
                                   CharSequence message, CharSequence actionText) {
        if (activity == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = UiKit.dp(activity, 20);
            card.setPadding(pad, UiKit.dp(activity, 18), pad, UiKit.dp(activity, 16));
            card.setBackground(ContextCompat.getDrawable(activity, R.drawable.kit_toast_bg));

            // Галочка-кружок
            android.widget.ImageView check = new android.widget.ImageView(activity);
            check.setImageResource(app.multiai.mobile.R.drawable.ic_check_circle);
            int accent = ContextCompat.getColor(activity, app.multiai.mobile.R.color.success);
            check.setColorFilter(accent);
            android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                    UiKit.dp(activity, 30), UiKit.dp(activity, 30));
            check.setLayoutParams(clp);
            card.addView(check);

            TextView titleTv = new TextView(activity);
            titleTv.setText(title);
            titleTv.setTextColor(ContextCompat.getColor(activity, app.multiai.mobile.R.color.text_primary));
            titleTv.setTextSize(18);
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            android.widget.LinearLayout.LayoutParams tlp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = UiKit.dp(activity, 10);
            titleTv.setLayoutParams(tlp);
            card.addView(titleTv);

            TextView msgTv = new TextView(activity);
            msgTv.setText(message);
            msgTv.setTextColor(ContextCompat.getColor(activity, app.multiai.mobile.R.color.text_secondary));
            msgTv.setTextSize(14);
            msgTv.setMaxLines(3);
            android.widget.LinearLayout.LayoutParams mlp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = UiKit.dp(activity, 4);
            msgTv.setLayoutParams(mlp);
            card.addView(msgTv);

            // Кнопка закрытия success popup.
            com.google.android.material.button.MaterialButton btn =
                    new com.google.android.material.button.MaterialButton(activity, null,
                            com.google.android.material.R.attr.materialButtonStyle);
            btn.setText(actionText);
            btn.setTextColor(ContextCompat.getColor(activity, app.multiai.mobile.R.color.text_on_accent));
            btn.setTextSize(14);
            btn.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            int btnAccent = app.multiai.mobile.utils.AccentColor.resolve(activity);
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(btnAccent));
            btn.setCornerRadius(UiKit.dp(activity, 12));
            android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(activity, 44));
            blp.topMargin = UiKit.dp(activity, 14);
            card.addView(btn, blp);

            FrameLayout host = new FrameLayout(activity);
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int side = UiKit.dp(activity, 20);
            cardLp.gravity = Gravity.CENTER;
            cardLp.leftMargin = side;
            cardLp.rightMargin = side;
            host.addView(card, cardLp);
            // Затемнение фона — клик по нему тоже закрывает.
            host.setBackgroundColor(0x99000000);
            host.setOnClickListener(v -> dismiss(host, card));

            // Attach only to this Activity. The toast inherits its FLAG_SECURE policy and
            // never requests or uses an application-overlay window.
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            host.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            decor.addView(host);

            btn.setOnClickListener(v -> dismiss(host, card));
            // Автозакрытие
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (host.getParent() != null) dismiss(host, card);
            }, 6000);

            card.setScaleX(0.9f);
            card.setScaleY(0.9f);
            card.setAlpha(0f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(MotionKit.STANDARD)
                    .setInterpolator(MotionKit.STANDARD_EASING).start();
        });
    }

    private static void dismiss(FrameLayout host, View card) {
        if (card != null) {
            card.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f)
                    .setDuration(MotionKit.FAST)
                    .setInterpolator(MotionKit.EXIT_EASING)
                    .withEndAction(() -> {
                        if (host.getParent() instanceof ViewGroup) {
                            ((ViewGroup) host.getParent()).removeView(host);
                        }
                    }).start();
        } else if (host.getParent() instanceof ViewGroup) {
            ((ViewGroup) host.getParent()).removeView(host);
        }
    }

    private static void showInternal(Activity activity, CharSequence message, Position position, boolean longDuration) {
        // Retire the previous toast before replacing it. Keep this short so rapid feedback
        // never stacks, while avoiding the old hard cut between consecutive messages.
        View oldHost = currentView != null ? currentView.get() : null;
        View oldCard = currentCard != null ? currentCard.get() : null;
        if (oldHost != null) {
            currentView = null;
            currentCard = null;
            if (oldCard != null) {
                oldCard.animate().cancel();
                oldCard.animate().alpha(0f).setDuration(MotionKit.FAST)
                        .setInterpolator(MotionKit.EXIT_EASING)
                        .withEndAction(() -> removeHost(activity, oldHost)).start();
            } else {
                removeHost(activity, oldHost);
            }
        }

        // Card.
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int padH = UiKit.dp(activity, 16);
        int padV = UiKit.dp(activity, 10);
        card.setPadding(padH, padV, padH, padV);
        card.setBackground(ContextCompat.getDrawable(activity, R.drawable.kit_toast_bg));

        TextView tv = new TextView(activity);
        tv.setText(message);
        tv.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        tv.setTextSize(14);
        tv.setMaxLines(3);
        card.addView(tv);

        FrameLayout host = new FrameLayout(activity);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = (position == Position.TOP ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        cardLp.topMargin = UiKit.dp(activity, position == Position.TOP ? 56 : 0);
        cardLp.bottomMargin = UiKit.dp(activity, position == Position.BOTTOM ? 80 : 0);
        host.addView(card, cardLp);

        // Attach to the Activity decor only and inherit its FLAG_SECURE policy.
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        host.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decor.addView(host);
        currentView = new WeakReference<>(host);
        currentCard = new WeakReference<>(card);

        // Enter animation.
        float sign = position == Position.TOP ? -1f : 1f;
        card.setTranslationY(UiKit.dp(activity, 12) * sign);
        card.setAlpha(0f);
        card.animate().translationY(0f).alpha(1f)
                .setDuration(MotionKit.STANDARD)
                .setInterpolator(MotionKit.STANDARD_EASING).start();

        long duration = longDuration ? DURATION_LONG : DURATION_SHORT;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (card == null) return;
            card.animate().alpha(0f).translationY(UiKit.dp(activity, 8) * sign)
                    .setDuration(MotionKit.STANDARD)
                    .setInterpolator(MotionKit.EXIT_EASING)
                    .withEndAction(() -> removeHost(activity, host))
                    .start();
        }, duration);
    }

    private static void removeHost(Activity activity, View host) {
        if (host == null) return;
        if (host.getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) host.getParent()).removeView(host);
            } catch (Throwable ignored) {
            }
        }
        if (currentView != null && currentView.get() == host) {
            currentView = null;
            currentCard = null;
        }
    }
}
