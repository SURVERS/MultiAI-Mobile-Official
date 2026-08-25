package app.multiai.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * TextView с бегущей волной-градиентом по тексту — как в «Читаю».
 * Волна — горизонтальный градиент от базового цвета к белому и обратно,
 * который перемещается слева направо бесконечно.
 */
public class WaveTextView extends AppCompatTextView {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator waveAnimator;
    private float waveX = -1f;
    private boolean waving = false;
    private int baseColor = Color.parseColor("#9AA0A6");
    private int waveColor = Color.WHITE;

    public WaveTextView(Context context) {
        super(context);
    }

    public WaveTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WaveTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setWaving(boolean waving) {
        if (this.waving == waving) {
            // Re-attach without rebind: the flag survived, the animator did not.
            if (waving) ensureWave();
            return;
        }
        this.waving = waving;
        if (waving) ensureWave(); else stopWave();
        invalidate();
    }

    private void ensureWave() {
        if (!waving || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        if (waveAnimator == null || !waveAnimator.isRunning()) startWave();
    }

    public void setBaseColor(int color) {
        this.baseColor = color;
        invalidate();
    }

    public void setWaveColor(int color) {
        this.waveColor = color;
        invalidate();
    }

    private void startWave() {
        stopWave();
        waveAnimator = ValueAnimator.ofFloat(-1f, 2f);
        waveAnimator.setDuration(1350L);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(new LinearInterpolator());
        waveAnimator.addUpdateListener(a -> {
            waveX = (float) a.getAnimatedValue();
            invalidate();
        });
        waveAnimator.start();
    }

    private void stopWave() {
        if (waveAnimator != null) {
            waveAnimator.cancel();
            waveAnimator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!waving || getWidth() == 0 || getText().length() == 0) {
            super.onDraw(canvas);
            return;
        }
        // Рисуем текст с градиентным шейдером-волной
        Paint paint = getPaint();
        float center = waveX * getWidth();
        float radius = Math.max(getWidth() * 0.42f, 1f);
        Shader shader = new LinearGradient(
                center - radius, 0, center + radius, 0,
                new int[]{baseColor, waveColor, baseColor},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        // super.onDraw использует getPaint() — шейдер применится
        super.onDraw(canvas);
        paint.setShader(null);
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
