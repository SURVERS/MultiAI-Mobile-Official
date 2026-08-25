package app.multiai.mobile.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.multiai.mobile.R;

/**
 * Линейный график расхода токенов по дням (экран «Расходы» профиля).
 *
 * <p>Рисует сглаженную кривую с полупрозрачной заливкой, горизонтальную сетку с
 * компактными подписями оси Y (0M … 10B) и даты по оси X. Тап по точке показывает
 * плашку с датой и потраченными токенами. Цвет линии задаётся акцентом
 * пользователя через {@link #setAccent}.
 */
public class UsageChartView extends View {

    /** Одна точка: день (метка для оси X) + потраченные токены. */
    public static final class Point {
        public final String label;
        public final float tokens;

        public Point(String label, float tokens) {
            this.label = label;
            this.tokens = tokens;
        }
    }

    private static final int GRID_LINES = 5;
    private static final float CURVE_SMOOTHNESS = 0.18f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final RectF tipRect = new RectF();

    private List<Point> points = new ArrayList<>();
    private int accent = ContextCompat.getColor(getContext(), R.color.accent);
    private int selected = -1;
    private float yMax = 1f;

    /** Доля отрисованного графика для анимации «прорисовки» (0..1). */
    private float reveal = 1f;
    private android.animation.ValueAnimator revealAnim;

    public UsageChartView(Context context) { this(context, null); }
    public UsageChartView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(density);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{density * 3, density * 3}, 0));

        axisTextPaint.setTextSize(9.5f * scaled);
        axisTextPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_hint));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.2f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setPathEffect(new CornerPathEffect(density * 6));

        fillPaint.setStyle(Paint.Style.FILL);

        dotPaint.setStyle(Paint.Style.FILL);
        dotHaloPaint.setStyle(Paint.Style.FILL);

        tipPaint.setStyle(Paint.Style.FILL);
        tipTextPaint.setTextSize(10f * scaled);
        tipSubPaint.setTextSize(11f * scaled);
    }

    public void setAccent(int accent) {
        this.accent = accent;
        linePaint.setColor(accent);
        dotPaint.setColor(accent);
        dotHaloPaint.setColor((accent & 0x00FFFFFF) | 0x29000000);
        fillPaint.setColor((accent & 0x00FFFFFF) | 0x24000000);
        tipPaint.setColor(ContextCompat.getColor(getContext(), R.color.surface_elevated));
        tipTextPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_hint));
        tipSubPaint.setColor(accent);
        tipSubPaint.setFakeBoldText(true);
        invalidate();
    }

    public void setData(@NonNull List<Point> data) {
        this.points = data;
        this.selected = -1;
        float max = 0f;
        for (Point p : data) max = Math.max(max, p.tokens);
        this.yMax = niceCeil(max <= 0f ? 1f : max);
        startReveal();
        invalidate();
    }

    /** Плавно «прорисовывает» кривую слева направо. */
    private void startReveal() {
        if (revealAnim != null) revealAnim.cancel();
        reveal = 0f;
        revealAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        revealAnim.setDuration(650L);
        revealAnim.setInterpolator(
                new android.view.animation.DecelerateInterpolator());
        revealAnim.addUpdateListener(a -> {
            reveal = (float) a.getAnimatedValue();
            invalidate();
        });
        revealAnim.start();
    }

    /** Округляет максимум до «красивой» величины сетки: 1/2/5 × 10^n. */
    private static float niceCeil(float v) {
        int exp = (int) Math.floor(Math.log10(v));
        float base = (float) Math.pow(10, exp);
        for (float m : new float[]{1f, 2f, 2.5f, 5f, 10f}) {
            if (v <= m * base) return m * base;
        }
        return 10f * base;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        int leftPad = (int) (34 * density);
        int rightPad = (int) (6 * density);
        int topPad = (int) (10 * density);
        int bottomPad = (int) (20 * density);

        float w = getWidth() - leftPad - rightPad;
        float h = getHeight() - topPad - bottomPad;

        // Сетка + подписи Y.
        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.divider));
        for (int i = 0; i < GRID_LINES; i++) {
            float y = topPad + h * i / (GRID_LINES - 1f);
            canvas.drawLine(leftPad, y, getWidth() - rightPad, y, gridPaint);
            String label = compact(yMax * (1f - i / (GRID_LINES - 1f)));
            canvas.drawText(label, leftPad - density * 4 - axisTextPaint.measureText(label), y + axisTextPaint.getTextSize() / 3, axisTextPaint);
        }

        // Точки (частично — по анимации прорисовки). Одна точка — рисуем её
        // без кривой (nDraw клэмпится к n, иначе xs[1] на длине-1 крашит).
        int n = points.size();
        int nDraw = reveal >= 1f ? n : Math.max(2, Math.round(n * reveal));
        nDraw = Math.min(nDraw, n);
        float stepX = n > 1 ? w / (n - 1) : 0f;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = leftPad + stepX * i;
            ys[i] = topPad + h * (1f - points.get(i).tokens / yMax);
        }

        if (n == 1) {
            // Единственная точка: круг в центре + подпись даты снизу.
            float cx = leftPad + w / 2f;
            canvas.drawCircle(cx, ys[0], 4f * density, dotPaint);
            String label = axisLabel(points.get(0).label);
            float tw = axisTextPaint.measureText(label);
            canvas.drawText(label, cx - tw / 2f, getHeight() - density * 5, axisTextPaint);
            return;
        }

        // Плавная кривая.
        linePath.reset();
        fillPath.reset();
        linePath.moveTo(xs[0], ys[0]);
        for (int i = 0; i < nDraw - 1; i++) {
            float midX = (xs[i] + xs[i + 1]) / 2f;
            float c1y = ys[i] + (ys[i + 1] - ys[i]) * CURVE_SMOOTHNESS;
            float c2y = ys[i + 1] - (ys[i + 1] - ys[i]) * CURVE_SMOOTHNESS;
            linePath.cubicTo(midX, c1y, midX, c2y, xs[i + 1], ys[i + 1]);
        }
        fillPath.set(linePath);
        fillPath.lineTo(xs[nDraw - 1], topPad + h);
        fillPath.lineTo(xs[0], topPad + h);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // Выбранная точка + плашка.
        if (selected >= 0 && selected < n) {
            canvas.drawCircle(xs[selected], ys[selected], 6.5f * density, dotHaloPaint);
            canvas.drawCircle(xs[selected], ys[selected], 3.2f * density, dotPaint);
            drawTooltip(canvas, xs, ys, density);
        }

        // Подписи X: не больше ~5, распределённые равномерно. Формат оси —
        // «13.08» из полной даты «2026-08-13» (голый «13» на разреженных
        // данных был непонятен).
        int labelCount = Math.min(5, n);
        int stride = Math.max(1, (n - 1) / Math.max(1, labelCount - 1));
        for (int i = 0; i < n; i += stride) {
            String label = axisLabel(points.get(i).label);
            float tw = axisTextPaint.measureText(label);
            float tx = Math.max(leftPad, Math.min(xs[i] - tw / 2f, getWidth() - rightPad - tw));
            canvas.drawText(label, tx, getHeight() - density * 5, axisTextPaint);
        }
    }

    /** Подпись оси X: «2026-08-13» → «13.08»; чужой формат — как есть. */
    private static String axisLabel(String raw) {
        if (raw != null && raw.length() >= 10 && raw.charAt(4) == '-') {
            return raw.substring(8, 10) + "." + raw.substring(5, 7);
        }
        return raw;
    }

    /** Заголовок тултипа: полная дата по-русски «13 авг». */
    private static String tooltipTitle(String raw) {
        if (raw != null && raw.length() >= 10 && raw.charAt(4) == '-') {
            String[] months = {"янв", "фев", "мар", "апр", "мая", "июн",
                    "июл", "авг", "сен", "окт", "ноя", "дек"};
            try {
                int day = Integer.parseInt(raw.substring(8, 10));
                int mon = Integer.parseInt(raw.substring(5, 7));
                if (mon >= 1 && mon <= 12) return day + " " + months[mon - 1];
            } catch (NumberFormatException ignored) {
            }
        }
        return raw;
    }

    private void drawTooltip(Canvas canvas, float[] xs, float[] ys, float density) {
        Point p = points.get(selected);
        String title = tooltipTitle(p.label);
        String value = compact(p.tokens) + " " +
                getResources().getString(R.string.profile_tokens_word);

        float padH = 8 * density, padV = 6 * density;
        float gap = 3 * density;
        float titleW = tipTextPaint.measureText(title);
        float valueW = tipSubPaint.measureText(value);
        float boxW = Math.max(titleW, valueW) + padH * 2;
        float boxH = padV * 2 + tipTextPaint.getTextSize() + gap + tipSubPaint.getTextSize();

        float cx = Math.max(boxW / 2 + 2 * density,
                Math.min(xs[selected], getWidth() - boxW / 2 - 2 * density));
        float top = Math.max(2 * density, ys[selected] - 10 * density - boxH);

        tipRect.set(cx - boxW / 2f, top, cx + boxW / 2f, top + boxH);
        canvas.drawRoundRect(tipRect, 8 * density, 8 * density, tipPaint);

        float textX = tipRect.left + padH;
        canvas.drawText(title, textX, tipRect.top + padV + tipTextPaint.getTextSize(), tipTextPaint);
        canvas.drawText(value, textX, tipRect.top + padV + tipTextPaint.getTextSize() + gap + tipSubPaint.getTextSize(), tipSubPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (points.isEmpty()) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN
                || event.getAction() == MotionEvent.ACTION_MOVE) {
            float density = getResources().getDisplayMetrics().density;
            int leftPad = (int) (34 * density);
            int rightPad = (int) (6 * density);
            float w = getWidth() - leftPad - rightPad;
            int n = points.size();
            float stepX = n > 1 ? w / (n - 1) : 1f;
            int idx = Math.round((event.getX() - leftPad) / stepX);
            idx = Math.max(0, Math.min(n - 1, idx));
            if (idx != selected) {
                selected = idx;
                invalidate();
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** 1234567 → «1.2M», 245000000 → «245M», 1200000000 → «1.2B». */
    public static String compact(float tokens) {
        if (tokens >= 1_000_000_000f) {
            float b = tokens / 1_000_000_000f;
            return String.format(Locale.US, b >= 10f ? "%.0fB" : "%.1fB", b);
        }
        if (tokens >= 1_000_000f) {
            float m = tokens / 1_000_000f;
            return String.format(Locale.US, m >= 10f ? "%.0fM" : "%.1fM", m);
        }
        if (tokens >= 1_000f) {
            float k = tokens / 1_000f;
            return String.format(Locale.US, k >= 10f ? "%.0fK" : "%.1fK", k);
        }
        return String.format(Locale.US, "%.0f", tokens);
    }
}
