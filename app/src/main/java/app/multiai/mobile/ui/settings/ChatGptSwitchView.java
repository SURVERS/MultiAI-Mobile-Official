package app.multiai.mobile.ui.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.CompoundButton;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.MotionKit;
import app.multiai.mobile.utils.AccentColor;

/** A deterministic ChatGPT-style switch that does not depend on Material switch drawables. */
public final class ChatGptSwitchView extends CompoundButton {

    private static final int TRACK_WIDTH_DP = 52;
    private static final int TRACK_HEIGHT_DP = 32;
    private static final int THUMB_DIAMETER_DP = 24;
    private static final int TOUCH_WIDTH_DP = 64;
    private static final int TOUCH_HEIGHT_DP = 48;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackBounds = new RectF();
    private float checkedProgress;
    private ValueAnimator progressAnimator;

    public ChatGptSwitchView(Context context) {
        this(context, null);
    }

    public ChatGptSwitchView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setButtonDrawable(null);
        setBackground(null);
        setText(null);
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinWidth(dp(TOUCH_WIDTH_DP));
        setMinHeight(dp(TOUCH_HEIGHT_DP));
        checkedProgress = isChecked() ? 1f : 0f;
        updateStateDescription();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(dp(TOUCH_WIDTH_DP), widthMeasureSpec);
        int height = resolveSize(dp(TOUCH_HEIGHT_DP), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int trackWidth = Math.min(dp(TRACK_WIDTH_DP), getWidth());
        int trackHeight = Math.min(dp(TRACK_HEIGHT_DP), getHeight());
        float left = (getWidth() - trackWidth) / 2f;
        float top = (getHeight() - trackHeight) / 2f;
        trackBounds.set(left, top, left + trackWidth, top + trackHeight);

        int offTrack = ContextCompat.getColor(getContext(), R.color.switch_track_off);
        int trackColor = isEnabled()
                ? blend(offTrack, AccentColor.resolve(getContext()), checkedProgress)
                : ContextCompat.getColor(getContext(), R.color.control_disabled);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColor);
        canvas.drawRoundRect(trackBounds, trackHeight / 2f, trackHeight / 2f, paint);

        int thumbDiameter = Math.min(dp(THUMB_DIAMETER_DP), trackHeight - dp(4));
        float radius = thumbDiameter / 2f;
        float startX = trackBounds.left + trackHeight / 2f;
        float endX = trackBounds.right - trackHeight / 2f;
        float thumbX = startX + (endX - startX) * checkedProgress;
        float thumbY = trackBounds.centerY();

        int offThumb = ContextCompat.getColor(getContext(), R.color.switch_thumb_off);
        int onThumb = AccentColor.contrastForeground(AccentColor.resolve(getContext()));
        int thumbColor = isEnabled()
                ? blend(offThumb, onThumb, checkedProgress)
                : ContextCompat.getColor(getContext(), R.color.text_disabled);
        paint.setColor(thumbColor);
        canvas.drawCircle(thumbX, thumbY, radius, paint);

        if (isFocused()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor((AccentColor.resolve(getContext()) & 0x00FFFFFF) | 0x66000000);
            RectF focus = new RectF(trackBounds);
            focus.inset(-dp(2), -dp(2));
            canvas.drawRoundRect(focus, focus.height() / 2f, focus.height() / 2f, paint);
        }
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (!changed) {
            checkedProgress = checked ? 1f : 0f;
            invalidate();
            updateStateDescription();
            return;
        }
        animateProgress(checked ? 1f : 0f);
        updateStateDescription();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return android.widget.Switch.class.getName();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (progressAnimator != null) progressAnimator.cancel();
        super.onDetachedFromWindow();
    }

    private void animateProgress(float target) {
        if (progressAnimator != null) progressAnimator.cancel();
        if (!isLaidOut()) {
            checkedProgress = target;
            invalidate();
            return;
        }
        progressAnimator = ValueAnimator.ofFloat(checkedProgress, target);
        progressAnimator.setDuration(MotionKit.STANDARD);
        progressAnimator.setInterpolator(MotionKit.STANDARD_EASING);
        progressAnimator.addUpdateListener(animation -> {
            checkedProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        progressAnimator.start();
    }

    private void updateStateDescription() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setStateDescription(isChecked() ? "Включено" : "Выключено");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int blend(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = Math.round(android.graphics.Color.alpha(from)
                + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t);
        int r = Math.round(android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t);
        int g = Math.round(android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t);
        int b = Math.round(android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t);
        return android.graphics.Color.argb(a, r, g, b);
    }
}
