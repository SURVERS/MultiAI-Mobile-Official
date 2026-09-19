package app.multiai.mobile.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.multiai.mobile.R;
import app.multiai.mobile.network.BackendApi;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.DeviceBanManager;

public class BanActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CornerGlowView glow;
    private TextView body;
    private boolean leaving;
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            DeviceBanManager.checkNow(BanActivity.this);
            handler.postDelayed(this, 15_000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = isDarkTheme();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(dark ? "#101014" : "#F7F7F9"));
        glow = new CornerGlowView(dark);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            glow.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    dp(36), dp(36), Shader.TileMode.CLAMP));
        }
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        View frost = new View(this);
        frost.setBackgroundColor(Color.parseColor(dark ? "#40101014" : "#66F7F7F9"));
        root.addView(frost, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        FrameLayout content = new FrameLayout(this);
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setPadding(dp(32), dp(40), dp(32), dp(40));
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        content.addView(center, centerParams);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_security);
        icon.setImageTintList(ColorStateList.valueOf(Color.parseColor(dark ? "#F08086" : "#AF2936")));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        center.addView(icon, item(dp(52), dp(52), 24));

        TextView title = new TextView(this);
        title.setText(R.string.device_banned_title_alt);
        title.setTextColor(Color.parseColor(dark ? "#F5F5F7" : "#1A1A1E"));
        title.setTextSize(26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        center.addView(title, item(-1, -2, 16));

        body = new TextView(this);
        body.setTextColor(Color.parseColor(dark ? "#C7C7CF" : "#51515D"));
        body.setTextSize(16);
        body.setLineSpacing(dp(4), 1f);
        body.setGravity(Gravity.CENTER);
        body.setMaxWidth(dp(420));
        center.addView(body, item(-2, -2, 32));
        updateMessage();

        Button close = new Button(this);
        close.setText(R.string.device_banned_exit);
        close.setAllCaps(false);
        close.setTextSize(15);
        close.setTextColor(Color.parseColor(dark ? "#F5F5F7" : "#25252B"));
        close.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        close.setMinHeight(dp(52));
        close.setMinWidth(dp(160));
        close.setPadding(dp(32), dp(12), dp(32), dp(12));
        GradientDrawable button = new GradientDrawable();
        button.setCornerRadius(dp(28));
        button.setColor(Color.parseColor(dark ? "#24242C" : "#E8E8EE"));
        button.setStroke(dp(1), Color.parseColor(dark ? "#42424C" : "#CBCBD5"));
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(
                Color.parseColor(dark ? "#26FFFFFF" : "#16000000")), button, null));
        close.setOnClickListener(v -> finishAndRemoveTask());
        center.addView(close, item(-2, -2, 0));
        setContentView(root);
    }

    public void showCurrentStatus() {
        if (leaving || isFinishing() || body == null) return;
        if (BackendApi.get().isBanned()) {
            updateMessage();
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            leaving = true;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
            finish();
        }
    }

    private void updateMessage() {
        String message = BackendApi.get().getBanMessage();
        body.setText(message == null || message.trim().isEmpty()
                ? getString(R.string.device_banned_message) : message);
    }

    @Override protected void onResume() {
        super.onResume();
        glow.start();
        handler.removeCallbacks(refreshStatus);
        handler.post(refreshStatus);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        glow.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        glow.stop();
        DeviceBanManager.onBanDestroyed();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        finishAndRemoveTask();
    }

    private boolean isDarkTheme() {
        String theme = new AppSettings(this).getTheme();
        if ("light".equals(theme)) return false;
        if ("dark".equals(theme)) return true;
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private LinearLayout.LayoutParams item(int width, int height, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class CornerGlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Shader[] gradients = new Shader[4];
        private final boolean dark;
        private ValueAnimator animator;
        private float phase;
        private float radius;

        CornerGlowView(boolean dark) {
            super(BanActivity.this);
            this.dark = dark;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            radius = Math.min(width, height) * .92f;
            if (radius <= 0) return;
            int[] colors = {0xFFCF3544, 0xFFAA2038, 0xFFDE4A3D, 0xFFBC2645};
            for (int i = 0; i < gradients.length; i++) {
                gradients[i] = new RadialGradient(0, 0, radius,
                        new int[]{colors[i], (colors[i] & 0x00FFFFFF) | 0x70000000,
                                colors[i] & 0x00FFFFFF},
                        new float[]{0f, .38f, 1f}, Shader.TileMode.CLAMP);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (radius <= 0) return;
            for (int i = 0; i < 4; i++) {
                double angle = phase * Math.PI * 2 + i * Math.PI / 2;
                float x = (i % 2 == 0 ? 0 : getWidth()) + (float) Math.sin(angle) * radius * .07f;
                float y = (i < 2 ? 0 : getHeight()) + (float) Math.cos(angle) * radius * .07f;
                float scale = .92f + .08f * (float) Math.sin(angle);
                paint.setShader(gradients[i]);
                paint.setAlpha((int) ((dark ? 160 : 125) + 25 * Math.cos(angle)));
                int save = canvas.save();
                canvas.translate(x, y);
                canvas.scale(scale, scale);
                canvas.drawCircle(0, 0, radius, paint);
                canvas.restoreToCount(save);
            }
        }

        void start() {
            if (animator != null || !ValueAnimator.areAnimatorsEnabled()) return;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(18_000L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(value -> {
                phase = (float) value.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void stop() {
            if (animator == null) return;
            animator.cancel();
            animator.removeAllUpdateListeners();
            animator = null;
        }
    }
}
