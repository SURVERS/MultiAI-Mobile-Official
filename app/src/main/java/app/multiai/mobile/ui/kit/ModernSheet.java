package app.multiai.mobile.ui.kit;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;

import app.multiai.mobile.R;

/**
 * Modern bottom sheet for the app. A rounded-top card slides up from the bottom,
 * anchored above the window edge with a translucent dim backdrop.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #menu(Context, String, List, OnSelect)} — a vertical list of selectable rows
 *       (replaces {@code android.widget.PopupMenu} and hand-built action sheets).</li>
 *   <li>{@link #custom(Context, String, View)} — an arbitrary body view for forms
 *       (rename, attach, feedback…).</li>
 * </ul>
 *
 * <p>Returned {@link Dialog} is already shown; dismiss via {@link Dialog#dismiss()}.
 */
public final class ModernSheet {

    private ModernSheet() {}

    /** A single row in a {@link #menu} sheet. */
    public static final class MenuItem {
        public final CharSequence label;
        public final int iconRes;          // 0 = no icon
        public final boolean destructive;  // renders label in @color/error
        public final Object tag;           // optional payload for the caller

        public MenuItem(CharSequence label) { this(label, 0, false, null); }
        public MenuItem(CharSequence label, int iconRes, boolean destructive, Object tag) {
            this.label = label; this.iconRes = iconRes; this.destructive = destructive; this.tag = tag;
        }
    }

    /** Selection callback for {@link #menu}. */
    public interface OnSelect { void onSelect(int index, MenuItem item); }

    /** Show a bottom-sheet menu list. */
    public static Dialog menu(Context ctx, String title, List<MenuItem> items, OnSelect onSelect) {
        final Dialog[] sheetRef = new Dialog[1];
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final MenuItem item = items.get(i);
            body.addView(menuRow(ctx, item, v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
                if (onSelect != null) onSelect.onSelect(index, item);
            }));
            if (i < items.size() - 1) {
                View div = new View(ctx);
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 1));
                divLp.setMarginStart(UiKit.dp(ctx, 14));
                div.setLayoutParams(divLp);
                div.setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider));
                body.addView(div);
            }
        }
        sheetRef[0] = custom(ctx, title, body);
        return sheetRef[0];
    }

    /** Show a bottom-sheet with a custom body view and a title. */
    public static Dialog custom(Context ctx, String title, View body) {
        return custom(ctx, title, body, true);
    }

    /**
     * Show a bottom-sheet with a custom body view and a title.
     *
     * @param cancelable if false, tapping the backdrop does NOT dismiss the sheet
     *                   and the dialog cannot be cancelled (used for blocking
     *                   question prompts that require an explicit answer).
     */
    public static Dialog custom(Context ctx, String title, View body, boolean cancelable) {
        AnimatedDialog d = new AnimatedDialog(ctx, AnimatedDialog.Mode.SHEET);
        d.setCancelable(cancelable);

        FrameLayout root = new FrameLayout(ctx);
        View backdrop = new View(ctx);
        backdrop.setBackgroundColor(ContextCompat.getColor(ctx, R.color.overlay_dim));
        if (cancelable) {
            backdrop.setOnClickListener(v -> d.dismiss());
        } else {
            backdrop.setOnClickListener(v -> {}); // swallow taps when non-cancelable
        }
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(ContextCompat.getDrawable(ctx, R.drawable.kit_sheet_bg));
        int pad = UiKit.dp(ctx, 20);
        int baseBottomPadding = UiKit.dp(ctx, 32);
        sheet.setPadding(pad, UiKit.dp(ctx, 12), pad, baseBottomPadding);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sheet, (view, insets) -> {
            int navigationBottom = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            view.setPadding(pad, UiKit.dp(ctx, 12), pad,
                    baseBottomPadding + navigationBottom);
            return insets;
        });

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        sheet.setLayoutParams(sheetLp);
        sheet.setOnClickListener(v -> {}); // swallow card taps

        // Drag handle
        View handle = new View(ctx);
        handle.setBackground(new android.graphics.drawable.InsetDrawable(
                UiKit.pillRes(ctx, R.color.stroke, UiKit.RADIUS_PILL),
                UiKit.dp(ctx, 6), UiKit.dp(ctx, 10),
                UiKit.dp(ctx, 6), UiKit.dp(ctx, 10)));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 48), UiKit.dp(ctx, 24));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handle.setLayoutParams(handleLp);
        handle.setClickable(true);
        sheet.addView(handle);

        // A non-cancellable sheet must not be dismissible through its drag handle:
        // approval sheets wait on a worker latch and otherwise leave that worker blocked.
        if (cancelable) {
            // Dragging the handle follows the finger. Releasing past the threshold closes the sheet;
            // an incomplete gesture settles back with the shared emphasized easing.
            final float[] dragStartY = new float[1];
            final long[] dragStartTime = new long[1];
            handle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dragStartY[0] = event.getRawY();
                        dragStartTime[0] = android.os.SystemClock.uptimeMillis();
                        sheet.animate().cancel();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float distance = Math.max(0f, event.getRawY() - dragStartY[0]);
                        sheet.setTranslationY(distance);
                        float height = Math.max(sheet.getHeight(), UiKit.dp(ctx, 240));
                        backdrop.setAlpha(Math.max(0.35f, 1f - distance / height));
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        float dy = Math.max(0f, event.getRawY() - dragStartY[0]);
                        long elapsed = Math.max(1L,
                                android.os.SystemClock.uptimeMillis() - dragStartTime[0]);
                        boolean flingDown = dy / elapsed > 0.7f;
                        if (dy > UiKit.dp(ctx, 72) || flingDown) {
                            d.dismiss();
                        } else {
                            sheet.animate().translationY(0f)
                                    .setDuration(MotionKit.STANDARD)
                                    .setInterpolator(MotionKit.STANDARD_EASING).start();
                            backdrop.animate().alpha(1f)
                                    .setDuration(MotionKit.STANDARD)
                                    .setInterpolator(MotionKit.STANDARD_EASING).start();
                        }
                        return true;
                    default:
                        return false;
                }
            });
        }

        // Title
        if (title != null && !title.isEmpty()) {
            TextView tvTitle = UiKit.text(ctx, title, 17, R.color.text_primary, true);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = UiKit.dp(ctx, 14);
            tvTitle.setLayoutParams(tLp);
            sheet.addView(tvTitle);
        }

        // Body
        if (body != null) {
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = UiKit.dp(ctx, title != null && !title.isEmpty() ? 8 : 14);
            bodyLp.bottomMargin = UiKit.dp(ctx, 12);
            body.setLayoutParams(bodyLp);
            sheet.addView(body);
        }

        root.addView(sheet);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            app.multiai.mobile.utils.SecurityHelper.applySecure(ctx, w);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Real backdrop blur (frosted glass) on Android 12+.
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    w.setBackgroundBlurRadius(UiKit.dp(ctx, 24));
                } catch (Throwable ignored) {}
            }
        }

        d.bind(backdrop, sheet);
        d.show();
        sheet.post(() -> androidx.core.view.ViewCompat.requestApplyInsets(sheet));
        d.playEnter();
        return d;
    }

    private static View menuRow(Context ctx, MenuItem item, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(ctx, 50));
        int padV = UiKit.dp(ctx, 10);
        row.setPadding(0, padV, 0, padV);
        row.setBackground(UiKit.rippleRounded(ctx, UiKit.RADIUS_MD,
                ContextCompat.getColor(ctx, R.color.ripple)));
        row.setOnClickListener(click);

        if (item.iconRes != 0) {
            int tint = ContextCompat.getColor(ctx, item.destructive ? R.color.error : R.color.icon);
            row.addView(UiKit.icon(ctx, item.iconRes, 22, tint));
            ((LinearLayout.LayoutParams) row.getChildAt(0).getLayoutParams()).setMarginEnd(UiKit.dp(ctx, 14));
        }

        TextView label = UiKit.text(ctx, item.label, 15,
                item.destructive ? R.color.error : R.color.text_primary, false);
        row.addView(label);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(lp);
        return row;
    }
}
