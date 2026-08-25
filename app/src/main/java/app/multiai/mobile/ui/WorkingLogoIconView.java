package app.multiai.mobile.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;

/**
 * Анимированная иконка статуса Work Mode: логотип MultiAI с бегущей светяшкой
 * по квадрантам (как на сплэш-скрине) + переворот вокруг вертикальной оси
 * после каждого полного цикла. В завершённом состоянии рисуется статичная
 * галочка {@code ic_check}.
 *
 * <p>Светяшка и переворот управляются <b>одним</b> аниматором: угол поворота
 * вычисляется из прогресса цикла, а не накапливается от текущего
 * {@link #getRotationY()}. Иначе прерванный на полпути флип (RecyclerView
 * отсоединяет строку при скролле) навсегда сдвигал точки покоя на 90°/270° —
 * иконка оставалась стоять рёбром (невидимой) либо сплющенной.</p>
 */
public final class WorkingLogoIconView extends View {

    public static final long CYCLE_DURATION_MS = 1_400L;
    public static final long FLIP_DURATION_MS = 420L;

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
    private Drawable checkDrawable;
    private ValueAnimator cycleAnimator;
    private float phase;
    /** Сколько пол-оборотов уже отыграно; хранится по модулю 2 (0..1). */
    private int flipTurns;
    /** Прогресс внутри текущего цикла — чтобы после reattach продолжить, а не дёрнуться. */
    private float resumeFraction;
    private boolean running;
    private boolean showCheck;

    public WorkingLogoIconView(Context context) {
        super(context);
        initialise();
    }

    public WorkingLogoIconView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initialise();
    }

    public WorkingLogoIconView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialise();
    }

    private void initialise() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);

        logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_multiai_logo);
        if (logoBitmap != null) {
            int halfWidth = logoBitmap.getWidth() / 2;
            int halfHeight = logoBitmap.getHeight() / 2;
            sourceRects[0] = new Rect(0, 0, halfWidth, halfHeight);
            sourceRects[1] = new Rect(halfWidth, 0, logoBitmap.getWidth(), halfHeight);
            sourceRects[2] = new Rect(halfWidth, halfHeight,
                    logoBitmap.getWidth(), logoBitmap.getHeight());
            sourceRects[3] = new Rect(0, halfHeight, halfWidth, logoBitmap.getHeight());
            for (int i = 0; i < SEGMENT_COUNT; i++) {
                destinationRects[i] = new RectF();
            }

            PorterDuffColorFilter inactiveFilter = new PorterDuffColorFilter(
                    INACTIVE_COLOR, PorterDuff.Mode.SRC_IN);
            PorterDuffColorFilter whiteFilter = new PorterDuffColorFilter(
                    Color.WHITE, PorterDuff.Mode.SRC_IN);

            inactivePaint.setColorFilter(inactiveFilter);
            wideGlowPaint.setColorFilter(whiteFilter);
            wideGlowPaint.setMaskFilter(new BlurMaskFilter(dp(14f), BlurMaskFilter.Blur.NORMAL));
            tightGlowPaint.setColorFilter(whiteFilter);
            tightGlowPaint.setMaskFilter(new BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL));
        }

        checkDrawable = ContextCompat.getDrawable(getContext(), R.drawable.ic_check);
        if (checkDrawable != null) {
            checkDrawable = checkDrawable.mutate();
            androidx.core.graphics.drawable.DrawableCompat.setTint(checkDrawable,
                    ContextCompat.getColor(getContext(), R.color.icon_muted));
        }
    }

    /**
     * Включает/выключает анимацию активной работы.
     * После завершения оставляет статичный логотип MultiAI: индикатор загрузки
     * не должен визуально превращаться в отдельную «успешную» галочку.
     */
    public void setRunning(boolean running) {
        if (this.running == running) {
            // RecyclerView can rebind a still-running row after its view was
            // detached. In that case the flag is unchanged but its animator
            // was cancelled in onDetachedFromWindow(), so revive it.
            if (running) ensureCycleAnimation();
            return;
        }
        this.running = running;
        if (running) {
            showCheck = false;
            flipTurns = 0;
            resumeFraction = 0f;
            ensureCycleAnimation();
        } else {
            stopCycleAnimation();
            showCheck = false;
        }
        invalidate();
    }

    public boolean isRunning() {
        return running;
    }

    /** Starts motion only while this recycled row is actually visible. */
    private void ensureCycleAnimation() {
        if (!running || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        if (cycleAnimator == null || !cycleAnimator.isRunning()) startCycleAnimation();
    }

    private void startCycleAnimation() {
        cancelCycleAnimator();
        applyFrame(resumeFraction);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ValueAnimator.areAnimatorsEnabled()) {
            // Без аниматоров показываем ровный кадр логотипа лицом к зрителю.
            setRotationY(0f);
            return;
        }

        cycleAnimator = ValueAnimator.ofFloat(0f, 1f);
        cycleAnimator.setDuration(CYCLE_DURATION_MS);
        cycleAnimator.setInterpolator(new LinearInterpolator());
        cycleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        cycleAnimator.setRepeatMode(ValueAnimator.RESTART);
        cycleAnimator.addUpdateListener(animation -> applyFrame(animation.getAnimatedFraction()));
        cycleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationRepeat(Animator animation) {
                // Каждый цикл — ровно пол-оборота, поэтому чётность и задаёт
                // стартовый угол следующего цикла: 0° → 180° → 0° → …
                flipTurns = (flipTurns + 1) % 2;
            }
        });
        cycleAnimator.start();
        if (resumeFraction > 0f) {
            cycleAnimator.setCurrentPlayTime((long) (resumeFraction * CYCLE_DURATION_MS));
        }
    }

    /**
     * Вычисляет кадр анимации из прогресса цикла: и фазу светяшки, и угол
     * переворота. Детерминированность важна — угол никогда не «уползает».
     */
    private void applyFrame(float fraction) {
        if (fraction < 0f) fraction = 0f;
        if (fraction > 1f) fraction = 1f;
        resumeFraction = fraction;
        phase = fraction * SEGMENT_COUNT;

        float flipProgress = FLIP_DURATION_MS <= 0L
                ? 1f
                : Math.min(1f, fraction * CYCLE_DURATION_MS / (float) FLIP_DURATION_MS);
        // Аналог DecelerateInterpolator(1f): 1 - (1 - x)^2
        float eased = 1f - (1f - flipProgress) * (1f - flipProgress);
        setRotationY((flipTurns * 180f + eased * 180f) % 360f);
        invalidate();
    }

    private void cancelCycleAnimator() {
        if (cycleAnimator != null) {
            cycleAnimator.removeAllUpdateListeners();
            cycleAnimator.removeAllListeners();
            cycleAnimator.cancel();
            cycleAnimator = null;
        }
    }

    /**
     * Останавливает движение и обязательно возвращает иконку лицом к зрителю.
     * Без сброса угла прерванный флип оставлял вид рёбром либо сплющенным.
     */
    private void stopCycleAnimation() {
        cancelCycleAnimator();
        flipTurns = 0;
        resumeFraction = 0f;
        phase = 0f;
        setRotationY(0f);
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
        if (showCheck) {
            drawCheck(canvas);
            return;
        }
        if (logoBitmap == null || destinationRects[0].isEmpty()) return;
        if (!running) {
            // A completed/cleared transient row keeps the branded mark instead
            // of impersonating a successful operation with a check icon.
            activePaint.setAlpha(255);
            for (int i = 0; i < SEGMENT_COUNT; i++) {
                canvas.drawBitmap(logoBitmap, sourceRects[i], destinationRects[i], activePaint);
            }
            return;
        }

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

    private void drawCheck(Canvas canvas) {
        if (checkDrawable == null) return;
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int right = getWidth() - getPaddingRight();
        int bottom = getHeight() - getPaddingBottom();
        checkDrawable.setBounds(left, top, right, bottom);
        checkDrawable.draw(canvas);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureCycleAnimation();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            // Returning from a backgrounded activity does not necessarily bind
            // the RecyclerView item again, so lifecycle must restart it too.
            post(this::ensureCycleAnimation);
        } else {
            cancelCycleAnimator();
            setRotationY(0f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        // Только аниматор: прогресс цикла сохраняем, чтобы после возврата в
        // зону видимости движение продолжилось с того же места.
        cancelCycleAnimator();
        setRotationY(0f);
        super.onDetachedFromWindow();
    }
}
