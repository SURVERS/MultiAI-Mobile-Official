package app.multiai.mobile.ui.kit;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

import app.multiai.mobile.R;

/**
 * Меню «+» по референсу ChatGPT: вертикальная карточка с круглыми иконками
 * (Камера / Фото / Файлы), всплывает над полем ввода. Общее для Work-режима,
 * Chat-режима и режима правки сообщения.
 *
 * <p>Окно НЕ фокусное: клавиатура не закрывается при открытии меню. Карточка
 * лежит на прозрачном полноэкранном backdrop'е — тап мимо закрывает меню
 * с плавной анимацией исчезновения.
 */
public final class AttachMenuPopup {

    public interface Callback {
        void onCamera();
        void onPhoto();
        void onFiles();
    }

    private AttachMenuPopup() {}

    /**
     * Показывает карточку над {@code anchorView} (поле ввода), прижатой к левому
     * краю. {@code onDismiss} вызывается после закрытия — туда вешаем обратную
     * анимацию кнопки «+».
     */
    public static PopupWindow show(View anchorView, Callback callback, Runnable onDismiss) {
        Context ctx = anchorView.getContext();
        View card = LayoutInflater.from(ctx).inflate(R.layout.view_attach_menu, null);
        float density = ctx.getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(ctx);
        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AnimatedPopup popup = new AnimatedPopup(card, root, onDismiss);

        popup.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
        popup.setHeight(ViewGroup.LayoutParams.MATCH_PARENT);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setFocusable(false);

        // Тап по фону — плавное закрытие; тап по карточке глотается
        root.setOnClickListener(v -> popup.animateDismiss());
        card.setOnClickListener(v -> {});

        card.findViewById(R.id.btnAttachCamera).setOnClickListener(v -> {
            popup.dismissNow();
            callback.onCamera();
        });
        card.findViewById(R.id.btnAttachPhoto).setOnClickListener(v -> {
            popup.dismissNow();
            callback.onPhoto();
        });
        card.findViewById(R.id.btnAttachFile).setOnClickListener(v -> {
            popup.dismissNow();
            callback.onFiles();
        });

        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

        int[] loc = new int[2];
        anchorView.getLocationOnScreen(loc);
        int x = loc[0] + Math.round(12 * density);
        int y = Math.max(Math.round(8 * density),
                loc[1] - card.getMeasuredHeight() - Math.round(10 * density));

        // Появление: fade + лёгкий подъём (альфу гасим до показа — без мигания)
        card.setAlpha(0f);
        popup.showAtLocation(anchorView, Gravity.TOP | Gravity.START, 0, 0);
        card.setTranslationX(x);
        card.setTranslationY(y + Math.round(10 * density));
        card.animate().alpha(1f).translationY(y)
                .setDuration(170)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .start();
        return popup;
    }

    /**
     * PopupWindow с плавным исчезновением: первый dismiss() запускает анимацию
     * карточки и только потом закрывает окно. dismissNow() — без анимации
     * (когда сразу открывается системный пикер).
     */
    private static final class AnimatedPopup extends PopupWindow {
        private final View card;
        private final Runnable onDismiss;
        private boolean dismissing;

        AnimatedPopup(View cardView, FrameLayout root, Runnable dismissCallback) {
            super(root);
            card = cardView;
            onDismiss = dismissCallback;
            setOnDismissListener(() -> {
                if (onDismiss != null) onDismiss.run();
            });
        }

        @Override
        public void dismiss() {
            animateDismiss();
        }

        void dismissNow() {
            if (dismissing) return;
            dismissing = true;
            AnimatedPopup.super.dismiss();
        }

        void animateDismiss() {
            if (dismissing) return;
            dismissing = true;
            float density = card.getContext().getResources().getDisplayMetrics().density;
            card.animate().alpha(0f).translationY(card.getTranslationY() + 10 * density)
                    .setDuration(150)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(() -> AnimatedPopup.super.dismiss())
                    .start();
        }
    }
}
