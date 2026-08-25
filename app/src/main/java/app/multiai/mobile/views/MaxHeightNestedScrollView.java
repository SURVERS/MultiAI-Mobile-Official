package app.multiai.mobile.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import app.multiai.mobile.R;

/** NestedScrollView с реально применяемой максимальной высотой внутри RecyclerView. */
public class MaxHeightNestedScrollView extends NestedScrollView {
    private int maxHeightPx = Integer.MAX_VALUE;
    private float downX;
    private float downY;

    public MaxHeightNestedScrollView(@NonNull Context context) {
        this(context, null);
    }

    public MaxHeightNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaxHeightNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs,
                                     int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs != null) {
            TypedArray values = context.obtainStyledAttributes(
                    attrs, R.styleable.MaxHeightNestedScrollView, defStyleAttr, 0);
            maxHeightPx = values.getDimensionPixelSize(
                    R.styleable.MaxHeightNestedScrollView_maxHeightCompat, Integer.MAX_VALUE);
            values.recycle();
        }
        setNestedScrollingEnabled(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxHeightPx != Integer.MAX_VALUE) {
            int requested = MeasureSpec.getSize(heightMeasureSpec);
            int cap = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? maxHeightPx : Math.min(requested, maxHeightPx);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                ViewParent downParent = getParent();
                if (downParent != null) downParent.requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                // The diff is vertically scrollable inside a RecyclerView. Once
                // the gesture is clearly vertical, keep it in this view so the
                // chat list cannot steal the scroll.
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > getTouchSlop()) {
                    ViewParent moveParent = getParent();
                    if (moveParent != null) moveParent.requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ViewParent endParent = getParent();
                if (endParent != null) endParent.requestDisallowInterceptTouchEvent(false);
                break;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
        return super.onTouchEvent(event);
    }

    private int getTouchSlop() {
        return android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }
}
