package app.multiai.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;

public class ThinkingWaveIconView extends AppCompatImageView {
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode maskMode = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
    private ValueAnimator animator;
    private android.graphics.drawable.Drawable baseDrawable;
    private android.graphics.drawable.Drawable waveDrawable;
    private float waveX;
    private boolean running;
    private int statusDrawableRes;

    public ThinkingWaveIconView(Context context) {
        super(context);
    }

    public ThinkingWaveIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ThinkingWaveIconView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setRunning(boolean running) {
        if (this.running == running) {
            // RecyclerView may re-attach a still-running row without rebinding
            // it. The flag is unchanged, but onDetachedFromWindow() already
            // cancelled the animator, so revive it instead of returning.
            if (running) ensureWave();
            return;
        }
        this.running = running;
        if (running) ensureWave(); else stopWave();
        invalidate();
    }

    public void setStatusDrawable(int drawableRes) {
        if (statusDrawableRes == drawableRes) return;
        statusDrawableRes = drawableRes;
        baseDrawable = null;
        waveDrawable = null;
        setImageResource(drawableRes);
        invalidate();
    }

    /** Runs only while this (possibly recycled) row is actually on screen. */
    private void ensureWave() {
        if (!running || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        if (animator == null || !animator.isRunning()) startWave();
    }

    private void startWave() {
        stopWave();
        animator = ValueAnimator.ofFloat(-1f, 2f);
        animator.setDuration(1350L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(animation -> {
            waveX = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopWave() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void ensureDrawables() {
        if (baseDrawable != null || getDrawable() == null || getDrawable().getConstantState() == null) return;
        baseDrawable = getDrawable().getConstantState().newDrawable().mutate();
        waveDrawable = getDrawable().getConstantState().newDrawable().mutate();
        androidx.core.graphics.drawable.DrawableCompat.setTint(baseDrawable,
                ContextCompat.getColor(getContext(), R.color.icon_muted));
        androidx.core.graphics.drawable.DrawableCompat.setTint(waveDrawable, Color.WHITE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ensureDrawables();
        if (baseDrawable == null) {
            super.onDraw(canvas);
            return;
        }
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int right = getWidth() - getPaddingRight();
        int bottom = getHeight() - getPaddingBottom();
        baseDrawable.setBounds(left, top, right, bottom);
        baseDrawable.draw(canvas);
        if (!running || getWidth() == 0) return;

        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        waveDrawable.setBounds(left, top, right, bottom);
        waveDrawable.draw(canvas);
        float center = waveX * getWidth();
        float radius = Math.max(getWidth() * 0.42f, 1f);
        maskPaint.setShader(new LinearGradient(center - radius, 0, center + radius, 0,
                new int[]{Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        maskPaint.setXfermode(maskMode);
        canvas.drawRect(0, 0, getWidth(), getHeight(), maskPaint);
        maskPaint.setXfermode(null);
        maskPaint.setShader(null);
        canvas.restoreToCount(layer);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureWave();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            post(this::ensureWave);
        } else {
            stopWave();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopWave();
        super.onDetachedFromWindow();
    }
}
