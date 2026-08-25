package app.multiai.mobile.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import app.multiai.mobile.R;

/**
 * Кольцевая диаграмма «Структура расходов» (доли моделей).
 * В центре — общее количество токенов и подпись, снаружи ничего лишнего.
 */
public class DonutChartView extends View {

    /** Сегмент: цвет модели + потраченные токены. */
    public static final class Segment {
        public final int color;
        public final float value;

        public Segment(int color, float value) {
            this.color = color;
            this.value = value;
        }
    }

    private static final float RING_WIDTH_DP = 20f;
    private static final float SEGMENT_GAP_DEG = 2.5f;
    private static final float CENTER_TEXT_PADDING_DP = 6f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float centerTextBaseSizePx;
    private float centerLabelBaseSizePx;
    private final RectF ringRect = new RectF();
    private final RectF elevatedRect = new RectF();

    private List<Segment> segments = new ArrayList<>();
    private String centerText = "";
    private String centerLabel = "";

    /** Доля развёрнутого кольца для анимации (0..1). */
    private float reveal = 1f;
    private android.animation.ValueAnimator revealAnim;

    /** Индекс выделенного сегмента (-1 = ничего не выделено; остальные притушены). */
    private int highlighted = -1;

    /** Тап по сегменту кольца. */
    public interface OnSegmentTapListener {
        void onSegmentTap(int index);
    }

    private OnSegmentTapListener tapListener;

    public void setOnSegmentTapListener(@Nullable OnSegmentTapListener listener) {
        this.tapListener = listener;
    }

    /** Обновляет только центральный текст (например, при выделении модели). */
    public void setCenter(@Nullable String text, @Nullable String label) {
        this.centerText = text != null ? text : "";
        this.centerLabel = label != null ? label : "";
        invalidate();
    }

    public DonutChartView(Context context) { this(context, null); }
    public DonutChartView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        float density = getResources().getDisplayMetrics().density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(RING_WIDTH_DP * density);
        ringPaint.setStrokeCap(Paint.Cap.BUTT);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(RING_WIDTH_DP * density);
        trackPaint.setColor(ContextCompat.getColor(getContext(), R.color.surface_tonal));

        centerTextBaseSizePx = 17f * scaled;
        centerTextPaint.setTextSize(centerTextBaseSizePx);
        centerTextPaint.setFakeBoldText(true);
        centerTextPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_primary));

        centerLabelBaseSizePx = 9.5f * scaled;
        centerLabelPaint.setTextSize(centerLabelBaseSizePx);
        centerLabelPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_hint));
    }

    public void setData(@NonNull List<Segment> segments, @Nullable String centerText, @Nullable String centerLabel) {
        this.segments = segments;
        this.centerText = centerText != null ? centerText : "";
        this.centerLabel = centerLabel != null ? centerLabel : "";
        startReveal();
        invalidate();
    }

    /** Кольцо плавно «разворачивается» по часовой. */
    private void startReveal() {
        if (revealAnim != null) revealAnim.cancel();
        reveal = 0f;
        revealAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        revealAnim.setDuration(700L);
        revealAnim.setInterpolator(
                new android.view.animation.DecelerateInterpolator());
        revealAnim.addUpdateListener(a -> {
            reveal = (float) a.getAnimatedValue();
            invalidate();
        });
        revealAnim.start();
    }

    /** Выделить сегмент (тап по легенде); -1 — сбросить. */
    public void setHighlight(int index) {
        this.highlighted = index;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN
                && tapListener != null && !segments.isEmpty()) {
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float dx = event.getX() - cx;
            float dy = event.getY() - cy;
            float dist = (float) Math.hypot(dx, dy);
            float size = Math.min(getWidth(), getHeight());
            float inset = RING_WIDTH_DP * density / 2f + 2f * density;
            float ringR = size / 2f - inset;
            float half = RING_WIDTH_DP * density / 2f + 6f * density; // допуск пальца

            if (Math.abs(dist - ringR) <= half) {
                float total = 0f;
                for (Segment s : segments) total += s.value;
                if (total <= 0f) return performClick();

                float angle = (float) Math.toDegrees(Math.atan2(dy, dx)) + 90f;
                if (angle < 0f) angle += 360f;

                float start = 0f;
                for (int i = 0; i < segments.size(); i++) {
                    float sweep = 360f * segments.get(i).value / total;
                    if (angle >= start && angle < start + sweep) {
                        tapListener.onSegmentTap(i);
                        performClick();
                        return true;
                    }
                    start += sweep;
                }
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float size = Math.min(getWidth(), getHeight());
        // Запас по краям: выделенный сегмент «выпирает» наружу.
        float inset = RING_WIDTH_DP * density / 2f + 8f * density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        ringRect.set(cx - size / 2f + inset, cy - size / 2f + inset,
                cx + size / 2f - inset, cy + size / 2f - inset);

        float total = 0f;
        for (Segment s : segments) total += s.value;

        canvas.drawArc(ringRect, 0f, 360f, false, trackPaint);

        if (total <= 0f || segments.isEmpty()) {
            drawCenter(canvas, cy);
            return;
        }

        float start = -90f;
        int lastIdx = segments.size() - 1;
        for (int i = 0; i < segments.size(); i++) {
            float sweep = 360f * segments.get(i).value / total * reveal;
            // Зазор только МЕЖДУ сегментами: последний дотягивает дугу до
            // конца (иначе сверху оставалась щель — «пустое место»).
            boolean isLast = i == lastIdx;
            float gap = segments.size() > 1 && !isLast ? SEGMENT_GAP_DEG : 0f;
            float visible = Math.max(0f, sweep - gap);
            if (visible > 0f) {
                ringPaint.setColor(segments.get(i).color);
                ringPaint.setAlpha(highlighted < 0 || highlighted == i ? 255 : 70);
                if (highlighted == i) {
                    // Выделенный сегмент выше остальных: шире кольцо и радиус наружу.
                    float extra = 3.5f * density;
                    elevatedRect.set(ringRect.left - extra, ringRect.top - extra,
                            ringRect.right + extra, ringRect.bottom + extra);
                    ringPaint.setStrokeWidth(RING_WIDTH_DP * density + 4f * density);
                    canvas.drawArc(elevatedRect, start + (gap > 0 ? gap / 2f : 0f), visible, false, ringPaint);
                    ringPaint.setStrokeWidth(RING_WIDTH_DP * density);
                } else {
                    canvas.drawArc(ringRect, start + (gap > 0 ? gap / 2f : 0f), visible, false, ringPaint);
                }
            }
            start += sweep;
        }
        ringPaint.setAlpha(255);

        drawCenter(canvas, cy);
    }

    private static void fitTextToWidth(Paint paint, float baseSizePx,
                                       String text, float maxWidth) {
        paint.setTextSize(baseSizePx);
        if (text == null || text.isEmpty()) return;
        float measured = paint.measureText(text);
        if (measured > maxWidth && measured > 0f) {
            paint.setTextSize(baseSizePx * maxWidth / measured);
        }
    }

    private void drawCenter(Canvas canvas, float cy) {
        if (centerText.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        float holeWidth = Math.max(0f,
                ringRect.width() - trackPaint.getStrokeWidth());
        float maxTextWidth = Math.max(1f,
                holeWidth - 2f * CENTER_TEXT_PADDING_DP * density);
        fitTextToWidth(centerTextPaint, centerTextBaseSizePx,
                centerText, maxTextWidth);
        fitTextToWidth(centerLabelPaint, centerLabelBaseSizePx,
                centerLabel, maxTextWidth);

        float textY = centerLabel.isEmpty()
                ? cy + centerTextPaint.getTextSize() / 3f
                : cy - centerLabelPaint.getTextSize() / 2f;
        centerTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(centerText, getWidth() / 2f, textY, centerTextPaint);
        if (!centerLabel.isEmpty()) {
            centerLabelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(centerLabel, getWidth() / 2f,
                    cy + centerTextPaint.getTextSize() * 0.9f, centerLabelPaint);
        }
    }
}
