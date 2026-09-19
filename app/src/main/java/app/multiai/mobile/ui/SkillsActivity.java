package app.multiai.mobile.ui;

import android.content.Intent;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import app.multiai.mobile.R;
import app.multiai.mobile.models.SkillPreset;
import app.multiai.mobile.ui.kit.ModernMenu;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.kit.MotionKit;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.SkillStore;

/**
 * Экран управления Skills (Work Mode только) — полноценная система по образцу
 * Desktop IDE: slug-вызов ({@code ./slug}), scope (пользователь/проект), цвет карточки,
 * Skills прочитанные с диска проекта ({@code .multiai/skills}, совместимость с
 * {@code .claude/skills}/{@code .cursor/skills}) помечены как read-only.
 */
public class SkillsActivity extends AppCompatActivity {

    private LinearLayout skillList;
    private TextView emptyView;
    private String filter = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(this,
                getString(R.string.skills_title), false);
        addCreateButton(scaffold);
        LinearLayout content = scaffold.content;

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.skills_subtitle);
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
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                renderSkills();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        content.addView(SettingsUI.sectionLabel(this, getString(R.string.skills_installed)));

        skillList = new LinearLayout(this);
        skillList.setOrientation(LinearLayout.VERTICAL);
        content.addView(skillList);

        emptyView = new TextView(this);
        emptyView.setText(R.string.skills_empty);
        emptyView.setTextColor(SettingsUI.colorHint(this));
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(SettingsUI.dp(this, 24), SettingsUI.dp(this, 32),
                SettingsUI.dp(this, 24), SettingsUI.dp(this, 16));
        content.addView(emptyView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (skillList != null) renderSkills();
    }

    private void addCreateButton(SettingsUI.Scaffold scaffold) {
        ViewGroup toolbar = (ViewGroup) scaffold.backButton.getParent();
        ImageView add = new ImageView(this);
        add.setImageResource(R.drawable.ic_plus);
        add.setColorFilter(SettingsUI.colorText(this));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(SettingsUI.colorCard(this));
        bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        add.setBackground(bg);
        add.setPadding(SettingsUI.dp(this, 10), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 10), SettingsUI.dp(this, 10));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                SettingsUI.dp(this, 40), SettingsUI.dp(this, 40),
                Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = SettingsUI.dp(this, 16);
        add.setLayoutParams(lp);
        add.setContentDescription(getString(R.string.skills_new));
        SettingsUI.attachPressAnimation(add, 0.9f);
        add.setOnClickListener(v -> showAddMenu(add));
        toolbar.addView(add);
    }

    private void showAddMenu(View anchor) {
        ModernMenu.show(anchor, getString(R.string.skills_new), Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.skills_add_menu_create), R.drawable.ic_plus, false, null),
                new ModernSheet.MenuItem(getString(R.string.skills_add_menu_import), R.drawable.ic_download, false, null)),
                (index, item) -> {
                    if (index == 0) openEditor(null);
                    else startActivity(new Intent(this, SkillImportActivity.class));
                });
    }

    private EditText createSearchInput() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setMinHeight(SettingsUI.dp(this, 48));
        input.setTextColor(SettingsUI.colorText(this));
        input.setHintTextColor(SettingsUI.colorHint(this));
        input.setHint(R.string.skills_search);
        input.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_magnify, 0, 0, 0);
        input.setCompoundDrawablePadding(SettingsUI.dp(this, 12));
        input.setPadding(SettingsUI.dp(this, 16), 0, SettingsUI.dp(this, 16), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsUI.colorSurfaceTonal(this));
        bg.setCornerRadius(SettingsUI.dp(this, 16));
        bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        input.setBackground(bg);
        AccentColor.applyCursorTint(input, AccentColor.resolve(this));
        return input;
    }

    private String activeWorkspaceUri() {
        return getSharedPreferences("multiaiide", MODE_PRIVATE)
                .getString("project_folder_uri", "");
    }

    private void renderSkills() {
        List<SkillPreset> all = SkillStore.availableForWorkspace(this, activeWorkspaceUri());
        List<SkillPreset> visible = new ArrayList<>();
        for (SkillPreset skill : all) {
            String hay = (skill.name + " " + skill.slug + " " + skill.description).toLowerCase(Locale.ROOT);
            if (filter.isEmpty() || hay.contains(filter)) visible.add(skill);
        }
        skillList.removeAllViews();
        emptyView.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < visible.size(); i++) {
            View row = skillRow(visible.get(i));
            skillList.addView(row);
            // Лёгкий staggered fade-in — карточки появляются по очереди, а не все сразу.
            row.setAlpha(0f);
            row.setTranslationY(SettingsUI.dp(this, 10));
            row.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(i, 8) * 28L)
                    .setDuration(MotionKit.STANDARD)
                    .setInterpolator(MotionKit.STANDARD_EASING)
                    .start();
        }
    }

    private View skillRow(SkillPreset skill) {
        LinearLayout card = SettingsUI.card(this);
        // Отступ между карточками — иначе они слипаются в одну плитку.
        LinearLayout.LayoutParams cardLp = (LinearLayout.LayoutParams) card.getLayoutParams();
        cardLp.bottomMargin = SettingsUI.dp(this, 10);
        card.setLayoutParams(cardLp);
        card.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 10), SettingsUI.dp(this, 14));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openSkill(skill));
        MotionKit.attachPress(card, 0.985f);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int accent = AccentColor.previewColor(skill.colorKey, AccentColor.isDark(this));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_skill_flask);
        icon.setColorFilter(skill.enabled ? accent : SettingsUI.colorMutedIcon(this));
        android.widget.FrameLayout iconWrap = new android.widget.FrameLayout(this);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(skill.enabled ? (accent & 0x00FFFFFF | 0x28000000) : SettingsUI.colorSurfaceTonal(this));
        iconWrap.setBackground(iconBg);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 36), SettingsUI.dp(this, 36));
        iconWrap.setLayoutParams(wLp);
        android.widget.FrameLayout.LayoutParams iLp = new android.widget.FrameLayout.LayoutParams(
                SettingsUI.dp(this, 20), SettingsUI.dp(this, 20));
        iLp.gravity = Gravity.CENTER;
        icon.setLayoutParams(iLp);
        iconWrap.addView(icon);
        row.addView(iconWrap);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = SettingsUI.dp(this, 12);
        textCol.setLayoutParams(tLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(skill.name);
        name.setTextColor(SettingsUI.colorText(this));
        name.setTextSize(16);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        // Имя сжимается/обрезается, чтобы бейдж «С диска проекта» НЕ ломался в столбик.
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameLp);
        titleRow.addView(name);
        if (skill.fromDisk) {
            TextView diskBadge = smallBadge(getString(R.string.skills_disk_badge), SettingsUI.colorHint(this));
            titleRow.addView(diskBadge);
        }
        textCol.addView(titleRow);

        if (skill.description != null && !skill.description.isEmpty()) {
            TextView desc = new TextView(this);
            desc.setText(skill.description);
            desc.setTextColor(SettingsUI.colorSubtext(this));
            desc.setTextSize(13);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(desc);
        }
        row.addView(textCol);

        TextView statusPill = pillText(skill.enabled
                ? getString(R.string.skills_enabled) : getString(R.string.skills_disabled),
                skill.enabled);
        row.addView(statusPill);

        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_more_vert);
        more.setColorFilter(SettingsUI.colorMutedIcon(this));
        more.setPadding(SettingsUI.dp(this, 10), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 10), SettingsUI.dp(this, 10));
        more.setOnClickListener(v -> showActions(more, skill));
        row.addView(more, new LinearLayout.LayoutParams(SettingsUI.dp(this, 40), SettingsUI.dp(this, 40)));

        card.addView(row);

        TextView meta = new TextView(this);
        String scope = skill.isWorkspaceScoped() ? getString(R.string.subagents_scope_workspace)
                : getString(R.string.subagents_scope_user);
        meta.setText(scope);
        meta.setTextColor(SettingsUI.colorHint(this));
        meta.setTextSize(11.5f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = SettingsUI.dp(this, 24);
        metaLp.topMargin = SettingsUI.dp(this, 6);
        meta.setLayoutParams(metaLp);
        card.addView(meta);
        return card;
    }

    private TextView smallBadge(String text, int textColor) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextColor(textColor);
        badge.setTextSize(10);
        // Одна строка — иначе «С диска проекта» рвётся в узкий столбик.
        badge.setSingleLine(true);
        badge.setPadding(SettingsUI.dp(this, 6), SettingsUI.dp(this, 2),
                SettingsUI.dp(this, 6), SettingsUI.dp(this, 2));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(SettingsUI.dp(this, 8));
        bg.setColor(SettingsUI.colorSurfaceTonal(this));
        bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        badge.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = SettingsUI.dp(this, 8);
        badge.setLayoutParams(lp);
        return badge;
    }

    private TextView pillText(String text, boolean active) {
        TextView pill = new TextView(this);
        pill.setText(text);
        pill.setTextSize(11);
        int accent = AccentColor.resolve(this);
        pill.setTextColor(active ? accent : SettingsUI.colorSubtext(this));
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(SettingsUI.dp(this, 8), SettingsUI.dp(this, 4),
                SettingsUI.dp(this, 8), SettingsUI.dp(this, 4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(SettingsUI.dp(this, 10));
        if (active) {
            bg.setColor(accent & 0x00FFFFFF | 0x28000000);
            bg.setStroke(1, accent & 0x00FFFFFF | 0x5A000000);
        } else {
            bg.setColor(SettingsUI.colorSurfaceTonal(this));
            bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        }
        pill.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = SettingsUI.dp(this, 4);
        pill.setLayoutParams(lp);
        return pill;
    }

    /** Диск-Skills открывают read-only просмотр; обычные — полный редактор. */
    private void openSkill(SkillPreset skill) {
        if (skill.fromDisk) openDiskSkillView(skill);
        else openEditor(skill.id);
    }

    private void openDiskSkillView(SkillPreset skill) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(SettingsUI.dp(this, 4), 0, SettingsUI.dp(this, 4), SettingsUI.dp(this, 8));

        TextView note = new TextView(this);
        note.setText(R.string.skill_disk_readonly_note);
        note.setTextColor(SettingsUI.colorSubtext(this));
        note.setTextSize(13);
        note.setPadding(SettingsUI.dp(this, 12), 0, SettingsUI.dp(this, 12), SettingsUI.dp(this, 12));
        body.addView(note);

        if (skill.diskRelPath != null && !skill.diskRelPath.isEmpty()) {
            TextView path = new TextView(this);
            path.setText(getString(R.string.skill_disk_path_label) + ": " + skill.diskRelPath);
            path.setTextColor(SettingsUI.colorHint(this));
            path.setTextSize(12);
            path.setTypeface(android.graphics.Typeface.MONOSPACE);
            path.setPadding(SettingsUI.dp(this, 12), 0, SettingsUI.dp(this, 12), SettingsUI.dp(this, 12));
            body.addView(path);
        }

        TextView contentLabel = SettingsUI.sectionLabel(this, getString(R.string.skill_content_section));
        body.addView(contentLabel);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUI.dp(this, 260));
        scrollLp.leftMargin = SettingsUI.dp(this, 12);
        scrollLp.rightMargin = SettingsUI.dp(this, 12);
        scroll.setLayoutParams(scrollLp);
        TextView contentText = new TextView(this);
        contentText.setText(skill.content);
        contentText.setTextColor(SettingsUI.colorText(this));
        contentText.setTextSize(13);
        GradientDrawable contentBg = new GradientDrawable();
        contentBg.setColor(SettingsUI.colorSurfaceTonal(this));
        contentBg.setCornerRadius(SettingsUI.dp(this, 12));
        contentText.setBackground(contentBg);
        contentText.setPadding(SettingsUI.dp(this, 12), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 12), SettingsUI.dp(this, 10));
        scroll.addView(contentText);
        body.addView(scroll);

        // Тумблер включить/выключен — сам файл на диске не редактируется, только override.
        boolean checked = skill.enabled;
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setPadding(SettingsUI.dp(this, 12), SettingsUI.dp(this, 12),
                SettingsUI.dp(this, 12), 0);
        TextView toggleLabel = new TextView(this);
        toggleLabel.setText(checked ? getString(R.string.skills_enabled) : getString(R.string.skills_disabled));
        toggleLabel.setTextColor(SettingsUI.colorText(this));
        toggleLabel.setTextSize(14);
        LinearLayout.LayoutParams toggleLabelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        toggleLabel.setLayoutParams(toggleLabelLp);
        toggleRow.addView(toggleLabel);
        TextView toggleAction = pillText(checked ? getString(R.string.skills_disable)
                : getString(R.string.skills_enable), checked);
        toggleAction.setOnClickListener(v -> {
            SkillStore.setDiskSkillEnabled(this, skill.normalizedSlug(), !skill.enabled);
            ModernToast.show(this, !skill.enabled
                    ? getString(R.string.skills_enabled) : getString(R.string.skills_disabled));
            renderSkills();
        });
        toggleRow.addView(toggleAction);
        body.addView(toggleRow);

        ModernSheet.custom(this, getString(R.string.skill_disk_view_title) + " · " + skill.name, body, true);
    }

    private void showActions(View anchor, SkillPreset skill) {
        if (skill.fromDisk) {
            String toggleLabel = skill.enabled
                    ? getString(R.string.skills_disable) : getString(R.string.skills_enable);
            ModernMenu.show(anchor, getString(R.string.skills_actions), Arrays.asList(
                    new ModernSheet.MenuItem(getString(R.string.skills_view)),
                    new ModernSheet.MenuItem(toggleLabel)), (index, item) -> {
                if (index == 0) {
                    openDiskSkillView(skill);
                } else {
                    SkillStore.setDiskSkillEnabled(this, skill.normalizedSlug(), !skill.enabled);
                    renderSkills();
                    ModernToast.show(this, skill.enabled
                            ? getString(R.string.skills_disabled) : getString(R.string.skills_enabled));
                }
            });
            return;
        }
        String toggleLabel = skill.enabled
                ? getString(R.string.skills_disable) : getString(R.string.skills_enable);
        ModernMenu.show(anchor, getString(R.string.skills_actions), Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.subagents_edit)),
                new ModernSheet.MenuItem(toggleLabel),
                new ModernSheet.MenuItem(getString(R.string.action_delete))),
                (index, item) -> {
                    if (index == 0) {
                        openEditor(skill.id);
                    } else if (index == 1) {
                        skill.enabled = !skill.enabled;
                        SkillStore.save(this, skill);
                        renderSkills();
                        ModernToast.show(this, skill.enabled
                                ? getString(R.string.skills_enabled)
                                : getString(R.string.skills_disabled));
                    } else {
                        SkillStore.delete(this, skill.id);
                        renderSkills();
                        ModernToast.show(this, getString(R.string.skills_deleted));
                    }
                });
    }

    private void openEditor(String id) {
        Intent intent = new Intent(this, SkillEditActivity.class);
        if (id != null) intent.putExtra(SkillEditActivity.EXTRA_SKILL_ID, id);
        startActivity(intent);
    }
}
