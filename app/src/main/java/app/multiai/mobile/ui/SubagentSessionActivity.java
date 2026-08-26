package app.multiai.mobile.ui;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.multiai.mobile.R;
import app.multiai.mobile.models.ToolCall;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.SubagentSessionStore;
import app.multiai.mobile.views.ToolWidgetRenderer;

public class SubagentSessionActivity extends AppCompatActivity {
    public static final String EXTRA_SESSION_ID = "subagent_session_id";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String sessionId;
    private SettingsUI.Scaffold scaffold;
    private long renderedUpdate = -1L;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderSession();
            handler.postDelayed(this, 600L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        String returnToChat = getString(R.string.subagent_return_to_chat);
        scaffold = SettingsUI.createScaffold(this, returnToChat, false);
        scaffold.backButton.setContentDescription(returnToChat);
        renderSession();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void renderSession() {
        JSONObject session = SubagentSessionStore.load(this, sessionId);
        if (session == null || scaffold == null) return;
        long updatedAt = session.optLong("updated_at", 0L);
        if (updatedAt == renderedUpdate) return;
        boolean followBottom = scaffold.scrollRoot.getScrollY() + scaffold.scrollRoot.getHeight()
                >= scaffold.content.getHeight() - SettingsUI.dp(this, 80);
        renderedUpdate = updatedAt;
        scaffold.content.removeAllViews();
        scaffold.content.addView(createHeader(session));
        renderEvents(session.optJSONArray("events"));
        String error = session.optString("error", "").trim();
        if (!error.isEmpty()) scaffold.content.addView(createError(error));
        if (followBottom) scaffold.scrollRoot.post(() -> scaffold.scrollRoot.fullScroll(View.FOCUS_DOWN));
    }

    private void renderEvents(JSONArray events) {
        if (events == null) return;
        for (int i = 0; i < events.length();) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) {
                i++;
                continue;
            }
            String type = event.optString("type", "");
            if ("task".equals(type)) {
                scaffold.content.addView(createTask(event.optString("detail", "")));
                i++;
            } else if ("thinking".equals(type)) {
                scaffold.content.addView(createThinking(event.optString("detail", "")));
                i++;
            } else if ("assistant".equals(type)) {
                scaffold.content.addView(createAssistant(event.optString("detail", "")));
                i++;
            } else if ("tool".equals(type)) {
                JSONObject normalized = normalizedTool(events, i);
                ToolCall first = normalized == null ? null : toolCall(normalized);
                if (first != null && ToolWidgetRenderer.isExploreTool(first.getName())) {
                    int cursor = normalized.optInt("next_index", i + 1);
                    List<ToolCall> explore = new ArrayList<>();
                    explore.add(first);
                    while (cursor < events.length()) {
                        JSONObject next = normalizedTool(events, cursor);
                        ToolCall call = next == null ? null : toolCall(next);
                        if (call == null || !ToolWidgetRenderer.isExploreTool(call.getName())) break;
                        explore.add(call);
                        cursor = next.optInt("next_index", cursor + 1);
                    }
                    View overview = ToolWidgetRenderer.renderExplore(this, explore);
                    if (overview != null) scaffold.content.addView(withMargin(overview, 4));
                    i = cursor;
                } else {
                    View tool = first == null ? null : ToolWidgetRenderer.render(this, first);
                    scaffold.content.addView(withMargin(tool != null ? tool : createRawEvent(event), 4));
                    i = normalized != null ? normalized.optInt("next_index", i + 1) : i + 1;
                }
            } else if (!"tool_result".equals(type)) {
                scaffold.content.addView(withMargin(createRawEvent(event), 4));
                i++;
            } else {
                i++;
            }
        }
    }

    private View createHeader(JSONObject session) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_subagent);
        icon.setColorFilter(SettingsUI.colorAccent(this));
        row.addView(icon, new LinearLayout.LayoutParams(SettingsUI.dp(this, 24), SettingsUI.dp(this, 24)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsLp.leftMargin = SettingsUI.dp(this, 12);
        labels.addView(text(session.optString("agent_name", "Сабагент"), 16,
                SettingsUI.colorText(this), true));
        String model = session.optString("model", "");
        String effort = session.optString("effort", "");
        labels.addView(text(model + (effort.isEmpty() ? "" : " · " + effort), 12,
                SettingsUI.colorHint(this), false));
        row.addView(labels, labelsLp);
        String status = session.optString("status", "running");
        row.addView(text("running".equals(status) ? "Работает"
                : "completed".equals(status) ? "Готово" : "Ошибка", 12,
                "failed".equals(status) ? ContextCompat.getColor(this, R.color.error)
                        : SettingsUI.colorSubtext(this), true));
        return withMargin(row, 4);
    }

    private View createTask(String value) {
        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.END);
        TextView bubble = text(value, 14, ContextCompat.getColor(this, R.color.user_bubble_text), false);
        bubble.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this, R.color.user_bubble));
        background.setCornerRadius(SettingsUI.dp(this, 17));
        bubble.setBackground(background);
        root.addView(bubble, new LinearLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.78f),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return withMargin(root, 8);
    }

    private View createThinking(String value) {
        View root = LayoutInflater.from(this).inflate(R.layout.item_workflow_thinking, null, false);
        TextView body = root.findViewById(R.id.textThinking);
        TextView preview = root.findViewById(R.id.thinkingPreview);
        View header = root.findViewById(R.id.thinkingHeader);
        ImageView toggle = root.findViewById(R.id.thinkingToggle);
        View scroll = root.findViewById(R.id.thinkingScroll);
        body.setText(value);
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() > 70) compact = compact.substring(0, 70) + "…";
        preview.setText(compact.isEmpty() ? getString(R.string.lay_thinking_process) : compact);
        scroll.setVisibility(View.GONE);
        toggle.setRotation(-90f);
        header.setOnClickListener(v -> {
            boolean open = scroll.getVisibility() != View.VISIBLE;
            scroll.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.animate().rotation(open ? 0f : -90f).setDuration(150L).start();
        });
        return withMargin(root, 2);
    }

    private View createAssistant(String value) {
        LinearLayout markdown = new LinearLayout(this);
        markdown.setOrientation(LinearLayout.VERTICAL);
        markdown.setPadding(0, SettingsUI.dp(this, 6), 0, SettingsUI.dp(this, 6));
        app.multiai.mobile.utils.MarkdownRenderer.renderToLayout(this, markdown, value);
        return withMargin(markdown, 2);
    }

    private JSONObject normalizedTool(JSONArray events, int index) {
        JSONObject source = events.optJSONObject(index);
        if (source == null || !"tool".equals(source.optString("type", ""))) return null;
        try {
            JSONObject normalized = new JSONObject(source.toString());
            String name = normalized.optString("tool_name", "");
            if (name.isEmpty()) name = legacyToolName(normalized.optString("title", ""));
            if (name.isEmpty()) return null;
            normalized.put("tool_name", name);
            if (!normalized.has("arguments")) {
                normalized.put("arguments", normalized.optString("detail", "{}"));
            }
            int next = index + 1;
            JSONObject result = next < events.length() ? events.optJSONObject(next) : null;
            if (result != null && "tool_result".equals(result.optString("type", ""))) {
                normalized.put("result", result.optString("detail", ""));
                normalized.put("status", result.optString("status", "success"));
                next++;
            }
            normalized.put("next_index", next);
            return normalized;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String legacyToolName(String title) {
        if ("Просмотр папки".equals(title)) return "list_dir";
        if ("Чтение файла".equals(title)) return "read_file";
        if ("Поиск по проекту".equals(title)) return "search_grep";
        if ("Поиск файлов".equals(title)) return "find_files";
        if ("Команда".equals(title)) return "execute_command";
        if ("Изменение файла".equals(title)) return "edit_file";
        if ("Запись файла".equals(title)) return "write_file";
        if ("Удаление".equals(title)) return "delete_path";
        if ("Перемещение".equals(title)) return "move_path";
        if ("Поиск в интернете".equals(title)) return "web_search";
        if ("Чтение страницы".equals(title)) return "url_fetch";
        return "";
    }

    private ToolCall toolCall(JSONObject event) {
        try {
            String id = event.optString("call_id", "sub-tool-" + event.optLong("timestamp"));
            String name = event.optString("tool_name", "");
            if (name.isEmpty()) return null;
            JSONObject arguments = new JSONObject(event.optString("arguments", "{}"));
            Map<String, String> params = new HashMap<>();
            JSONArray names = arguments.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i);
                    Object value = arguments.opt(key);
                    params.put(key, value == null ? "" : String.valueOf(value));
                }
            }
            ToolCall call = new ToolCall(id, name, params);
            String status = event.optString("status", "loading");
            call.setStatus(status);
            String result = event.optString("result", "");
            if (!result.isEmpty()) {
                try { call.setResult(new JSONObject(result)); }
                catch (Exception ignored) { call.setResult(new JSONObject().put("output", result)); }
            }
            return call;
        } catch (Exception ignored) {
            return null;
        }
    }

    private View createRawEvent(JSONObject event) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(SettingsUI.dp(this, 2), SettingsUI.dp(this, 7),
                SettingsUI.dp(this, 2), SettingsUI.dp(this, 7));
        row.addView(text(event.optString("title", "Действие"), 13,
                SettingsUI.colorSubtext(this), true));
        String detail = event.optString("detail", "").trim();
        if (!detail.isEmpty()) {
            TextView body = text(detail, 12, SettingsUI.colorHint(this), false);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            row.addView(body);
        }
        return row;
    }

    private View createError(String value) {
        TextView error = text(value, 13, ContextCompat.getColor(this, R.color.error), false);
        error.setPadding(SettingsUI.dp(this, 12), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 12), SettingsUI.dp(this, 10));
        return withMargin(error, 8);
    }

    private View withMargin(View view, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = SettingsUI.dp(this, 16);
        params.rightMargin = SettingsUI.dp(this, 16);
        params.topMargin = SettingsUI.dp(this, topDp);
        view.setLayoutParams(params);
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
}
