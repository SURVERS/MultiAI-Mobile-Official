package app.multiai.mobile.ui.kit;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Independent tail fades survive incoming chunks without restarting older text. */
public final class StreamingReveal {
    private final List<Tail> tails = new ArrayList<>();
    private String previous = "";
    private TextView target;
    private Spannable attached;
    private boolean scheduled;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            scheduled = false;
            if (target == null) return;
            long now = SystemClock.uptimeMillis();
            Iterator<Tail> iterator = tails.iterator();
            while (iterator.hasNext()) {
                Tail tail = iterator.next();
                if (now - tail.started >= 180L) {
                    if (attached != null) attached.removeSpan(tail);
                    iterator.remove();
                }
            }
            target.invalidate();
            if (!tails.isEmpty() && target.isAttachedToWindow()) {
                scheduled = true;
                target.postOnAnimation(this);
            }
        }
    };

    public void update(TextView view) {
        String current = view.getText().toString();
        if (target != view || !current.startsWith(previous)) reset();
        target = view;
        int start = previous.length();
        previous = current;
        if (!ValueAnimator.areAnimatorsEnabled()) { reset(); return; }
        if (start == current.length() && tails.isEmpty()) return;
        if (!(view.getText() instanceof Spannable)) {
            view.setText(view.getText(), TextView.BufferType.SPANNABLE);
        }
        if (attached != null) for (Tail tail : tails) attached.removeSpan(tail);
        attached = (Spannable) view.getText();
        long now = SystemClock.uptimeMillis();
        tails.removeIf(tail -> now - tail.started >= 180L);
        if (start < current.length()) tails.add(new Tail(start, current.length(), now));
        for (Tail tail : tails) {
            attached.setSpan(tail, tail.start, tail.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!scheduled) { scheduled = true; view.postOnAnimation(frame); }
    }

    public void reset() {
        if (target != null) target.removeCallbacks(frame);
        if (attached != null) for (Tail tail : tails) attached.removeSpan(tail);
        tails.clear();
        attached = null;
        target = null;
        scheduled = false;
        previous = "";
    }

    private static final class Tail extends CharacterStyle {
        final int start, end;
        final long started;
        Tail(int start, int end, long started) {
            this.start = start; this.end = end; this.started = started;
        }
        @Override public void updateDrawState(TextPaint paint) {
            float progress = Math.min(1f, (SystemClock.uptimeMillis() - started) / 180f);
            float alpha = 0.7f + 0.3f * (1f - (1f - progress) * (1f - progress));
            paint.setAlpha(Math.round(paint.getAlpha() * alpha));
        }
    }
}
