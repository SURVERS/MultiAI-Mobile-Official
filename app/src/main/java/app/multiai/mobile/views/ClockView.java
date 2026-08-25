package app.multiai.mobile.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.TimeZone;

import app.multiai.mobile.utils.AccentColor;

/**
 * Аналоговый циферблат часов.
 * Рендерит круг с делениями и 3 стрелки (часовая, минутная, секундная).
 * Поддерживает произвольный timezone.
 */
public class ClockView extends View {
    private Paint bgPaint;
    private Paint tickPaint;
    private Paint hourPaint;
    private Paint minutePaint;
    private Paint secondPaint;
    private Paint centerPaint;

    private int hour = 0;
    private int minute = 0;
    private int second = 0;
    private String timezone = null;

    public ClockView(Context context) {
        super(context);
        init();
    }

    public ClockView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ClockView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), app.multiai.mobile.R.color.surface_card));
        bgPaint.setStyle(Paint.Style.FILL);

        tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tickPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), app.multiai.mobile.R.color.text_hint));
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(dp(1.2f));

        hourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hourPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), app.multiai.mobile.R.color.text_primary));
        hourPaint.setStyle(Paint.Style.STROKE);
        hourPaint.setStrokeWidth(dp(3f));
        hourPaint.setStrokeCap(Paint.Cap.ROUND);

        minutePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minutePaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), app.multiai.mobile.R.color.text_primary));
        minutePaint.setStyle(Paint.Style.STROKE);
        minutePaint.setStrokeWidth(dp(2f));
        minutePaint.setStrokeCap(Paint.Cap.ROUND);

        secondPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secondPaint.setColor(AccentColor.resolve(getContext()));
        secondPaint.setStyle(Paint.Style.STROKE);
        secondPaint.setStrokeWidth(dp(1.3f));
        secondPaint.setStrokeCap(Paint.Cap.ROUND);

        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setColor(AccentColor.resolve(getContext()));
        centerPaint.setStyle(Paint.Style.FILL);

        syncNow();
    }

    public void setTimezone(String tz) {
        this.timezone = tz;
        syncNow();
        invalidate();
    }

    public void setTime(int h, int m, int s) {
        this.hour = h;
        this.minute = m;
        this.second = s;
        invalidate();
    }

    private void syncNow() {
        Calendar cal = (timezone != null && !timezone.isEmpty())
            ? Calendar.getInstance(TimeZone.getTimeZone(timezone))
            : Calendar.getInstance();
        hour = cal.get(Calendar.HOUR_OF_DAY);
        minute = cal.get(Calendar.MINUTE);
        second = cal.get(Calendar.SECOND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(2);

        // Circle background
        canvas.drawCircle(cx, cy, radius, bgPaint);

        // 12 tick marks
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30 - 90);
            float inner = radius - dp(i % 3 == 0 ? 9 : 5);
            float outer = radius - dp(2);
            float sx = cx + (float) Math.cos(angle) * inner;
            float sy = cy + (float) Math.sin(angle) * inner;
            float ex = cx + (float) Math.cos(angle) * outer;
            float ey = cy + (float) Math.sin(angle) * outer;
            tickPaint.setStrokeWidth(dp(i % 3 == 0 ? 2f : 1.2f));
            canvas.drawLine(sx, sy, ex, ey, tickPaint);
        }

        // Hour hand
        float hourAngle = (float) Math.toRadians((hour % 12 + minute / 60f) * 30 - 90);
        canvas.drawLine(cx, cy,
            cx + (float) Math.cos(hourAngle) * radius * 0.5f,
            cy + (float) Math.sin(hourAngle) * radius * 0.5f,
            hourPaint);

        // Minute hand
        float minAngle = (float) Math.toRadians((minute + second / 60f) * 6 - 90);
        canvas.drawLine(cx, cy,
            cx + (float) Math.cos(minAngle) * radius * 0.72f,
            cy + (float) Math.sin(minAngle) * radius * 0.72f,
            minutePaint);

        // Second hand
        float secAngle = (float) Math.toRadians(second * 6 - 90);
        canvas.drawLine(cx, cy,
            cx + (float) Math.cos(secAngle) * radius * 0.85f,
            cy + (float) Math.sin(secAngle) * radius * 0.85f,
            secondPaint);

        // Center dot
        canvas.drawCircle(cx, cy, dp(3), centerPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
