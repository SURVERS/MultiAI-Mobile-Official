package app.multiai.mobile.ui.settings;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * ChatGPT-style dropdown menu, привязывается справа от анкора.
 * Формат item: [id, title, subtitle_or_null, selected_bool].
 */
public final class DropdownMenu {

    public static class Item {
        public final String id;
        public final String title;
        public final String subtitle;
        public Item(String id, String title, String subtitle) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    public interface OnItemSelected {
        void onSelected(String id);
    }

    public static void show(View anchor, Item[] items, String currentId, OnItemSelected cb) {
        Context ctx = anchor.getContext();

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsUI.colorCard(ctx));
        bg.setCornerRadius(dp(ctx, 16));
        bg.setStroke(dp(ctx, 1), SettingsUI.colorStroke(ctx));
        container.setBackground(bg);
        container.setPadding(dp(ctx, 5), dp(ctx, 5), dp(ctx, 5), dp(ctx, 5));
        container.setElevation(dp(ctx, 10));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            container.setOutlineAmbientShadowColor(SettingsUI.colorShadowAmbient(ctx));
            container.setOutlineSpotShadowColor(SettingsUI.colorShadowSpot(ctx));
        }

        PopupWindow popup = new PopupWindow(container,
                dp(ctx, 240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(ctx, 12));

        for (Item item : items) {
            View row = buildRow(ctx, item, item.id.equals(currentId));
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (cb != null) cb.onSelected(item.id);
            });
            container.addView(row);
        }

        int xOff = -dp(ctx, 240) + anchor.getWidth() - dp(ctx, 8);
        int yOff = -dp(ctx, 8);
        popup.showAsDropDown(anchor, xOff, yOff, Gravity.END);
    }

    private static View buildRow(Context ctx, Item item, boolean selected) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        row.setClickable(true);
        row.setFocusable(true);

        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setCornerRadius(dp(ctx, 10));
        if (selected) rowBg.setColor(SettingsUI.colorRipple(ctx));
        row.setBackground(rowBg);

        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(ctx.getDrawable(tv.resourceId));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        col.setLayoutParams(colLp);

        TextView title = new TextView(ctx);
        title.setText(item.title);
        title.setTextColor(SettingsUI.colorText(ctx));
        title.setTextSize(15);
        col.addView(title);

        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(item.subtitle);
            sub.setTextColor(SettingsUI.colorSubtext(ctx));
            sub.setTextSize(12);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = dp(ctx, 1);
            sub.setLayoutParams(sLp);
            col.addView(sub);
        }

        row.addView(col);

        if (selected) {
            TextView check = new TextView(ctx);
            check.setText("✓");
            check.setTextColor(SettingsUI.colorText(ctx));
            check.setTextSize(16);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.leftMargin = dp(ctx, 8);
            check.setLayoutParams(cLp);
            row.addView(check);
        }

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(ctx, 1);
        rowLp.bottomMargin = dp(ctx, 1);
        row.setLayoutParams(rowLp);
        return row;
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
