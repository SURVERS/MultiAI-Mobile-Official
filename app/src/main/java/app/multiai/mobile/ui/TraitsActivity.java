package app.multiai.mobile.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.settings.DropdownMenu;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AppSettings;

/**
 * Подэкран "Характеристики".
 * 4 карточки, каждая с dropdown справа (Более / По умолчанию / Менее).
 * Снизу — danger-кнопка "Сбросить характеристики".
 */
public class TraitsActivity extends AppCompatActivity {

    private AppSettings s;
    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        s = new AppSettings(this);

        SettingsUI.Scaffold sc = SettingsUI.createScaffold(this, getString(R.string.scr_traits_section), false);
        LinearLayout root = sc.content;

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);
        rebuildList();

        // ── Сбросить ──
        root.addView(SettingsUI.spacer(this, 16));
        LinearLayout resetCard = new LinearLayout(this);
        resetCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable resetBg = new GradientDrawable();
        resetBg.setColor(androidx.core.content.ContextCompat.getColor(this, R.color.error_bg));
        resetBg.setCornerRadius(SettingsUI.dp(this, 14));
        resetCard.setBackground(resetBg);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.leftMargin = SettingsUI.dp(this, 16);
        rLp.rightMargin = SettingsUI.dp(this, 16);
        resetCard.setLayoutParams(rLp);

        TextView reset = new TextView(this);
        reset.setText(getString(R.string.scr_reset_traits));
        reset.setTextColor(SettingsUI.colorDanger(this));
        reset.setTextSize(16);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, SettingsUI.dp(this, 14), 0, SettingsUI.dp(this, 14));
        reset.setClickable(true);
        reset.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        reset.setForeground(getDrawable(tv.resourceId));
        reset.setOnClickListener(v -> {
            s.clearAllTraits();
            s.setPersonalizationCustomTraits("");
            rebuildList();
        });
        resetCard.addView(reset);
        root.addView(resetCard);
    }

    private void rebuildList() {
        listContainer.removeAllViews();
        // Используем короткие названия из PersonalizationActivity.resolveTraitShortNames
        String[][] shortNames = PersonalizationActivity.resolveTraitShortNames(this);
        String[][] traits = PersonalizationActivity.resolveTraits(this);
        for (int i = 0; i < shortNames.length; i++) {
            if (i > 0) listContainer.addView(SettingsUI.spacer(this, 8));
            String[] shortName = shortNames[i];
            String[] fullTrait = traits[i];
            // [key, shortTitle, more_title, more_desc, less_title, less_desc]
            String[] merged = {
                    shortName[0], shortName[1],
                    fullTrait[1], fullTrait[2],
                    fullTrait[3], fullTrait[4]
            };
            listContainer.addView(buildTraitCard(merged));
        }

        String custom = s.getPersonalizationCustomTraits();
        if (!custom.isEmpty()) {
            for (String c : custom.split(",")) {
                if (c.trim().isEmpty()) continue;
                listContainer.addView(SettingsUI.spacer(this, 8));
                String[] custTrait = {"custom:" + c.trim(), c.trim(),
                        getString(R.string.scr_level_more), getString(R.string.scr_trait_custom_more_d),
                        getString(R.string.scr_level_less), getString(R.string.scr_trait_custom_less_d)};
                listContainer.addView(buildTraitCard(custTrait));
            }
        }
    }

    private View buildTraitCard(String[] trait) {
        final String key = trait[0];

        LinearLayout card = SettingsUI.card(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(getDrawable(tv.resourceId));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        col.setLayoutParams(colLp);

        TextView t = new TextView(this);
        t.setText(trait[1]);
        t.setTextColor(SettingsUI.colorText(this));
        t.setTextSize(16);
        col.addView(t);

        TextView sub = new TextView(this);
        sub.setText(levelLabel(s.getTraitLevel(key)));
        sub.setTextColor(SettingsUI.colorSubtext(this));
        sub.setTextSize(13);
        col.addView(sub);
        row.addView(col);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_down);
        chev.setColorFilter(SettingsUI.colorSubtext(this));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 18), SettingsUI.dp(this, 18));
        chLp.leftMargin = SettingsUI.dp(this, 8);
        chev.setLayoutParams(chLp);
        row.addView(chev);

        row.setOnClickListener(v -> {
            DropdownMenu.Item[] items = new DropdownMenu.Item[]{
                    new DropdownMenu.Item("more", trait[2], trait[3]),
                    new DropdownMenu.Item("default", getString(R.string.scr_level_default), null),
                    new DropdownMenu.Item("less", trait[4], trait[5]),
            };
            DropdownMenu.show(row, items, s.getTraitLevel(key), id -> {
                s.setTraitLevel(key, id);
                sub.setText(levelLabel(id));
            });
        });

        card.addView(row);
        return card;
    }

    private String levelLabel(String level) {
        switch (level) {
            case "more": return getString(R.string.scr_level_more);
            case "less": return getString(R.string.scr_level_less);
            default: return getString(R.string.scr_level_default);
        }
    }
}
