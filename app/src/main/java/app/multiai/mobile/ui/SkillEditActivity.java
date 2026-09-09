package app.multiai.mobile.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.Locale;

import app.multiai.mobile.R;
import app.multiai.mobile.models.SkillPreset;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernMenu;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.SkillStore;

/**
 * Редактор Skill: имя, slug (команда вызова "./slug"), описание, цвет, область,
 * текст инструкции. Логика вызова полностью повторяет Desktop IDE — см. javadoc
 * {@link app.multiai.mobile.utils.SkillInvocation}.
 */
public class SkillEditActivity extends AppCompatActivity {

    public static final String EXTRA_SKILL_ID = "skill_id";
    /** Опциональные поля предзаполнения из SkillImportActivity — только для НОВОГО Skill. */
    public static final String EXTRA_PREFILL_NAME = "skill_prefill_name";
    public static final String EXTRA_PREFILL_SLUG = "skill_prefill_slug";
    public static final String EXTRA_PREFILL_DESCRIPTION = "skill_prefill_description";
    public static final String EXTRA_PREFILL_CONTENT = "skill_prefill_content";

    private SkillPreset skill;
    private boolean editing;
    private EditText nameInput;
    private EditText slugInput;
    private EditText descInput;
    private EditText contentInput;
    private TextView colorValue;
    private TextView scopeValue;
    private String selectedColor;
    private String selectedScope;
    private String workspaceUri;
    private boolean slugManuallyEdited;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        String id = getIntent().getStringExtra(EXTRA_SKILL_ID);
        skill = id != null ? SkillStore.find(this, id) : null;
        editing = skill != null;
        if (skill == null) {
            skill = new SkillPreset();
            skill.name = stringExtra(EXTRA_PREFILL_NAME);
            skill.slug = stringExtra(EXTRA_PREFILL_SLUG);
            skill.description = stringExtra(EXTRA_PREFILL_DESCRIPTION);
            skill.content = stringExtra(EXTRA_PREFILL_CONTENT);
            slugManuallyEdited = !skill.slug.isEmpty();
        } else {
            slugManuallyEdited = true;
        }
        selectedColor = skill.colorKey;
        selectedScope = skill.scope;
        workspaceUri = skill.workspaceUri;

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(this,
                getString(editing ? R.string.skill_edit_title : R.string.skill_new_title), true);
        scaffold.saveButton.setContentDescription(getString(R.string.subagent_save));
        scaffold.saveButton.setOnClickListener(v -> save());

        buildContent(scaffold.content);
    }

    private String stringExtra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }

    private void buildContent(LinearLayout content) {
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.skill_editor_subtitle);
        subtitle.setTextColor(SettingsUI.colorSubtext(this));
        subtitle.setTextSize(14);
        subtitle.setPadding(SettingsUI.dp(this, 20), SettingsUI.dp(this, 8),
                SettingsUI.dp(this, 20), SettingsUI.dp(this, 12));
        content.addView(subtitle);

        // Карточка: Имя + Slug + Описание
        LinearLayout identity = SettingsUI.card(this);
        identity.addView(SettingsUI.sectionLabel(this, getString(R.string.skill_identity_section)));
        nameInput = addInput(identity, getString(R.string.skill_name), getString(R.string.skill_name_hint),
                skill.name, false, 1);
        nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (!slugManuallyEdited && slugInput != null) {
                    slugInput.setText(SkillPreset.slugify(s == null ? "" : s.toString()));
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        identity.addView(SettingsUI.divider(this));
        slugInput = addInput(identity, getString(R.string.skill_slug), getString(R.string.skill_slug_hint),
                skill.slug, false, 1);
        slugInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                slugManuallyEdited = true;
                refreshSlugHelper();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        TextView slugHelper = new TextView(this);
        slugHelper.setTextColor(SettingsUI.colorHint(this));
        slugHelper.setTextSize(12);
        LinearLayout.LayoutParams helperLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helperLp.leftMargin = SettingsUI.dp(this, 14);
        helperLp.rightMargin = SettingsUI.dp(this, 14);
        helperLp.bottomMargin = SettingsUI.dp(this, 14);
        slugHelper.setLayoutParams(helperLp);
        identity.addView(slugHelper);
        this.slugHelperView = slugHelper;
        refreshSlugHelper();
        identity.addView(SettingsUI.divider(this));
        descInput = addInput(identity, getString(R.string.skill_description),
                getString(R.string.skill_description_hint), skill.description, false, 2);
        content.addView(identity);

        content.addView(SettingsUI.sectionGap(this));

        // Карточка: цвет + область
        LinearLayout runtime = SettingsUI.card(this);
        runtime.addView(SettingsUI.sectionLabel(this, getString(R.string.skill_runtime_section)));
        colorValue = trailingValue(colorLabel());
        runtime.addView(SettingsUI.rowWithTrailing(this, R.drawable.ic_s_palette,
                getString(R.string.skill_color), null, colorValue, this::showColorPicker));
        runtime.addView(SettingsUI.divider(this));
        scopeValue = trailingValue(displayScope());
        runtime.addView(SettingsUI.rowWithTrailing(this, R.drawable.ic_s_briefcase,
                getString(R.string.skill_scope), null, scopeValue, this::showScopeMenu));
        content.addView(runtime);

        content.addView(SettingsUI.sectionGap(this));

        // Карточка: Контент (system prompt фрагмент)
        LinearLayout promptCard = SettingsUI.card(this);
        promptCard.addView(SettingsUI.sectionLabel(this, getString(R.string.skill_content_section)));

        TextView contentNote = new TextView(this);
        contentNote.setText(R.string.skill_content_note);
        contentNote.setTextColor(SettingsUI.colorHint(this));
        contentNote.setTextSize(12);
        contentNote.setPadding(SettingsUI.dp(this, 14), 0,
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 8));
        promptCard.addView(contentNote);

        contentInput = addInput(promptCard, null, getString(R.string.skill_content_hint),
                skill.content, true, 12);
        content.addView(promptCard);

        // Кнопка удаления (только при редактировании)
        if (editing) {
            content.addView(SettingsUI.sectionGap(this));
            LinearLayout danger = SettingsUI.card(this);
            danger.addView(SettingsUI.rowDanger(this, getString(R.string.skill_delete), this::confirmDelete));
            content.addView(danger);
        }
    }

    private TextView slugHelperView;

    private void refreshSlugHelper() {
        if (slugHelperView == null || slugInput == null) return;
        String slug = SkillPreset.slugify(slugInput.getText().toString());
        slugHelperView.setText(slug.isEmpty() ? getString(R.string.skill_slug_required)
                : getString(R.string.skill_slug_helper, slug));
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
        input.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 10));
        AccentColor.applyCursorTint(input, AccentColor.resolve(this));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsUI.colorSurfaceTonal(this));
        bg.setCornerRadius(SettingsUI.dp(this, 12));
        bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        input.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                multiline ? SettingsUI.dp(this, 200) : ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = SettingsUI.dp(this, 14);
        lp.rightMargin = SettingsUI.dp(this, 14);
        lp.bottomMargin = SettingsUI.dp(this, 14);
        input.setLayoutParams(lp);
        parent.addView(input);
        return input;
    }

    private void showColorPicker() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(SettingsUI.dp(this, 4), 0, SettingsUI.dp(this, 4), SettingsUI.dp(this, 6));
        final android.app.Dialog[] dialog = new android.app.Dialog[1];
        for (String key : AccentColor.keys()) {
            TextView row = new TextView(this);
            row.setText((key.equals(selectedColor) ? "✓  " : "    ") + AccentColor.label(key, this));
            row.setTextColor(SettingsUI.colorText(this));
            row.setTextSize(15);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14), SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(AccentColor.previewColor(key, AccentColor.isDark(this)));
            rowBg.setAlpha(34);
            rowBg.setCornerRadius(SettingsUI.dp(this, 12));
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = SettingsUI.dp(this, 6);
            row.setLayoutParams(rowLp);
            row.setOnClickListener(v -> {
                selectedColor = key;
                if (colorValue != null) colorValue.setText(colorLabel());
                if (dialog[0] != null) dialog[0].dismiss();
            });
            body.addView(row);
        }
        dialog[0] = ModernSheet.custom(this, getString(R.string.skill_color), body, true);
    }

    private void showScopeMenu() {
        ModernMenu.show(scopeValue, getString(R.string.skill_scope), Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.subagents_scope_user)),
                new ModernSheet.MenuItem(getString(R.string.subagents_scope_workspace))), (index, item) -> {
            if (index == 0) {
                selectedScope = SkillPreset.SCOPE_USER;
                workspaceUri = "";
            } else {
                String active = activeWorkspaceUri();
                if (active.isEmpty()) {
                    ModernToast.show(this, getString(R.string.skill_scope_workspace_missing));
                    return;
                }
                selectedScope = SkillPreset.SCOPE_WORKSPACE;
                workspaceUri = active;
            }
            if (scopeValue != null) scopeValue.setText(displayScope());
        });
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

    private String colorLabel() {
        return AccentColor.label(selectedColor, this);
    }

    private String displayScope() {
        return SkillPreset.SCOPE_WORKSPACE.equals(selectedScope)
                ? getString(R.string.subagents_scope_workspace) : getString(R.string.subagents_scope_user);
    }

    private void save() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameInput.setError(getString(R.string.skill_name_required));
            nameInput.requestFocus();
            return;
        }
        String slug = SkillPreset.slugify(slugInput.getText().toString());
        if (slug.isEmpty()) {
            slugInput.setError(getString(R.string.skill_slug_required));
            slugInput.requestFocus();
            return;
        }
        if (SkillStore.slugTaken(this, slug, skill.id)) {
            slugInput.setError(getString(R.string.skill_slug_taken));
            slugInput.requestFocus();
            return;
        }
        String content = contentInput.getText().toString().trim();
        if (content.isEmpty()) {
            contentInput.setError(getString(R.string.skill_content_required));
            contentInput.requestFocus();
            return;
        }
        skill.name = name;
        skill.slug = slug;
        skill.description = descInput.getText().toString().trim();
        skill.content = content;
        skill.colorKey = selectedColor;
        skill.scope = selectedScope;
        skill.workspaceUri = SkillPreset.SCOPE_WORKSPACE.equals(selectedScope) ? workspaceUri : "";
        SkillStore.save(this, skill);
        ModernToast.show(this, getString(R.string.skill_saved));
        finish();
    }

    private void confirmDelete() {
        ModernDialog.show(this,
                getString(R.string.skill_delete),
                getString(R.string.skill_delete_confirm, skill.name),
                getString(android.R.string.cancel),
                getString(R.string.action_delete),
                true,
                () -> {
                    SkillStore.delete(this, skill.id);
                    finish();
                });
    }

    private String activeWorkspaceUri() {
        return getSharedPreferences("multiaiide", MODE_PRIVATE)
                .getString("project_folder_uri", "");
    }
}
