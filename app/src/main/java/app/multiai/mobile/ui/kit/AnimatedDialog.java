package app.multiai.mobile.ui.kit;

import android.app.Dialog;
import android.content.Context;
import android.view.View;

/** Dialog shell that guarantees the same animated exit for every dismissal path. */
final class AnimatedDialog extends Dialog {

    enum Mode { CENTER, SHEET }

    private final Mode mode;
    private View backdrop;
    private View panel;
    private boolean closing;

    AnimatedDialog(Context context, Mode mode) {
        super(context, android.R.style.Theme_Translucent_NoTitleBar);
        this.mode = mode;
    }

    void bind(View backdrop, View panel) {
        this.backdrop = backdrop;
        this.panel = panel;
    }

    void playEnter() {
        if (backdrop == null || panel == null) return;
        backdrop.animate().cancel();
        panel.animate().cancel();
        backdrop.setAlpha(0f);
        panel.setAlpha(0f);
        if (mode == Mode.SHEET) {
            panel.setTranslationY(UiKit.dp(getContext(), 56));
        } else {
            panel.setScaleX(0.94f);
            panel.setScaleY(0.94f);
        }
        backdrop.animate()
                .alpha(1f)
                .setDuration(MotionKit.STANDARD)
                .setInterpolator(MotionKit.STANDARD_EASING)
                .start();
        if (mode == Mode.SHEET) {
            panel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(MotionKit.EMPHASIZED)
                    .setInterpolator(MotionKit.STANDARD_EASING)
                    .start();
        } else {
            panel.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(MotionKit.STANDARD)
                    .setInterpolator(MotionKit.STANDARD_EASING)
                    .start();
        }
    }

    @Override
    public void dismiss() {
        if (closing) return;
        if (!isShowing() || panel == null || backdrop == null) {
            super.dismiss();
            return;
        }
        closing = true;
        backdrop.animate().cancel();
        panel.animate().cancel();
        backdrop.animate()
                .alpha(0f)
                .setDuration(MotionKit.FAST)
                .setInterpolator(MotionKit.EXIT_EASING)
                .start();
        if (mode == Mode.SHEET) {
            float target = Math.max(panel.getHeight(), UiKit.dp(getContext(), 96));
            panel.animate()
                    .alpha(0.92f)
                    .translationY(target)
                    .setDuration(MotionKit.STANDARD)
                    .setInterpolator(MotionKit.EXIT_EASING)
                    .withEndAction(super::dismiss)
                    .start();
        } else {
            panel.animate()
                    .alpha(0f)
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(MotionKit.FAST)
                    .setInterpolator(MotionKit.EXIT_EASING)
                    .withEndAction(super::dismiss)
                    .start();
        }
    }
}
