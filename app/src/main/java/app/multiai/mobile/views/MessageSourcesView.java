package app.multiai.mobile.views;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import app.multiai.mobile.R;
import app.multiai.mobile.models.Message;
import app.multiai.mobile.utils.FaviconLoader;
import app.multiai.mobile.utils.MessageSources;

public final class MessageSourcesView {
    private static final String TAG = "message_sources_button";
    private MessageSourcesView() {}

    public static void bind(View actionBar, List<Message> messages, Message message, boolean visible) {
        if (!(actionBar instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) actionBar;
        View previous = bar.findViewWithTag(TAG);
        if (previous != null) bar.removeView(previous);
        if (!visible) return;
        List<MessageSources.Source> sources = MessageSources.collect(messages, message);
        Context context = bar.getContext();
        android.view.ViewGroup.LayoutParams barParams = bar.getLayoutParams();
        barParams.width = sources.isEmpty() ? -2 : -1;
        bar.setLayoutParams(barParams);
        for (int i = 0; i < bar.getChildCount(); i++) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) bar.getChildAt(i).getLayoutParams();
            params.width = sources.isEmpty() ? dp(context, 44) : 0;
            params.weight = sources.isEmpty() ? 0 : 1;
            bar.getChildAt(i).setLayoutParams(params);
        }
        if (sources.isEmpty()) return;
        LinearLayout button = new LinearLayout(context);
        button.setTag(TAG);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(context, 6), 0, dp(context, 6), 0);
        button.setMinimumHeight(dp(context, 44));
        button.setContentDescription(context.getString(R.string.message_sources));
        button.setTooltipText(context.getString(R.string.message_sources));
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
        Set<String> domains = new HashSet<>();
        for (MessageSources.Source source : sources) {
            if (!domains.add(source.domain)) continue;
            ImageView icon = icon(context, source, 18);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18));
            if (button.getChildCount() > 0) params.setMarginStart(-dp(context, 5));
            button.addView(icon, params);
            if (domains.size() == 3) break;
        }
        TextView label = text(context, context.getString(R.string.message_sources), 12, false);
        label.setSingleLine(true);
        label.setPadding(dp(context, 5), 0, 0, 0);
        button.addView(label);
        button.setOnClickListener(v -> show(context, sources));
        app.multiai.mobile.ui.kit.MotionKit.attachPress(button, 0.96f);
        bar.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 44)));
    }

    private static void show(Context context, List<MessageSources.Source> sources) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(context, R.color.bg));
        View handle = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        shape.setCornerRadius(dp(context, 4));
        handle.setBackground(shape);
        handle.setAlpha(0.35f);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(context, 32), dp(context, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = dp(context, 12);
        handleParams.bottomMargin = dp(context, 18);
        root.addView(handle, handleParams);
        TextView heading = text(context, context.getString(R.string.message_sources), 16, false);
        heading.setPadding(dp(context, 22), 0, dp(context, 22), dp(context, 16));
        root.addView(heading);
        divider(context, root);
        NestedScrollView scroll = new NestedScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(context, 22), 0, dp(context, 22), dp(context, 28));
        for (MessageSources.Source source : sources) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(context, 20), 0, dp(context, 24));
            LinearLayout site = new LinearLayout(context);
            site.setGravity(Gravity.CENTER_VERTICAL);
            site.addView(icon(context, source, 20), new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)));
            TextView domain = text(context, source.domain, 14, false);
            domain.setPadding(dp(context, 8), 0, 0, 0);
            site.addView(domain);
            row.addView(site);
            TextView title = text(context, source.title, 19, true);
            title.setPadding(0, dp(context, 7), 0, 0);
            row.addView(title);
            if (!source.excerpt.isEmpty()) {
                TextView excerpt = text(context, source.excerpt, 14, false);
                excerpt.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
                excerpt.setMaxLines(3);
                excerpt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                excerpt.setPadding(0, dp(context, 9), 0, 0);
                row.addView(excerpt);
            }
            android.util.TypedValue ripple = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(v -> {
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(source.url)));
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(context, R.string.message_sources_no_browser, Toast.LENGTH_SHORT).show();
                }
            });
            app.multiai.mobile.ui.kit.MotionKit.attachPress(row, 0.985f);
            list.addView(row);
            divider(context, list);
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.88f);
        dialog.setContentView(root, new android.view.ViewGroup.LayoutParams(-1, height));
        dialog.getBehavior().setPeekHeight((int) (height * 0.68f));
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_COLLAPSED);
        dialog.show();
    }

    private static ImageView icon(Context context, MessageSources.Source source, int size) {
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FaviconLoader.into(context, icon, source.url, R.drawable.ic_s_globe, R.color.text_secondary, source.domain);
        return icon;
    }

    private static TextView text(Context context, String value, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static void divider(Context context, LinearLayout parent) {
        View line = new View(context);
        line.setBackgroundColor(ContextCompat.getColor(context, R.color.text_secondary));
        line.setAlpha(0.18f);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(context, 1)));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
