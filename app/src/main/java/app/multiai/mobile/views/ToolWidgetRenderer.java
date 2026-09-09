package app.multiai.mobile.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.view.animation.LinearInterpolator;

import app.multiai.mobile.R;
import app.multiai.mobile.models.ToolCall;

/**
 * Рендерит красивые виджеты для tool results:
 * - get_weather → погодная карточка с динамическим фоном
 * - get_datetime → часы с циферблатом
 * - web_search → карточка с результатами
 * - url_fetch → компактная карточка-превью (ссылка + title)
 */
public final class ToolWidgetRenderer {

    private ToolWidgetRenderer() {}

    /**
     * Создаёт современный виджет для каждого валидного tool call.
     *
     * <p>Ни один штатный вызов не возвращает {@code null}: иначе адаптеры на
     * мгновение показывали старую fallback-карточку до повторного bind после
     * результата/ошибки.</p>
     */
    public static View render(Context context, ToolCall toolCall) {
        if (toolCall == null) return null;
        if (isExploreTool(toolCall.getName())) {
            return renderExplore(context, Collections.singletonList(toolCall));
        }
        if (isIdeTool(toolCall.getName())) {
            return renderIdeTool(context, toolCall);
        }
        if ("project_rules".equals(toolCall.getName())) return renderProjectRules(context, toolCall);
        if ("invoke_subagent".equals(toolCall.getName())) return renderSubagent(context, toolCall);
        if ("enter_plan_mode".equals(toolCall.getName())) return renderPlanEntry(context, toolCall);
        if ("present_plan".equals(toolCall.getName())) return renderPlanCard(context, toolCall);
        if ("todo".equals(toolCall.getName())) return renderTodo(context, toolCall);

        if (hasFailed(toolCall)) return renderToolFailure(context, toolCall);
        if ("question".equals(toolCall.getName())) return renderQuestion(context, toolCall);

        // Web tools own their loading state too, so the search block never
        // flashes an incompatible view while the request is in flight.
        if ("web_search".equals(toolCall.getName())) return renderSearch(context, toolCall);
        if ("url_fetch".equals(toolCall.getName())) return renderUrlFetch(context, toolCall);
        if ("summarize_past_chat".equals(toolCall.getName())) {
            return renderPastChatSummary(context, toolCall);
        }
        if ("summarize_chat_history".equals(toolCall.getName())) {
            return renderSummarize(context, toolCall);
        }

        JSONObject result = toolCall.getResult();
        if ("success".equals(toolCall.getStatus()) && result != null) {
            switch (toolCall.getName()) {
                case "get_weather":
                    return renderWeather(context, result);
                case "get_datetime":
                case "get_current_time":
                    return renderDateTime(context, result);
                case "save_memory":
                    return renderSaveMemory(context, toolCall, result);
                default:
                    break;
            }
        }
        return renderGenericTool(context, toolCall);
    }

    /** Renders the old transient message format with the same modern visual language. */
    public static View renderLegacyToolExecution(Context context, String toolName,
                                                 String toolDetails, boolean completed) {
        return renderGenericTool(context, toolName, toolDetails,
                completed ? "success" : "loading", R.drawable.ic_settings_gear);
    }

    /** Compact safe fallback for tool types without a specialised widget yet. */
    private static View renderGenericTool(Context ctx, ToolCall call) {
        return renderGenericTool(ctx, call.getName(), call.getDisplayText(), call.getStatus(),
                call.getIconRes());
    }

    private static View renderGenericTool(Context ctx, String toolName, String details,
                                          String status, int iconRes) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) dp(ctx, 40));
        row.setPadding((int) dp(ctx, 2), (int) dp(ctx, 2), (int) dp(ctx, 2), (int) dp(ctx, 2));
        row.setForeground(selectableForeground(ctx));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes != 0 ? iconRes : R.drawable.ic_file_outline);
        int stateColor = "success".equals(status) ? R.color.success
                : "loading".equals(status) ? R.color.accent : R.color.icon_muted;
        icon.setColorFilter(color(ctx, stateColor));
        row.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 18), (int) dp(ctx, 18)));

        String labelText = details == null ? "" : details.trim();
        if (labelText.isEmpty() || labelText.equals(toolName)) {
            labelText = ctx.getString(R.string.kit_tool_default);
        }
        TextView label = new TextView(ctx);
        label.setText(labelText);
        label.setTextColor(color(ctx, R.color.text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = (int) dp(ctx, 8);
        row.addView(label, labelLp);

        if ("loading".equals(status)) {
            ProgressBar progress = new ProgressBar(ctx);
            progress.setIndeterminate(true);
            row.addView(progress, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));
        } else if ("success".equals(status)) {
            ImageView complete = new ImageView(ctx);
            complete.setImageResource(R.drawable.ic_check);
            complete.setColorFilter(color(ctx, R.color.success));
            row.addView(complete, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));
        }
        return row;
    }

    private static GradientDrawable outlinedSurface(Context ctx, float cornerRadiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(ctx, R.color.surface_tonal));
        background.setCornerRadius(dp(ctx, cornerRadiusDp));
        background.setStroke((int) dp(ctx, 1), color(ctx, R.color.stroke));
        return background;
    }

    private static View renderProjectRules(Context ctx, ToolCall call) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight((int) dp(ctx, 40));
        header.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        header.setForeground(selectableForeground(ctx));

        ImageView icon = new ImageView(ctx);
        // Собственная иконка правил, а не общий ic_file_outline из Explore.
        icon.setImageResource(R.drawable.ic_included_rules);
        icon.setColorFilter(color(ctx, R.color.accent));
        header.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 18), (int) dp(ctx, 18)));
        TextView title = new TextView(ctx);
        title.setText(R.string.tool_widget_included_rules);
        title.setTextColor(color(ctx, R.color.text_secondary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = (int) dp(ctx, 8);
        header.addView(title, titleLp);

        JSONObject result = call.getResult();
        JSONArray files = result != null ? result.optJSONArray("files") : null;
        TextView count = new TextView(ctx);
        int fileCount = files == null ? 0 : files.length();
        count.setText(String.valueOf(fileCount));
        count.setTextColor(color(ctx, R.color.text_disabled));
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.addView(count);
        ImageView chevron = new ImageView(ctx);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(color(ctx, R.color.icon_muted));
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16));
        chevronLp.leftMargin = (int) dp(ctx, 8);
        header.addView(chevron, chevronLp);
        root.addView(header);

        LinearLayout details = new LinearLayout(ctx);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding((int) dp(ctx, 12), (int) dp(ctx, 8),
                (int) dp(ctx, 12), (int) dp(ctx, 8));
        GradientDrawable detailsBg = new GradientDrawable();
        detailsBg.setColor(color(ctx, R.color.surface_tonal));
        detailsBg.setCornerRadius(dp(ctx, 12));
        detailsBg.setStroke((int) dp(ctx, 1), color(ctx, R.color.stroke));
        details.setBackground(detailsBg);
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                String path = files.optString(i, "").trim();
                if (path.isEmpty()) continue;
                String baseName = fileName(path);
                if (baseName.isEmpty()) baseName = path;

                LinearLayout ruleRow = new LinearLayout(ctx);
                ruleRow.setOrientation(LinearLayout.HORIZONTAL);
                ruleRow.setGravity(Gravity.CENTER_VERTICAL);
                ruleRow.setPadding(0, (int) dp(ctx, 4), 0, (int) dp(ctx, 4));

                ImageView fileIcon = new ImageView(ctx);
                fileIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                fileIcon.setImageDrawable(FileIconDrawable.forName(ctx, baseName));
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                        (int) dp(ctx, 18), (int) dp(ctx, 18));
                iconLp.rightMargin = (int) dp(ctx, 8);
                ruleRow.addView(fileIcon, iconLp);

                TextView nameView = new TextView(ctx);
                nameView.setText(baseName);
                nameView.setTextColor(color(ctx, R.color.text_secondary));
                nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                nameView.setTypeface(Typeface.MONOSPACE);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                ruleRow.addView(nameView, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                details.addView(ruleRow);
            }
        }
        boolean expanded = call.isExpanded();
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 0f : -90f);
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(v -> {
            boolean open = !call.isExpanded();
            call.setExpanded(open);
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(open ? 0f : -90f).setDuration(150).start();
        });
        root.addView(details);
        return root;
    }

    private static View renderSubagent(Context ctx, ToolCall call) {
        LinearLayout card = new LinearLayout(ctx);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight((int) dp(ctx, 48));
        card.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        card.setForeground(selectableForeground(ctx));

        String presetId = call.getParams().getOrDefault("preset_id", "");
        if (presetId.trim().isEmpty()) presetId = app.multiai.mobile.utils.SubagentStore.BUILTIN_GENERAL_ID;
        app.multiai.mobile.models.SubagentPreset preset =
                app.multiai.mobile.utils.SubagentStore.find(ctx, presetId);
        JSONObject result = call.getResult();
        String agentName = result != null ? result.optString("agent_name", "") : "";
        if (agentName.isEmpty()) agentName = preset != null ? preset.name : "general-purpose";
        boolean loading = "loading".equals(call.getStatus());
        boolean failed = "error".equals(call.getStatus());

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_subagent);
        icon.setColorFilter(color(ctx, failed ? R.color.error : R.color.icon_muted));
        card.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 19), (int) dp(ctx, 19)));

        LinearLayout labels = new LinearLayout(ctx);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsLp.leftMargin = (int) dp(ctx, 9);
        TextView title = new TextView(ctx);
        title.setText((loading ? "Работает " : failed ? "Ошибка " : "Готово · ") + agentName);
        title.setTextColor(color(ctx, failed ? R.color.error : R.color.text_secondary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(title);
        TextView task = new TextView(ctx);
        task.setText(call.getParams().getOrDefault("task", ""));
        task.setTextColor(color(ctx, R.color.text_disabled));
        task.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        task.setSingleLine(true);
        task.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(task);
        card.addView(labels, labelsLp);

        if (loading) {
            ProgressBar spinner = new ProgressBar(ctx);
            spinner.setIndeterminate(true);
            card.addView(spinner, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));
        }
        ImageView arrow = new ImageView(ctx);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setColorFilter(color(ctx, R.color.icon_muted));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17));
        arrowLp.leftMargin = (int) dp(ctx, 9);
        card.addView(arrow, arrowLp);

        String sessionId = result != null ? result.optString("session_id", "") : "";
        boolean canOpen = call.canReopen() || (!sessionId.isEmpty()
                && ctx instanceof app.multiai.mobile.ui.ChatActivity);
        card.setClickable(canOpen);
        card.setFocusable(canOpen);
        arrow.setAlpha(canOpen ? 1f : 0.35f);
        card.setOnClickListener(canOpen ? v -> {
            if (call.canReopen()) call.reopen();
            else ((app.multiai.mobile.ui.ChatActivity) ctx).openSubagentSession(sessionId);
        } : null);
        return card;
    }

    private static boolean isIdeTool(String name) {
        return "edit_file".equals(name)
                || "write_file".equals(name)
                || "delete_path".equals(name)
                || "move_path".equals(name);
    }

    private static View renderTodo(Context ctx, ToolCall call) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);
        JSONObject result = call.getResult();
        JSONArray items = result != null ? result.optJSONArray("items") : null;
        if (items == null) {
            try { items = new JSONArray(call.getParams().getOrDefault("items", "[]")); }
            catch (Exception ignored) { items = new JSONArray(); }
        }
        int completed = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "completed".equals(item.optString("status"))) completed++;
        }

        String summary = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "in_progress".equals(item.optString("status"))) {
                summary = item.optString("title", "");
                break;
            }
        }
        if (summary.isEmpty() && items.length() > 0) {
            JSONObject item = items.optJSONObject(Math.max(0, Math.min(completed, items.length() - 1)));
            if (item != null) summary = item.optString("title", "");
        }

        TextView chip = new TextView(ctx);
        chip.setText((completed > 0 ? "✓  " : "○  ") + summary);
        chip.setTextColor(color(ctx, R.color.text_primary));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setMaxWidth((int) dp(ctx, 300));
        chip.setPadding((int) dp(ctx, 12), (int) dp(ctx, 8),
                (int) dp(ctx, 12), (int) dp(ctx, 8));
        android.graphics.drawable.GradientDrawable chipBg =
                new android.graphics.drawable.GradientDrawable();
        chipBg.setColor(color(ctx, R.color.surface_tonal));
        chipBg.setCornerRadius(dp(ctx, 18));
        chipBg.setStroke((int) dp(ctx, 1), color(ctx, R.color.stroke));
        chip.setBackground(chipBg);
        root.addView(chip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) dp(ctx, 14), (int) dp(ctx, 12),
                (int) dp(ctx, 14), (int) dp(ctx, 12));
        card.setBackground(outlinedSurface(ctx, 14));
        card.setVisibility(View.GONE);
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView header = new TextView(ctx);
        header.setText(ctx.getString(R.string.work_todo_progress, completed, items.length()));
        header.setTextColor(color(ctx, R.color.text_primary));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headerRow.addView(header, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView collapse = new TextView(ctx);
        collapse.setText("×");
        collapse.setTextColor(color(ctx, R.color.text_secondary));
        collapse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        collapse.setGravity(Gravity.CENTER);
        headerRow.addView(collapse, new LinearLayout.LayoutParams(
                (int) dp(ctx, 32), (int) dp(ctx, 32)));
        card.addView(headerRow);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String status = item.optString("status", "pending");
            TextView row = new TextView(ctx);
            row.setText(("completed".equals(status) ? "✓  "
                    : "in_progress".equals(status) ? "◉  " : "○  ")
                    + item.optString("title", ""));
            row.setTextColor(color(ctx, "completed".equals(status)
                    ? R.color.text_disabled : R.color.text_secondary));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.setPadding(0, (int) dp(ctx, 5), 0, 0);
            if ("completed".equals(status)) {
                row.setPaintFlags(row.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            }
            card.addView(row);
        }
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = (int) dp(ctx, 6);
        root.addView(card, cardParams);
        View.OnClickListener toggle = v -> {
            boolean expand = card.getVisibility() != View.VISIBLE;
            card.setVisibility(expand ? View.VISIBLE : View.GONE);
            chip.setVisibility(expand ? View.GONE : View.VISIBLE);
        };
        chip.setOnClickListener(toggle);
        collapse.setOnClickListener(toggle);
        return root;
    }

    private static View renderPlanEntry(Context ctx, ToolCall call) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) dp(ctx, 40));
        row.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_plan_mode);
        icon.setColorFilter(color(ctx, R.color.icon_muted));
        row.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 18), (int) dp(ctx, 18)));
        TextView title = new TextView(ctx);
        title.setText(R.string.tool_widget_plan_mode);
        title.setTextColor(color(ctx, R.color.text_secondary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = (int) dp(ctx, 8);
        row.addView(title, titleLp);
        TextView state = new TextView(ctx);
        state.setText("loading".equals(call.getStatus())
                ? R.string.tool_widget_enabling
                : R.string.tool_widget_active_read_only);
        state.setTextColor(color(ctx, R.color.text_disabled));
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stateLp.leftMargin = (int) dp(ctx, 8);
        row.addView(state, stateLp);
        if ("loading".equals(call.getStatus())) {
            ProgressBar progress = new ProgressBar(ctx);
            row.addView(progress, new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16)));
        } else {
            ImageView check = new ImageView(ctx);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(color(ctx, R.color.success));
            row.addView(check, new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16)));
        }
        return row;
    }

    /** Compact Plan Mode card. The full Markdown stays available through the
     * ToolCall's reopen action, which survives ordinary RecyclerView rebinds. */
    private static View renderPlanCard(Context ctx, ToolCall call) {
        JSONObject result = call.getResult();
        String markdown = result != null ? result.optString("markdown", "") : "";
        if (markdown.trim().isEmpty()) markdown = call.getParams().getOrDefault("markdown", "");
        boolean waitingForExit = result != null && result.optBoolean("waiting_for_exit", false);

        LinearLayout root = new LinearLayout(ctx);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setMinimumHeight((int) dp(ctx, 40));
        root.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        root.setForeground(selectableForeground(ctx));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_plan_mode);
        icon.setColorFilter(color(ctx, R.color.icon_muted));
        root.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 18), (int) dp(ctx, 18)));

        TextView title = new TextView(ctx);
        title.setText(R.string.tool_widget_plan);
        title.setTextColor(color(ctx, R.color.text_secondary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = (int) dp(ctx, 8);
        root.addView(title, titleLp);

        TextView summary = new TextView(ctx);
        summary.setText(markdown.trim().isEmpty() ? "Составляется…"
                : waitingForExit ? "Готов · ожидает выхода" : "Готов");
        summary.setTextColor(color(ctx, R.color.text_disabled));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        summaryLp.leftMargin = (int) dp(ctx, 8);
        root.addView(summary, summaryLp);

        ImageView arrow = new ImageView(ctx);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setColorFilter(color(ctx, R.color.icon_muted));
        root.addView(arrow, new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16)));

        final String fullPlanMarkdown = markdown;
        boolean canOpen = call.canReopen() || (!fullPlanMarkdown.trim().isEmpty()
                && ctx instanceof app.multiai.mobile.ui.ChatActivity);
        root.setClickable(canOpen);
        root.setFocusable(canOpen);
        root.setAlpha(canOpen || markdown.trim().isEmpty() ? 1f : 0.65f);
        root.setOnClickListener(canOpen ? v -> {
            if (call.canReopen()) call.reopen();
            else ((app.multiai.mobile.ui.ChatActivity) ctx).showPlanPreview(fullPlanMarkdown);
        } : null);
        return root;
    }

    private static View renderQuestion(Context ctx, ToolCall call) {
        boolean waiting = "loading".equals(call.getStatus()) || call.getResult() == null;
        String question = questionText(call);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) dp(ctx, 14), (int) dp(ctx, 11),
                (int) dp(ctx, 14), (int) dp(ctx, 11));
        card.setBackground(outlinedSurface(ctx, 14));
        card.setForeground(selectableForeground(ctx));

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = new TextView(ctx);
        badge.setText("?");
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(color(ctx, R.color.accent));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setShape(GradientDrawable.OVAL);
        badgeBackground.setColor(color(ctx, R.color.surface_tonal));
        badgeBackground.setStroke((int) dp(ctx, 1), color(ctx, R.color.accent));
        badge.setBackground(badgeBackground);
        header.addView(badge, new LinearLayout.LayoutParams((int) dp(ctx, 22), (int) dp(ctx, 22)));

        TextView state = new TextView(ctx);
        state.setText(waiting ? ctx.getString(R.string.work_question_waiting)
                : ctx.getString(R.string.work_question_answered));
        state.setTextColor(color(ctx, R.color.text_secondary));
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stateLp.leftMargin = (int) dp(ctx, 9);
        header.addView(state, stateLp);

        if (waiting) {
            ProgressBar progress = new ProgressBar(ctx);
            progress.setIndeterminate(true);
            header.addView(progress, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));
        } else {
            ImageView check = new ImageView(ctx);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(color(ctx, R.color.success));
            header.addView(check, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));
        }
        card.addView(header);

        if (!question.isEmpty()) {
            TextView questionView = new TextView(ctx);
            questionView.setText(question);
            questionView.setTextColor(color(ctx, R.color.text_primary));
            questionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            questionView.setLineSpacing(0f, 1.1f);
            LinearLayout.LayoutParams questionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            questionLp.topMargin = (int) dp(ctx, 7);
            card.addView(questionView, questionLp);
        }

        boolean canReopen = waiting && call.canReopen();
        card.setClickable(canReopen);
        card.setFocusable(canReopen);
        card.setOnClickListener(canReopen ? v -> call.reopen() : null);
        return card;
    }

    private static String questionText(ToolCall call) {
        String question = call.getParams().getOrDefault("question", "").trim();
        if (!question.isEmpty()) return question;
        String rawQuestions = call.getParams().getOrDefault("questions", "").trim();
        if (rawQuestions.isEmpty()) return "";
        try {
            JSONArray questions = new JSONArray(rawQuestions);
            JSONObject first = questions.optJSONObject(0);
            return first == null ? "" : first.optString("question", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean isExploreTool(String name) {
        return "read_file".equals(name)
                || "read_skill".equals(name)
                || "list_dir".equals(name)
                || "search_grep".equals(name)
                || "find_files".equals(name)
                || "execute_command".equals(name);
    }

    /** Render adjacent project inspection/command calls as one IDE-like Explore block. */
    public static View renderExplore(Context ctx, List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return null;
        List<ToolCall> supportedCalls = new ArrayList<>();
        for (ToolCall call : calls) {
            if (call == null || !isExploreTool(call.getName())) continue;
            // A call starts in loading before every parameter/result field is
            // necessarily available. Keep it in the same Explore block so no
            // legacy fallback can flash between state updates.
            supportedCalls.add(call);
        }
        if (supportedCalls.isEmpty()) return null;
        View root = LayoutInflater.from(ctx).inflate(R.layout.widget_ide_explore, null);
        LinearLayout header = root.findViewById(R.id.exploreHeader);
        LinearLayout rows = root.findViewById(R.id.exploreRows);
        View details = root.findViewById(R.id.exploreDetails);
        ImageView chevron = root.findViewById(R.id.exploreChevron);
        TextView summary = root.findViewById(R.id.exploreSummary);

        int files = 0;
        int commands = 0;
        for (ToolCall call : supportedCalls) {
            if ("execute_command".equals(call.getName())) commands++;
            else files++;
        }
        StringBuilder summaryText = new StringBuilder();
        if (commands > 0) summaryText.append(ctx.getResources().getQuantityString(
                R.plurals.ide_tool_run_count, commands, commands));
        if (files > 0) {
            if (summaryText.length() > 0) summaryText.append(" ");
            summaryText.append(ctx.getResources().getQuantityString(
                    R.plurals.ide_tool_file_count, files, files));
        }
        summary.setText(summaryText.toString());

        final boolean[] rowsBuilt = {false};
        Runnable buildRows = () -> {
            if (rowsBuilt[0]) return;
            rows.removeAllViews();
            for (ToolCall call : supportedCalls) addExploreRow(ctx, rows, call);
            rowsBuilt[0] = true;
        };
        boolean expanded = supportedCalls.get(0).isExploreExpanded();
        if (expanded) buildRows.run();
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 0f : -90f);
        header.setOnClickListener(v -> {
            boolean open = !supportedCalls.get(0).isExploreExpanded();
            for (ToolCall call : supportedCalls) call.setExploreExpanded(open);
            if (open) buildRows.run();
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(open ? 0f : -90f).setDuration(150).start();
        });
        return root;
    }

    private static void addExploreRow(Context ctx, LinearLayout parent, ToolCall call) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) dp(ctx, 2), 0, (int) dp(ctx, 2));

        LinearLayout title = new LinearLayout(ctx);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMinimumHeight((int) dp(ctx, 30));

        boolean rowFailed = hasFailed(call);
        TextView label = new TextView(ctx);
        label.setText(exploreLabel(ctx, call));
        label.setTextColor(color(ctx, rowFailed ? R.color.error : R.color.text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        if ("loading".equals(call.getStatus())) startStatusShimmer(label);
        title.addView(label, new LinearLayout.LayoutParams(
                rowFailed ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, rowFailed ? 1f : 0f));
        JSONObject callResult = call.getResult();
        String resultPath = callResult != null ? firstNonEmpty(
                callResult.optString("path", ""), firstNonEmpty(
                        callResult.optString("file", ""),
                        callResult.optString("directory", ""))) : "";
        String file = ("read_file".equals(call.getName()) || "list_dir".equals(call.getName()))
                ? fileName(firstNonEmpty(
                        firstNonEmpty(call.getParams().get("path"), call.getParams().get("directory")),
                        resultPath)) : "";
        if ("read_skill".equals(call.getName())) {
            file = firstNonEmpty(call.getParams().get("slug"),
                    callResult != null ? callResult.optString("slug", "SKILL.md") : "SKILL.md");
        }
        String command = "execute_command".equals(call.getName())
                ? firstNonEmpty(call.getParams().get("cmd"), call.getParams().get("command")) : "";
        String targetText = !file.isEmpty() ? file : command;
        // Для упавшего вызова имя цели уже внутри текста ошибки — не дублируем.
        if (rowFailed) {
            ImageView failChevron = new ImageView(ctx);
            failChevron.setImageResource(R.drawable.ic_chevron_down);
            failChevron.setColorFilter(color(ctx, R.color.icon_muted));
            failChevron.setRotation(call.isExpanded() ? 0f : -90f);
            LinearLayout.LayoutParams failChevronLp = new LinearLayout.LayoutParams(
                    (int) dp(ctx, 15), (int) dp(ctx, 15));
            failChevronLp.leftMargin = (int) dp(ctx, 6);
            title.addView(failChevron, failChevronLp);

            LinearLayout failDetails = new LinearLayout(ctx);
            failDetails.setOrientation(LinearLayout.VERTICAL);
            failDetails.setPadding((int) dp(ctx, 6), 0, 0, (int) dp(ctx, 4));
            failDetails.setVisibility(call.isExpanded() ? View.VISIBLE : View.GONE);
            final boolean[] failBuilt = {false};
            Runnable buildFailDetails = () -> {
                if (failBuilt[0]) return;
                addCodeSection(ctx, failDetails, ctx.getString(R.string.tool_fail_reason),
                        failureReason(ctx, call));
                failBuilt[0] = true;
            };
            if (call.isExpanded()) buildFailDetails.run();
            row.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(failDetails, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            title.setOnClickListener(v -> {
                boolean open = !call.isExpanded();
                call.setExpanded(open);
                if (open) buildFailDetails.run();
                failDetails.setVisibility(open ? View.VISIBLE : View.GONE);
                failChevron.animate().rotation(open ? 0f : -90f).setDuration(150).start();
            });
            parent.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        if (!targetText.isEmpty()) {
            if (!file.isEmpty()) {
                ImageView badge;
                if ("read_skill".equals(call.getName())) {
                    badge = new ImageView(ctx);
                    badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    badge.setImageResource(R.drawable.ic_skill_flask);
                    badge.setColorFilter(color(ctx, R.color.icon_muted));
                } else if ("list_dir".equals(call.getName())) {
                    // Просмотр папки — иконка папки (как в разделе «Файлы»), а не файла.
                    badge = new ImageView(ctx);
                    badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    badge.setImageResource(R.drawable.ic_folder_outline);
                    badge.setColorFilter(color(ctx, R.color.icon_muted));
                } else {
                    badge = createFileIcon(ctx, file);
                }
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                        (int) dp(ctx, 20), (int) dp(ctx, 20));
                badgeParams.leftMargin = (int) dp(ctx, 8);
                title.addView(badge, badgeParams);
            }
            TextView target = new TextView(ctx);
            target.setText(targetText);
            target.setTextColor(color(ctx, R.color.text_primary));
            target.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            target.setTypeface(Typeface.MONOSPACE);
            target.setSingleLine(true);
            target.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            targetParams.leftMargin = (int) dp(ctx, file.isEmpty() ? 8 : 7);
            targetParams.rightMargin = (int) dp(ctx, 4);
            title.addView(target, targetParams);
        } else {
            title.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if ("execute_command".equals(call.getName())) {
            LinearLayout commandDetails = new LinearLayout(ctx);
            commandDetails.setOrientation(LinearLayout.VERTICAL);
            commandDetails.setPadding((int) dp(ctx, 18), 0, 0, (int) dp(ctx, 4));
            commandDetails.setVisibility(call.isExpanded() ? View.VISIBLE : View.GONE);
            final boolean[] detailsBuilt = {false};
            Runnable buildDetails = () -> {
                if (detailsBuilt[0]) return;
                JSONObject result = call.getResult();
                boolean failed = "error".equals(call.getStatus())
                        || (result != null && !result.optBoolean("success", true) && result.has("error"));
                String commandValue = firstNonEmpty(call.getParams().get("cmd"), call.getParams().get("command"));
                addCodeSection(ctx, commandDetails, ctx.getString(R.string.ide_tool_input), "$ " + commandValue);
                addCodeSection(ctx, commandDetails, ctx.getString(R.string.ide_tool_output),
                        resultText(ctx, result, "output", failed));
                detailsBuilt[0] = true;
            };
            if (call.isExpanded()) buildDetails.run();
            row.addView(commandDetails, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            title.setOnClickListener(v -> {
                boolean open = !call.isExpanded();
                call.setExpanded(open);
                if (open) buildDetails.run();
                commandDetails.setVisibility(open ? View.VISIBLE : View.GONE);
            });
        }
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static String exploreLabel(Context ctx, ToolCall call) {
        String name = call.getName();
        if ("loading".equals(call.getStatus())) {
            if ("search_grep".equals(name)) return ctx.getString(R.string.ide_tool_searching_query,
                    call.getParams().getOrDefault("query", "…"));
            if ("find_files".equals(name)) return ctx.getString(R.string.ide_tool_searching_files,
                    call.getParams().getOrDefault("pattern", "…"));
            if ("execute_command".equals(name)) return ctx.getString(R.string.ide_tool_running);
            if ("list_dir".equals(name)) return ctx.getString(R.string.ide_tool_listing);
            return ctx.getString(R.string.ide_tool_reading);
        }
        JSONObject result = call.getResult();
        if (result != null && (result.optBoolean("cancelled", false)
                || "CANCELLED_BY_USER".equals(result.optString("code")))) {
            return ctx.getString(R.string.ide_tool_cancelled, cancelledToolAction(ctx, name));
        }
        if (hasFailed(call)) {
            // Человеко-читаемая формулировка вместо «Ошибка чтения».
            return failureLabel(ctx, call);
        }
        if ("search_grep".equals(name)) {
            int count = resultCount(result, "matches");
            return ctx.getResources().getQuantityString(R.plurals.ide_tool_searched_results, count, count);
        }
        if ("find_files".equals(name)) {
            int count = resultCount(result, "files");
            return ctx.getResources().getQuantityString(R.plurals.ide_tool_searched_files, count, count);
        }
        if ("execute_command".equals(name)) return ctx.getString("error".equals(call.getStatus())
                ? R.string.ide_tool_failed : R.string.ide_tool_ran);
        if ("list_dir".equals(name)) {
            int count = resultCount(result, "entries");
            return ctx.getResources().getQuantityString(
                    R.plurals.ide_tool_listed_objects, count, count);
        }
        return ctx.getString("error".equals(call.getStatus())
                ? R.string.ide_tool_read_failed : R.string.ide_tool_read);
    }

    private static String cancelledToolAction(Context ctx, String name) {
        if ("list_dir".equals(name)) return ctx.getString(R.string.ide_tool_action_list_dir);
        if ("search_grep".equals(name)) return ctx.getString(R.string.ide_tool_action_search);
        if ("find_files".equals(name)) return ctx.getString(R.string.ide_tool_action_find_files);
        if ("execute_command".equals(name)) return ctx.getString(R.string.ide_tool_action_command);
        return ctx.getString(R.string.ide_tool_action_read_file);
    }

    private static int resultCount(JSONObject result, String key) {
        if (result == null) return 0;
        int count = result.optInt("count", -1);
        if (count >= 0) return count;
        JSONArray values = result.optJSONArray(key);
        return values == null ? 0 : values.length();
    }

    private static String fileName(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static ImageView createFileIcon(Context ctx, String name) {
        ImageView badge = new ImageView(ctx);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.setImageDrawable(FileIconResolver.drawable(ctx, name));
        return badge;
    }

    private static final class FileIconResolver {
        static Drawable drawable(Context ctx, String name) {
            return FileIconDrawable.forName(ctx, name);
        }
    }

    private static View renderIdeTool(Context ctx, ToolCall toolCall) {
        View root = LayoutInflater.from(ctx).inflate(R.layout.widget_ide_tool, null);
        View header = root.findViewById(R.id.ideToolHeader);
        ImageView icon = root.findViewById(R.id.ideToolIcon);
        ImageView chevron = root.findViewById(R.id.ideToolChevron);
        TextView action = root.findViewById(R.id.ideToolAction);
        ImageView fileBadge = root.findViewById(R.id.ideToolFileBadge);
        TextView target = root.findViewById(R.id.ideToolTarget);
        TextView stats = root.findViewById(R.id.ideToolStats);
        ProgressBar progress = root.findViewById(R.id.ideToolProgress);
        View details = root.findViewById(R.id.ideToolDetails);
        LinearLayout content = root.findViewById(R.id.ideToolDetailsContent);
        final Runnable[] buildDetails = {null};

        String name = toolCall.getName();
        JSONObject result = toolCall.getResult();
        boolean loading = "loading".equals(toolCall.getStatus());
        boolean failed = "error".equals(toolCall.getStatus())
                || (result != null && !result.optBoolean("success", true) && result.has("error"));
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);

        if ("execute_command".equals(name)) {
            icon.setImageResource(R.drawable.ic_terminal_tools);
            action.setText(ctx.getString(loading ? R.string.ide_tool_running
                    : failed ? R.string.ide_tool_failed : R.string.ide_tool_ran));
            if (loading) startStatusShimmer(action);
            String command = firstNonEmpty(toolCall.getParams().get("cmd"),
                    toolCall.getParams().get("command"));
            target.setText(command.isEmpty() ? ctx.getString(R.string.ide_tool_command) : command);
            if (result != null && !loading) {
                int exitCode = result.optInt("exit_code", failed ? -1 : 0);
                stats.setText(ctx.getString(R.string.ide_tool_exit_code, exitCode));
                stats.setTextColor(color(ctx, exitCode == 0 ? R.color.success : R.color.error));
            } else {
                stats.setText("");
            }
            buildDetails[0] = new Runnable() {
                private boolean built;
                @Override public void run() {
                    if (built) return;
                    addCodeSection(ctx, content, ctx.getString(R.string.ide_tool_input), "$ " + command);
                    addCodeSection(ctx, content, ctx.getString(R.string.ide_tool_output),
                            resultText(ctx, result, "output", failed));
                    built = true;
                }
            };
            if (toolCall.isExpanded()) buildDetails[0].run();
        } else if ("delete_path".equals(name)) {
            icon.setImageResource(R.drawable.ic_trash);
            action.setText(ctx.getString(loading ? R.string.ide_tool_deleting
                    : failed ? R.string.ide_tool_failed : R.string.ide_tool_deleted));
            if (loading) startStatusShimmer(action);
            fileBadge.setVisibility(View.GONE);
            String path = toolCall.getParams().getOrDefault("path", "");
            target.setText(path);
            if (result != null && !loading && !failed) {
                int files = result.optInt("deleted_files", 0);
                int directories = result.optInt("deleted_directories", 0);
                stats.setText(files + " f · " + directories + " d");
                stats.setTextColor(color(ctx, R.color.success));
            } else {
                stats.setText("");
            }
            buildDetails[0] = new Runnable() {
                private boolean built;
                @Override public void run() {
                    if (built) return;
                    addCodeSection(ctx, content, ctx.getString(R.string.ide_tool_input), path);
                    addCodeSection(ctx, content, failed ? ctx.getString(R.string.ide_tool_error)
                            : ctx.getString(R.string.ide_tool_output),
                            resultText(ctx, result, failed ? "error" : "message", failed));
                    built = true;
                }
            };
            if (toolCall.isExpanded()) buildDetails[0].run();
        } else if ("move_path".equals(name)) {
            icon.setImageResource(R.drawable.ic_folder_outline);
            action.setText(ctx.getString(loading ? R.string.ide_tool_moving
                    : failed ? R.string.ide_tool_failed : R.string.ide_tool_moved));
            if (loading) startStatusShimmer(action);
            fileBadge.setVisibility(View.GONE);
            String source = firstNonEmpty(toolCall.getParams().get("source_path"),
                    toolCall.getParams().get("from_path"));
            String destination = firstNonEmpty(toolCall.getParams().get("destination_path"),
                    toolCall.getParams().get("to_path"));
            target.setText(source + " → " + destination);
            stats.setText("");
            buildDetails[0] = new Runnable() {
                private boolean built;
                @Override public void run() {
                    if (built) return;
                    addCodeSection(ctx, content, ctx.getString(R.string.ide_tool_input),
                            source + " → " + destination);
                    addCodeSection(ctx, content, failed ? ctx.getString(R.string.ide_tool_error)
                            : ctx.getString(R.string.ide_tool_output),
                            resultText(ctx, result, failed ? "error" : "message", failed));
                    built = true;
                }
            };
            if (toolCall.isExpanded()) buildDetails[0].run();
        } else if ("edit_file".equals(name)) {
            icon.setVisibility(View.GONE);
            ((LinearLayout.LayoutParams) action.getLayoutParams()).leftMargin = 0;
            action.setText(ctx.getString(loading ? R.string.ide_tool_editing
                    : failed ? R.string.ide_tool_failed : R.string.ide_tool_edited));
            if (loading) startStatusShimmer(action);
            bindFileTarget(ctx, fileBadge, target, toolCall.getParams().get("path"));
            String patch = firstNonEmpty(toolCall.getParams().get("patch"),
                    result != null ? result.optString("patch", "") : "");
            PatchStats patchStats = countPatch(patch);
            if (patchStats.additions > 0 || patchStats.deletions > 0) {
                stats.setText("+" + patchStats.additions + "  -" + patchStats.deletions);
                stats.setTextColor(color(ctx, R.color.text_secondary));
            } else {
                stats.setText("");
            }
            buildDetails[0] = new Runnable() {
                private boolean built;
                @Override public void run() {
                    if (built) return;
                    if (failed) {
                        addCodeSection(ctx, content, ctx.getString(R.string.ide_tool_error),
                                resultText(ctx, result, "error", true));
                    } else {
                        String oldContent = result != null && result.has("old_content")
                                ? result.optString("old_content", "") : "";
                        String newContent = result != null && result.has("new_content")
                                ? result.optString("new_content", "") : "";
                        if (result != null && result.has("old_content") && result.has("new_content")) {
                            addContentDiff(ctx, content, oldContent, newContent);
                        } else {
                            addDiff(ctx, content, patch);
                        }
                    }
                    built = true;
                }
            };
            if (toolCall.isExpanded()) buildDetails[0].run();
        } else {
            icon.setVisibility(View.GONE);
            ((LinearLayout.LayoutParams) action.getLayoutParams()).leftMargin = 0;
            String targetPath = toolCall.getParams().get("path");
            boolean created = result != null && result.has("created")
                    ? result.optBoolean("created", false)
                    : !projectFileExists(ctx, targetPath);
            action.setText(ctx.getString(loading
                    ? (created ? R.string.ide_tool_writing : R.string.ide_tool_editing)
                    : failed ? R.string.ide_tool_failed
                    : created ? R.string.ide_tool_wrote : R.string.ide_tool_edited));
            if (loading) startStatusShimmer(action);
            bindFileTarget(ctx, fileBadge, target, targetPath);
            stats.setText("");
            buildDetails[0] = new Runnable() {
                private boolean built;
                @Override public void run() {
                    if (built) return;
                    if (failed) {
                        addCodeSection(ctx, content, ctx.getString(R.string.ide_tool_error),
                                resultText(ctx, result, "error", true));
                    } else {
                        boolean hasBeforeAfter = result != null
                                && result.has("old_content") && result.has("new_content");
                        if (hasBeforeAfter) {
                            addContentDiff(ctx, content, result.optString("old_content", ""),
                                    result.optString("new_content", ""));
                        } else {
                            addCodeSection(ctx, content, ctx.getString(R.string.ide_tool_content),
                                    toolCall.getParams().getOrDefault("content", ""));
                        }
                    }
                    built = true;
                }
            };
            if (toolCall.isExpanded()) buildDetails[0].run();
        }

        boolean canExpand = "edit_file".equals(name) || "execute_command".equals(name)
                || "write_file".equals(name) || "delete_path".equals(name)
                || "move_path".equals(name);
        boolean expanded = canExpand && toolCall.isExpanded();
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setVisibility(canExpand ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 0f : -90f);
        header.setClickable(canExpand);
        header.setFocusable(canExpand);
        header.setOnClickListener(v -> {
            if (!canExpand) return;
            boolean open = !toolCall.isExpanded();
            toolCall.setExpanded(open);
            if (open && buildDetails[0] != null) buildDetails[0].run();
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(open ? 0f : -90f).setDuration(150).start();
        });
        return root;
    }

    private static void bindFileTarget(Context ctx, ImageView badge, TextView target, String path) {
        String file = fileName(path);
        if (file.isEmpty()) {
            badge.setVisibility(View.GONE);
            target.setText("");
            return;
        }
        badge.setImageDrawable(FileIconResolver.drawable(ctx, file));
        badge.setVisibility(View.VISIBLE);
        target.setText(file);
    }

    private static boolean projectFileExists(Context ctx, String path) {
        if (path == null || path.trim().isEmpty()) return false;
        try {
            String treeUri = ctx.getSharedPreferences("multiaiide", Context.MODE_PRIVATE)
                    .getString("project_folder_uri", null);
            if (treeUri == null || treeUri.trim().isEmpty()) return false;
            DocumentFile current = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri));
            if (current == null || !current.exists()) return false;
            for (String part : path.replace('\\', '/').split("/")) {
                if (part.isEmpty() || ".".equals(part)) continue;
                if ("..".equals(part)) return false;
                current = current.findFile(part);
                if (current == null || !current.exists()) return false;
            }
            return current.isFile();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String resultText(Context ctx, JSONObject result, String key, boolean failed) {
        String executionError = ctx.getString(R.string.ide_tool_execution_error);
        if (result == null) return failed ? executionError : "—";
        String value = result.optString(key, "");
        if (value.isEmpty() && failed) value = result.optString("error", executionError);
        return value.isEmpty() ? "—" : value;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) return first;
        return second != null ? second : "";
    }

    private static void addCodeSection(Context ctx, LinearLayout parent, String label, String value) {
        TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextColor(color(ctx, R.color.text_disabled));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        labelView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labelView.setLetterSpacing(0.08f);
        labelView.setPadding((int) dp(ctx, 12), (int) dp(ctx, 4),
                (int) dp(ctx, 12), (int) dp(ctx, 3));
        parent.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView code = new TextView(ctx);
        code.setText(value == null || value.isEmpty() ? "—" : value);
        code.setTextColor(color(ctx, R.color.code_text));
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setPadding((int) dp(ctx, 12), 0, (int) dp(ctx, 12), (int) dp(ctx, 9));
        code.setMinWidth((int) dp(ctx, 260));
        parent.addView(code, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static final Pattern HUNK_PATTERN = Pattern.compile(
            "@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*");

    private static void addDiff(Context ctx, LinearLayout parent, String patch) {
        if (patch == null || patch.trim().isEmpty()) {
            addCodeSection(ctx, parent, ctx.getString(R.string.ide_tool_diff), "—");
            return;
        }

        int oldLine = 0;
        int newLine = 0;
        int previousOldEnd = -1;
        boolean inHunk = false;
        boolean renderedChange = false;
        List<String> unboundedChanges = new ArrayList<>();
        for (String line : patch.split("\\R", -1)) {
            Matcher hunk = HUNK_PATTERN.matcher(line);
            if (hunk.matches()) {
                int oldStart = parseInt(hunk.group(1), 0);
                int oldCount = parseInt(hunk.group(2), 1);
                newLine = parseInt(hunk.group(3), 0);
                if (previousOldEnd >= 0 && oldStart > previousOldEnd) {
                    addHiddenLines(ctx, parent, oldStart - previousOldEnd);
                }
                oldLine = oldStart;
                previousOldEnd = oldStart + oldCount;
                inHunk = true;
                continue;
            }
            if (!inHunk) {
                if ((line.startsWith("+") && !line.startsWith("+++"))
                        || (line.startsWith("-") && !line.startsWith("---"))) {
                    unboundedChanges.add(line);
                }
                continue;
            }
            if (line.startsWith("---") || line.startsWith("+++")) continue;
            if (line.startsWith("\\ No newline")) {
                addDiffNote(ctx, parent, line.substring(2));
            } else if (line.startsWith("+")) {
                addDiffRow(ctx, parent, "", String.valueOf(newLine++), "+", line.substring(1), true, false);
                renderedChange = true;
            } else if (line.startsWith("-")) {
                addDiffRow(ctx, parent, String.valueOf(oldLine++), "", "−", line.substring(1), false, true);
                renderedChange = true;
            } else {
                String code = line.startsWith(" ") ? line.substring(1) : line;
                addDiffRow(ctx, parent, String.valueOf(oldLine++), String.valueOf(newLine++), " ", code, false, false);
            }
        }
        if (!renderedChange && !unboundedChanges.isEmpty()) {
            int oldFallback = 1;
            int newFallback = 1;
            for (String line : unboundedChanges) {
                if (line.startsWith("+")) {
                    addDiffRow(ctx, parent, "", String.valueOf(newFallback++), "+",
                            line.substring(1), true, false);
                } else {
                    addDiffRow(ctx, parent, String.valueOf(oldFallback++), "", "−",
                            line.substring(1), false, true);
                }
            }
        }
    }

    /** Build an IDE-style diff from the before/after contents returned by write_file. */
    private static DiffStats addContentDiff(Context ctx, LinearLayout parent,
                                            String oldContent, String newContent) {
        String[] oldLines = splitLines(oldContent);
        String[] newLines = splitLines(newContent);
        List<DiffOp> ops = buildDiffOps(oldLines, newLines);
        DiffStats stats = new DiffStats();
        boolean hasChanges = false;
        for (DiffOp op : ops) if (op.kind != '=') { hasChanges = true; break; }

        int index = 0;
        while (index < ops.size()) {
            DiffOp current = ops.get(index);
            if (current.kind != '=') {
                if (current.kind == '+') stats.additions++;
                if (current.kind == '-') stats.deletions++;
                addDiffRow(ctx, parent, current.oldNo, current.newNo,
                        current.kind == '+' ? "+" : "−", current.text,
                        current.kind == '+', current.kind == '-');
                index++;
                continue;
            }

            int end = index;
            while (end < ops.size() && ops.get(end).kind == '=') end++;
            int count = end - index;
            if (!hasChanges || count <= 4) {
                for (int i = index; i < end; i++) {
                    DiffOp op = ops.get(i);
                    addDiffRow(ctx, parent, op.oldNo, op.newNo, " ", op.text, false, false);
                }
            } else {
                for (int i = index; i < index + 2; i++) {
                    DiffOp op = ops.get(i);
                    addDiffRow(ctx, parent, op.oldNo, op.newNo, " ", op.text, false, false);
                }
                addHiddenLines(ctx, parent, count - 4);
                for (int i = end - 2; i < end; i++) {
                    DiffOp op = ops.get(i);
                    addDiffRow(ctx, parent, op.oldNo, op.newNo, " ", op.text, false, false);
                }
            }
            index = end;
        }
        if (ops.isEmpty()) addCodeSection(ctx, parent, ctx.getString(R.string.ide_tool_diff), "—");
        return stats;
    }

    private static String[] splitLines(String value) {
        if (value == null || value.isEmpty()) return new String[0];
        return value.replace("\r\n", "\n").split("\n", -1);
    }

    private static List<DiffOp> buildDiffOps(String[] oldLines, String[] newLines) {
        List<DiffOp> ops = new ArrayList<>();
        int n = oldLines.length, m = newLines.length;
        // Keep rendering bounded for very large files; the common case gets an
        // LCS diff that preserves separate distant edits.
        if ((long) n * (long) m > 200000L) {
            int prefix = 0;
            while (prefix < n && prefix < m && oldLines[prefix].equals(newLines[prefix])) prefix++;
            int suffix = 0;
            while (suffix < n - prefix && suffix < m - prefix
                    && oldLines[n - suffix - 1].equals(newLines[m - suffix - 1])) suffix++;
            for (int i = 0; i < prefix; i++) ops.add(new DiffOp('=', i + 1, i + 1, oldLines[i]));
            for (int i = prefix; i < n - suffix; i++) ops.add(new DiffOp('-', i + 1, "", oldLines[i]));
            for (int i = prefix; i < m - suffix; i++) ops.add(new DiffOp('+', "", i + 1, newLines[i]));
            for (int i = 0; i < suffix; i++) {
                int oldNo = n - suffix + i + 1;
                int newNo = m - suffix + i + 1;
                ops.add(new DiffOp('=', oldNo, newNo, oldLines[oldNo - 1]));
            }
            return ops;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = oldLines[i].equals(newLines[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && oldLines[i].equals(newLines[j])) {
                ops.add(new DiffOp('=', i + 1, j + 1, oldLines[i])); i++; j++;
            } else if (i < n && (j >= m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                ops.add(new DiffOp('-', i + 1, "", oldLines[i++]));
            } else {
                ops.add(new DiffOp('+', "", j + 1, newLines[j++]));
            }
        }
        return ops;
    }

    private static void addHiddenLines(Context ctx, LinearLayout parent, int count) {
        if (count <= 0) return;
        TextView view = new TextView(ctx);
        view.setText(ctx.getResources().getQuantityString(
                R.plurals.ide_tool_hidden_lines, count, count));
        view.setTextColor(color(ctx, R.color.text_disabled));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setGravity(Gravity.CENTER);
        view.setPadding((int) dp(ctx, 12), (int) dp(ctx, 7),
                (int) dp(ctx, 12), (int) dp(ctx, 7));
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void addDiffNote(Context ctx, LinearLayout parent, String note) {
        TextView view = new TextView(ctx);
        view.setText(note);
        view.setTextColor(color(ctx, R.color.text_disabled));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding((int) dp(ctx, 68), (int) dp(ctx, 3),
                (int) dp(ctx, 12), (int) dp(ctx, 3));
        parent.addView(view);
    }

    private static void addDiffRow(Context ctx, LinearLayout parent, String oldNo, String newNo,
                                   String marker, String code, boolean added, boolean deleted) {
        String displayNo;
        if (added) displayNo = newNo;
        else if (deleted) displayNo = oldNo;
        else if (oldNo == null || oldNo.isEmpty() || oldNo.equals(newNo)) displayNo = oldNo;
        else displayNo = oldNo + "→" + newNo;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumWidth((int) dp(ctx, 300));
        row.setBackgroundColor(color(ctx, added ? R.color.ide_tool_add_bg
                : deleted ? R.color.ide_tool_delete_bg : R.color.ide_tool_code_bg));

        row.addView(diffCell(ctx, displayNo, 44, R.color.text_disabled, Gravity.END));
        row.addView(diffCell(ctx, marker, 24,
                added ? R.color.ide_tool_add_text : deleted ? R.color.ide_tool_delete_text
                        : R.color.text_disabled, Gravity.CENTER));

        TextView codeView = new TextView(ctx);
        codeView.setText(code);
        codeView.setTextColor(color(ctx, added ? R.color.ide_tool_add_text
                : deleted ? R.color.ide_tool_delete_text : R.color.code_text));
        codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        codeView.setTypeface(Typeface.MONOSPACE);
        codeView.setSingleLine(true);
        codeView.setTextIsSelectable(true);
        codeView.setPadding(0, (int) dp(ctx, 3), (int) dp(ctx, 12), (int) dp(ctx, 3));
        row.addView(codeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (added) {
            int addedIndex = 0;
            Object tag = parent.getTag(R.id.ideToolDetailsContent);
            if (tag instanceof Integer) addedIndex = (Integer) tag;
            parent.setTag(R.id.ideToolDetailsContent, addedIndex + 1);
            row.setAlpha(0f);
            row.setTranslationY(dp(ctx, 5));
            row.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(addedIndex, 30) * 22L)
                    .setDuration(180L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private static TextView diffCell(Context ctx, String value, int widthDp, int colorRes, int gravity) {
        TextView view = new TextView(ctx);
        view.setText(value);
        view.setTextColor(color(ctx, colorRes));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setGravity(gravity | Gravity.CENTER_VERTICAL);
        view.setPadding(0, (int) dp(ctx, 3), (int) dp(ctx, 7), (int) dp(ctx, 3));
        view.setBackgroundColor(color(ctx, R.color.ide_tool_gutter));
        view.setLayoutParams(new LinearLayout.LayoutParams((int) dp(ctx, widthDp),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    private static PatchStats countPatch(String patch) {
        PatchStats stats = new PatchStats();
        if (patch == null) return stats;
        for (String line : patch.split("\\R", -1)) {
            if (line.startsWith("+") && !line.startsWith("+++")) stats.additions++;
            if (line.startsWith("-") && !line.startsWith("---")) stats.deletions++;
        }
        return stats;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static int color(Context ctx, int resId) {
        return androidx.core.content.ContextCompat.getColor(ctx, resId);
    }

    /** ChatGPT-style light sweep used only while a tool is running. */
    private static void startStatusShimmer(TextView view) {
        if (view == null) return;
        Object previous = view.getTag();
        if (previous instanceof ValueAnimator) ((ValueAnimator) previous).cancel();
        view.setTag(null);

        Runnable start = () -> {
            if (!view.isAttachedToWindow() || view.getWidth() <= 0) return;
            if (view.getTag() instanceof ValueAnimator) return;
            int base = view.getTextColors().getColorForState(
                    view.getDrawableState(), view.getCurrentTextColor());
            int highlight = blendColors(base, Color.WHITE, isDarkColor(base) ? 1f : 0.82f);
            int glow = blendColors(base, Color.WHITE, isDarkColor(base) ? 0.68f : 0.48f);
            int width = Math.max(1, view.getWidth());
            ValueAnimator animator = ValueAnimator.ofFloat(-0.8f, 1.8f);
            animator.setDuration(900L);
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> {
                float progress = (Float) a.getAnimatedValue();
                float startX = progress * width;
                float endX = startX + width * 0.72f;
                view.getPaint().setShader(new LinearGradient(startX, 0f, endX, 0f,
                        new int[]{base, base, glow, highlight, glow, base, base},
                        new float[]{0f, 0.16f, 0.34f, 0.5f, 0.66f, 0.84f, 1f},
                        Shader.TileMode.CLAMP));
                view.invalidate();
            });
            animator.start();
            view.setTag(animator);
        };

        if (view.getWidth() > 0 && view.isAttachedToWindow()) {
            start.run();
        } else {
            view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    view.removeOnLayoutChangeListener(this);
                    start.run();
                }
            });
            view.post(start);
        }

        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                Object tag = v.getTag();
                if (tag instanceof ValueAnimator) ((ValueAnimator) tag).cancel();
                view.getPaint().setShader(null);
                v.setTag(null);
            }
        });
    }

    private static boolean isDarkColor(int value) {
        return (Color.red(value) * 299 + Color.green(value) * 587 + Color.blue(value) * 114) < 128000;
    }

    private static int blendColors(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    private static final class PatchStats {
        int additions;
        int deletions;
    }

    private static final class DiffStats {
        int additions;
        int deletions;
    }

    private static final class DiffOp {
        final char kind;
        final String oldNo;
        final String newNo;
        final String text;

        DiffOp(char kind, int oldNo, int newNo, String text) {
            this(kind, oldNo > 0 ? String.valueOf(oldNo) : "",
                    newNo > 0 ? String.valueOf(newNo) : "", text);
        }

        DiffOp(char kind, String oldNo, int newNo, String text) {
            this(kind, oldNo, newNo > 0 ? String.valueOf(newNo) : "", text);
        }

        DiffOp(char kind, int oldNo, String newNo, String text) {
            this(kind, oldNo > 0 ? String.valueOf(oldNo) : "", newNo, text);
        }

        DiffOp(char kind, String oldNo, String newNo, String text) {
            this.kind = kind;
            this.oldNo = oldNo;
            this.newNo = newNo;
            this.text = text == null ? "" : text;
        }
    }

    // ───────────────────── WEATHER ─────────────────────
    private static View renderWeather(Context ctx, JSONObject data) {
        View root = LayoutInflater.from(ctx).inflate(R.layout.widget_weather, null);

        String city = data.optString("city", data.optString("location", ""));
        String condition = data.optString("weather", data.optString("condition",
            data.optString("description", "")));
        double tempC = data.optDouble("temp_c", data.optDouble("temperature", Double.NaN));
        double feelsC = data.optDouble("feels_like", tempC);
        int humidity = data.optInt("humidity", -1);
        double wind = data.optDouble("wind_speed", data.optDouble("wind", Double.NaN));

        ((TextView) root.findViewById(R.id.weatherCity)).setText(city.isEmpty() ? "—" : city);
        ((TextView) root.findViewById(R.id.weatherCondition)).setText(capitalize(condition));
        ((TextView) root.findViewById(R.id.weatherTemp)).setText(
            Double.isNaN(tempC) ? "—" : formatTemp(tempC));

        ImageView iconView = root.findViewById(R.id.weatherIcon);
        iconView.setImageResource(weatherIconRes(condition, isNight()));

        TextView feelsView = root.findViewById(R.id.weatherFeelsLike);
        feelsView.setText(Double.isNaN(feelsC) ? "—" : formatTemp(feelsC));

        TextView humidityView = root.findViewById(R.id.weatherHumidity);
        humidityView.setText(humidity >= 0 ? humidity + "%" : "—");

        TextView windView = root.findViewById(R.id.weatherWind);
        windView.setText(Double.isNaN(wind) ? "—"
                : String.format(Locale.US, "%.0f ", wind) + ctx.getString(R.string.w_unit_ms));

        // Динамический фон
        LinearLayout gradient = root.findViewById(R.id.weatherGradient);
        gradient.setBackgroundResource(weatherBackground(condition, isNight()));

        return root;
    }

    private static String formatTemp(double t) {
        int rounded = (int) Math.round(t);
        return (rounded > 0 ? "+" : "") + rounded + "°";
    }

    private static int weatherIconRes(String cond, boolean night) {
        if (cond == null) cond = "";
        String c = cond.toLowerCase(Locale.ROOT);
        if (c.contains("гроз") || c.contains("thunder")) return R.drawable.ic_weather_thunder;
        if (c.contains("снег") || c.contains("snow")) return R.drawable.ic_weather_snow;
        if (c.contains("дожд") || c.contains("ливен") || c.contains("rain") || c.contains("drizzl"))
            return R.drawable.ic_weather_rain;
        if (c.contains("туман") || c.contains("fog") || c.contains("mist"))
            return R.drawable.ic_weather_fog;
        if (c.contains("пасмур") || c.contains("облач") || c.contains("overcast")
            || c.contains("cloud")) return R.drawable.ic_weather_cloud;
        if (c.contains("ясн") || c.contains("clear") || c.contains("солн")
            || c.contains("sun")) return night ? R.drawable.ic_weather_moon : R.drawable.ic_weather_sun;
        return night ? R.drawable.ic_weather_moon : R.drawable.ic_weather_sun;
    }

    private static int weatherBackground(String cond, boolean night) {
        if (cond == null) cond = "";
        String c = cond.toLowerCase(Locale.ROOT);
        if (c.contains("гроз") || c.contains("thunder")) return R.drawable.weather_bg_thunder;
        if (c.contains("снег") || c.contains("snow")) return R.drawable.weather_bg_snow;
        if (c.contains("дожд") || c.contains("ливен") || c.contains("rain") || c.contains("drizzl"))
            return R.drawable.weather_bg_rain;
        if (c.contains("пасмур") || c.contains("облач") || c.contains("overcast")
            || c.contains("cloud")) return R.drawable.weather_bg_cloudy;
        if (night) return R.drawable.weather_bg_night;
        return R.drawable.weather_bg_clear;
    }

    private static boolean isNight() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour < 6 || hour >= 21;
    }

    // ───────────────────── DATETIME ─────────────────────
    private static View renderDateTime(Context ctx, JSONObject data) {
        View root = LayoutInflater.from(ctx).inflate(R.layout.widget_datetime, null);

        String timezone = data.optString("timezone", "");
        String formatted = data.optString("formatted", "");
        String iso = data.optString("iso", data.optString("datetime", ""));
        String utcOffset = data.optString("utc_offset", "");
        String dayOfWeek = data.optString("day_of_week", "");

        Date date = new Date();
        if (!iso.isEmpty()) {
            try {
                String cleanIso = iso.replace('Z', ' ').trim();
                if (cleanIso.length() > 19) cleanIso = cleanIso.substring(0, 19);
                // Пробуем с 'T'
                SimpleDateFormat isoFmt = new SimpleDateFormat(
                    cleanIso.contains("T") ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd HH:mm:ss",
                    Locale.US);
                Date parsed = isoFmt.parse(cleanIso);
                if (parsed != null) date = parsed;
            } catch (Exception ignored) {}
        }

        TimeZone tz = timezone.isEmpty() ? TimeZone.getDefault() : TimeZone.getTimeZone(timezone);

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", new Locale("ru"));
        timeFmt.setTimeZone(tz);
        SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru"));
        dateFmt.setTimeZone(tz);

        ((TextView) root.findViewById(R.id.timeText)).setText(timeFmt.format(date));

        String dateLine = dayOfWeek.isEmpty()
            ? capitalize(dateFmt.format(date))
            : capitalize(dateFmt.format(date));
        ((TextView) root.findViewById(R.id.dateText)).setText(dateLine);

        String tzLabel = timezone.isEmpty() ? TimeZone.getDefault().getID() : timezone;
        String offsetLabel;
        if (!utcOffset.isEmpty()) {
            offsetLabel = utcOffset;
        } else {
            int offset = tz.getOffset(date.getTime()) / 3600000;
            offsetLabel = "UTC" + (offset >= 0 ? "+" : "") + offset;
        }
        ((TextView) root.findViewById(R.id.timezoneText)).setText(tzLabel + " · " + offsetLabel);

        // Устанавливаем часовой пояс для циферблата
        ClockView clock = root.findViewById(R.id.clockView);
        java.util.Calendar cal = java.util.Calendar.getInstance(tz);
        cal.setTime(date);
        clock.setTime(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND));

        return root;
    }

    // ───────────────────── SAVE MEMORY ─────────────────────
    private static View renderSaveMemory(Context ctx, ToolCall toolCall, JSONObject data) {
        View root = LayoutInflater.from(ctx).inflate(R.layout.widget_save_memory, null);

        // Пробуем достать name/content из result, иначе из исходных params тула
        String name = data.optString("name", "");
        String content = data.optString("content", "");
        if (name.isEmpty()) name = toolCall.getParams().getOrDefault("name", "");
        if (content.isEmpty()) content = toolCall.getParams().getOrDefault("content", "");

        TextView nameView = root.findViewById(R.id.memoryName);
        TextView contentView = root.findViewById(R.id.memoryContent);

        nameView.setText(name.isEmpty() ? "—" : name);
        if (content.isEmpty()) {
            contentView.setVisibility(View.GONE);
        } else {
            contentView.setText(content);
        }

        return root;
    }

    // ───────────────────── WEB SEARCH ─────────────────────

    /**
     * Compact IDE-style web search block.
     *
     * <p>Replaces the old full-width card that showed a favicon, a two-line
     * title and a two-line snippet per hit: in a chat thread that occupied most
     * of the screen for very little information. The new block is a single
     * collapsed line ("Найдено N источников") that expands into one dense row
     * per source, matching the Explore/IDE tool blocks around it.</p>
     */
    private static View renderSearch(Context ctx, ToolCall toolCall) {
        JSONObject data = toolCall.getResult();
        boolean loading = "loading".equals(toolCall.getStatus());
        String query = toolCall.getParams().getOrDefault("query", "").trim();

        JSONArray results = null;
        if (data != null) {
            results = data.optJSONArray("results");
            if (results == null) results = data.optJSONArray("items");
        }
        final int count = results == null ? 0 : results.length();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight((int) dp(ctx, 34));
        header.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        header.setForeground(selectableForeground(ctx));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_magnify);
        icon.setColorFilter(color(ctx, R.color.icon_muted));
        header.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));

        TextView label = new TextView(ctx);
        label.setText(loading
                ? (query.isEmpty() ? ctx.getString(R.string.web_search_running_generic)
                        : ctx.getString(R.string.web_search_running, query))
                : count > 0 ? ctx.getResources().getQuantityString(
                        R.plurals.web_search_sources, count, count)
                : ctx.getString(R.string.web_search_no_results));
        label.setTextColor(color(ctx, R.color.text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (loading) startStatusShimmer(label);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = (int) dp(ctx, 8);
        header.addView(label, labelLp);

        ImageView chevron = new ImageView(ctx);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(color(ctx, R.color.icon_muted));
        LinearLayout.LayoutParams chevronLp =
                new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16));
        chevronLp.leftMargin = (int) dp(ctx, 8);
        chevron.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        header.addView(chevron, chevronLp);
        root.addView(header);

        if (count == 0) return root;

        LinearLayout details = new LinearLayout(ctx);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding((int) dp(ctx, 10), (int) dp(ctx, 6),
                (int) dp(ctx, 10), (int) dp(ctx, 6));
        GradientDrawable detailsBg = new GradientDrawable();
        detailsBg.setColor(color(ctx, R.color.surface_tonal));
        detailsBg.setCornerRadius(dp(ctx, 12));
        detailsBg.setStroke((int) dp(ctx, 1), color(ctx, R.color.stroke));
        details.setBackground(detailsBg);

        for (int i = 0; i < count; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            final String url = item.optString("url", item.optString("link", ""));
            String title = item.optString("title", "").trim();
            int citation = item.optInt("citation", i + 1);
            String domain = extractDomain(url);

            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight((int) dp(ctx, 30));
            row.setForeground(selectableForeground(ctx));

            TextView index = new TextView(ctx);
            index.setText(String.valueOf(citation));
            index.setTextColor(color(ctx, R.color.text_disabled));
            index.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            index.setTypeface(Typeface.MONOSPACE);
            index.setGravity(Gravity.CENTER);
            index.setMinWidth((int) dp(ctx, 16));
            row.addView(index);

            TextView titleView = new TextView(ctx);
            titleView.setText(title.isEmpty() ? (url.isEmpty() ? "—" : url) : title);
            titleView.setTextColor(color(ctx, R.color.text_primary));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            titleView.setSingleLine(true);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            titleLp.leftMargin = (int) dp(ctx, 8);
            row.addView(titleView, titleLp);

            if (!domain.isEmpty()) {
                TextView domainView = new TextView(ctx);
                domainView.setText(domain);
                domainView.setTextColor(color(ctx, R.color.text_disabled));
                domainView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                domainView.setTypeface(Typeface.MONOSPACE);
                domainView.setSingleLine(true);
                domainView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                domainView.setMaxWidth((int) dp(ctx, 110));
                LinearLayout.LayoutParams domainLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                domainLp.leftMargin = (int) dp(ctx, 8);
                row.addView(domainView, domainLp);
            }

            if (!url.isEmpty()) row.setOnClickListener(v -> openUrl(v.getContext(), url));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            details.addView(row, rowLp);
        }

        boolean expanded = toolCall.isExpanded();
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 0f : -90f);
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(v -> {
            boolean open = !toolCall.isExpanded();
            toolCall.setExpanded(open);
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(open ? 0f : -90f).setDuration(150).start();
        });
        LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailsLp.topMargin = (int) dp(ctx, 4);
        root.addView(details, detailsLp);
        return root;
    }

    // ───────────────────── URL FETCH ─────────────────────

    /** Single compact line: what was loaded and from where. Tap opens the page. */
    private static View renderUrlFetch(Context ctx, ToolCall toolCall) {
        JSONObject data = toolCall.getResult();
        boolean loading = "loading".equals(toolCall.getStatus());
        final String url = toolCall.getParams().getOrDefault("url",
                data == null ? "" : data.optString("url", data.optString("link", "")));
        String title = data == null ? "" : data.optString("title", "").trim();

        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) dp(ctx, 34));
        row.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        row.setForeground(selectableForeground(ctx));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_open_in_new);
        icon.setColorFilter(color(ctx, R.color.icon_muted));
        row.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));

        TextView label = new TextView(ctx);
        label.setText(loading ? ctx.getString(R.string.url_fetch_loading)
                : ctx.getString(R.string.url_fetch_done));
        label.setTextColor(color(ctx, R.color.text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        if (loading) startStatusShimmer(label);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = (int) dp(ctx, 8);
        row.addView(label, labelLp);

        TextView target = new TextView(ctx);
        String domain = extractDomain(url);
        target.setText(title.isEmpty() ? (domain.isEmpty() ? url : domain) : title);
        target.setTextColor(color(ctx, R.color.text_primary));
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        target.setSingleLine(true);
        target.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        targetLp.leftMargin = (int) dp(ctx, 8);
        row.addView(target, targetLp);

        if (!url.isEmpty()) {
            ImageView arrow = new ImageView(ctx);
            arrow.setImageResource(R.drawable.ic_chevron_right);
            arrow.setColorFilter(color(ctx, R.color.icon_muted));
            row.addView(arrow, new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> openUrl(v.getContext(), url));
        }
        return row;
    }

    private static void openUrl(Context ctx, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    // ───────────────────── CHAT SUMMARIZATION ─────────────────────

    /**
     * Compact row for {@code summarize_chat_history}.
     *
     * <p>A separate, tool-free AI client compacts the older part of the thread
     * into a hidden summary. The visible transcript never changes, so the only
     * thing to show is a one-liner with the produced summary behind a chevron.</p>
     */
    private static View renderSummarize(Context ctx, ToolCall call) {
        JSONObject result = call.getResult();
        boolean loading = "loading".equals(call.getStatus());
        boolean skipped = result != null && result.optBoolean("skipped", false);
        String summary = result == null ? "" : result.optString("summary", "").trim();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight((int) dp(ctx, 34));
        header.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        header.setForeground(selectableForeground(ctx));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_memory_note_check);
        icon.setColorFilter(color(ctx, R.color.icon_muted));
        header.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));

        TextView label = new TextView(ctx);
        label.setText(loading ? ctx.getString(R.string.summarize_running)
                : skipped ? ctx.getString(R.string.summarize_skipped)
                : ctx.getString(R.string.summarize_done));
        label.setTextColor(color(ctx, R.color.text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (loading) startStatusShimmer(label);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = (int) dp(ctx, 8);
        header.addView(label, labelLp);

        if (summary.isEmpty()) {
            root.addView(header);
            return root;
        }

        ImageView chevron = new ImageView(ctx);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(color(ctx, R.color.icon_muted));
        LinearLayout.LayoutParams chevronLp =
                new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16));
        chevronLp.leftMargin = (int) dp(ctx, 8);
        header.addView(chevron, chevronLp);
        root.addView(header);

        LinearLayout details = new LinearLayout(ctx);
        details.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable detailsBg = new GradientDrawable();
        detailsBg.setColor(color(ctx, R.color.surface_tonal));
        detailsBg.setCornerRadius(dp(ctx, 12));
        detailsBg.setStroke((int) dp(ctx, 1), color(ctx, R.color.stroke));
        details.setBackground(detailsBg);
        addCodeSection(ctx, details, ctx.getString(R.string.summarize_summary), summary);

        boolean expanded = call.isExpanded();
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 0f : -90f);
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(v -> {
            boolean open = !call.isExpanded();
            call.setExpanded(open);
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(open ? 0f : -90f).setDuration(150).start();
        });
        LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailsLp.topMargin = (int) dp(ctx, 4);
        root.addView(details, detailsLp);
        return root;
    }

    /**
     * Compact row for {@code summarize_past_chat}.
     *
     * <p>Reads a different, earlier conversation through a separate AI client.
     * The row names the source chat and hides the produced briefing behind a
     * chevron, so a long digest never floods the workflow drawer.</p>
     */
    private static View renderPastChatSummary(Context ctx, ToolCall call) {
        JSONObject result = call.getResult();
        boolean loading = "loading".equals(call.getStatus());
        boolean needsSelection = result != null && result.optBoolean("needs_selection", false);
        String chatTitle = result == null ? "" : result.optString("chat_title", "").trim();
        String summary = result == null ? "" : result.optString("summary", "").trim();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight((int) dp(ctx, 34));
        header.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        header.setForeground(selectableForeground(ctx));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_memory_note_check);
        icon.setColorFilter(color(ctx, R.color.icon_muted));
        header.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));

        TextView label = new TextView(ctx);
        label.setText(loading ? ctx.getString(R.string.past_chat_running)
                : needsSelection ? ctx.getString(R.string.past_chat_needs_selection)
                : chatTitle.isEmpty() ? ctx.getString(R.string.past_chat_empty)
                : ctx.getString(R.string.past_chat_done, chatTitle));
        label.setTextColor(color(ctx, R.color.text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (loading) startStatusShimmer(label);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = (int) dp(ctx, 8);
        header.addView(label, labelLp);

        if (summary.isEmpty()) {
            root.addView(header);
            return root;
        }

        ImageView chevron = new ImageView(ctx);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(color(ctx, R.color.icon_muted));
        LinearLayout.LayoutParams chevronLp =
                new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16));
        chevronLp.leftMargin = (int) dp(ctx, 8);
        header.addView(chevron, chevronLp);
        root.addView(header);

        LinearLayout details = new LinearLayout(ctx);
        details.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable detailsBg = new GradientDrawable();
        detailsBg.setColor(color(ctx, R.color.surface_tonal));
        detailsBg.setCornerRadius(dp(ctx, 12));
        detailsBg.setStroke((int) dp(ctx, 1), color(ctx, R.color.stroke));
        details.setBackground(detailsBg);
        addCodeSection(ctx, details, ctx.getString(R.string.past_chat_summary), summary);

        boolean expanded = call.isExpanded();
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 0f : -90f);
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(v -> {
            boolean open = !call.isExpanded();
            call.setExpanded(open);
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(open ? 0f : -90f).setDuration(150).start();
        });
        LinearLayout.LayoutParams pastDetailsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pastDetailsLp.topMargin = (int) dp(ctx, 4);
        root.addView(details, pastDetailsLp);
        return root;
    }

    // ───────────────────── TOOL FAILURES ─────────────────────

    /** Single source of truth for "this tool call failed". */
    public static boolean hasFailed(ToolCall call) {
        if (call == null) return false;
        if ("error".equals(call.getStatus())) return true;
        JSONObject result = call.getResult();
        if (result == null) return false;
        if (result.optBoolean("cancelled", false)) return false;
        return result.has("error") && !result.optBoolean("success", true);
    }

    /**
     * Compact, human-readable failure row.
     *
     * <p>Failures used to fall back to the legacy red tool card, which printed
     * the raw backend string ("A relative file path is required") on a full-width
     * tinted block with a ✕ badge. Eight failed reads produced eight of those.
     * Now every failure is one line in the project's own language plus an
     * expandable reason.</p>
     */
    public static View renderToolFailure(Context ctx, ToolCall call) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight((int) dp(ctx, 34));
        header.setPadding((int) dp(ctx, 2), 0, (int) dp(ctx, 2), 0);
        header.setForeground(selectableForeground(ctx));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_error_warning);
        icon.setColorFilter(color(ctx, R.color.error));
        header.addView(icon, new LinearLayout.LayoutParams((int) dp(ctx, 17), (int) dp(ctx, 17)));

        TextView label = new TextView(ctx);
        label.setText(failureLabel(ctx, call));
        label.setTextColor(color(ctx, R.color.error));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = (int) dp(ctx, 8);
        header.addView(label, labelLp);

        ImageView chevron = new ImageView(ctx);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(color(ctx, R.color.icon_muted));
        LinearLayout.LayoutParams chevronLp =
                new LinearLayout.LayoutParams((int) dp(ctx, 16), (int) dp(ctx, 16));
        chevronLp.leftMargin = (int) dp(ctx, 8);
        header.addView(chevron, chevronLp);
        root.addView(header);

        LinearLayout details = new LinearLayout(ctx);
        details.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable detailsBg = new GradientDrawable();
        detailsBg.setColor(color(ctx, R.color.surface_tonal));
        detailsBg.setCornerRadius(dp(ctx, 12));
        detailsBg.setStroke((int) dp(ctx, 1), color(ctx, R.color.stroke));
        details.setBackground(detailsBg);
        addCodeSection(ctx, details, ctx.getString(R.string.tool_fail_reason),
                failureReason(ctx, call));

        boolean expanded = call.isExpanded();
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(expanded ? 0f : -90f);
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(v -> {
            boolean open = !call.isExpanded();
            call.setExpanded(open);
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(open ? 0f : -90f).setDuration(150).start();
        });
        LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailsLp.topMargin = (int) dp(ctx, 4);
        root.addView(details, detailsLp);
        return root;
    }

    /** Per-tool wording: "Не удалось прочитать MainActivity.java". */
    public static String failureLabel(Context ctx, ToolCall call) {
        String name = call.getName() == null ? "" : call.getName();
        String path = firstNonEmpty(call.getParams().get("path"),
                call.getParams().get("directory"));
        JSONObject result = call.getResult();
        if (path.trim().isEmpty() && result != null) {
            path = firstNonEmpty(result.optString("path", ""),
                    firstNonEmpty(result.optString("file", ""), result.optString("directory", "")));
        }
        String file = fileName(path);
        switch (name) {
            case "read_file":
                return file.isEmpty() ? ctx.getString(R.string.tool_fail_read_generic)
                        : ctx.getString(R.string.tool_fail_read, file);
            case "list_dir":
                return file.isEmpty() ? ctx.getString(R.string.tool_fail_list_dir_generic)
                        : ctx.getString(R.string.tool_fail_list_dir, file);
            case "search_grep":
                return ctx.getString(R.string.tool_fail_search);
            case "find_files":
                return ctx.getString(R.string.tool_fail_find_files);
            case "execute_command":
                return ctx.getString(R.string.tool_fail_command);
            case "write_file":
                return file.isEmpty() ? ctx.getString(R.string.tool_fail_generic, name)
                        : ctx.getString(R.string.tool_fail_write, file);
            case "edit_file":
                return file.isEmpty() ? ctx.getString(R.string.tool_fail_generic, name)
                        : ctx.getString(R.string.tool_fail_edit, file);
            case "delete_path":
                return file.isEmpty() ? ctx.getString(R.string.tool_fail_generic, name)
                        : ctx.getString(R.string.tool_fail_delete, file);
            case "move_path":
                return ctx.getString(R.string.tool_fail_move);
            case "web_search":
                return ctx.getString(R.string.tool_fail_web_search);
            case "url_fetch":
                return ctx.getString(R.string.tool_fail_url_fetch);
            case "save_memory":
                return ctx.getString(R.string.tool_fail_memory);
            case "summarize_chat_history":
                return ctx.getString(R.string.tool_fail_summarize);
            case "summarize_past_chat":
                return ctx.getString(R.string.tool_fail_past_chat);
            case "get_weather":
                return ctx.getString(R.string.tool_fail_weather);
            case "get_current_time":
            case "get_datetime":
                return ctx.getString(R.string.tool_fail_time);
            case "invoke_subagent":
                return ctx.getString(R.string.tool_fail_subagent);
            case "project_rules":
                return ctx.getString(R.string.tool_fail_rules);
            default:
                return ctx.getString(R.string.tool_fail_generic, name);
        }
    }

    private static String failureReason(Context ctx, ToolCall call) {
        JSONObject result = call.getResult();
        if (result != null) {
            String error = result.optString("error", "").trim();
            if (!error.isEmpty()) return error;
            String message = result.optString("message", "").trim();
            if (!message.isEmpty()) return message;
        }
        return ctx.getString(R.string.tool_fail_unknown_reason);
    }

    // ───────────────────── helpers ─────────────────────
    private static String extractDomain(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return url;
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static float dp(Context ctx, float value) {
        return value * ctx.getResources().getDisplayMetrics().density;
    }

    /**
     * Themed press feedback for tool rows.
     *
     * android.R.drawable.list_selector_background is the legacy Holo asset: on
     * press it paints the old orange/yellow gradient, which looked like a bug on
     * the dark UI. Resolving ?attr/selectableItemBackground keeps the app's own
     * ripple instead.
     */
    private static Drawable selectableForeground(Context ctx) {
        try {
            TypedValue value = new TypedValue();
            if (ctx.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, value, true)) {
                if (value.resourceId != 0) return ctx.getDrawable(value.resourceId);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
