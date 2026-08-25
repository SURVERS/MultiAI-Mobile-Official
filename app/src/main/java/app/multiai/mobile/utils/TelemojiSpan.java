package app.multiai.mobile.utils;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.style.ReplacementSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.airbnb.lottie.LottieDrawable;

/** Рисует Telemoji inline и связывает invalidation Lottie с TextView. */
public final class TelemojiSpan extends ReplacementSpan {

    private final Drawable drawable;
    private final int sizePx;
    private View hostView;
    private Drawable.Callback drawableCallback;
    private View.OnAttachStateChangeListener attachListener;

    public TelemojiSpan(@NonNull Drawable drawable, int sizePx) {
        this.drawable = drawable;
        this.sizePx = Math.max(1, sizePx);
        drawable.setBounds(0, 0, this.sizePx, this.sizePx);
    }

    public void setHostView(@NonNull View view) {
        if (hostView == view && drawableCallback != null) return;
        detachFromHost();
        hostView = view;
        drawableCallback = new Drawable.Callback() {
            @Override
            public void invalidateDrawable(@NonNull Drawable who) {
                view.postInvalidateOnAnimation();
            }

            @Override
            public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
                view.postDelayed(what, Math.max(0L, when - SystemClock.uptimeMillis()));
            }

            @Override
            public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
                view.removeCallbacks(what);
            }
        };
        drawable.setCallback(drawableCallback);

        if (drawable instanceof LottieDrawable) {
            attachListener = new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View attachedView) {
                    startAnimationIfNeeded();
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View detachedView) {
                    ((LottieDrawable) drawable).pauseAnimation();
                }
            };
            view.addOnAttachStateChangeListener(attachListener);
        }
    }

    public void startAnimationIfNeeded() {
        if (!(drawable instanceof LottieDrawable) || hostView == null) return;
        LottieDrawable lottie = (LottieDrawable) drawable;
        drawable.setCallback(drawableCallback);
        if (!lottie.isAnimating()) lottie.playAnimation();
    }

    /** Останавливает animator и разрывает ссылку на старый/recycled TextView. */
    public void release() {
        if (drawable instanceof LottieDrawable) {
            ((LottieDrawable) drawable).cancelAnimation();
        }
        detachFromHost();
    }

    private void detachFromHost() {
        if (hostView != null && attachListener != null) {
            hostView.removeOnAttachStateChangeListener(attachListener);
        }
        drawable.setCallback(null);
        hostView = null;
        drawableCallback = null;
        attachListener = null;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt metrics) {
        if (metrics != null) {
            Paint.FontMetricsInt original = paint.getFontMetricsInt();
            int textHeight = original.descent - original.ascent;
            if (sizePx > textHeight) {
                int center = (original.ascent + original.descent) / 2;
                metrics.ascent = center - sizePx / 2;
                metrics.descent = metrics.ascent + sizePx;
                metrics.top = Math.min(original.top, metrics.ascent);
                metrics.bottom = Math.max(original.bottom, metrics.descent);
            } else {
                metrics.ascent = original.ascent;
                metrics.descent = original.descent;
                metrics.top = original.top;
                metrics.bottom = original.bottom;
            }
        }
        return sizePx;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint paint) {
        int drawTop = top + Math.max(0, (bottom - top - sizePx) / 2);
        canvas.save();
        canvas.translate(x, drawTop);
        drawable.draw(canvas);
        canvas.restore();
    }
}
