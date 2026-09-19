package app.multiai.mobile.ui.kit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.content.ContextCompat;
import app.multiai.mobile.R;

/** Non-interactive scroll position overlay; it never captures chat gestures. */
public final class ChatScrollIndicator extends RecyclerView.ItemDecoration {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float shownTop;
    private float shownHeight;
    private float shownAlpha;
    private long lastFrame;
    private long lastScroll;
    private boolean initialized;

    public static void attach(RecyclerView view) {
        ChatScrollIndicator indicator = new ChatScrollIndicator();
        view.addItemDecoration(indicator);
        view.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView recycler, int dx, int dy) {
                if (dy != 0) {
                    indicator.lastScroll = SystemClock.uptimeMillis();
                    recycler.postInvalidateOnAnimation();
                }
            }
            @Override public void onScrollStateChanged(RecyclerView recycler, int state) {
                indicator.lastScroll = SystemClock.uptimeMillis();
                recycler.postInvalidateOnAnimation();
            }
        });
    }

    @Override public void onDrawOver(Canvas canvas, RecyclerView parent, RecyclerView.State state) {
        int range = parent.computeVerticalScrollRange();
        int extent = parent.computeVerticalScrollExtent();
        if (range <= extent || extent <= 0) {
            initialized = false;
            return;
        }
        float density = parent.getResources().getDisplayMetrics().density;
        float track = parent.getHeight() - 16 * density;
        if (track <= 0) return;
        float height = Math.min(track, Math.max(26 * density, track * extent / range));
        float fraction = Math.max(0, Math.min(1f,
                parent.computeVerticalScrollOffset() / (float) (range - extent)));
        float top = 8 * density + (track - height) * fraction;
        long now = SystemClock.uptimeMillis();
        boolean scrolling = parent.getScrollState() != RecyclerView.SCROLL_STATE_IDLE;
        float targetAlpha = scrolling || now - lastScroll < 650L ? 150f : 0f;
        if (!initialized) {
            shownTop = top;
            shownHeight = height;
            shownAlpha = 0;
            initialized = true;
            lastFrame = now;
        }
        // RecyclerView estimates range from visible rows; different message heights
        // change that estimate abruptly. Smooth geometry, not the actual scroll.
        float dt = Math.max(1L, Math.min(48L, now - lastFrame));
        lastFrame = now;
        boolean motion = android.animation.ValueAnimator.areAnimatorsEnabled();
        float positionBlend = motion ? 1f - (float) Math.exp(-dt / 65f) : 1f;
        float alphaBlend = motion ? 1f - (float) Math.exp(-dt / 90f) : 1f;
        shownTop += (top - shownTop) * positionBlend;
        shownHeight += (height - shownHeight) * positionBlend;
        shownAlpha += (targetAlpha - shownAlpha) * alphaBlend;
        if (Math.abs(shownTop - top) < 0.5f) shownTop = top;
        if (Math.abs(shownHeight - height) < 0.5f) shownHeight = height;
        if (Math.abs(shownAlpha - targetAlpha) < 1f) shownAlpha = targetAlpha;
        shownHeight = Math.min(track, shownHeight);
        shownTop = Math.max(8 * density, Math.min(shownTop, 8 * density + track - shownHeight));
        float right = parent.getWidth() - 3 * density;
        paint.setColor(ContextCompat.getColor(parent.getContext(), R.color.text_secondary));
        paint.setAlpha(Math.round(shownAlpha));
        bounds.set(right - 3 * density, shownTop, right, shownTop + shownHeight);
        canvas.drawRoundRect(bounds, 2 * density, 2 * density, paint);
        if (shownTop != top || shownHeight != height || shownAlpha != targetAlpha) {
            parent.postInvalidateOnAnimation();
        } else if (!scrolling && targetAlpha > 0) {
            parent.postInvalidateDelayed(Math.max(1L, 650L - (now - lastScroll)));
        }
    }
}
