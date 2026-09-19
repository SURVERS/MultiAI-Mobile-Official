package app.multiai.mobile.ui.kit;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;

import java.util.List;

import app.multiai.mobile.R;

/**
 * Anchored dropdown menu — modern replacement for {@link android.widget.PopupMenu}.
 *
 * <p>Shows a small rounded card with the given rows, anchored to a view (typically the
 * tapped row/button). Tapping a row fires the callback and dismisses; tapping outside
 * just dismisses.
 *
 * <pre>{@code
 *   ModernMenu.show(v, "Тема",
 *       Arrays.asList(
 *           new ModernSheet.MenuItem("Системные"),
 *           new ModernSheet.MenuItem("Светлый"),
 *           new ModernSheet.MenuItem("Тёмный")),
 *       (i, item) -> applyTheme(item.label.toString()));
 * }</pre>
 */
public final class ModernMenu {

    private ModernMenu() {}

    /**
     * Show a dropdown menu anchored to {@code anchor} (appears below it by default,
     * or above if there is no room).
     */
    public static PopupWindow show(View anchor, String title, List<ModernSheet.MenuItem> items,
                                   ModernSheet.OnSelect onSelect) {
        Context ctx = anchor.getContext();

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(ctx, 6);
        list.setPadding(pad, pad, pad, pad);
        list.setBackground(ContextCompat.getDrawable(ctx, R.drawable.kit_toast_bg));
        list.setElevation(UiKit.dp(ctx, 12));

        boolean hasTitle = title != null && !title.isEmpty();
        if (hasTitle) {
            android.widget.TextView tvTitle = UiKit.text(ctx, title, 12, R.color.text_hint, true);
            tvTitle.setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 8), UiKit.dp(ctx, 10), UiKit.dp(ctx, 4));
            list.addView(tvTitle);
        }

        final PopupWindow[] popupRef = new PopupWindow[1];
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final ModernSheet.MenuItem item = items.get(i);
            list.addView(menuRow(ctx, item, v -> {
                if (popupRef[0] != null) popupRef[0].dismiss();
                if (onSelect != null) onSelect.onSelect(index, item);
            }));
        }

        int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(UiKit.dp(ctx, 240), View.MeasureSpec.AT_MOST);
        int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        list.measure(widthMeasureSpec, heightMeasureSpec);
        int contentW = list.getMeasuredWidth();
        int contentH = list.getMeasuredHeight();

        PopupWindow popup = new AnimatedPopupWindow(list, contentW, contentH, true);
        popupRef[0] = popup;
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(UiKit.dp(ctx, 12));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int anchorBottom = loc[1] + anchor.getHeight();
        int displayH = ctx.getResources().getDisplayMetrics().heightPixels;
        int displayW = ctx.getResources().getDisplayMetrics().widthPixels;

        int gravity;
        int y;
        if (anchorBottom + contentH + UiKit.dp(ctx, 16) > displayH) {
            // not enough room below — anchor to the top of the anchor
            y = -anchor.getHeight() - contentH;
            gravity = Gravity.TOP;
        } else {
            y = 0;
            gravity = Gravity.TOP;
        }

        // For right-aligned user bubbles: if anchor is on the right half of screen,
        // anchor popup to the right edge instead of the left.
        if (loc[0] + anchor.getWidth() / 2 >= displayW / 2) {
            gravity |= Gravity.END;
        }

        popup.showAsDropDown(anchor, 0, y, gravity);

        // Smooth enter animation: fade-in + scale-up from the top edge (pivotY=0)
        // so the dropdown feels like it unfolds downward over ~180ms.
        list.setPivotY(0f);
        list.setAlpha(0f);
        list.setScaleY(0.92f);
        list.animate()
                .alpha(1f)
                .scaleY(1f)
                .setDuration(MotionKit.duration(MotionKit.STANDARD))
                .setInterpolator(MotionKit.STANDARD_EASING)
                .start();

        return popup;
    }

    private static View menuRow(Context ctx, ModernSheet.MenuItem item, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(ctx, 44));
        int padV = UiKit.dp(ctx, 10);
        int padH = UiKit.dp(ctx, 10);
        row.setPadding(padH, padV, padH, padV);
        if (item.disabled) {
            row.setBackground(null);
            row.setOnClickListener(null);
        } else {
            row.setBackground(UiKit.rippleRounded(ctx, UiKit.RADIUS_SM,
                    ContextCompat.getColor(ctx, R.color.ripple)));
            row.setOnClickListener(click);
            MotionKit.attachPress(row, 0.985f);
        }

        if (item.iconRes != 0) {
            int tint = ContextCompat.getColor(ctx, item.disabled ? R.color.text_hint
                    : item.destructive ? R.color.error : R.color.icon);
            row.addView(UiKit.icon(ctx, item.iconRes, 20, tint));
            ((LinearLayout.LayoutParams) row.getChildAt(row.getChildCount() - 1).getLayoutParams())
                    .setMarginEnd(UiKit.dp(ctx, 12));
        }

        android.widget.TextView label = UiKit.text(ctx, item.label, item.disabled ? 12 : 14,
                item.disabled ? R.color.text_hint
                        : item.destructive ? R.color.error : R.color.text_primary,
                false);
        row.addView(label);
        return row;
    }
}
