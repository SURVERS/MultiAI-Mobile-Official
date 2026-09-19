package app.multiai.mobile.ui.kit;

import android.animation.TimeInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Shared motion timings and restrained Material-style easing. */
public final class MotionKit {

    public static final long FAST = 120L;
    public static final long STANDARD = 220L;
    public static final long EMPHASIZED = 300L;

    public static final TimeInterpolator STANDARD_EASING =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    public static final TimeInterpolator EXIT_EASING =
            new PathInterpolator(0.4f, 0f, 1f, 1f);

    private MotionKit() {}

    public static long duration(long duration) {
        return android.animation.ValueAnimator.areAnimatorsEnabled() ? duration : 0L;
    }

    public static androidx.recyclerview.widget.RecyclerView.ItemAnimator listAnimator() {
        androidx.recyclerview.widget.DefaultItemAnimator animator =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setAddDuration(duration(220));
        animator.setRemoveDuration(duration(160));
        animator.setMoveDuration(duration(220));
        return animator;
    }

    public static void enterGroup(android.view.ViewGroup group) {
        group.post(() -> {
            int visibleIndex = 0;
            android.graphics.Rect visible = new android.graphics.Rect();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getHeight() < 16
                        || !child.getLocalVisibleRect(visible)) continue;
                child.animate().cancel();
                child.setAlpha(0f);
                child.setTranslationY(UiKit.dp(child.getContext(), 12));
                child.animate().alpha(1f).translationY(0f)
                        .setStartDelay(duration(Math.min(visibleIndex++ * 35L, 140L)))
                        .setDuration(duration(280L)).setInterpolator(STANDARD_EASING)
                        .withEndAction(() -> child.animate().setStartDelay(0L)).start();
            }
        });
    }

    /** Adds quiet scale/alpha feedback without consuming the click event. */
    public static void attachPress(View view, float pressedScale) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(pressedScale)
                            .scaleY(pressedScale)
                            .alpha(0.88f)
                            .setDuration(duration(FAST))
                            .setInterpolator(STANDARD_EASING)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(duration(STANDARD))
                            .setInterpolator(STANDARD_EASING)
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    public static void fadeIn(View view, float offsetDp) {
        if (view == null) return;
        view.animate().cancel();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.setTranslationY(UiKit.dp(view.getContext(), offsetDp));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(duration(STANDARD))
                .setInterpolator(STANDARD_EASING)
                .start();
    }

    public static void fadeOut(View view, float offsetDp, Runnable endAction) {
        if (view == null) return;
        view.animate().cancel();
        view.animate()
                .alpha(0f)
                .translationY(UiKit.dp(view.getContext(), offsetDp))
                .setDuration(duration(FAST))
                .setInterpolator(EXIT_EASING)
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setTranslationY(0f);
                    if (endAction != null) endAction.run();
                })
                .start();
    }
}
