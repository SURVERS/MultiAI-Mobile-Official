package app.multiai.mobile.ui.kit;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import app.multiai.mobile.R;
import app.multiai.mobile.network.oauth.OAuthSession;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class MultiAiConnectionStatus {
    private static final String MODELS_URL = "https://multiai.store/v1/models";
    private final TextView view;
    private final Supplier<String> connectedText;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "multiai-connection-check");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> polling;
    private AnimatorSet dotsAnimator;
    private boolean started;
    private boolean connecting;

    public MultiAiConnectionStatus(TextView view, Supplier<String> connectedText) {
        this.view = view;
        this.connectedText = connectedText;
    }

    public void start() {
        if (started || view == null) return;
        started = true;
        showConnecting();
        polling = executor.scheduleWithFixedDelay(this::check, 0, 3, TimeUnit.SECONDS);
    }

    public void stop() {
        started = false;
        if (polling != null) polling.cancel(true);
        polling = null;
        view.removeCallbacks(restartDots);
        if (dotsAnimator != null) dotsAnimator.cancel();
        dotsAnimator = null;
    }

    public void refreshConnectedText() {
        if (view != null && !connecting) view.setText(connectedText.get());
    }

    public void destroy() {
        stop();
        executor.shutdownNow();
    }

    private void check() {
        boolean connected = hasInternetCapability(view.getContext()) && canReachMultiAi();
        view.post(() -> {
            if (!started) return;
            if (connected) showConnected();
            else showConnecting();
        });
    }

    private boolean canReachMultiAi() {
        try {
            Request.Builder request = new Request.Builder().url(MODELS_URL).get();
            String token = OAuthSession.get().getAccessToken();
            if (token != null && !token.trim().isEmpty()) request.header("Authorization", "Bearer " + token);
            try (Response response = client.newCall(request.build()).execute()) {
                return response.code() < 500;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasInternetCapability(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showConnected() {
        connecting = false;
        view.removeCallbacks(restartDots);
        if (dotsAnimator != null) dotsAnimator.cancel();
        dotsAnimator = null;
        view.setText(connectedText.get());
    }

    private void showConnecting() {
        if (connecting && dotsAnimator != null && dotsAnimator.isRunning()) return;
        connecting = true;
        view.removeCallbacks(restartDots);
        if (dotsAnimator != null) dotsAnimator.cancel();
        String base = view.getContext().getString(R.string.chat_connecting);
        SpannableString text = new SpannableString(base + "...");
        DotAlphaSpan first = new DotAlphaSpan();
        DotAlphaSpan second = new DotAlphaSpan();
        DotAlphaSpan third = new DotAlphaSpan();
        text.setSpan(first, base.length(), base.length() + 1, 0);
        text.setSpan(second, base.length() + 1, base.length() + 2, 0);
        text.setSpan(third, base.length() + 2, base.length() + 3, 0);
        view.setText(text);
        dotsAnimator = new AnimatorSet();
        dotsAnimator.playTogether(
                dotAnimator(first, 0, 255, 0, 300),
                dotAnimator(second, 0, 255, 150, 300),
                dotAnimator(third, 0, 255, 300, 300),
                dotAnimator(first, 255, 0, 1000, 400),
                dotAnimator(second, 255, 0, 1000, 400),
                dotAnimator(third, 255, 0, 1000, 400));
        dotsAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (started && connecting) view.postDelayed(restartDots, 300);
            }
        });
        dotsAnimator.start();
    }

    private final Runnable restartDots = () -> {
        if (!started || !connecting) return;
        dotsAnimator = null;
        showConnecting();
    };

    private ValueAnimator dotAnimator(DotAlphaSpan span, int from, int to, long delay, long duration) {
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setStartDelay(delay);
        animator.setDuration(duration);
        animator.addUpdateListener(value -> {
            span.alpha = (int) value.getAnimatedValue();
            view.invalidate();
        });
        return animator;
    }

    private static final class DotAlphaSpan extends CharacterStyle {
        private int alpha;

        @Override public void updateDrawState(TextPaint paint) {
            paint.setAlpha((int) (paint.getAlpha() * (alpha / 255f)));
        }
    }
}
