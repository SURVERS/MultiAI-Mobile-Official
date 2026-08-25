package app.multiai.mobile.ui;

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

public class GeneralSettingsActivity extends AppCompatActivity {

    private AppSettings s;
    private TextView langValue;
    private TextView emojiValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        s = new AppSettings(this);

        SettingsUI.Scaffold sc = SettingsUI.createScaffold(this, getString(app.multiai.mobile.R.string.general_title), false);
        LinearLayout root = sc.content;

        LinearLayout card = SettingsUI.card(this);
        String current = langLabel(s.getLanguage());
        View row = SettingsUI.rowWithTrailing(this, app.multiai.mobile.R.drawable.ic_android,
                getString(app.multiai.mobile.R.string.language_title), current, null, () -> {});
        langValue = (TextView) ((LinearLayout) ((LinearLayout) row).getChildAt(1)).getChildAt(1);
        row.setOnClickListener(v -> ModernMenu.show(v, getString(app.multiai.mobile.R.string.language_title),
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
        card.addView(row);
        card.addView(SettingsUI.divider(this));

        // ── Стиль эмодзи (Telemoji) ──
        View emojiRow = SettingsUI.rowWithTrailing(this, app.multiai.mobile.R.drawable.ic_s_sparkle,
                getString(app.multiai.mobile.R.string.settings_emoji_style),
                emojiLabel(s.getEmojiMode()),
                null, () -> {});
        emojiValue = (TextView) ((LinearLayout) ((LinearLayout) emojiRow).getChildAt(1)).getChildAt(1);
        emojiRow.setOnClickListener(v -> ModernMenu.show(v,
                getString(app.multiai.mobile.R.string.settings_emoji_style),
                Arrays.asList(
                        new ModernSheet.MenuItem(getString(app.multiai.mobile.R.string.emoji_mode_system)),
                        new ModernSheet.MenuItem(getString(app.multiai.mobile.R.string.emoji_mode_static)),
                        new ModernSheet.MenuItem(getString(app.multiai.mobile.R.string.emoji_mode_animated))),
                (idx, item) -> {
                    String val = idx == 0 ? app.multiai.mobile.utils.AppSettings.EMOJI_MODE_SYSTEM
                            : idx == 1 ? app.multiai.mobile.utils.AppSettings.EMOJI_MODE_STATIC
                            : app.multiai.mobile.utils.AppSettings.EMOJI_MODE_ANIMATED;
                    s.setEmojiMode(val);
                    emojiValue.setText(emojiLabel(val));
                    app.multiai.mobile.utils.TelemojiProvider.get(this).clearCache();
                    ModernToast.show(this, emojiLabel(val));
                    recreate();
                }));
        card.addView(emojiRow);
        card.addView(SettingsUI.divider(this));

        // Segmented mode pill toggle.
        card.addView(SettingsUI.rowToggle(this, app.multiai.mobile.R.drawable.ic_s_briefcase,
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

        // «Стриминг ответов», «Показывать размышления» и «Использовать
        // инструменты» убраны из общих настроек: это не пользовательские
        // переключатели, а поведение по умолчанию текущего протокола.
        card.addView(SettingsUI.divider(this));

        // Управление вайтлистом команд Work Mode: раньше редактор был доступен
        // только из диалога подтверждения, то есть в момент, когда команда уже
        // выполняется. Теперь список правится из настроек в любое время.
        card.addView(SettingsUI.row(this, app.multiai.mobile.R.drawable.ic_terminal_tools,
                getString(app.multiai.mobile.R.string.work_access_whitelist_manage),
                getString(app.multiai.mobile.R.string.settings_command_whitelist_sub),
                () -> app.multiai.mobile.utils.WorkModeAccess
                        .showCommandWhitelistManager(this)));
        root.addView(card);
    }

    private String emojiLabel(String mode) {
        if (app.multiai.mobile.utils.AppSettings.EMOJI_MODE_STATIC.equals(mode)) {
            return getString(app.multiai.mobile.R.string.emoji_mode_static);
        } else if (app.multiai.mobile.utils.AppSettings.EMOJI_MODE_ANIMATED.equals(mode)) {
            return getString(app.multiai.mobile.R.string.emoji_mode_animated);
        }
        return getString(app.multiai.mobile.R.string.emoji_mode_system);
    }

    private String langLabel(String v) {
        return getString("en".equals(v)
                ? app.multiai.mobile.R.string.language_english
                : app.multiai.mobile.R.string.language_russian);
    }
}
