package app.multiai.mobile.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Locale;

import app.multiai.mobile.R;

/**
 * Логарифмический слайдер объёма токенов для «Магазина» (1M…10B).
 *
 * <p>Отличия от системного SeekBar: шкала логарифмическая, поэтому отметки
 * 1M / 10M / 100M / 1B / 10B идут строго через равные промежутки; трек и подписи
 * рисуются сами, крупный палец с ореолом, значения «прилипают» к красивым
 * числам. Слушатель получает готовое количество токенов.
 */
public class LogSliderView extends View {

    public interface Listener {
        void onTokensChanged(long tokens, boolean fromUser);
    }

    private static final long MIN = 1_000_000L;
    private static final long MAX = 10_000_000_000L;
    private static final long[] TICKS = {1_000_000L, 10_000_000L, 100_000_000L,
            1_000_000_000L, 10_000_000_000L};
    private static final String[] TICK_LABELS = {"1M", "10M", "100M", "1B", "10B"};

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long tokens = MIN;
    private float fraction; // 0..1 по лог-шкале
    private int accent = ContextCompat.getColor(getContext(), R.color.accent);
    private int onAccent = 0xFFFFFFFF;
    private Listener listener;

    public LogSliderView(Context context) { this(context, null); }
    public LogSliderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(5 * density);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(5 * density);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint.setStyle(Paint.Style.FILL);

        tickLabelPaint.setTextSize(10 * scaled);
        tickLabelPaint.setTextAlign(Paint.Align.CENTER);

        thumbPaint.setStyle(Paint.Style.FILL);
        thumbCorePaint.setStyle(Paint.Style.FILL);
        haloPaint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_SOFTWARE, haloPaint); // для RadialGradient-ореола
        applyColors();
    }

    public void setAccentColor(int accent, int onAccent) {
        this.accent = accent;
        this.onAccent = onAccent;
        applyColors();
        invalidate();
    }

    private void applyColors() {
        fillPaint.setColor(accent);
        tickPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_hint));
        tickLabelPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_hint));
        thumbPaint.setColor(accent);
        thumbCorePaint.setColor(onAccent);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Установка значения извне (чипы, поле ввода) — без события fromUser. */
    public void setTokens(long value) {
        this.tokens = clampSnap(value);
        this.fraction = fractionOf(this.tokens);
        invalidate();
    }

    // ─────────────── геометрия ───────────────

    private float padPx() {
        // Радиус гало: палец + свечение должны полностью влезать по краям.
        return 22 * getResources().getDisplayMetrics().density;
    }

    private float trackLeft() { return padPx(); }
    private float trackRight() { return getWidth() - padPx(); }

    private float fractionOf(long v) {
        double f = (Math.log(Math.max(v, MIN)) - Math.log(MIN))
                / (Math.log(MAX) - Math.log(MIN));
        return (float) Math.max(0, Math.min(1, f));
    }

    private long tokensOfFraction(float f) {
        double t = Math.exp(Math.log(MIN) + f * (Math.log(MAX) - Math.log(MIN)));
        return clampSnap(Math.round(t));
    }

    private static long clampSnap(long v) {
        v = Math.max(MIN, Math.min(MAX, v));
        int digits = (int) Math.floor(Math.log10(v)) - 1; // 2 значащие цифры
        long pow = (long) Math.pow(10, Math.max(0, digits));
        long sig = Math.round(v / (double) pow);
        if (sig <= 10) sig = Math.max(1, Math.min(10, sig));
        else if (sig <= 50) sig = (sig + 4) / 5 * 5;
        else sig = (sig + 24) / 50 * 50;
        return sig * pow;
    }

    // ─────────────── рисование ───────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = trackLeft();
        float right = trackRight();
        float w = right - left;
        float cy = 26 * density;                    // центр трека
        float bottomLabelY = cy + 9 * density + tickLabelPaint.getTextSize();

        // Трек + заполненная часть
        trackPaint.setColor(ContextCompat.getColor(getContext(), R.color.surface_tonal));
        canvas.drawLine(left, cy, right, cy, trackPaint);
        float thumbX = left + w * fraction;
        canvas.drawLine(left, cy, thumbX, cy, fillPaint);

        // Отметки: точки на треке + подписи под ними (лог-шкала → равные шаги)
        for (int i = 0; i < TICKS.length; i++) {
            float x = left + w * fractionOf(TICKS[i]);
            boolean passed = TICKS[i] <= tokens;
            tickPaint.setColor(ContextCompat.getColor(getContext(),
                    passed ? R.color.text_hint : R.color.stroke));
            canvas.drawCircle(x, cy, 2.4f * density, tickPaint);
            canvas.drawText(TICK_LABELS[i], x, bottomLabelY, tickLabelPaint);
        }

        // Ореол + палец (гало умещается в отступы по краям — не обрезается)
        float r = 10 * density;
        haloPaint.setShader(new RadialGradient(thumbX, cy, 2.0f * r,
                (accent & 0x00FFFFFF) | 0x2E000000,
                (accent & 0x00FFFFFF), Shader.TileMode.CLAMP));
        canvas.drawCircle(thumbX, cy, 2.0f * r, haloPaint);
        canvas.drawCircle(thumbX, cy, r, thumbPaint);
        canvas.drawCircle(thumbX, cy, 3.6f * density, thumbCorePaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int h = (int) (62 * density);
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), h);
    }

    // ─────────────── касания ───────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Не даём ScrollView перехватить горизонтальное перетаскивание.
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                updateFromTouch(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                // Финальное «прилипание» к красивому числу.
                tokens = clampSnap(tokens);
                fraction = fractionOf(tokens);
                invalidate();
                if (listener != null) listener.onTokensChanged(tokens, true);
                performClick();
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** Плавное обновление прямо из позиции пальца — без снапа во время движения. */
    private void updateFromTouch(MotionEvent event) {
        float left = trackLeft();
        float right = trackRight();
        float f = (event.getX() - left) / Math.max(1f, right - left);
        f = Math.max(0f, Math.min(1f, f));
        fraction = f;
        long v = Math.round(Math.exp(Math.log(MIN) + f * (Math.log(MAX) - Math.log(MIN))));
        tokens = v;
        invalidate();
        if (listener != null) listener.onTokensChanged(tokens, true);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
