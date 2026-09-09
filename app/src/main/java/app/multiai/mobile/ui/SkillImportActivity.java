package app.multiai.mobile.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import app.multiai.mobile.R;
import app.multiai.mobile.models.SkillPreset;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.SkillFrontmatter;

/**
 * Импорт произвольного Skill: пользователь вставляет содержимое любого {@code SKILL.md}
 * (например, скачанное из GitHub-репозитория skill-пакета) как plain text, экран
 * разбирает YAML frontmatter ({@code name}/{@code description}) через
 * {@link SkillFrontmatter} и передаёт распознанные поля в {@link SkillEditActivity}
 * для финального сохранения (пользователь может поправить slug/имя перед сохранением).
 *
 * <p>Это Android-эквивалент PC IDE {@code settings:importBotSkillFolder} — там источник
 * это папка на диске с {@code SKILL.md}, здесь (без произвольного файлового доступа
 * к внешним папкам) — вставленный текст, что покрывает тот же сценарий "принеси готовый
 * skill откуда угодно" без необходимости в SAF-доступе к чужой директории.
 */
public class SkillImportActivity extends AppCompatActivity {

    private EditText pasteInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(this,
                getString(R.string.skill_import_title), false);
        LinearLayout content = scaffold.content;

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.skill_import_subtitle);
        subtitle.setTextColor(SettingsUI.colorSubtext(this));
        subtitle.setTextSize(14);
        subtitle.setPadding(SettingsUI.dp(this, 20), SettingsUI.dp(this, 8),
                SettingsUI.dp(this, 20), SettingsUI.dp(this, 14));
        content.addView(subtitle);

        LinearLayout card = SettingsUI.card(this);
        card.addView(SettingsUI.sectionLabel(this, getString(R.string.skill_import_paste_label)));
        pasteInput = new EditText(this);
        pasteInput.setHint(R.string.skill_import_paste_hint);
        pasteInput.setHintTextColor(SettingsUI.colorHint(this));
        pasteInput.setTextColor(SettingsUI.colorText(this));
        pasteInput.setTextSize(13.5f);
        pasteInput.setTypeface(android.graphics.Typeface.MONOSPACE);
        pasteInput.setGravity(Gravity.TOP);
        pasteInput.setMaxLines(16);
        pasteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pasteInput.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 10));
        AccentColor.applyCursorTint(pasteInput, AccentColor.resolve(this));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsUI.colorSurfaceTonal(this));
        bg.setCornerRadius(SettingsUI.dp(this, 12));
        bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        pasteInput.setBackground(bg);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUI.dp(this, 280));
        inputLp.leftMargin = SettingsUI.dp(this, 14);
        inputLp.rightMargin = SettingsUI.dp(this, 14);
        inputLp.bottomMargin = SettingsUI.dp(this, 14);
        pasteInput.setLayoutParams(inputLp);
        card.addView(pasteInput);
        content.addView(card);

        content.addView(SettingsUI.sectionGap(this));

        TextView action = new TextView(this);
        action.setText(getString(R.string.skill_import_action));
        action.setTextColor(AccentColor.contrastForeground(this));
        action.setTextSize(15);
        action.setTypeface(null, android.graphics.Typeface.BOLD);
        action.setGravity(Gravity.CENTER);
        action.setPadding(0, SettingsUI.dp(this, 14), 0, SettingsUI.dp(this, 14));
        GradientDrawable actionBg = new GradientDrawable();
        actionBg.setColor(AccentColor.resolve(this));
        actionBg.setCornerRadius(SettingsUI.dp(this, 14));
        action.setBackground(actionBg);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.leftMargin = SettingsUI.dp(this, 16);
        actionLp.rightMargin = SettingsUI.dp(this, 16);
        action.setLayoutParams(actionLp);
        action.setClickable(true);
        action.setFocusable(true);
        app.multiai.mobile.ui.kit.MotionKit.attachPress(action, 0.96f);
        action.setOnClickListener(v -> parseAndContinue());
        content.addView(action);
    }

    private void parseAndContinue() {
        String raw = pasteInput.getText().toString();
        if (raw.trim().isEmpty()) {
            ModernToast.show(this, getString(R.string.skill_import_empty));
            return;
        }
        SkillFrontmatter.Parsed parsed = SkillFrontmatter.parse(raw);
        String name = parsed.title != null && !parsed.title.trim().isEmpty()
                ? parsed.title.trim() : getString(R.string.skill_new_title);
        String slug = SkillPreset.slugify(name);
        String description = parsed.description != null ? parsed.description.trim() : "";
        String body = parsed.body != null ? parsed.body.trim() : raw.trim();

        Intent intent = new Intent(this, SkillEditActivity.class);
        intent.putExtra(SkillEditActivity.EXTRA_PREFILL_NAME, name);
        intent.putExtra(SkillEditActivity.EXTRA_PREFILL_SLUG, slug);
        intent.putExtra(SkillEditActivity.EXTRA_PREFILL_DESCRIPTION, description);
        intent.putExtra(SkillEditActivity.EXTRA_PREFILL_CONTENT, body);
        startActivity(intent);
        finish();
    }
}
