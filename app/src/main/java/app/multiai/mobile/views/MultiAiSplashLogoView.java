package app.multiai.mobile.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import app.multiai.mobile.R;

/**
 * Draws the original MultiAI mark as four independent quadrants and moves a
 * soft white glow clockwise around them. Keeping one source bitmap preserves
 * the exact launcher/sidebar logo geometry without duplicate cropped assets.
 */
public final class MultiAiSplashLogoView extends View {

    public static final long CYCLE_DURATION_MS = 2_400L;

    private static final int SEGMENT_COUNT = 4;
    private static final float HOLD_PORTION = 0.52f;
    private static final int INACTIVE_COLOR = Color.rgb(21, 21, 21);
    private static final int PAINT_FLAGS = Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG
            | Paint.DITHER_FLAG;

    private final Rect[] sourceRects = new Rect[SEGMENT_COUNT];
    private final RectF[] destinationRects = new RectF[SEGMENT_COUNT];
    private final Paint inactivePaint = new Paint(PAINT_FLAGS);
    private final Paint wideGlowPaint = new Paint(PAINT_FLAGS);
    private final Paint tightGlowPaint = new Paint(PAINT_FLAGS);
    private final Paint activePaint = new Paint(PAINT_FLAGS);

    private Bitmap logoBitmap;
    private ValueAnimator glowAnimator;
    private float phase;

    public MultiAiSplashLogoView(Context context) {
        this(context, null);
    }

    public MultiAiSplashLogoView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultiAiSplashLogoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialise();
    }

    private void initialise() {
        // BlurMaskFilter needs software rendering for bitmap-backed canvas draws.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);

        logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_multiai_logo);
        if (logoBitmap == null) {
            setWillNotDraw(true);
            return;
        }

        int halfWidth = logoBitmap.getWidth() / 2;
        int halfHeight = logoBitmap.getHeight() / 2;
        sourceRects[0] = new Rect(0, 0, halfWidth, halfHeight);                         // top-left
        sourceRects[1] = new Rect(halfWidth, 0, logoBitmap.getWidth(), halfHeight);     // top-right
        sourceRects[2] = new Rect(halfWidth, halfHeight,
                logoBitmap.getWidth(), logoBitmap.getHeight());                         // bottom-right
        sourceRects[3] = new Rect(0, halfHeight, halfWidth, logoBitmap.getHeight());    // bottom-left
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            destinationRects[i] = new RectF();
        }

        PorterDuffColorFilter inactiveFilter = new PorterDuffColorFilter(
                INACTIVE_COLOR, PorterDuff.Mode.SRC_IN);
        PorterDuffColorFilter whiteFilter = new PorterDuffColorFilter(
                Color.WHITE, PorterDuff.Mode.SRC_IN);

        inactivePaint.setColorFilter(inactiveFilter);

        wideGlowPaint.setColorFilter(whiteFilter);
        wideGlowPaint.setMaskFilter(new BlurMaskFilter(dp(22f), BlurMaskFilter.Blur.NORMAL));

        tightGlowPaint.setColorFilter(whiteFilter);
        tightGlowPaint.setMaskFilter(new BlurMaskFilter(dp(9f), BlurMaskFilter.Blur.NORMAL));
    }

    /**
     * Starts one continuous clockwise TL -> TR -> BR -> BL loop.
     *
     * @return true when motion is enabled; false when Android's animator scale
     * is disabled and the view intentionally stays on a static first segment.
     */
    public boolean startGlowAnimation() {
        stopGlowAnimation();
        phase = 0f;
        invalidate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ValueAnimator.areAnimatorsEnabled()) {
            return false;
        }

        glowAnimator = ValueAnimator.ofFloat(0f, SEGMENT_COUNT);
        glowAnimator.setDuration(CYCLE_DURATION_MS);
        glowAnimator.setInterpolator(new LinearInterpolator());
        glowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        glowAnimator.setRepeatMode(ValueAnimator.RESTART);
        glowAnimator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
        glowAnimator.start();
        return true;
    }

    public void stopGlowAnimation() {
        if (glowAnimator != null) {
            glowAnimator.cancel();
            glowAnimator.removeAllUpdateListeners();
            glowAnimator = null;
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateDestinationRects(width, height);
    }

    private void updateDestinationRects(int width, int height) {
        if (logoBitmap == null) return;

        float availableWidth = Math.max(0f, width - getPaddingLeft() - getPaddingRight());
        float availableHeight = Math.max(0f, height - getPaddingTop() - getPaddingBottom());
        float scale = Math.min(
                availableWidth / logoBitmap.getWidth(),
                availableHeight / logoBitmap.getHeight());

        float drawWidth = logoBitmap.getWidth() * scale;
        float drawHeight = logoBitmap.getHeight() * scale;
        float left = getPaddingLeft() + (availableWidth - drawWidth) / 2f;
        float top = getPaddingTop() + (availableHeight - drawHeight) / 2f;
        float splitX = left + sourceRects[0].width() * scale;
        float splitY = top + sourceRects[0].height() * scale;
        float right = left + drawWidth;
        float bottom = top + drawHeight;

        destinationRects[0].set(left, top, splitX, splitY);
        destinationRects[1].set(splitX, top, right, splitY);
        destinationRects[2].set(splitX, splitY, right, bottom);
        destinationRects[3].set(left, splitY, splitX, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (logoBitmap == null || destinationRects[0].isEmpty()) return;

        for (int i = 0; i < SEGMENT_COUNT; i++) {
            canvas.drawBitmap(logoBitmap, sourceRects[i], destinationRects[i], inactivePaint);
        }

        float normalizedPhase = phase % SEGMENT_COUNT;
        if (normalizedPhase < 0f) normalizedPhase += SEGMENT_COUNT;
        int current = ((int) Math.floor(normalizedPhase)) % SEGMENT_COUNT;
        int next = (current + 1) % SEGMENT_COUNT;
        float stepProgress = normalizedPhase - (float) Math.floor(normalizedPhase);

        float transition = 0f;
        if (stepProgress > HOLD_PORTION) {
            transition = (stepProgress - HOLD_PORTION) / (1f - HOLD_PORTION);
            // Smoothstep removes the hard edge when one segment hands off to the next.
            transition = transition * transition * (3f - 2f * transition);
        }

        drawActiveSegment(canvas, current, 1f - transition);
        drawActiveSegment(canvas, next, transition);
    }

    private void drawActiveSegment(Canvas canvas, int index, float intensity) {
        if (intensity <= 0.001f) return;

        wideGlowPaint.setAlpha(Math.round(92f * intensity));
        canvas.drawBitmap(logoBitmap, sourceRects[index], destinationRects[index], wideGlowPaint);

        tightGlowPaint.setAlpha(Math.round(178f * intensity));
        canvas.drawBitmap(logoBitmap, sourceRects[index], destinationRects[index], tightGlowPaint);

        activePaint.setAlpha(Math.round(255f * intensity));
        canvas.drawBitmap(logoBitmap, sourceRects[index], destinationRects[index], activePaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopGlowAnimation();
        super.onDetachedFromWindow();
    }
}
