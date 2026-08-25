package app.multiai.mobile.ui.kit;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.utils.AppSettings;

/**
 * The "MultiAI + WORK/CHAT badge" button shown in the chat toolbar.
 *
 * <p>Tapping it opens an anchored dropdown directly below the button. The current
 * mode label ("Work") stays in place; the other option ("Chat") appears beneath it
 * inside the dropdown. Selecting an item switches the work/chat mode and launches
 * the corresponding activity.
 *
 * <p>Usage: call {@link #attachTo(Activity, View, String)} where {@code anchor} is the
 * container that holds the MultiAI text + badge (the button the user taps).
 */
public final class ModeSwitcher {

    private ModeSwitcher() {}

    public static final String MODE_WORK = "project";
    public static final String MODE_CHAT = "chat";

    /**
     * The dropdown currently on screen (if any). Kept static so that opening a
     * new dropdown — or leaving its host screen — always dismisses the previous
     * one, preventing orphaned floating rectangles.
     */
    private static PopupWindow visibleDropdown;

    /**
     * Builds the MultiAI + badge button (a horizontal LinearLayout) for embedding in a toolbar.
     *
     * @param activity     the hosting activity
     * @param currentMode  {@link #MODE_WORK} or {@link #MODE_CHAT}
     * @param onTapped     callback invoked when the button is tapped (open the dropdown).
     *                     The built view is already clickable and calls this.
     */
    public static View buildButton(Activity activity, String currentMode, Runnable onTapped) {
        LinearLayout btn = new LinearLayout(activity);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setBackground(UiKit.rippleRounded(activity, UiKit.RADIUS_PILL,
                ContextCompat.getColor(activity, R.color.ripple)));
        int padH = UiKit.dp(activity, 8);
        int padV = UiKit.dp(activity, 4);
        btn.setPadding(padH, padV, padH, padV);

        // "MultiAI" text
        TextView title = new TextView(activity);
        title.setText("MultiAI");
        title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.addView(title);

        // Mode badge (superscript-style, medium size, next to the title)
        TextView badge = new TextView(activity);
        boolean isWork = MODE_WORK.equals(currentMode);
        badge.setText(activity.getString(isWork ? R.string.mode_badge_work : R.string.mode_badge_chat));
        badge.setTextColor(isWork ? android.graphics.Color.BLACK
                : ContextCompat.getColor(activity, R.color.text_secondary));
        badge.setTextSize(11);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setBackgroundResource(isWork ? R.drawable.mode_badge_work : R.drawable.mode_badge_chat);
        int bPadH = UiKit.dp(activity, 6);
        int bPadV = UiKit.dp(activity, 2);
        badge.setPadding(bPadH, bPadV, bPadH, bPadV);
        badge.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.gravity = Gravity.CENTER_VERTICAL;
        badgeLp.setMarginStart(UiKit.dp(activity, 7));
        badge.setLayoutParams(badgeLp);
        btn.addView(badge);

        btn.setOnClickListener(v -> { if (onTapped != null) onTapped.run(); });
        return btn;
    }

    /**
     * Shows the mode-switch dropdown anchored below {@code anchor}.
     *
     * <p>The current mode appears first (highlighted), the other option below it —
     * the anchor button itself doesn't move.
     */
    public static PopupWindow showDropdown(Activity activity, View anchor, String currentMode) {
        // Dismiss any dropdown still showing from a previous tap so we never
        // stack two popups (which leaves an orphaned white rectangle behind).
        dismissVisibleDropdown();

        boolean isWork = MODE_WORK.equals(currentMode);

        // Build rows: current mode first, then the other.
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(activity, 6);
        list.setPadding(pad, pad, pad, pad);
        list.setBackground(ContextCompat.getDrawable(activity, R.drawable.kit_toast_bg));
        list.setElevation(UiKit.dp(activity, 12));

        // First row: current mode (highlighted with a leading dot)
        // Use theme-aware text color (not the badge color, which is black for
        // Work and would be invisible on the dark dropdown background).
        int activeTextColor = ContextCompat.getColor(activity, R.color.text_primary);
        list.addView(modeRow(activity, activity.getString(isWork ? R.string.mode_work : R.string.mode_chat), true,
                isWork ? R.drawable.mode_badge_work : R.drawable.mode_badge_chat,
                activeTextColor,
                v -> { /* current mode — no-op, just dismiss */ }));

        // Second row: the other mode
        boolean otherWork = !isWork;
        list.addView(modeRow(activity, activity.getString(otherWork ? R.string.mode_work : R.string.mode_chat), false, 0,
                ContextCompat.getColor(activity, R.color.text_secondary),
                v -> switchMode(activity, otherWork ? MODE_WORK : MODE_CHAT)));

        int widthSpec = View.MeasureSpec.makeMeasureSpec(UiKit.dp(activity, 180), View.MeasureSpec.AT_MOST);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        list.measure(widthSpec, heightSpec);

        PopupWindow popup = new AnimatedPopupWindow(
                list, list.getMeasuredWidth(), list.getMeasuredHeight(), true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(UiKit.dp(activity, 12));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> {
            // Clear the tracked reference once the popup is gone so a later
            // dismissVisibleDropdown() call doesn't try to dismiss a dead window.
            if (visibleDropdown == popup) {
                visibleDropdown = null;
            }
        });
        // Anchor the dropdown to the top-left of the screen (below the status bar)
        // so it stays aligned with the MultiAI button regardless of where the badge
        // sits horizontally. showAsDropDown(anchor) inherits the badge's X offset,
        // which made the popup drift to the right of the title.
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START,
                UiKit.dp(activity, 12),
                anchor.getTop() + anchor.getHeight() + UiKit.dp(activity, 4)
                        + (anchor.getRootView() != null ? UiKit.dp(activity, 56) : 0));
        visibleDropdown = popup;

        // Fade/scale-in animation
        list.setAlpha(0f);
        list.setScaleY(0.92f);
        list.setPivotY(0f);
        list.animate().alpha(1f).scaleY(1f)
                .setDuration(MotionKit.STANDARD)
                .setInterpolator(MotionKit.STANDARD_EASING).start();

        return popup;
    }

    /**
     * Dismisses the mode-switch dropdown currently on screen (if any). Safe to
     * call from any host (drawer, sidebar, activity lifecycle) to make sure the
     * popup cannot outlive the anchor that opened it.
     */
    public static void dismissVisibleDropdown() {
        if (visibleDropdown != null) {
            if (visibleDropdown.isShowing()) {
                visibleDropdown.dismiss();
            }
            visibleDropdown = null;
        }
    }

    private static View modeRow(Activity activity, String label, boolean active,
                                int badgeRes, int textColor, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(activity, 44));
        int padH = UiKit.dp(activity, 10);
        int padV = UiKit.dp(activity, 8);
        row.setPadding(padH, padV, padH, padV);
        row.setBackground(UiKit.rippleRounded(activity, UiKit.RADIUS_SM,
                ContextCompat.getColor(activity, R.color.ripple)));
        row.setOnClickListener(click);

        if (active) {
            // Active dot
            View dot = new View(activity);
            android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
            dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotBg.setColor(textColor);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    UiKit.dp(activity, 7), UiKit.dp(activity, 7));
            dotLp.setMarginEnd(UiKit.dp(activity, 10));
            dot.setLayoutParams(dotLp);
            row.addView(dot);
        } else {
            // Spacer to align with the dot
            View spacer = new View(activity);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                    UiKit.dp(activity, 17), UiKit.dp(activity, 7));
            spacer.setLayoutParams(spLp);
            row.addView(spacer);
        }

        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(14);
        tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        row.addView(tv);

        return row;
    }

    private static void switchMode(Activity activity, String newMode) {
        new AppSettings(activity).setChatMode(newMode);
        Class<?> target = MODE_WORK.equals(newMode)
                ? app.multiai.mobile.ui.ChatActivity.class
                : app.multiai.mobile.ui.ChatModeActivity.class;
        Intent intent = new Intent(activity, target)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        activity.finish();
    }
}
