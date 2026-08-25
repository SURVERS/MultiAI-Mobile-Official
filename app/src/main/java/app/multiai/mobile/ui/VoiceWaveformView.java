package app.multiai.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Красивая кастомная waveform visualization — стиль ChatGPT голос.
 * Рендерит живые столбцы с бегущей волной + реакция на реальную громкость микрофона.
 */
public class VoiceWaveformView extends View {

    private static final int BAR_COUNT = 48;
    private static final float MIN_HEIGHT = 0.08f;
    private static final float MAX_HEIGHT = 1.0f;
    private static final float SMOOTHING = 0.78f;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final float[] targets = new float[BAR_COUNT];
    private final float[] current = new float[BAR_COUNT];

    private ValueAnimator idleAnimator;
    private float wavePhase = 0f;
    private float amplitudeFactor = 0.3f;
    private boolean listening = true;

    public VoiceWaveformView(Context context) { super(context); init(); }
    public VoiceWaveformView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public VoiceWaveformView(Context context, AttributeSet attrs, int def) { super(context, attrs, def); init(); }

    private void init() {
        // Resolve the bar color from the theme accent token so the waveform stays
        // visible in both light and dark mode (was a hardcoded light gray that
        // vanished on light surfaces).
        barPaint.setColor(resolveBarColor());
        barPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < BAR_COUNT; i++) {
            current[i] = MIN_HEIGHT;
            targets[i] = MIN_HEIGHT;
        }
        startIdle();
    }

    /** Picks a bar color that reads on the current background. */
    private int resolveBarColor() {
        try {
            return androidx.core.content.ContextCompat.getColor(getContext(), app.multiai.mobile.R.color.accent);
        } catch (Exception ignored) {
            return 0xFFE5E5E7; // fallback to the original light gray
        }
    }

    public void setListening(boolean listening) {
        this.listening = listening;
        if (listening) startIdle();
        else stopIdle();
    }

    /** Реальная громкость (0..1) с микрофона. */
    public void setAmplitude(float amp) {
        amplitudeFactor = Math.max(0.15f, Math.min(1.0f, amp));
    }

    private void startIdle() {
        if (idleAnimator != null && idleAnimator.isRunning()) return;
        idleAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        idleAnimator.setDuration(1800);
        idleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idleAnimator.setInterpolator(new LinearInterpolator());
        idleAnimator.addUpdateListener(anim -> {
            wavePhase = (float) anim.getAnimatedValue();
            tick();
            invalidate();
        });
        idleAnimator.start();
    }

    private void stopIdle() {
        if (idleAnimator != null) {
            idleAnimator.cancel();
            idleAnimator = null;
        }
    }

    private void tick() {
        for (int i = 0; i < BAR_COUNT; i++) {
            float phaseOffset = (float) i / BAR_COUNT * (float) (Math.PI * 4);
            // Центральный gaussian "пик" движется
            float centerDistance = Math.abs(i - BAR_COUNT / 2f) / (BAR_COUNT / 2f);
            float envelope = (float) Math.exp(-centerDistance * centerDistance * 2.2f);
            // Бегущая волна
            float wave = (float) ((Math.sin(wavePhase * 3f + phaseOffset) + 1f) * 0.5f);
            float noise = (float) (Math.sin(wavePhase * 5.3f + phaseOffset * 1.7f) * 0.3f + 0.7f);
            targets[i] = MIN_HEIGHT + (envelope * 0.5f + wave * 0.35f + noise * 0.15f) * amplitudeFactor * (MAX_HEIGHT - MIN_HEIGHT);
            current[i] = current[i] * SMOOTHING + targets[i] * (1 - SMOOTHING);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        float spacing = width / (float) BAR_COUNT;
        float barWidth = Math.max(2f, spacing * 0.5f);
        float centerY = height / 2f;
        float radius = barWidth / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barHeight = current[i] * height * 0.85f;
            float left = i * spacing + (spacing - barWidth) / 2f;
            float top = centerY - barHeight / 2f;
            float bottom = centerY + barHeight / 2f;
            barRect.set(left, top, left + barWidth, bottom);
            canvas.drawRoundRect(barRect, radius, radius, barPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopIdle();
    }
}
