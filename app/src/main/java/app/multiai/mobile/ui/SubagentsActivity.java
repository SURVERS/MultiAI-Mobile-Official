package app.multiai.mobile.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.multiai.mobile.R;
import app.multiai.mobile.models.SubagentPreset;
import app.multiai.mobile.ui.kit.ModernMenu;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.SubagentStore;

/** Settings list for installed built-in and user-created subagent presets. */
public class SubagentsActivity extends AppCompatActivity {
    private LinearLayout userList;
    private TextView userCount;
    private TextView emptyTitle;
    private String filter = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(this,
                getString(R.string.subagents_title), false);
        addCreateButton(scaffold);
        LinearLayout content = scaffold.content;

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.subagents_subtitle);
        subtitle.setTextColor(SettingsUI.colorSubtext(this));
        subtitle.setTextSize(14);
        subtitle.setPadding(SettingsUI.dp(this, 20), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 20), SettingsUI.dp(this, 10));
        content.addView(subtitle);

        EditText search = createSearchInput();
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.leftMargin = SettingsUI.dp(this, 16);
        searchLp.rightMargin = SettingsUI.dp(this, 16);
        searchLp.bottomMargin = SettingsUI.dp(this, 8);
        content.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                filter = value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
                renderUserPresets();
            }
            @Override public void afterTextChanged(Editable value) { }
        });

        userCount = SettingsUI.sectionLabel(this, getString(R.string.subagents_installed));
        content.addView(userCount);
        userList = new LinearLayout(this);
        userList.setOrientation(LinearLayout.VERTICAL);
        content.addView(userList);

        emptyTitle = new TextView(this);
        emptyTitle.setText(R.string.subagents_empty_title);
        emptyTitle.setTextColor(SettingsUI.colorText(this));
        emptyTitle.setTextSize(15);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        emptyTitle.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 22),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 4));
        content.addView(emptyTitle);

        TextView emptySubtitle = new TextView(this);
        emptySubtitle.setText(R.string.subagents_empty_subtitle);
        emptySubtitle.setTextColor(SettingsUI.colorHint(this));
        emptySubtitle.setTextSize(13);
        emptySubtitle.setGravity(Gravity.CENTER);
        emptySubtitle.setPadding(SettingsUI.dp(this, 28), 0, SettingsUI.dp(this, 28), SettingsUI.dp(this, 16));
        content.addView(emptySubtitle);

        TextView create = pillButton(getString(R.string.subagents_new));
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        createLp.gravity = Gravity.CENTER_HORIZONTAL;
        createLp.bottomMargin = SettingsUI.dp(this, 18);
        create.setLayoutParams(createLp);
        create.setOnClickListener(v -> openEditor(null));
        content.addView(create);

        content.addView(SettingsUI.sectionLabel(this, getString(R.string.subagents_builtin)));
        LinearLayout builtins = SettingsUI.card(this);
        List<SubagentPreset> builtInPresets = SubagentStore.builtIns(this);
        for (int i = 0; i < builtInPresets.size(); i++) {
            if (i > 0) builtins.addView(SettingsUI.divider(this));
            builtins.addView(builtInRow(builtInPresets.get(i)));
        }
        content.addView(builtins);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (userList != null) renderUserPresets();
    }

    private void addCreateButton(SettingsUI.Scaffold scaffold) {
        ViewGroup toolbar = (ViewGroup) scaffold.backButton.getParent();
        ImageView add = new ImageView(this);
        add.setImageResource(R.drawable.ic_plus);
        add.setColorFilter(SettingsUI.colorText(this));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(SettingsUI.colorCard(this));
        background.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        add.setBackground(background);
        add.setPadding(SettingsUI.dp(this, 10), SettingsUI.dp(this, 10), SettingsUI.dp(this, 10), SettingsUI.dp(this, 10));
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                SettingsUI.dp(this, 40), SettingsUI.dp(this, 40), Gravity.END | Gravity.CENTER_VERTICAL);
        params.rightMargin = SettingsUI.dp(this, 16);
        add.setLayoutParams(params);
        add.setContentDescription(getString(R.string.subagents_new));
        add.setOnClickListener(v -> openEditor(null));
        toolbar.addView(add);
    }

    private EditText createSearchInput() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setMinHeight(SettingsUI.dp(this, 48));
        input.setMinimumHeight(SettingsUI.dp(this, 48));
        input.setIncludeFontPadding(false);
        input.setTextColor(SettingsUI.colorText(this));
        input.setHintTextColor(SettingsUI.colorHint(this));
        input.setHint(R.string.subagents_search);
        input.setContentDescription(getString(R.string.subagents_search));
        input.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_magnify, 0, 0, 0);
        input.setCompoundDrawablePadding(SettingsUI.dp(this, 12));
        input.setPadding(SettingsUI.dp(this, 16), 0, SettingsUI.dp(this, 16), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(SettingsUI.colorSurfaceTonal(this));
        background.setCornerRadius(SettingsUI.dp(this, 16));
        background.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        input.setBackground(background);
        AccentColor.applyCursorTint(input, AccentColor.resolve(this));
        return input;
    }

    private void renderUserPresets() {
        List<SubagentPreset> all = SubagentStore.getAll(this);
        List<SubagentPreset> visible = new ArrayList<>();
        for (SubagentPreset preset : all) {
            String haystack = (preset.name + " " + preset.description).toLowerCase(Locale.ROOT);
            if (filter.isEmpty() || haystack.contains(filter)) visible.add(preset);
        }
        userCount.setText(getResources().getQuantityString(R.plurals.subagents_installed_count,
                all.size(), all.size()));
        userList.removeAllViews();
        for (SubagentPreset preset : visible) userList.addView(presetRow(preset));
        emptyTitle.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View presetRow(SubagentPreset preset) {
        LinearLayout card = SettingsUI.card(this);
        card.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 10), SettingsUI.dp(this, 14));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openEditor(preset.id));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = colorDot(preset.colorKey, 12);
        row.addView(dot);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = SettingsUI.dp(this, 12);
        text.setLayoutParams(textLp);
        TextView title = new TextView(this);
        title.setText(preset.name);
        title.setTextColor(SettingsUI.colorText(this));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        text.addView(title);
        TextView detail = new TextView(this);
        detail.setText(preset.description.isEmpty() ? getString(R.string.subagents_no_description)
                : preset.description);
        detail.setTextColor(SettingsUI.colorSubtext(this));
        detail.setTextSize(13);
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(detail);
        row.addView(text);

        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_more_vert);
        more.setColorFilter(SettingsUI.colorMutedIcon(this));
        more.setPadding(SettingsUI.dp(this, 10), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 10), SettingsUI.dp(this, 10));
        more.setContentDescription(getString(R.string.subagents_actions));
        more.setOnClickListener(v -> showActions(more, preset));
        row.addView(more, new LinearLayout.LayoutParams(SettingsUI.dp(this, 40), SettingsUI.dp(this, 40)));
        card.addView(row);

        TextView meta = new TextView(this);
        String scope = preset.isWorkspaceScoped() ? getString(R.string.subagents_scope_workspace)
                : getString(R.string.subagents_scope_user);
        meta.setText(scope + " · " + preset.displayModel() + " · " + toolSummary(preset));
        meta.setTextColor(SettingsUI.colorHint(this));
        meta.setTextSize(12);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = SettingsUI.dp(this, 24);
        metaLp.topMargin = SettingsUI.dp(this, 6);
        meta.setLayoutParams(metaLp);
        card.addView(meta);
        return card;
    }

    private View builtInRow(SubagentPreset preset) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14), SettingsUI.dp(this, 12), SettingsUI.dp(this, 14));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openEditor(preset.id));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_subagent);
        icon.setColorFilter(SettingsUI.colorMutedIcon(this));
        row.addView(icon, new LinearLayout.LayoutParams(SettingsUI.dp(this, 22), SettingsUI.dp(this, 22)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = SettingsUI.dp(this, 14);
        text.setLayoutParams(textLp);
        TextView title = new TextView(this);
        title.setText(preset.name);
        title.setTextColor(SettingsUI.colorText(this));
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        text.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(preset.description);
        subtitle.setTextColor(SettingsUI.colorSubtext(this));
        subtitle.setTextSize(12);
        subtitle.setMaxLines(2);
        text.addView(subtitle);
        row.addView(text);
        String tools = SubagentStore.BUILTIN_EXPLORE_ID.equals(preset.id)
                ? getString(R.string.subagents_read_tools) : getString(R.string.subagents_all_tools);
        TextView toolsPill = pillButton(tools + " · " + preset.displayModel()
                + (preset.modelEffort.isEmpty() ? "" : " " + preset.modelEffort));
        toolsPill.setTextSize(11);
        row.addView(toolsPill);
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setColorFilter(SettingsUI.colorMutedIcon(this));
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 16));
        chevronLp.leftMargin = SettingsUI.dp(this, 8);
        row.addView(chevron, chevronLp);
        return row;
    }

    private void showActions(View anchor, SubagentPreset preset) {
        ModernMenu.show(anchor, getString(R.string.subagents_actions), Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.subagents_edit)),
                new ModernSheet.MenuItem(getString(R.string.subagents_duplicate)),
                new ModernSheet.MenuItem(getString(R.string.action_delete))), (index, item) -> {
            if (index == 0) openEditor(preset.id);
            else if (index == 1) {
                SubagentPreset copy = preset.copyAsNew();
                SubagentStore.save(this, copy);
                openEditor(copy.id);
            } else {
                SubagentStore.delete(this, preset.id);
                renderUserPresets();
            }
        });
    }

    private void openEditor(String id) {
        Intent intent = new Intent(this, SubagentEditActivity.class);
        if (id != null) intent.putExtra(SubagentEditActivity.EXTRA_PRESET_ID, id);
        startActivity(intent);
    }

    private View colorDot(String key, int sizeDp) {
        View dot = new View(this);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(AccentColor.previewColor(key, AccentColor.isDark(this)));
        background.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        dot.setBackground(background);
        dot.setLayoutParams(new LinearLayout.LayoutParams(SettingsUI.dp(this, sizeDp), SettingsUI.dp(this, sizeDp)));
        return dot;
    }

    private TextView pillButton(String label) {
        TextView pill = new TextView(this);
        pill.setText(label);
        pill.setTextColor(SettingsUI.colorSubtext(this));
        pill.setTextSize(12);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(SettingsUI.dp(this, 10), SettingsUI.dp(this, 6),
                SettingsUI.dp(this, 10), SettingsUI.dp(this, 6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(SettingsUI.colorSurfaceTonal(this));
        background.setCornerRadius(SettingsUI.dp(this, 12));
        background.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        pill.setBackground(background);
        return pill;
    }

    private String toolSummary(SubagentPreset preset) {
        if (!SubagentPreset.TOOLS_CUSTOM.equals(preset.toolsMode)) {
            return getString(R.string.subagents_default_permissions);
        }
        return getResources().getQuantityString(R.plurals.subagents_tool_count,
                preset.allowedTools.size(), preset.allowedTools.size());
    }
}
