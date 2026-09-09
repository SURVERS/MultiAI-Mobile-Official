package app.multiai.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * ChatGPT-style image generation placeholder: a rounded block with soft
 * brand-colored blobs slowly breathing and cross-fading into each other.
 * Tapping the block energizes the blobs and ripples from the touch point.
 */
public class ImageGenShimmerView extends View {

    private static final long LOOP_MS = 9000;
    private static final float ENERGY_DECAY_S = 1.2f;

    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF block = new RectF();
    private final Path clipPath = new Path();
    private ValueAnimator animator;
    private long startTicks;
    private float t;

    private float energy = 0f;
    private float lastTapTime = -10f;

    // Brand palette: violet / blue / coral blobs on a dark canvas.
    private static final int[] BLOB_COLORS = {0xFF7C5CFC, 0xFF4F8CFF, 0xFFE07856};

    public ImageGenShimmerView(Context context) { this(context, null); }

    public ImageGenShimmerView(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public ImageGenShimmerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        blockPaint.setColor(0xFF1B1B20);
        setClickable(true);
    }

    public void setRunning(boolean running) {
        if (running) start();
        else stop();
    }

    private void start() {
        if (animator != null && animator.isRunning()) return;
        startTicks = System.nanoTime();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LOOP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            t = (System.nanoTime() - startTicks) / 1_000_000_000f;
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
        // Blobs flare up on tap.
        lastTapTime = t;
        if (animator == null || !animator.isRunning()) start();
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            lastTapTime = t;
            if (animator == null || !animator.isRunning()) start();
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float radius = 16f * d;
        float w = getWidth();
        float h = getHeight();
        float min = Math.min(w, h);

        energy = Math.max(0f, 1f - (t - lastTapTime) / ENERGY_DECAY_S);

        block.set(0f, 0f, w, h);
        canvas.drawRoundRect(block, radius, radius, blockPaint);

        int save = canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(block, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);

        // Slow-breathing color blobs (Lissajous drift), softened with blur.
        // Colors cross-fade into each other; a tap speeds them up and swells them.
        blobPaint.setMaskFilter(new BlurMaskFilter(min * 0.16f, BlurMaskFilter.Blur.NORMAL));
        float speed = 1f + 2.2f * energy;
        for (int i = 0; i < BLOB_COLORS.length; i++) {
            float phase = t * 0.55f * speed + i * 2.1f;
            float cx = w * (0.5f + 0.30f * (float) Math.sin(phase * 0.9f + i * 1.7f));
            float cy = h * (0.5f + 0.30f * (float) Math.cos(phase * 0.7f + i * 2.3f));
            float r = min * (0.42f + 0.10f * (float) Math.sin(phase * 1.3f) + 0.14f * energy);
            int base = mix(BLOB_COLORS[i], BLOB_COLORS[(i + 1) % BLOB_COLORS.length],
                    0.5f + 0.5f * (float) Math.sin(t * 0.4f * speed + i * 2.0f));
            int alpha = 66 + (int) (26 * (0.5f + 0.5f * (float) Math.sin(phase * 1.1f)))
                    + (int) (50 * energy);
            blobPaint.setShader(new RadialGradient(cx, cy, r,
                    withAlpha(base, Math.min(alpha, 190)), withAlpha(base, 0),
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, blobPaint);
        }

        // Expanding touch ripples removed — blobs carry the reaction themselves.

        canvas.restoreToCount(save);
    }

    private static int mix(int a, int b, float k) {
        float clamped = Math.max(0f, Math.min(1f, k));
        int r = Math.round(((a >> 16) & 0xFF) * (1 - clamped) + ((b >> 16) & 0xFF) * clamped);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - clamped) + ((b >> 8) & 0xFF) * clamped);
        int bl = Math.round((a & 0xFF) * (1 - clamped) + (b & 0xFF) * clamped);
        return (r << 16) | (g << 8) | bl;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.min(alpha, 255) << 24);
    }
}
