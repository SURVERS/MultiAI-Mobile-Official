package app.multiai.mobile.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Применяет TelemojiSpan к уже отрендеренному обычному или Markdown-тексту. */
public final class Telemoji {

    private static final int MAX_EMOJI_CODE_UNITS = 32;
    private static final Map<TextView, Long> TEXT_GENERATIONS = new WeakHashMap<>();

    private Telemoji() {
    }

    public static void apply(@NonNull Context context, @NonNull TextView textView,
                             @Nullable CharSequence text) {
        long generation = nextGeneration(textView);
        releaseSpans(textView.getText());
        SpannableStringBuilder builder = copyWithoutTelemoji(text);
        AppSettings settings = new AppSettings(context);
        if (settings.isEmojiModeSystem()) {
            textView.setText(builder);
            return;
        }

        boolean animated = settings.isEmojiModeAnimated();
        String expectedMode = settings.getEmojiMode();
        TelemojiProvider provider = TelemojiProvider.get(context);
        if (animated) textView.setLayerType(View.LAYER_TYPE_NONE, null);

        Set<String> pending = applyCachedSpans(builder, provider, animated, textView);
        textView.setText(builder);
        startAnimations(builder);
        if (!pending.isEmpty()) {
            loadPending(context.getApplicationContext(), textView, provider, animated,
                    expectedMode, generation, pending);
        }
    }

    @NonNull
    private static SpannableStringBuilder copyWithoutTelemoji(@Nullable CharSequence text) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text == null ? "" : text);
        TelemojiSpan[] spans = builder.getSpans(0, builder.length(), TelemojiSpan.class);
        for (TelemojiSpan span : spans) builder.removeSpan(span);
        return builder;
    }

    private static void releaseSpans(@Nullable CharSequence text) {
        if (!(text instanceof Spanned)) return;
        Spanned spanned = (Spanned) text;
        TelemojiSpan[] spans = spanned.getSpans(0, spanned.length(), TelemojiSpan.class);
        for (TelemojiSpan span : spans) span.release();
    }

    @NonNull
    private static Set<String> applyCachedSpans(@NonNull SpannableStringBuilder builder,
                                                @NonNull TelemojiProvider provider,
                                                boolean animated,
                                                @NonNull TextView hostView) {
        Set<String> pending = new LinkedHashSet<>();
        int emojiSize = Math.max(1, Math.round(hostView.getTextSize() * 1.15f));
        int index = 0;
        while (index < builder.length()) {
            int matchLength = findLongestMatch(builder, index, provider, animated);
            if (matchLength == 0) {
                index++;
                continue;
            }

            String emoji = builder.subSequence(index, index + matchLength).toString();
            Drawable drawable = provider.getCachedDrawable(emoji, animated);
            if (drawable == null) {
                pending.add(emoji);
            } else {
                TelemojiSpan span = new TelemojiSpan(drawable, emojiSize);
                span.setHostView(hostView);
                builder.setSpan(span, index, index + matchLength,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            index += matchLength;
        }
        return pending;
    }

    static int findLongestMatch(@NonNull CharSequence text, int start,
                                @NonNull TelemojiProvider provider, boolean animated) {
        int maxLength = Math.min(text.length() - start,
                Math.min(MAX_EMOJI_CODE_UNITS, provider.getMaxEmojiLength()));
        for (int length = maxLength; length > 0; length--) {
            String candidate = text.subSequence(start, start + length).toString();
            if (provider.getPathForEmoji(candidate, animated) != null) return length;
        }
        return 0;
    }

    private static void loadPending(@NonNull Context context, @NonNull TextView textView,
                                    @NonNull TelemojiProvider provider, boolean animated,
                                    @NonNull String expectedMode, long generation,
                                    @NonNull Set<String> pending) {
        WeakReference<TextView> viewReference = new WeakReference<>(textView);
        for (String emoji : pending) {
            provider.loadDrawableAsync(emoji, animated, drawable -> {
                if (drawable == null) return;
                TextView currentView = viewReference.get();
                if (currentView == null || !isCurrent(currentView, generation)) return;
                AppSettings currentSettings = new AppSettings(context);
                if (!expectedMode.equals(currentSettings.getEmojiMode())) return;
                rebuildAvailableSpans(currentView, provider, animated);
            });
        }
    }

    private static void rebuildAvailableSpans(@NonNull TextView textView,
                                              @NonNull TelemojiProvider provider,
                                              boolean animated) {
        releaseSpans(textView.getText());
        SpannableStringBuilder builder = copyWithoutTelemoji(textView.getText());
        applyCachedSpans(builder, provider, animated, textView);
        textView.setText(builder);
        startAnimations(builder);
    }

    private static void startAnimations(@NonNull Spanned text) {
        TelemojiSpan[] spans = text.getSpans(0, text.length(), TelemojiSpan.class);
        for (TelemojiSpan span : spans) span.startAnimationIfNeeded();
    }

    private static long nextGeneration(@NonNull TextView textView) {
        synchronized (TEXT_GENERATIONS) {
            Long current = TEXT_GENERATIONS.get(textView);
            long next = current == null ? 1L : current + 1L;
            TEXT_GENERATIONS.put(textView, next);
            return next;
        }
    }

    private static boolean isCurrent(@NonNull TextView textView, long generation) {
        synchronized (TEXT_GENERATIONS) {
            Long current = TEXT_GENERATIONS.get(textView);
            return current != null && current == generation;
        }
    }

    /** Инвалидирует pending-загрузки перед прямым setText во время стриминга/recycle. */
    public static void clearTracking(@NonNull TextView textView) {
        nextGeneration(textView);
        CharSequence text = textView.getText();
        if (!(text instanceof Spanned)) return;
        Spanned spanned = (Spanned) text;
        TelemojiSpan[] spans = spanned.getSpans(0, spanned.length(), TelemojiSpan.class);
        for (TelemojiSpan span : spans) span.release();
    }
}
