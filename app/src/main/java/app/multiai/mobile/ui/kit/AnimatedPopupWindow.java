package app.multiai.mobile.ui.kit;

import android.view.View;
import android.widget.PopupWindow;

/** PopupWindow with a quiet animated exit for selection and outside-tap dismissal. */
final class AnimatedPopupWindow extends PopupWindow {

    private final View animatedContent;
    private boolean closing;

    AnimatedPopupWindow(View contentView, int width, int height, boolean focusable) {
        super(contentView, width, height, focusable);
        this.animatedContent = contentView;
    }

    @Override
    public void dismiss() {
        if (closing) return;
        if (!isShowing() || animatedContent == null) {
            super.dismiss();
            return;
        }
        closing = true;
        animatedContent.animate().cancel();
        animatedContent.animate()
                .alpha(0f)
                .scaleY(0.96f)
                .setDuration(MotionKit.FAST)
                .setInterpolator(MotionKit.EXIT_EASING)
                .withEndAction(super::dismiss)
                .start();
    }
}
