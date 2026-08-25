package app.multiai.mobile.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppSettings;

public class MemorySettingsActivity extends AppCompatActivity {

    private AppSettings s;
    private EditText nickInput;
    private EditText profInput;
    private EditText aboutInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        s = new AppSettings(this);

        SettingsUI.Scaffold sc = SettingsUI.createScaffold(this, getString(R.string.scr_memory_title), true);
        if (sc.saveButton != null) sc.saveButton.setOnClickListener(v -> {
            save();
            finish();
        });

        LinearLayout root = sc.content;

        // ── Управление памятью ──
        LinearLayout c1 = SettingsUI.card(this);
        c1.addView(SettingsUI.row(this, 0, getString(R.string.scr_memory_manage), null,
                () -> startActivity(new Intent(this, SavedMemoriesActivity.class))));
        root.addView(c1);

        // ── Toggles ──
        root.addView(SettingsUI.spacer(this, 16));
        LinearLayout tcard = SettingsUI.card(this);
        tcard.addView(SettingsUI.rowToggle(this, 0, getString(R.string.scr_memory_ref_title),
                getString(R.string.scr_memory_ref_desc),
                s.isMemoryReferenceEnabled(),
                c -> s.setMemoryReferenceEnabled(c)));
        tcard.addView(SettingsUI.divider(this));
        tcard.addView(SettingsUI.rowToggle(this, 0, getString(R.string.scr_memory_chat_title),
                getString(R.string.scr_memory_chat_desc),
                s.isMemoryChatHistoryEnabled(),
                c -> s.setMemoryChatHistoryEnabled(c)));
        root.addView(tcard);

        // ── Поля профиля ──
        root.addView(SettingsUI.sectionLabel(this, getString(R.string.scr_memory_nick)));
        nickInput = buildInput(s.getNickname(), getString(R.string.scr_memory_nick_hint));
        root.addView(nickInput);

        root.addView(SettingsUI.sectionLabel(this, getString(R.string.scr_memory_prof)));
        profInput = buildInput(s.getProfession(), getString(R.string.scr_memory_prof_hint));
        root.addView(profInput);

        root.addView(SettingsUI.sectionLabel(this, getString(R.string.scr_memory_about)));
        aboutInput = buildInput(s.getAbout(),
                getString(R.string.scr_memory_about_hint));
        aboutInput.setMinLines(4);
        aboutInput.setGravity(Gravity.TOP | Gravity.START);
        root.addView(aboutInput);
    }

    private EditText buildInput(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        // Hint blends toward the accent color at reduced opacity for a modern,
        // theme-aware placeholder that follows the user-chosen accent.
        int accent = SettingsUI.colorAccent(this);
        e.setHintTextColor((accent & 0x00FFFFFF) | 0x66000000);
        e.setTextColor(SettingsUI.colorText(this));
        e.setTextSize(15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsUI.colorCard(this));
        bg.setCornerRadius(SettingsUI.dp(this, 14));
        // Theme-aware hairline stroke so the field reads as an input on both
        // light and dark surfaces (matches the card stroke in SettingsUI.card).
        bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        e.setBackground(bg);
        // Accent-tinted caret, consistent with chat inputs (AccentColor helper).
        AccentColor.applyCursorTint(e, accent);
        e.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 12),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = SettingsUI.dp(this, 16);
        lp.rightMargin = SettingsUI.dp(this, 16);
        e.setLayoutParams(lp);
        return e;
    }

    private void save() {
        s.setNickname(nickInput.getText().toString());
        s.setProfession(profInput.getText().toString());
        s.setAbout(aboutInput.getText().toString());
        ModernToast.show(this, getString(R.string.scr_saved));
    }
}
