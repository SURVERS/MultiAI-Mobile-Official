package app.multiai.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;

import app.multiai.mobile.ui.kit.ModernMenu;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AppSettings;

/**
 * Экран "Общее" — основные настройки приложения.
 *
 * Порядок пунктов (по ТЗ):
 * 1. Язык
 * 2. Стиль эмодзи
 * 3. Сабагенты  (только Work Mode, но доступен всегда)
 * 4. Skills     (Work Mode only — видно в настройках всегда, но инжект только в Work)
 * 5. Кастомный провайдер
 * 6. Разрешённые команды
 *
 * Голос переехал сюда же — добавляется до "Разрешённых команд".
 */
public class GeneralSettingsActivity extends AppCompatActivity {

    private AppSettings s;
    private TextView langValue;
    private TextView emojiValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        s = new AppSettings(this);

        SettingsUI.Scaffold sc = SettingsUI.createScaffold(this,
                getString(app.multiai.mobile.R.string.general_title), false);
        LinearLayout root = sc.content;

        // ── Карточка 1: Язык и эмодзи ──
        LinearLayout card = SettingsUI.card(this);

        // 1. Язык
        String currentLang = langLabel(s.getLanguage());
        View langRow = SettingsUI.rowWithTrailing(this,
                app.multiai.mobile.R.drawable.ic_android,
                getString(app.multiai.mobile.R.string.language_title),
                currentLang, null, () -> {});
        langValue = (TextView) ((LinearLayout) ((LinearLayout) langRow).getChildAt(1)).getChildAt(1);
        langRow.setOnClickListener(v -> ModernMenu.show(v,
                getString(app.multiai.mobile.R.string.language_title),
                Arrays.asList(
                        new ModernSheet.MenuItem(getString(app.multiai.mobile.R.string.language_russian)),
                        new ModernSheet.MenuItem(getString(app.multiai.mobile.R.string.language_english))),
                (idx, item) -> {
                    String val = idx == 0 ? "ru" : "en";
                    s.setLanguage(val);
                    langValue.setText(langLabel(val));
                    app.multiai.mobile.MultiAIApp.applyLanguage(val);
                    recreate();
                }));
        card.addView(langRow);
        card.addView(SettingsUI.divider(this));

        // 2. Стиль эмодзи
        View emojiRow = SettingsUI.rowWithTrailing(this,
                app.multiai.mobile.R.drawable.ic_s_emoji,
                getString(app.multiai.mobile.R.string.settings_emoji_style),
                emojiLabel(s.getEmojiMode()), null, () -> {});
        emojiValue = (TextView) ((LinearLayout) ((LinearLayout) emojiRow).getChildAt(1)).getChildAt(1);
        emojiRow.setOnClickListener(v -> ModernMenu.show(v,
                getString(app.multiai.mobile.R.string.settings_emoji_style),
                Arrays.asList(
                        new ModernSheet.MenuItem(getString(app.multiai.mobile.R.string.emoji_mode_system)),
                        new ModernSheet.MenuItem(getString(app.multiai.mobile.R.string.emoji_mode_static)),
                        new ModernSheet.MenuItem(getString(app.multiai.mobile.R.string.emoji_mode_animated))),
                (idx, item) -> {
                    String val = idx == 0 ? AppSettings.EMOJI_MODE_SYSTEM
                            : idx == 1 ? AppSettings.EMOJI_MODE_STATIC
                            : AppSettings.EMOJI_MODE_ANIMATED;
                    s.setEmojiMode(val);
                    emojiValue.setText(emojiLabel(val));
                    app.multiai.mobile.utils.TelemojiProvider.get(this).clearCache();
                    ModernToast.show(this, emojiLabel(val));
                    recreate();
                }));
        card.addView(emojiRow);

        root.addView(card);

        // ── Карточка 2: Сабагенты + Skills + Кастомный провайдер ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout agentCard = SettingsUI.card(this);

        // 3. Сабагенты
        agentCard.addView(SettingsUI.row(this,
                app.multiai.mobile.R.drawable.ic_subagent,
                getString(app.multiai.mobile.R.string.subagents_title),
                getString(app.multiai.mobile.R.string.subagents_subtitle),
                () -> startActivity(new Intent(this, SubagentsActivity.class))));
        agentCard.addView(SettingsUI.divider(this));

        // 4. Skills (Work Mode только — инжект)
        agentCard.addView(SettingsUI.row(this,
                app.multiai.mobile.R.drawable.ic_skill_flask,
                getString(app.multiai.mobile.R.string.skills_title),
                getString(app.multiai.mobile.R.string.skills_settings_subtitle),
                () -> startActivity(new Intent(this, SkillsActivity.class))));
        agentCard.addView(SettingsUI.divider(this));

        // 5. Кастомный провайдер
        agentCard.addView(SettingsUI.row(this,
                app.multiai.mobile.R.drawable.ic_provider_plug,
                getString(app.multiai.mobile.R.string.custom_provider_title),
                getString(app.multiai.mobile.R.string.custom_provider_settings_subtitle),
                () -> startActivity(new Intent(this, CustomProviderActivity.class))));

        root.addView(agentCard);

        // ── Карточка 3: Голос + Разрешённые команды ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout controlCard = SettingsUI.card(this);

        // Голос (перенесён из ChatModeSettingsActivity)
        controlCard.addView(SettingsUI.row(this,
                app.multiai.mobile.R.drawable.ic_volume,
                getString(app.multiai.mobile.R.string.voice_title),
                getString(app.multiai.mobile.R.string.voice_subtitle),
                () -> startActivity(new Intent(this, VoiceSettingsActivity.class))));
        controlCard.addView(SettingsUI.divider(this));

        // 6. Разрешённые команды
        controlCard.addView(SettingsUI.row(this,
                app.multiai.mobile.R.drawable.ic_terminal_tools,
                getString(app.multiai.mobile.R.string.work_access_whitelist_manage),
                getString(app.multiai.mobile.R.string.settings_command_whitelist_sub),
                () -> app.multiai.mobile.utils.WorkModeAccess.showCommandWhitelistManager(this)));

        root.addView(controlCard);

        // ── Segmented mode pill toggle (остался, но без divider выше) ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout modeCard = SettingsUI.card(this);
        modeCard.addView(SettingsUI.rowToggle(this,
                app.multiai.mobile.R.drawable.ic_s_briefcase,
                getString(app.multiai.mobile.R.string.settings_mode_switcher),
                getString(app.multiai.mobile.R.string.settings_mode_switcher_sub),
                s.isSegmentedModeEnabled(),
                checked -> {
                    s.setSegmentedModeEnabled(checked);
                    ModernToast.show(this, getString(checked
                            ? app.multiai.mobile.R.string.state_enabled
                            : app.multiai.mobile.R.string.state_disabled));
                    recreate();
                }));
        root.addView(modeCard);
    }

    private String emojiLabel(String mode) {
        if (AppSettings.EMOJI_MODE_STATIC.equals(mode))
            return getString(app.multiai.mobile.R.string.emoji_mode_static);
        if (AppSettings.EMOJI_MODE_ANIMATED.equals(mode))
            return getString(app.multiai.mobile.R.string.emoji_mode_animated);
        return getString(app.multiai.mobile.R.string.emoji_mode_system);
    }

    private String langLabel(String v) {
        return getString("en".equals(v)
                ? app.multiai.mobile.R.string.language_english
                : app.multiai.mobile.R.string.language_russian);
    }
}
