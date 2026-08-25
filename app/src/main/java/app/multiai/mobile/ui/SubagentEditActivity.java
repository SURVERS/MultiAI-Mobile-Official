package app.multiai.mobile.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.multiai.mobile.R;
import app.multiai.mobile.models.ModelInfo;
import app.multiai.mobile.models.SubagentPreset;
import app.multiai.mobile.network.MultiAiClient;
import app.multiai.mobile.network.oauth.OAuthSession;
import app.multiai.mobile.ui.kit.ModernMenu;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.kit.ModelCatalogUi;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.SubagentStore;

/** Mobile editor for a user-created subagent preset. */
public class SubagentEditActivity extends AppCompatActivity {
    public static final String EXTRA_PRESET_ID = "subagent_preset_id";

    private static final LinkedHashMap<String, String> TOOL_LABELS = new LinkedHashMap<>();
    static {
        TOOL_LABELS.put("read_file", "Read");
        TOOL_LABELS.put("search_grep", "Grep");
        TOOL_LABELS.put("find_files", "Glob");
        TOOL_LABELS.put("list_dir", "List files");
        TOOL_LABELS.put("execute_command", "Bash");
        TOOL_LABELS.put("edit_file", "Edit");
        TOOL_LABELS.put("write_file", "Write");
        TOOL_LABELS.put("delete_path", "Delete");
        TOOL_LABELS.put("move_path", "Move");
        TOOL_LABELS.put("web_search", "WebSearch");
        TOOL_LABELS.put("url_fetch", "WebFetch");
    }

    private SubagentPreset preset;
    private boolean editing;
    private boolean builtIn;
    private EditText nameInput;
    private EditText descriptionInput;
    private EditText promptInput;
    private TextView colorValue;
    private TextView modelValue;
    private TextView scopeValue;
    private TextView toolsValue;
    private String selectedColor;
    private String modelId;
    private String modelName;
    private String selectedEffort;
    private String selectedScope;
    private String workspaceUri;
    private String toolsMode;
    private final List<String> selectedTools = new ArrayList<>();
    private boolean injectAgentsMd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        String id = getIntent().getStringExtra(EXTRA_PRESET_ID);
        preset = id == null ? null : SubagentStore.find(this, id);
        editing = preset != null;
        builtIn = preset != null && SubagentStore.isBuiltIn(preset.id);
        if (preset == null) preset = new SubagentPreset();
        loadState();

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(this,
                getString(editing ? R.string.subagent_edit_title : R.string.subagent_new_title), true);
        scaffold.saveButton.setContentDescription(getString(R.string.subagent_save));
        scaffold.saveButton.setOnClickListener(v -> save());
        buildContent(scaffold.content);
    }

    private void loadState() {
        selectedColor = preset.colorKey;
        modelId = preset.modelId;
        modelName = preset.modelName;
        selectedEffort = preset.modelEffort;
        selectedScope = preset.scope;
        workspaceUri = preset.workspaceUri;
        toolsMode = preset.toolsMode;
        selectedTools.addAll(preset.allowedTools);
        injectAgentsMd = preset.injectAgentsMd;
    }

    private void buildContent(LinearLayout content) {
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.subagent_editor_subtitle);
        subtitle.setTextColor(SettingsUI.colorSubtext(this));
        subtitle.setTextSize(14);
        subtitle.setPadding(SettingsUI.dp(this, 20), SettingsUI.dp(this, 8), SettingsUI.dp(this, 20), SettingsUI.dp(this, 12));
        content.addView(subtitle);

        if (builtIn) {
            subtitle.setText(R.string.subagent_builtin_runtime_subtitle);
            LinearLayout runtime = SettingsUI.card(this);
            runtime.addView(SettingsUI.sectionLabel(this, preset.name));
            modelValue = trailingValue(displayModel());
            runtime.addView(SettingsUI.rowWithTrailing(this, R.drawable.ic_s_sparkle,
                    getString(R.string.subagent_model_and_effort), preset.description,
                    modelValue, this::loadModelCatalog));
            content.addView(runtime);
            refreshLabels();
            return;
        }

        LinearLayout identity = SettingsUI.card(this);
        identity.addView(SettingsUI.sectionLabel(this, getString(R.string.subagent_identity_section)));
        nameInput = addInput(identity, getString(R.string.subagent_name),
                getString(R.string.subagent_name_hint), preset.name, false, 1);
        identity.addView(SettingsUI.divider(this));
        descriptionInput = addInput(identity, getString(R.string.subagent_description),
                getString(R.string.subagent_description_hint), preset.description, false, 2);
        identity.addView(SettingsUI.divider(this));
        colorValue = trailingValue(colorLabel());
        identity.addView(SettingsUI.rowWithTrailing(this, R.drawable.ic_s_palette,
                getString(R.string.subagent_color), null, colorValue, this::showColorPicker));
        content.addView(identity);

        content.addView(SettingsUI.sectionGap(this));
        LinearLayout runtime = SettingsUI.card(this);
        runtime.addView(SettingsUI.sectionLabel(this, getString(R.string.subagent_runtime_section)));
        modelValue = trailingValue(displayModel());
        runtime.addView(SettingsUI.rowWithTrailing(this, R.drawable.ic_s_sparkle,
                getString(R.string.subagent_model), null, modelValue, this::showModelMenu));
        runtime.addView(SettingsUI.divider(this));
        scopeValue = trailingValue(displayScope());
        runtime.addView(SettingsUI.rowWithTrailing(this, R.drawable.ic_s_briefcase,
                getString(R.string.subagent_scope), null, scopeValue, this::showScopeMenu));
        runtime.addView(SettingsUI.divider(this));
        toolsValue = trailingValue(displayTools());
        runtime.addView(SettingsUI.rowWithTrailing(this, R.drawable.ic_s_gear,
                getString(R.string.subagent_allowed_tools), null, toolsValue, this::showToolsMenu));
        content.addView(runtime);

        content.addView(SettingsUI.sectionGap(this));
        LinearLayout promptCard = SettingsUI.card(this);
        promptCard.addView(SettingsUI.sectionLabel(this, getString(R.string.subagent_system_prompt)));
        promptInput = addInput(promptCard, null, getString(R.string.subagent_prompt_hint),
                preset.systemPrompt, true, 7);
        content.addView(promptCard);

        if (editing) {
            content.addView(SettingsUI.sectionGap(this));
            LinearLayout danger = SettingsUI.card(this);
            danger.addView(SettingsUI.rowDanger(this, getString(R.string.subagent_delete), this::confirmDelete));
            content.addView(danger);
        }
        refreshLabels();
    }

    private EditText addInput(LinearLayout parent, String label, String hint, String value,
                              boolean multiline, int maxLines) {
        if (label != null) parent.addView(SettingsUI.sectionLabel(this, label));
        EditText input = new EditText(this);
        input.setText(value == null ? "" : value);
        input.setHint(hint);
        input.setHintTextColor(SettingsUI.colorHint(this));
        input.setTextColor(SettingsUI.colorText(this));
        input.setTextSize(15);
        input.setSingleLine(!multiline);
        input.setMaxLines(maxLines);
        input.setGravity(multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 10));
        AccentColor.applyCursorTint(input, AccentColor.resolve(this));
        GradientDrawable background = new GradientDrawable();
        background.setColor(SettingsUI.colorSurfaceTonal(this));
        background.setCornerRadius(SettingsUI.dp(this, 12));
        background.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        input.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, multiline ? SettingsUI.dp(this, 132)
                : ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = SettingsUI.dp(this, 14);
        params.rightMargin = SettingsUI.dp(this, 14);
        params.bottomMargin = SettingsUI.dp(this, 14);
        input.setLayoutParams(params);
        parent.addView(input);
        return input;
    }

    private void showColorPicker() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(SettingsUI.dp(this, 4), 0, SettingsUI.dp(this, 4), SettingsUI.dp(this, 6));
        final Dialog[] dialog = new Dialog[1];
        for (String key : AccentColor.keys()) {
            TextView row = new TextView(this);
            row.setText((key.equals(selectedColor) ? "✓  " : "    ") + AccentColor.label(key, this));
            row.setTextColor(SettingsUI.colorText(this));
            row.setTextSize(15);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14), SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
            GradientDrawable rowBackground = new GradientDrawable();
            rowBackground.setColor(AccentColor.previewColor(key, AccentColor.isDark(this)));
            rowBackground.setAlpha(34);
            rowBackground.setCornerRadius(SettingsUI.dp(this, 12));
            row.setBackground(rowBackground);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = SettingsUI.dp(this, 6);
            row.setLayoutParams(rowParams);
            row.setOnClickListener(v -> {
                selectedColor = key;
                refreshLabels();
                if (dialog[0] != null) dialog[0].dismiss();
            });
            body.addView(row);
        }
        dialog[0] = ModernSheet.custom(this, getString(R.string.subagent_color), body, true);
    }

    private void showModelMenu() {
        ModernMenu.show(modelValue, getString(R.string.subagent_model), Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.subagent_model_inherit)),
                new ModernSheet.MenuItem(getString(R.string.subagent_model_choose))), (index, item) -> {
            if (index == 0) {
                modelId = "";
                modelName = "";
                selectedEffort = "";
                refreshLabels();
            } else {
                loadModelCatalog();
            }
        });
    }

    private void loadModelCatalog() {
        ModernToast.show(this, getString(R.string.subagent_model_loading));
        new Thread(() -> {
            List<ModelInfo> catalog = new MultiAiClient().fetchModelCatalog(OAuthSession.get().getAccessToken());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (catalog.isEmpty()) {
                    ModernToast.show(this, getString(R.string.subagent_model_unavailable));
                    return;
                }
                ModelCatalogUi.show(this, catalog, modelId, selectedEffort, (model, effort) -> {
                    modelId = model.id;
                    modelName = model.displayName;
                    String resolved = model.resolveEffort(effort);
                    selectedEffort = resolved == null ? "" : resolved;
                    refreshLabels();
                });
            });
        }, "subagent-model-catalog").start();
    }

    private void showScopeMenu() {
        ModernMenu.show(scopeValue, getString(R.string.subagent_scope), Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.subagents_scope_user)),
                new ModernSheet.MenuItem(getString(R.string.subagents_scope_workspace))), (index, item) -> {
            if (index == 0) {
                selectedScope = SubagentPreset.SCOPE_USER;
                workspaceUri = "";
            } else {
                String active = activeWorkspaceUri();
                if (active.isEmpty()) {
                    ModernToast.show(this, getString(R.string.subagent_scope_workspace_missing));
                    return;
                }
                selectedScope = SubagentPreset.SCOPE_WORKSPACE;
                workspaceUri = active;
            }
            refreshLabels();
        });
    }

    private void showToolsMenu() {
        ModernMenu.show(toolsValue, getString(R.string.subagent_allowed_tools), Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.subagents_default_permissions)),
                new ModernSheet.MenuItem(getString(R.string.subagents_custom_permissions))), (index, item) -> {
            toolsMode = index == 0 ? SubagentPreset.TOOLS_DEFAULT : SubagentPreset.TOOLS_CUSTOM;
            if (SubagentPreset.TOOLS_CUSTOM.equals(toolsMode)) showToolPicker();
            refreshLabels();
        });
    }

    private void showToolPicker() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(SettingsUI.dp(this, 12), 0, SettingsUI.dp(this, 12), SettingsUI.dp(this, 12));
        for (Map.Entry<String, String> entry : TOOL_LABELS.entrySet()) {
            body.addView(toolPermissionRow(entry.getKey(), entry.getValue()));
        }
        ModernSheet.custom(this, getString(R.string.subagents_custom_permissions), body, true);
    }

    /** Large, row-wide controls make tool permissions easy to scan and tap. */
    private View toolPermissionRow(String toolId, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 14), 0, SettingsUI.dp(this, 14), 0);
        row.setClickable(true);
        row.setFocusable(true);

        android.widget.ImageView indicator = new android.widget.ImageView(this);
        LinearLayout.LayoutParams indicatorLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 24), SettingsUI.dp(this, 24));
        indicator.setLayoutParams(indicatorLp);
        row.addView(indicator);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(SettingsUI.colorText(this));
        title.setTextSize(16);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = SettingsUI.dp(this, 14);
        title.setLayoutParams(titleLp);
        row.addView(title);

        boolean checked = selectedTools.contains(toolId);
        updateToolPermissionRow(row, indicator, checked);
        row.setOnClickListener(v -> {
            boolean next = !selectedTools.contains(toolId);
            if (next) selectedTools.add(toolId);
            else selectedTools.remove(toolId);
            updateToolPermissionRow(row, indicator, next);
        });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUI.dp(this, 52));
        rowLp.topMargin = SettingsUI.dp(this, 6);
        row.setLayoutParams(rowLp);
        return row;
    }

    private void updateToolPermissionRow(LinearLayout row, android.widget.ImageView indicator,
                                         boolean checked) {
        int accent = AccentColor.resolve(this);
        GradientDrawable rowBackground = new GradientDrawable();
        rowBackground.setCornerRadius(SettingsUI.dp(this, 14));
        rowBackground.setColor(checked ? withAlpha(accent, 24) : SettingsUI.colorSurfaceTonal(this));
        rowBackground.setStroke(SettingsUI.dp(this, 1), checked ? withAlpha(accent, 170)
                : SettingsUI.colorStroke(this));
        row.setBackground(rowBackground);

        GradientDrawable indicatorBackground = new GradientDrawable();
        indicatorBackground.setCornerRadius(SettingsUI.dp(this, 7));
        indicatorBackground.setColor(checked ? accent : Color.TRANSPARENT);
        indicatorBackground.setStroke(SettingsUI.dp(this, checked ? 0 : 2),
                checked ? accent : SettingsUI.colorHint(this));
        indicator.setBackground(indicatorBackground);
        indicator.setImageResource(checked ? R.drawable.ic_check : 0);
        indicator.setColorFilter(checked ? Color.WHITE : Color.TRANSPARENT);
        indicator.setPadding(SettingsUI.dp(this, 4), SettingsUI.dp(this, 4),
                SettingsUI.dp(this, 4), SettingsUI.dp(this, 4));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    private TextView trailingValue(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(SettingsUI.colorSubtext(this));
        text.setTextSize(13);
        text.setMaxWidth(SettingsUI.dp(this, 156));
        text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        return text;
    }

    private void refreshLabels() {
        if (colorValue != null) colorValue.setText(colorLabel());
        if (modelValue != null) modelValue.setText(displayModel());
        if (scopeValue != null) scopeValue.setText(displayScope());
        if (toolsValue != null) toolsValue.setText(displayTools());
    }

    private String colorLabel() {
        return AccentColor.label(selectedColor, this);
    }

    private String displayModel() {
        if (modelName == null || modelName.trim().isEmpty()) {
            return getString(R.string.subagent_model_inherit);
        }
        return selectedEffort == null || selectedEffort.trim().isEmpty()
                ? modelName : modelName + " · " + selectedEffort;
    }

    private String displayScope() {
        return SubagentPreset.SCOPE_WORKSPACE.equals(selectedScope)
                ? getString(R.string.subagents_scope_workspace) : getString(R.string.subagents_scope_user);
    }

    private String displayTools() {
        if (!SubagentPreset.TOOLS_CUSTOM.equals(toolsMode)) {
            return getString(R.string.subagents_default_permissions);
        }
        return getResources().getQuantityString(R.plurals.subagents_tool_count,
                selectedTools.size(), selectedTools.size());
    }

    private void save() {
        if (builtIn) {
            preset.modelId = modelId == null ? "" : modelId;
            preset.modelName = modelName == null ? "" : modelName;
            preset.modelEffort = selectedEffort == null ? "" : selectedEffort;
            SubagentStore.saveBuiltInRuntime(this, preset);
            ModernToast.show(this, getString(R.string.subagent_saved));
            finish();
            return;
        }
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameInput.setError(getString(R.string.subagent_name_required));
            nameInput.requestFocus();
            return;
        }
        preset.name = name;
        preset.colorKey = selectedColor;
        preset.modelId = modelId == null ? "" : modelId;
        preset.modelName = modelName == null ? "" : modelName;
        preset.modelEffort = selectedEffort == null ? "" : selectedEffort;
        preset.scope = selectedScope;
        preset.workspaceUri = SubagentPreset.SCOPE_WORKSPACE.equals(selectedScope) ? workspaceUri : "";
        preset.toolsMode = toolsMode;
        preset.allowedTools = new ArrayList<>(selectedTools);
        preset.description = descriptionInput.getText().toString().trim();
        preset.systemPrompt = promptInput.getText().toString().trim();
        preset.injectAgentsMd = injectAgentsMd;
        SubagentStore.save(this, preset);
        ModernToast.show(this, getString(R.string.subagent_saved));
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.subagent_delete)
                .setMessage(getString(R.string.subagent_delete_confirm, preset.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    SubagentStore.delete(this, preset.id);
                    finish();
                }).show();
    }

    private String activeWorkspaceUri() {
        return getSharedPreferences("multiaiide", MODE_PRIVATE)
                .getString("project_folder_uri", "");
    }
}
