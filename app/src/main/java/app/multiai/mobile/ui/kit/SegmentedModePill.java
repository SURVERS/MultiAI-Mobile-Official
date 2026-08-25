package app.multiai.mobile.ui.kit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;
import app.multiai.mobile.utils.AppSettings;

/**
 * Segmented Chat/Work mode pill. "Чат" on the left, "Работа" on the right.
 * The active segment gets an elevated darker background pill.
 */
public class SegmentedModePill extends LinearLayout {

    private final TextView chatTab;
    private final TextView workTab;

    public SegmentedModePill(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(ContextCompat.getDrawable(context, R.drawable.segmented_pill_bg));
        int dp3 = UiKit.dp(context, 3);
        setPadding(dp3, dp3, dp3, dp3);

        // «Чат» слева, «Работа» справа (локализовано).
        chatTab = makeSegment(context, context.getString(R.string.mode_chat));
        addView(chatTab);

        workTab = makeSegment(context, context.getString(R.string.mode_work));
        addView(workTab);

        updateActiveState();
        attachListeners();
    }

    private TextView makeSegment(Context ctx, String label) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        boolean compact = ctx.getResources().getConfiguration().screenWidthDp < 400;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12 : 13);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setMinHeight(UiKit.dp(ctx, compact ? 36 : 40));

        int horizontalPadding = UiKit.dp(ctx, compact ? 7 : 12);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(tv.getTextSize());
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        int stableWidth = (int) Math.ceil(paint.measureText(label)) + horizontalPadding * 2;
        tv.setLayoutParams(new LinearLayout.LayoutParams(stableWidth,
                LayoutParams.WRAP_CONTENT));
        tv.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setBackground(UiKit.rippleRounded(ctx, UiKit.RADIUS_PILL,
                ContextCompat.getColor(ctx, R.color.ripple)));
        return tv;
    }

    private void updateActiveState() {
        boolean isChatMode = new AppSettings(getContext()).isChatMode();
        Context ctx = getContext();

        // Active gets the elevated pill bg + bold text.
        TextView active = isChatMode ? chatTab : workTab;
        TextView inactive = isChatMode ? workTab : chatTab;

        active.setBackground(ContextCompat.getDrawable(ctx, R.drawable.segmented_pill_active_bg));
        active.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        active.setTypeface(null, Typeface.BOLD);

        inactive.setBackground(UiKit.rippleRounded(ctx, UiKit.RADIUS_PILL,
                ContextCompat.getColor(ctx, R.color.ripple)));
        inactive.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
        inactive.setTypeface(null, Typeface.NORMAL);
    }

    private void attachListeners() {
        chatTab.setOnClickListener(v -> {
            if (!new AppSettings(getContext()).isChatMode()) {
                switchTo(getContext(), true);
            }
        });
        workTab.setOnClickListener(v -> {
            if (new AppSettings(getContext()).isChatMode()) {
                switchTo(getContext(), false);
            }
        });
    }

    public void refresh() {
        updateActiveState();
    }

    private static void switchTo(Context ctx, boolean toChatMode) {
        new AppSettings(ctx).setChatMode(toChatMode ? "chat" : "project");
        Class<?> target = toChatMode
                ? app.multiai.mobile.ui.ChatModeActivity.class
                : app.multiai.mobile.ui.ChatActivity.class;
        Intent intent = new Intent(ctx, target)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ctx.startActivity(intent);
        if (ctx instanceof Activity) {
            ((Activity) ctx).overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        }
    }
}
