package app.multiai.mobile.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import app.multiai.mobile.R;

public class OnboardingActivity extends AppCompatActivity {

    private static final String TAG = "Onboarding";
    private static final String PREFS_NAME = "multiai_profile";
    private static final int TOTAL_PAGES = 5;
    private static final long STARTUP_SPLASH_DURATION_MS =
            app.multiai.mobile.views.MultiAiSplashLogoView.CYCLE_DURATION_MS + 200L;
    private static final long REDUCED_MOTION_SPLASH_DURATION_MS = 320L;
    private static final long STARTUP_SPLASH_FADE_MS = 180L;

    private ViewPager2 viewPager;
    private app.multiai.mobile.network.oauth.OAuthLoginController oauth;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isOnboarding = true;
    private boolean finalPageAnimStarted = false;
    private int restoredPage;
    private boolean startupDispatched;
    private boolean startupExitStarted;
    private boolean startupMotionEnabled;
    private app.multiai.mobile.views.MultiAiSplashLogoView startupLogo;
    private TextView finalWelcome, finalStatus;
    private ProgressBar finalProgress;
    private View progressFill;
    private String finalDisplayName;
    private TextView biometricStatus;
    private TextView biometricRetry;
    private TextView biometricSettings;
    private boolean biometricPromptVisible;
    private boolean biometricGateVisible;
    private final Runnable biometricRetryUnlock = () -> {
        if (biometricRetry == null) return;
        biometricRetry.setEnabled(true);
        biometricRetry.setAlpha(1f);
        biometricRetry.setBackgroundResource(R.drawable.biometric_retry_button);
        biometricRetry.setText(getString(R.string.scr_retry));
    };
    /** Infinite/looping animators currently attached to onboarding pages.
     *  Tracked so we can cancel them on rebind to avoid stacking duplicates. */
    private final java.util.List<ObjectAnimator> activeAnimators = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        oauth = new app.multiai.mobile.network.oauth.OAuthLoginController(this);
        restoredPage = savedInstanceState == null ? 0
                : savedInstanceState.getInt("onboarding_page", 0);

        if (savedInstanceState == null) {
            showStartupSplash();
        } else {
            // A configuration/process recreation must not replay the full launch delay.
            restorePostSplashWindow();
            dispatchStartup();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!startupDispatched || biometricGateVisible || isFinishing()) return;
        if (app.multiai.mobile.utils.AppLockManager.get(this).isUnlockRequired()) {
            showBiometricGate();
        }
    }

    private void showStartupSplash() {
        setContentView(R.layout.activity_startup_splash);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setLightSystemBarIcons(false);

        startupLogo = findViewById(R.id.startupLogo);
        startupMotionEnabled = startupLogo.startGlowAnimation();
        long visibleDuration = startupMotionEnabled
                ? STARTUP_SPLASH_DURATION_MS
                : REDUCED_MOTION_SPLASH_DURATION_MS;
        handler.postDelayed(this::finishStartupSplash, visibleDuration);
    }

    private void finishStartupSplash() {
        if (startupExitStarted || startupDispatched || isFinishing() || isDestroyed()) return;
        startupExitStarted = true;

        if (startupLogo == null) {
            completeStartupSplash();
            return;
        }

        startupLogo.stopGlowAnimation();
        if (!startupMotionEnabled) {
            completeStartupSplash();
            return;
        }

        startupLogo.animate()
                .alpha(0f)
                .setDuration(STARTUP_SPLASH_FADE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(this::completeStartupSplash)
                .start();
    }

    private void completeStartupSplash() {
        if (startupDispatched || isFinishing() || isDestroyed()) return;
        startupLogo = null;
        restorePostSplashWindow();
        dispatchStartup();
    }

    private void restorePostSplashWindow() {
        getWindow().setBackgroundDrawableResource(R.drawable.ambient_background);
        int background = ContextCompat.getColor(this, R.color.bg);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        setLightSystemBarIcons(getResources().getBoolean(R.bool.light_status_bar));
    }

    private void setLightSystemBarIcons(boolean light) {
        View decorView = getWindow().getDecorView();
        int visibility = decorView.getSystemUiVisibility();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            visibility = light
                    ? visibility | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : visibility & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            visibility = light
                    ? visibility | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : visibility & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decorView.setSystemUiVisibility(visibility);
    }

    /** Runs the exact pre-splash launcher routing once. */
    private void dispatchStartup() {
        if (startupDispatched || isFinishing() || isDestroyed()) return;
        startupDispatched = true;

        // Branded biometric gate remains visible behind the trusted system prompt and
        // provides a recovery path if the user accidentally cancels authentication.
        if (new app.multiai.mobile.utils.AppSettings(this).isBiometricEnabled()
                && app.multiai.mobile.utils.AppLockManager.get(this).isUnlockRequired()) {
            showBiometricGate();
            return;
        }

        proceedOnboarding();
    }

    private void showBiometricGate() {
        biometricGateVisible = true;
        setContentView(R.layout.activity_biometric_gate);
        int bg = ContextCompat.getColor(this, R.color.bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        app.multiai.mobile.utils.SecurityHelper.setSecure(this, true);
        biometricStatus = findViewById(R.id.biometricStatus);
        biometricRetry = findViewById(R.id.biometricRetry);
        biometricSettings = findViewById(R.id.biometricSettings);
        biometricRetry.setOnClickListener(v -> requestBiometric());
        biometricSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
        });
        getWindow().getDecorView().post(this::requestBiometric);
    }

    private void requestBiometric() {
        if (biometricPromptVisible) return;
        handler.removeCallbacks(biometricRetryUnlock);
        biometricRetry.setVisibility(View.GONE);
        biometricSettings.setVisibility(View.GONE);
        biometricStatus.setText(getString(R.string.scr_bio_use_fingerprint));
        biometricStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        if (!app.multiai.mobile.utils.SecurityHelper.isBiometricAvailable(this)) {
            biometricStatus.setText(getString(R.string.scr_bio_unavailable));
            biometricStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
            biometricSettings.setVisibility(View.VISIBLE);
            return;
        }
        biometricPromptVisible = true;
        app.multiai.mobile.utils.SecurityHelper.showBiometricPrompt(this,
                new app.multiai.mobile.utils.SecurityHelper.Callback() {
                    @Override
                    public void onSuccess() {
                        biometricPromptVisible = false;
                        proceedOnboarding();
                    }

                    @Override
                    public void onError(int errorCode, String errString) {
                        biometricPromptVisible = false;
                        handleBiometricError(errorCode);
                    }

                    @Override
                    public void onFailure() {
                        biometricStatus.setText(getString(R.string.scr_bio_not_recognized));
                        biometricStatus.setTextColor(ContextCompat.getColor(
                                OnboardingActivity.this, R.color.error));
                    }
                });
    }

    private void handleBiometricError(int errorCode) {
        biometricRetry.setVisibility(View.VISIBLE);
        biometricRetry.setEnabled(true);
        biometricRetry.setAlpha(1f);
        biometricRetry.setBackgroundResource(R.drawable.biometric_retry_button);
        biometricRetry.setText(getString(R.string.scr_retry));
        biometricSettings.setVisibility(View.GONE);
        if (app.multiai.mobile.utils.SecurityHelper.isLockoutError(errorCode)) {
            biometricStatus.setText(getString(R.string.scr_bio_lockout));
            biometricStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
            biometricRetry.setEnabled(false);
            biometricRetry.setAlpha(0.7f);
            biometricRetry.setBackgroundResource(R.drawable.biometric_retry_button_disabled);
            biometricRetry.setText(getString(R.string.scr_bio_wait));
            handler.postDelayed(biometricRetryUnlock, 30_000L);
        } else if (app.multiai.mobile.utils.SecurityHelper.isUnavailableError(errorCode)) {
            biometricStatus.setText(getString(R.string.scr_bio_unavailable));
            biometricStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
            biometricRetry.setVisibility(View.GONE);
            biometricSettings.setVisibility(View.VISIBLE);
        } else {
            biometricStatus.setText(getString(R.string.scr_bio_cancelled));
            biometricStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    /** Continues the normal onCreate/onboarding flow after the biometric gate passes. */
    private void proceedOnboarding() {
        app.multiai.mobile.utils.AppLockManager lockManager =
                app.multiai.mobile.utils.AppLockManager.get(this);
        Intent pendingRoute = lockManager.unlockAndTakePendingRoute();
        biometricGateVisible = false;
        if (pendingRoute != null) {
            startActivity(pendingRoute);
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("onboarding_completed", false)) {
            startChat();
            return;
        }

        setContentView(R.layout.activity_onboarding);
        int bgColor = androidx.core.content.ContextCompat.getColor(this, app.multiai.mobile.R.color.bg);
        getWindow().setStatusBarColor(bgColor);
        getWindow().setNavigationBarColor(bgColor);
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        viewPager = findViewById(R.id.viewPager);
        viewPager.setUserInputEnabled(false);
        viewPager.setOffscreenPageLimit(1);
        viewPager.setAdapter(new OnboardingAdapter());
        if (getIntent() == null || getIntent().getData() == null) {
            viewPager.setCurrentItem(restoredPage, false);
        }
        if (restoredPage > 0) {
            viewPager.setAlpha(0f);
            viewPager.setScaleX(1.015f);
            viewPager.setScaleY(1.015f);
            viewPager.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        }
        handleOAuthCallback(getIntent());

        // Trigger final page animations when it becomes visible
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // Start final page animations only when the last page becomes visible
                if (position == TOTAL_PAGES - 1 && !finalPageAnimStarted) {
                    finalPageAnimStarted = true;
                    handler.postDelayed(() -> startFinalPageAnimations(), 100);
                }
            }
        });

    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt("onboarding_page", viewPager == null ? restoredPage : viewPager.getCurrentItem());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOAuthCallback(intent);
    }

    private void handleOAuthCallback(Intent intent) {
        if (intent == null || intent.getData() == null || oauth == null || viewPager == null) return;
        boolean handled = oauth.handleCallback(intent, new app.multiai.mobile.network.oauth.OAuthLoginController.Callback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> viewPager.setCurrentItem(3, true));
            }

            @Override
            public void onError(@NonNull String error, String detail) {
                String message = "access_denied".equals(error)
                        ? getString(R.string.scr_signin_cancelled)
                        : "OAuth: " + error;
                runOnUiThread(() -> android.widget.Toast.makeText(
                        OnboardingActivity.this, message, android.widget.Toast.LENGTH_SHORT).show());
            }
        });
        if (handled) intent.setData(null);
    }

    @Override
    protected void onDestroy() {
        if (startupLogo != null) {
            startupLogo.animate().withEndAction(null).cancel();
            startupLogo.stopGlowAnimation();
            startupLogo = null;
        }
        isOnboarding = false;
        cancelActiveAnimators();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public boolean isInOnboarding() { return isOnboarding; }

    private void completeOnboarding() {
        isOnboarding = false;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("onboarding_completed", true).apply();
        startChat();
    }

    private void startChat() {
        String mode = new app.multiai.mobile.utils.AppSettings(this).getChatMode();
        Class<?> target = "chat".equals(mode) ? ChatModeActivity.class : ChatActivity.class;
        startActivity(new Intent(this, target));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    // ═══════════════════ HELPERS ═══════════════════

    private int dp(float val) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics());
    }

    /** Resolve a semantic color resource to an int. */
    private int col(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    /** Cancel & forget all tracked looping animators (logo float/pulse). Called on
     *  rebind and onDestroy so duplicate infinite animations never stack up. */
    private void cancelActiveAnimators() {
        for (ObjectAnimator a : activeAnimators) {
            if (a != null) a.cancel();
        }
        activeAnimators.clear();
    }

    /** Start and track a looping animator so it is cancelled on rebind/destroy. */
    private void trackLoop(ObjectAnimator anim) {
        activeAnimators.add(anim);
        anim.start();
    }

    private TextView txt(String text, float sizeSp, String color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(Color.parseColor(color));
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setLetterSpacing(0.01f);
        return tv;
    }

    /** TextView whose color comes from a semantic color resource. */
    private TextView txtRes(String text, float sizeSp, int colorRes, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(col(colorRes));
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setLetterSpacing(0.01f);
        return tv;
    }

    private View spacer(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    private GradientDrawable pill(String color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(color));
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    /** Rounded-rect drawable from a semantic color resource. */
    private GradientDrawable pillRes(int colorRes, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(col(colorRes));
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private GradientDrawable grad(String[] colors, int radiusDp) {
        int[] c = new int[colors.length];
        for (int i = 0; i < colors.length; i++) c[i] = Color.parseColor(colors[i]);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private GradientDrawable gradStroke(String[] colors, String stroke, int radiusDp) {
        GradientDrawable gd = grad(colors, radiusDp);
        gd.setStroke(dp(1.5f), Color.parseColor(stroke));
        return gd;
    }

    /** Rounded ripple mask over a fill drawable, for touch feedback on solid cards. */
    private void applyRipple(View v, GradientDrawable fill, int radiusDp) {
        ColorStateList rippleCsl = ColorStateList.valueOf(col(R.color.ripple));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        v.setBackground(new RippleDrawable(rippleCsl, fill, mask));
    }

    private ImageView icon(int resId, int sizeDp, String tint) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(resId);
        if (tint != null) iv.setColorFilter(Color.parseColor(tint));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    private View makeButton(String text, String[] gradient, View.OnClickListener click) {
        // Button label uses the theme's accent-foreground token so it stays legible on
        // the accent fill in both light and dark mode.
        TextView btn = txtRes(text, 16, R.color.text_on_accent, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(32), dp(16), dp(32), dp(16));
        // Ripple foreground for Material touch feedback
        GradientDrawable bg = grad(gradient, 14);
        btn.setBackground(bg);
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        btn.setForeground(getDrawable(outValue.resourceId));
        btn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        btn.setOnClickListener(v -> {
            // Micro press animation (restrained, quick settle)
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            ).start();
            click.onClick(v);
        });
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    /** Button variant whose fill comes from a semantic color resource (theme-aware).
     *  Use this when the button must adapt to light/dark accent automatically. */
    private View makeButtonRes(String text, int fillRes, View.OnClickListener click) {
        TextView btn = txtRes(text, 16, R.color.text_on_accent, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(32), dp(16), dp(32), dp(16));
        GradientDrawable bg = pillRes(fillRes, 14);
        btn.setBackground(bg);
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        btn.setForeground(getDrawable(outValue.resourceId));
        btn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        btn.setOnClickListener(v -> {
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            ).start();
            click.onClick(v);
        });
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }

    private void fadeSlideIn(View v, int delayMs, int offsetDp) {
        v.setAlpha(0f);
        v.setTranslationY(dp(offsetDp));
        v.animate().alpha(1f).translationY(0).setDuration(400)
                .setStartDelay(delayMs).setInterpolator(new OvershootInterpolator(0.6f)).start();
    }

    // ═══════════════════ ADAPTER ═══════════════════

    private class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout f = new FrameLayout(parent.getContext());
            f.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new VH(f);
        }
        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            // Cancel any looping animators from the previous page so they don't
            // keep running (and stack duplicates) when the page is rebound.
            cancelActiveAnimators();
            FrameLayout c = (FrameLayout) holder.itemView;
            c.removeAllViews();
            c.addView(buildPage(position));
        }
        @Override public int getItemCount() { return TOTAL_PAGES; }
        class VH extends RecyclerView.ViewHolder { VH(View v) { super(v); } }
    }

    // ═══════════════════ PAGE BUILDERS ═══════════════════

    private View buildPage(int index) {
        switch (index) {
            case 0: return buildWelcomePage();
            case 1: return buildSetupPage();
            case 2: return buildLoginPage();
            case 3: return buildModePage();
            case 4: return buildFinalPage();
            default: return new View(this);
        }
    }

    // ── PAGE 1: Welcome ──
    private View buildWelcomePage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(40), dp(80), dp(40), dp(60));

        // MultiAI icon (app launcher icon)
        ImageView logo = icon(R.drawable.ic_multiai_logo, 200, null);
        logo.setColorFilter(col(R.color.icon));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(200), dp(200));
        logoLp.gravity = Gravity.CENTER;
        logo.setLayoutParams(logoLp);
        root.addView(logo);
        fadeSlideIn(logo, 0, 30);

        // Floating glow animation on logo (tracked so it is cancelled on rebind)
        ObjectAnimator floatAnim = ObjectAnimator.ofFloat(logo, "translationY", 0f, -dp(8), 0f);
        floatAnim.setDuration(3000);
        floatAnim.setRepeatCount(ValueAnimator.INFINITE);
        floatAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        trackLoop(floatAnim);

        root.addView(spacer(24));

        TextView title = txtRes(getString(R.string.scr_onb_welcome_title), 34, R.color.text_primary, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        fadeSlideIn(title, 200, 20);

        root.addView(spacer(6));

        TextView sub = txtRes(getString(R.string.scr_onb_welcome_sub), 20, R.color.text_secondary, true);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
        fadeSlideIn(sub, 350, 20);

        root.addView(spacer(20));

        TextView desc = txtRes(getString(R.string.scr_onb_welcome_desc), 14, R.color.text_hint, false);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(3), 1f);
        root.addView(desc);
        fadeSlideIn(desc, 500, 20);

        root.addView(spacer(48));

        View btn = makeButtonRes(getString(R.string.scr_onb_start), R.color.accent,
                v -> viewPager.setCurrentItem(1, true));
        root.addView(btn);
        fadeSlideIn(btn, 700, 30);

        // Page indicator dots
        root.addView(spacer(24));
        root.addView(buildDots(0));

        return root;
    }

    private View buildSetupPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(24), dp(40), dp(24), dp(36));

        TextView title = txtRes(getString(R.string.scr_onb_setup_title), 26,
                R.color.text_primary, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        root.addView(spacer(22));
        TextView themeTitle = txtRes(getString(R.string.scr_onb_choose_theme), 16,
                R.color.text_secondary, true);
        root.addView(themeTitle);
        root.addView(spacer(10));

        app.multiai.mobile.utils.AppSettings settings =
                new app.multiai.mobile.utils.AppSettings(this);
        LinearLayout themeRow = setupOptionRow();
        themeRow.addView(buildSetupOption(R.drawable.ic_moon, null,
                getString(R.string.scr_onb_theme_dark), getString(R.string.scr_onb_theme_dark_desc),
                "dark".equals(settings.getTheme()), v -> animatePreferenceChange(() -> {
                    settings.setTheme("dark");
                    app.multiai.mobile.MultiAIApp.applyTheme("dark");
                })));
        themeRow.addView(setupOptionGap());
        themeRow.addView(buildSetupOption(R.drawable.ic_s_sun, null,
                getString(R.string.scr_onb_theme_light), getString(R.string.scr_onb_theme_light_desc),
                "light".equals(settings.getTheme()), v -> animatePreferenceChange(() -> {
                    settings.setTheme("light");
                    app.multiai.mobile.MultiAIApp.applyTheme("light");
                })));
        root.addView(themeRow);

        root.addView(spacer(22));
        TextView languageTitle = txtRes(getString(R.string.scr_onb_choose_language), 16,
                R.color.text_secondary, true);
        root.addView(languageTitle);
        root.addView(spacer(10));

        LinearLayout languageRow = setupOptionRow();
        languageRow.addView(buildSetupOption(0, "🇷🇺", getString(R.string.scr_onb_language_ru),
                getString(R.string.scr_onb_language_ru_desc), "ru".equals(settings.getLanguage()),
                v -> animatePreferenceChange(() -> {
                    settings.setLanguage("ru");
                    app.multiai.mobile.MultiAIApp.applyLanguage("ru");
                })));
        languageRow.addView(setupOptionGap());
        languageRow.addView(buildSetupOption(0, "🇺🇸", getString(R.string.scr_onb_language_en),
                getString(R.string.scr_onb_language_en_desc), "en".equals(settings.getLanguage()),
                v -> animatePreferenceChange(() -> {
                    settings.setLanguage("en");
                    app.multiai.mobile.MultiAIApp.applyLanguage("en");
                })));
        root.addView(languageRow);

        root.addView(spacer(22));
        View next = makeButtonRes(getString(R.string.scr_continue), R.color.accent,
                v -> viewPager.setCurrentItem(2, true));
        root.addView(next);
        root.addView(spacer(16));
        root.addView(buildDots(1));
        return root;
    }

    private LinearLayout setupOptionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View setupOptionGap() {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        return gap;
    }

    private View buildSetupOption(int iconRes, String symbol, String title, String description,
                                  boolean selected, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(12), dp(14), dp(12), dp(14));
        card.setLayoutParams(new LinearLayout.LayoutParams(0, dp(132), 1f));
        GradientDrawable fill = pillRes(selected ? R.color.surface_tonal : R.color.surface_card, 16);
        fill.setStroke(dp(selected ? 2 : 1), col(selected ? R.color.accent : R.color.stroke));
        applyRipple(card, fill, 16);

        if (iconRes != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes);
            icon.setColorFilter(col(R.color.icon));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28), dp(28));
            iconLp.gravity = Gravity.CENTER_HORIZONTAL;
            icon.setLayoutParams(iconLp);
            card.addView(icon);
        } else {
            TextView icon = txtRes(symbol, 25, R.color.text_primary, false);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon);
        }
        card.addView(spacer(5));

        TextView heading = txtRes(title, 14, R.color.text_primary, true);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading);
        card.addView(spacer(4));

        TextView desc = txtRes(description, 11, R.color.text_hint, false);
        desc.setGravity(Gravity.CENTER);
        desc.setMaxLines(3);
        card.addView(desc);
        card.setOnClickListener(selected ? null : listener);
        card.setClickable(!selected);
        return card;
    }

    private void animatePreferenceChange(Runnable applyChange) {
        if (viewPager == null) {
            applyChange.run();
            return;
        }
        restoredPage = viewPager.getCurrentItem();
        viewPager.animate().cancel();
        viewPager.animate()
                .alpha(0f)
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(160)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(applyChange)
                .start();
    }

    private View buildLoginPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(40), dp(80), dp(40), dp(60));

        ImageView logo = icon(R.drawable.ic_multiai_logo, 180, null);
        logo.setColorFilter(col(R.color.icon));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(180), dp(180));
        logoLp.gravity = Gravity.CENTER;
        logo.setLayoutParams(logoLp);
        root.addView(logo);
        fadeSlideIn(logo, 0, 24);

        root.addView(spacer(24));

        TextView title = txtRes(getString(R.string.scr_onb_login_title), 28,
                R.color.text_primary, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        fadeSlideIn(title, 150, 18);

        root.addView(spacer(12));

        TextView desc = txtRes(getString(R.string.scr_onb_login_desc), 14,
                R.color.text_hint, false);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(3), 1f);
        root.addView(desc);
        fadeSlideIn(desc, 300, 18);

        root.addView(spacer(40));

        com.google.android.material.button.MaterialButton btn =
                new com.google.android.material.button.MaterialButton(this);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        btn.setText(R.string.scr_onb_signin_multiai);
        btn.setTextColor(col(R.color.icon_on_accent));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundTintList(ColorStateList.valueOf(col(R.color.accent)));
        btn.setCornerRadius(dp(14));
        btn.setIconResource(R.drawable.ic_multiai_logo);
        btn.setIconTint(ColorStateList.valueOf(col(R.color.icon_on_accent)));
        btn.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
        btn.setIconSize(dp(32));
        btn.setIconPadding(0);
        btn.setOnClickListener(v -> {
            if (oauth.getStore().hasRefreshToken()) {
                viewPager.setCurrentItem(3, true);
            } else {
                oauth.startLogin();
            }
        });
        root.addView(btn);
        fadeSlideIn(btn, 450, 24);

        root.addView(spacer(24));
        root.addView(buildDots(2));
        return root;
    }

    // ── PAGE 1: Mode selection (side-by-side) ──
    private View buildModePage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(24), dp(60), dp(24), dp(60));

        // MultiAI icon
        ImageView logo = icon(R.drawable.ic_multiai_logo, 128, null);
        logo.setColorFilter(col(R.color.icon));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(128), dp(128));
        logoLp.gravity = Gravity.CENTER;
        logo.setLayoutParams(logoLp);
        root.addView(logo);
        fadeSlideIn(logo, 0, 20);

        root.addView(spacer(16));

        TextView title = txtRes(getString(R.string.scr_onb_choose_mode), 22, R.color.text_primary, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        fadeSlideIn(title, 100, 15);

        root.addView(spacer(32));

        // Side-by-side cards
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setGravity(Gravity.CENTER);
        cards.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cards.addView(buildModeCard(R.drawable.ic_onb_chatmode, "Just Chatting", "#22D3EE",
                getString(R.string.scr_onb_mode_chat_desc), true, null));

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 1));
        cards.addView(gap);

        cards.addView(buildModeCard(R.drawable.ic_onb_projectmode, "Project Work", "#8B5CF6",
                getString(R.string.scr_onb_mode_project_desc), true, null));

        root.addView(cards);
        fadeSlideIn(cards, 300, 30);

        root.addView(spacer(24));

        TextView hint = txtRes(getString(R.string.scr_onb_tap_to_select), 12, R.color.text_hint, false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint);
        fadeSlideIn(hint, 500, 10);

        root.addView(spacer(16));
        root.addView(buildDots(3));

        return root;
    }

    private View buildModeCard(int iconRes, String name, String accent, String desc, boolean active, String badge) {
        final boolean isChatMode = name.contains("Chat");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(16), dp(24), dp(16), dp(24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        card.setLayoutParams(lp);

        // Theme-aware surface: a card fill with a hairline stroke. Ripple gives touch feedback.
        GradientDrawable fill = pillRes(R.color.surface_card, 18);
        fill.setStroke(dp(1), col(R.color.stroke));
        applyRipple(card, fill, 18);
        card.setClickable(true);
        card.setFocusable(true);

        // Icon
        ImageView ic = icon(iconRes, 40, accent);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        icLp.gravity = Gravity.CENTER;
        ic.setLayoutParams(icLp);
        card.addView(ic);
        card.addView(spacer(12));

        // Name
        TextView nameTv = txtRes(name, 14, R.color.text_secondary, true);
        nameTv.setGravity(Gravity.CENTER);
        card.addView(nameTv);
        card.addView(spacer(6));

        // Description
        TextView descTv = txtRes(desc, 11, R.color.text_hint, false);
        descTv.setGravity(Gravity.CENTER);
        descTv.setLineSpacing(dp(2), 1f);
        card.addView(descTv);

        if (badge != null) {
            card.addView(spacer(10));
            TextView bdg = txt(badge, 10, "#F59E0B", true);
            bdg.setGravity(Gravity.CENTER);
            bdg.setPadding(dp(12), dp(4), dp(12), dp(4));
            bdg.setBackground(pill("#422006", 8));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.gravity = Gravity.CENTER;
            bdg.setLayoutParams(bLp);
            card.addView(bdg);
        }

        if (active) {
            card.setOnClickListener(v -> {
                String mode = isChatMode ? "chat" : "project";
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("work_mode", mode).apply();
                new app.multiai.mobile.utils.AppSettings(this).setChatMode(mode);
                handler.removeCallbacksAndMessages(null);
                viewPager.setCurrentItem(4, true);
            });
        }

        return card;
    }

    // ── PAGE 7: Final loading (animations deferred to startFinalPageAnimations) ──
    private View buildFinalPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(dp(40), dp(80), dp(40), dp(60));

        finalDisplayName = getString(R.string.scr_onb_friend);
        // MultiAI OAuth profile lives in the encrypted OAuthStore; if the user has
        // already signed in, prefer the saved display name for the welcome text.
        try {
            app.multiai.mobile.network.oauth.OAuthStore oauthStore =
                    new app.multiai.mobile.network.oauth.OAuthStore(this);
            if (oauthStore.hasRefreshToken()) {
                String saved = oauthStore.getName();
                if (saved != null && !saved.isEmpty()) finalDisplayName = saved;
            }
        } catch (Exception ignored) { /* fall back to default */ }

        // MultiAI icon with glow pulse
        ImageView logo = icon(R.drawable.ic_multiai_logo, 160, null);
        logo.setColorFilter(col(R.color.icon));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(160), dp(160));
        logoLp.gravity = Gravity.CENTER;
        logo.setLayoutParams(logoLp);
        root.addView(logo);
        ObjectAnimator pulse = ObjectAnimator.ofFloat(logo, "alpha", 0.5f, 1f, 0.5f);
        pulse.setDuration(2000);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        trackLoop(pulse);

        root.addView(spacer(32));

        // Welcome text (typing animation — deferred)
        finalWelcome = txtRes("", 26, R.color.text_primary, true);
        finalWelcome.setGravity(Gravity.CENTER);
        root.addView(finalWelcome);

        root.addView(spacer(16));

        // Status text (changes over time — deferred)
        finalStatus = txtRes("", 14, R.color.text_hint, false);
        finalStatus.setGravity(Gravity.CENTER);
        finalStatus.setLineSpacing(dp(2), 1f);
        root.addView(finalStatus);

        root.addView(spacer(32));

        // Progress bar — built as a simple colored strip whose width is scaled.
        // Using a real ProgressBar (LayerDrawable + ClipDrawable) introduces visible
        // stepping because progress levels are quantized; animating a View's scale
        // is pixel-smooth and frame-perfect.
        final View progressTrack = new View(this);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(200), dp(4));
        trackLp.gravity = Gravity.CENTER;
        progressTrack.setLayoutParams(trackLp);
        // Track uses a tonal surface so it reads in both light and dark mode.
        progressTrack.setBackground(pillRes(R.color.surface_tonal, 2));

        progressFill = new View(this);
        progressFill.setBackground(pillRes(R.color.accent, 2));
        android.widget.FrameLayout progressHolder = new android.widget.FrameLayout(this);
        progressHolder.setLayoutParams(new LinearLayout.LayoutParams(dp(200), dp(4)));
        android.widget.FrameLayout.LayoutParams fillLp = new android.widget.FrameLayout.LayoutParams(
                dp(200), dp(4));
        fillLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        progressFill.setLayoutParams(fillLp);
        progressFill.setPivotX(0f);
        progressFill.setScaleX(0f);
        progressHolder.addView(progressTrack);
        progressHolder.addView(progressFill);
        root.addView(progressHolder);

        // Keep a reference so the startFinalPageAnimations can drive the fill.
        finalProgress = new ProgressBar(this); // unused, kept for field compatibility
        finalProgress.setVisibility(View.GONE);
        root.addView(finalProgress);

        // All animations are triggered by startFinalPageAnimations() when page becomes visible
        return root;
    }

    private void startFinalPageAnimations() {
        if (finalWelcome == null || finalStatus == null || finalProgress == null) return;

        // ── Typing animation ──
        String welcomeText = getString(R.string.scr_welcome_name, finalDisplayName);
        for (int i = 0; i <= welcomeText.length(); i++) {
            final int idx = i;
            handler.postDelayed(() -> {
                if (finalWelcome != null) finalWelcome.setText(welcomeText.substring(0, idx) + "|");
            }, 50L * i);
        }
        long typeEnd = 50L * welcomeText.length();
        handler.postDelayed(() -> { if (finalWelcome != null) finalWelcome.setText(welcomeText); }, typeEnd + 200);

        // ── Changing status phrases ──
        String[] phrases = getResources().getStringArray(R.array.scr_onboarding_phrases);

        long delay = typeEnd + 500;
        int interval = 1300;
        for (int i = 0; i < phrases.length; i++) {
            final int idx = i;
            handler.postDelayed(() -> {
                if (finalStatus == null) return;
                finalStatus.setAlpha(0f);
                finalStatus.setText(phrases[idx]);
                finalStatus.animate().alpha(1f).setDuration(300).start();
            }, delay + (long) interval * i);
        }

        // ── Smooth progress bar ──
        // Drive the fill View's scaleX from 0→1. This is pixel-smooth: no quantized
        // progress levels, no ClipDrawable stepping. LinearInterpolator = constant speed.
        progressFill.setScaleX(0f);
        ObjectAnimator progressAnim = ObjectAnimator.ofFloat(progressFill, "scaleX", 0f, 1f);
        progressAnim.setDuration(14500);
        progressAnim.setStartDelay(delay);
        progressAnim.setInterpolator(new LinearInterpolator());
        progressAnim.start();

        // ── Complete after all phrases finish ──
        handler.postDelayed(this::completeOnboarding, delay + (long) interval * phrases.length + 500);
    }

    // ═══════════════════ PAGE DOTS ═══════════════════

    private View buildDots(int activePage) {
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dots.setLayoutParams(dlp);
        for (int i = 0; i < TOTAL_PAGES; i++) {
            View dot = new View(this);
            boolean isActive = (i == activePage);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(isActive ? 24 : 8), dp(8));
            dotLp.setMargins(dp(3), 0, dp(3), 0);
            dot.setLayoutParams(dotLp);
            dot.setBackground(pillRes(isActive ? R.color.accent : R.color.surface_tonal, 4));
            dots.addView(dot);
        }
        return dots;
    }
}
