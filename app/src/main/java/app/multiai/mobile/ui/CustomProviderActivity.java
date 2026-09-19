package app.multiai.mobile.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.models.CustomProvider;
import app.multiai.mobile.network.CustomProviderClient;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.kit.MotionKit;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.CustomProviderStore;

/**
 * Экран управления кастомными AI-провайдерами.
 *
 * <p>Позволяет добавить собственный OpenAI-compatible или Anthropic-совместимый
 * провайдер с API-ключом и базовым URL, а затем редактировать уже добавленный
 * провайдер (имя, ключ, URL, тип, список моделей) — на PC IDE редактирования нет
 * (только добавление/удаление), это осознанное расширение по прямой просьбе.
 *
 * <p>Аналог SettingsCustomProvidersPanel из Desktop IDE.
 */
public class CustomProviderActivity extends AppCompatActivity {

    private LinearLayout providerList;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(this,
                getString(R.string.custom_provider_title), false);
        addAddButton(scaffold);
        LinearLayout content = scaffold.content;

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.custom_provider_subtitle);
        subtitle.setTextColor(SettingsUI.colorSubtext(this));
        subtitle.setTextSize(14);
        subtitle.setPadding(SettingsUI.dp(this, 20), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 20), SettingsUI.dp(this, 10));
        content.addView(subtitle);

        content.addView(SettingsUI.sectionLabel(this, getString(R.string.custom_provider_list_title)));

        providerList = new LinearLayout(this);
        providerList.setOrientation(LinearLayout.VERTICAL);
        content.addView(providerList);

        emptyView = new TextView(this);
        emptyView.setText(R.string.custom_provider_empty);
        emptyView.setTextColor(SettingsUI.colorHint(this));
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(SettingsUI.dp(this, 24), SettingsUI.dp(this, 32),
                SettingsUI.dp(this, 24), SettingsUI.dp(this, 16));
        content.addView(emptyView);

        renderProviders();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (providerList != null) renderProviders();
    }

    private void addAddButton(SettingsUI.Scaffold scaffold) {
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
        add.setContentDescription(getString(R.string.custom_provider_add));
        SettingsUI.attachPressAnimation(add, 0.9f);
        add.setOnClickListener(v -> showProviderSheet(null));
        toolbar.addView(add);
    }

    private void renderProviders() {
        List<CustomProvider> providers = CustomProviderStore.getAll(this);
        providerList.removeAllViews();
        emptyView.setVisibility(providers.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < providers.size(); i++) {
            if (i > 0) providerList.addView(SettingsUI.sectionGap(this));
            View card = providerCard(providers.get(i));
            providerList.addView(card);
            card.setAlpha(0f);
            card.setTranslationY(SettingsUI.dp(this, 12));
            card.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(i, 6) * 45L)
                    .setDuration(MotionKit.STANDARD)
                    .setInterpolator(MotionKit.STANDARD_EASING)
                    .start();
        }
    }

    private View providerCard(CustomProvider provider) {
        LinearLayout card = SettingsUI.card(this);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showProviderSheet(provider));
        MotionKit.attachPress(card, 0.99f);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 12), SettingsUI.dp(this, 12));

        ImageView icon = new ImageView(this);
        icon.setImageResource(provider.isAnthropic()
                ? R.drawable.ic_model_claude : R.drawable.ic_model_openai);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 28), SettingsUI.dp(this, 28));
        icon.setLayoutParams(iconLp);
        header.addView(icon);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = SettingsUI.dp(this, 12);
        textCol.setLayoutParams(tLp);

        TextView name = new TextView(this);
        name.setText(provider.displayName.isEmpty()
                ? provider.paradigmLabel() : provider.displayName);
        name.setTextColor(SettingsUI.colorText(this));
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(name);

        TextView url = new TextView(this);
        url.setText(provider.baseURL);
        url.setTextColor(SettingsUI.colorSubtext(this));
        url.setTextSize(12);
        url.setMaxLines(1);
        url.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(url);
        header.addView(textCol);

        TextView badge = new TextView(this);
        badge.setText(provider.paradigmLabel());
        badge.setTextSize(11);
        badge.setTextColor(SettingsUI.colorSubtext(this));
        badge.setPadding(SettingsUI.dp(this, 8), SettingsUI.dp(this, 3),
                SettingsUI.dp(this, 8), SettingsUI.dp(this, 3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(SettingsUI.dp(this, 8));
        badgeBg.setColor(SettingsUI.colorSurfaceTonal(this));
        badgeBg.setStroke(1, SettingsUI.colorStroke(this));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams bdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bdLp.rightMargin = SettingsUI.dp(this, 8);
        badge.setLayoutParams(bdLp);
        header.addView(badge);

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setColorFilter(SettingsUI.colorMutedIcon(this));
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 18), SettingsUI.dp(this, 18));
        header.addView(chevron, chevLp);

        card.addView(header);
        card.addView(SettingsUI.divider(this));

        LinearLayout modelsSection = new LinearLayout(this);
        modelsSection.setOrientation(LinearLayout.VERTICAL);
        modelsSection.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 8),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 12));
        modelsSection.setClickable(false);
        card.addView(modelsSection);

        TextView modelsLabel = new TextView(this);
        modelsLabel.setText(getString(R.string.custom_provider_models) + " (" + provider.models.size() + ")");
        modelsLabel.setTextColor(SettingsUI.colorSubtext(this));
        modelsLabel.setTextSize(12);
        modelsLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        modelsSection.addView(modelsLabel);

        // Manual model ID input + add button
        LinearLayout manualModelRow = new LinearLayout(this);
        manualModelRow.setOrientation(LinearLayout.HORIZONTAL);
        manualModelRow.setGravity(Gravity.CENTER_VERTICAL);
        manualModelRow.setPadding(0, SettingsUI.dp(this, 8), 0, 0);

        EditText modelIdInput = styledInput(getString(R.string.custom_provider_model_hint));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.rightMargin = SettingsUI.dp(this, 8);
        manualModelRow.addView(modelIdInput, inputLp);

        TextView addModelBtn = new TextView(this);
        addModelBtn.setText(R.string.custom_provider_add_model);
        addModelBtn.setTextColor(AccentColor.contrastForeground(this));
        addModelBtn.setTextSize(13);
        addModelBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        addModelBtn.setGravity(Gravity.CENTER);
        addModelBtn.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 8),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 8));
        GradientDrawable addBg = new GradientDrawable();
        addBg.setCornerRadius(SettingsUI.dp(this, 10));
        addBg.setColor(AccentColor.resolve(this));
        addModelBtn.setBackground(addBg);
        addModelBtn.setClickable(true);
        addModelBtn.setFocusable(true);
        addModelBtn.setOnClickListener(v -> {
            String modelId = modelIdInput.getText().toString().trim();
            if (modelId.isEmpty()) return;
            boolean alreadyExists = false;
            for (CustomProvider.ProviderModel pm : provider.models) {
                if (modelId.equals(pm.requestName)) {
                    alreadyExists = true;
                    break;
                }
            }
            if (!alreadyExists) {
                CustomProvider.ProviderModel manual = new CustomProvider.ProviderModel(
                        modelId, modelId, 128000, 4096, null, null, null, 0, 0, 0);
                provider.models.add(manual);
                CustomProviderStore.save(this, provider);
                renderProviders();
                modelIdInput.setText("");
                ModernToast.show(this, getString(R.string.custom_provider_model_added, modelId));
            } else {
                ModernToast.show(this, getString(R.string.custom_provider_model_exists));
            }
        });
        manualModelRow.addView(addModelBtn);
        modelsSection.addView(manualModelRow);

        if (provider.models.isEmpty()) {
            TextView noModels = new TextView(this);
            noModels.setText(R.string.custom_provider_no_models);
            noModels.setTextColor(SettingsUI.colorHint(this));
            noModels.setTextSize(12);
            noModels.setPadding(0, SettingsUI.dp(this, 4), 0, 0);
            modelsSection.addView(noModels);
        } else {
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(0, SettingsUI.dp(this, 6), 0, 0);
            int shown = Math.min(provider.models.size(), 6);
            for (int i = 0; i < shown; i++) {
                CustomProvider.ProviderModel model = provider.models.get(i);
                View chip = modelChip(model, provider);
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cLp.rightMargin = SettingsUI.dp(this, 6);
                chip.setLayoutParams(cLp);
                chips.addView(chip);
            }
            if (provider.models.size() > shown) {
                // Вместо «+N» — кликабельная кнопка, открывающая sheet со всеми
                // моделями; оттуда можно удалить любую, а не только видимые чипы.
                View more = staticChip("+" + (provider.models.size() - shown));
                more.setClickable(true);
                more.setFocusable(true);
                more.setOnClickListener(v -> showAllModelsSheet(provider));
                chips.addView(more);
            }
            scroll.addView(chips);
            modelsSection.addView(scroll);
        }

        return card;
    }

    /**
     * Sheet со всеми моделями провайдера (дизайн — системный ModernSheet).
     * Любую модель можно удалить, а не только те, что влезли в чипы.
     */
    private void showAllModelsSheet(CustomProvider provider) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            if (provider.models.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText(R.string.custom_provider_no_models);
                empty.setTextColor(SettingsUI.colorHint(this));
                empty.setTextSize(13);
                empty.setPadding(0, SettingsUI.dp(this, 12), 0, SettingsUI.dp(this, 12));
                list.addView(empty);
                return;
            }
            for (int i = 0; i < provider.models.size(); i++) {
                CustomProvider.ProviderModel model = provider.models.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(SettingsUI.dp(this, 48));
                row.setPadding(0, SettingsUI.dp(this, 6), 0, SettingsUI.dp(this, 6));

                TextView name = new TextView(this);
                name.setText(model.displayName == null || model.displayName.isEmpty()
                        ? model.requestName : model.displayName);
                name.setTextColor(SettingsUI.colorText(this));
                name.setTextSize(14);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                ImageView delete = new ImageView(this);
                delete.setImageResource(R.drawable.ic_close);
                delete.setColorFilter(SettingsUI.colorMutedIcon(this));
                int dp8 = SettingsUI.dp(this, 8);
                delete.setPadding(dp8, dp8, dp8, dp8);
                delete.setContentDescription(getString(R.string.custom_provider_model_deleted));
                delete.setOnClickListener(v -> {
                    provider.models.remove(model);
                    CustomProviderStore.save(this, provider);
                    rebuild[0].run();
                    renderProviders();
                    ModernToast.show(this, getString(R.string.custom_provider_model_deleted));
                });
                row.addView(delete, new LinearLayout.LayoutParams(
                        SettingsUI.dp(this, 36), SettingsUI.dp(this, 36)));
                list.addView(row);
            }
        };
        rebuild[0].run();

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Список не выше ~половины экрана
        int maxListHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.45);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight));
        scroll.post(() -> {
            int measured = list.getHeight() + SettingsUI.dp(this, 8);
            scroll.getLayoutParams().height = Math.min(measured, maxListHeight);
            scroll.requestLayout();
        });

        String title = getString(R.string.custom_provider_models) + " (" + provider.models.size() + ")";
        app.multiai.mobile.ui.kit.ModernSheet.custom(this, title, scroll, true);
    }

    private View modelChip(CustomProvider.ProviderModel model, CustomProvider provider) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(SettingsUI.dp(this, 10), SettingsUI.dp(this, 5),
                SettingsUI.dp(this, 6), SettingsUI.dp(this, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(SettingsUI.dp(this, 12));
        bg.setColor(SettingsUI.colorSurfaceTonal(this));
        bg.setStroke(1, SettingsUI.colorStroke(this));
        chip.setBackground(bg);

        TextView label = new TextView(this);
        label.setText(model.displayName == null || model.displayName.isEmpty()
                ? model.requestName : model.displayName);
        label.setTextColor(SettingsUI.colorText(this));
        label.setTextSize(12);
        chip.addView(label);

        // Delete button (only for manually-added models — those with no info from provider)
        boolean isManual = model.contextWindow == 128000 && model.maxOutputTokens == 4096
                && model.priceInput == 0 && model.priceOutput == 0;
        if (isManual) {
            TextView delete = new TextView(this);
            delete.setText("✕");
            delete.setTextColor(SettingsUI.colorHint(this));
            delete.setTextSize(12);
            delete.setPadding(SettingsUI.dp(this, 4), 0, 0, 0);
            delete.setClickable(true);
            delete.setFocusable(true);
            delete.setOnClickListener(v -> {
                provider.models.remove(model);
                CustomProviderStore.save(this, provider);
                renderProviders();
                ModernToast.show(this, getString(R.string.custom_provider_model_deleted));
            });
            chip.addView(delete);
        }
        return chip;
    }

    private View staticChip(String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextColor(SettingsUI.colorText(this));
        chip.setTextSize(12);
        chip.setPadding(SettingsUI.dp(this, 10), SettingsUI.dp(this, 5),
                SettingsUI.dp(this, 10), SettingsUI.dp(this, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(SettingsUI.dp(this, 12));
        bg.setColor(SettingsUI.colorSurfaceTonal(this));
        bg.setStroke(1, SettingsUI.colorStroke(this));
        chip.setBackground(bg);
        return chip;
    }

    // ═══════════ Sheet добавления / редактирования провайдера ═══════════

    /** {@code existing == null} → режим добавления; иначе редактирование на месте. */
    private void showProviderSheet(CustomProvider existing) {
        boolean editing = existing != null;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsUI.dp(this, 16);
        body.setPadding(pad, 0, pad, pad);

        final String[] selectedParadigm = {editing ? existing.paradigm : CustomProvider.PARADIGM_OPENAI};

        body.addView(label(getString(R.string.custom_provider_kind)));
        LinearLayout typeRow = buildTypeSegment(selectedParadigm, editing);
        LinearLayout.LayoutParams typeRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        typeRowLp.bottomMargin = SettingsUI.dp(this, 4);
        body.addView(typeRow, typeRowLp);
        body.addView(SettingsUI.spacer(this, 12));

        body.addView(label(getString(R.string.custom_provider_name)));
        EditText nameInput = styledInput(getString(R.string.custom_provider_name_hint));
        if (editing) nameInput.setText(existing.displayName);
        body.addView(nameInput);
        body.addView(SettingsUI.spacer(this, 12));

        body.addView(label(getString(R.string.custom_provider_api_key)));
        EditText keyInput = styledInput("sk-…");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (editing) keyInput.setText(existing.apiKey);
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams keyInputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        keyRow.addView(keyInput, keyInputLp);
        ImageView toggleKeyVisibility = new ImageView(this);
        toggleKeyVisibility.setImageResource(R.drawable.ic_eye);
        toggleKeyVisibility.setColorFilter(SettingsUI.colorMutedIcon(this));
        toggleKeyVisibility.setPadding(SettingsUI.dp(this, 8), SettingsUI.dp(this, 8),
                SettingsUI.dp(this, 8), SettingsUI.dp(this, 8));
        toggleKeyVisibility.setContentDescription(getString(R.string.custom_provider_show_key));
        final boolean[] keyVisible = {false};
        toggleKeyVisibility.setOnClickListener(v -> {
            keyVisible[0] = !keyVisible[0];
            keyInput.setInputType(InputType.TYPE_CLASS_TEXT | (keyVisible[0]
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            keyInput.setSelection(keyInput.getText().length());
            toggleKeyVisibility.setImageResource(keyVisible[0] ? R.drawable.ic_eye_off : R.drawable.ic_eye);
            toggleKeyVisibility.setContentDescription(getString(keyVisible[0]
                    ? R.string.custom_provider_hide_key : R.string.custom_provider_show_key));
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 36), SettingsUI.dp(this, 36));
        toggleLp.leftMargin = SettingsUI.dp(this, 6);
        keyRow.addView(toggleKeyVisibility, toggleLp);
        body.addView(keyRow);
        body.addView(SettingsUI.spacer(this, 12));

        body.addView(label(getString(R.string.custom_provider_base_url)));
        EditText urlInput = styledInput("https://api.example.com");
        if (editing) urlInput.setText(existing.baseURL);
        body.addView(urlInput);
        body.addView(SettingsUI.spacer(this, 6));

        TextView urlNote = new TextView(this);
        urlNote.setText(R.string.custom_provider_auto_fetch_note);
        urlNote.setTextColor(SettingsUI.colorHint(this));
        urlNote.setTextSize(12);
        body.addView(urlNote);

        body.addView(SettingsUI.spacer(this, 12));

        if (editing) {
            body.addView(SettingsUI.spacer(this, 14));
            TextView reload = actionRow(getString(R.string.custom_provider_reload_models), false);
            reload.setOnClickListener(v -> reloadModels(existing, nameInput, keyInput, urlInput, selectedParadigm[0], reload));
            body.addView(reload);
        }

        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        dialogRef[0] = ModernSheet.custom(this,
                getString(editing ? R.string.custom_provider_edit_title : R.string.custom_provider_add_title),
                body, true);

        body.addView(SettingsUI.spacer(this, 16));
        TextView saveBtn = new TextView(this);
        saveBtn.setText(getString(editing ? R.string.custom_provider_save : R.string.custom_provider_add));
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setTextColor(AccentColor.contrastForeground(this));
        saveBtn.setTextSize(15);
        saveBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setCornerRadius(SettingsUI.dp(this, 14));
        saveBg.setColor(AccentColor.resolve(this));
        saveBtn.setBackground(saveBg);
        saveBtn.setPadding(0, SettingsUI.dp(this, 14), 0, SettingsUI.dp(this, 14));
        saveBtn.setClickable(true);
        saveBtn.setFocusable(true);
        MotionKit.attachPress(saveBtn, 0.97f);
        saveBtn.setOnClickListener(v -> {
            String key = keyInput.getText().toString().trim();
            String baseUrl = urlInput.getText().toString().trim();
            String pName = nameInput.getText().toString().trim();
            if (key.isEmpty()) {
                ModernToast.show(this, getString(R.string.custom_provider_error_key));
                return;
            }
            if (baseUrl.isEmpty()) {
                ModernToast.show(this, getString(R.string.custom_provider_error_url));
                return;
            }
            try { new java.net.URL(baseUrl); } catch (Exception e) {
                ModernToast.show(this, getString(R.string.custom_provider_error_url));
                return;
            }
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            if (editing) {
                existing.displayName = pName;
                existing.paradigm = selectedParadigm[0];
                existing.apiKey = key;
                existing.baseURL = baseUrl;
                CustomProviderStore.save(this, existing);
                renderProviders();
                ModernToast.show(this, getString(R.string.custom_provider_updated));
            } else {
                doAddProvider(pName, selectedParadigm[0], key, baseUrl, "");
            }
        });
        body.addView(saveBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (editing) {
            body.addView(SettingsUI.spacer(this, 10));
            TextView deleteBtn = actionRow(getString(R.string.custom_provider_delete_title), true);
            deleteBtn.setOnClickListener(v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                confirmDelete(existing);
            });
            body.addView(deleteBtn);
        }
    }

    /** Реальный двухсегментный переключатель OpenAI/Anthropic — тап сразу переключает, без промежуточного меню. */
    private LinearLayout buildTypeSegment(String[] selectedRef, boolean editing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.segmented_pill_bg));
        int pad3 = SettingsUI.dp(this, 3);
        row.setPadding(pad3, pad3, pad3, pad3);

        TextView openAiSeg = segmentLabel(getString(R.string.custom_provider_type_openai));
        TextView anthropicSeg = segmentLabel(getString(R.string.custom_provider_type_anthropic));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(openAiSeg, segLp);
        row.addView(anthropicSeg, segLp);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            boolean anthropic = CustomProvider.PARADIGM_ANTHROPIC.equals(selectedRef[0]);
            styleSegment(openAiSeg, !anthropic);
            styleSegment(anthropicSeg, anthropic);
        };
        openAiSeg.setOnClickListener(v -> {
            selectedRef[0] = CustomProvider.PARADIGM_OPENAI;
            refresh[0].run();
        });
        anthropicSeg.setOnClickListener(v -> {
            selectedRef[0] = CustomProvider.PARADIGM_ANTHROPIC;
            refresh[0].run();
        });
        refresh[0].run();
        return row;
    }

    private TextView segmentLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setMinHeight(SettingsUI.dp(this, 40));
        tv.setPadding(SettingsUI.dp(this, 8), 0, SettingsUI.dp(this, 8), 0);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private void styleSegment(TextView seg, boolean active) {
        seg.animate().cancel();
        if (active) {
            seg.setBackground(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.segmented_pill_active_bg));
            seg.setTextColor(SettingsUI.colorText(this));
            seg.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            seg.setBackground(null);
            seg.setTextColor(SettingsUI.colorHint(this));
            seg.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        seg.setScaleX(0.97f);
        seg.setScaleY(0.97f);
        seg.animate().scaleX(1f).scaleY(1f)
                .setDuration(MotionKit.FAST)
                .setInterpolator(MotionKit.STANDARD_EASING)
                .start();
    }

    private TextView actionRow(String text, boolean destructive) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(destructive ? SettingsUI.colorDanger(this) : AccentColor.resolve(this));
        tv.setPadding(0, SettingsUI.dp(this, 12), 0, SettingsUI.dp(this, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(SettingsUI.dp(this, 12));
        bg.setColor(SettingsUI.colorSurfaceTonal(this));
        bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        tv.setBackground(bg);
        tv.setClickable(true);
        tv.setFocusable(true);
        MotionKit.attachPress(tv, 0.97f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** Перезагружает список моделей провайдера, используя текущие (возможно ещё не сохранённые) поля формы. */
    private void reloadModels(CustomProvider provider, EditText nameInput, EditText keyInput,
                              EditText urlInput, String paradigm, TextView triggerLabel) {
        String key = keyInput.getText().toString().trim();
        String baseUrl = urlInput.getText().toString().trim();
        if (key.isEmpty() || baseUrl.isEmpty()) {
            ModernToast.show(this, getString(R.string.custom_provider_error_url));
            return;
        }
        CustomProvider probe = new CustomProvider();
        probe.paradigm = paradigm;
        probe.apiKey = key;
        probe.baseURL = baseUrl;
        String original = triggerLabel.getText().toString();
        triggerLabel.setText(getString(R.string.custom_provider_reloading));
        triggerLabel.setEnabled(false);
        new Thread(() -> {
            List<CustomProviderClient.FetchedModel> fetched = CustomProviderClient.fetchModels(probe);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                triggerLabel.setEnabled(true);
                triggerLabel.setText(original);
                if (fetched.isEmpty()) {
                    ModernToast.show(this, getString(R.string.custom_provider_reload_empty));
                    return;
                }
                provider.models.clear();
                for (CustomProviderClient.FetchedModel m : fetched) {
                    CustomProvider.ProviderModel pm = new CustomProvider.ProviderModel(m.id, m.displayName,
                            m.contextWindow, m.maxOutputTokens, m.inputModalities,
                            m.reasoningLevels, m.reasoningDefault,
                            m.priceInput, m.priceOutput, m.priceCached,
                            m.hasPricing, true, m.free);
                    pm.kind = m.kind;
                    provider.models.add(pm);
                }
                provider.apiKey = key;
                provider.baseURL = baseUrl;
                provider.paradigm = paradigm;
                String name = nameInput.getText().toString().trim();
                if (!name.isEmpty()) provider.displayName = name;
                CustomProviderStore.save(this, provider);
                renderProviders();
                ModernToast.show(this, getString(R.string.custom_provider_reload_done, fetched.size()));
            });
        }, "custom-provider-reload").start();
    }

    private void confirmDelete(CustomProvider provider) {
        ModernDialog.show(this,
                getString(R.string.custom_provider_delete_title),
                getString(R.string.custom_provider_delete_message, provider.displayName),
                getString(android.R.string.cancel),
                getString(R.string.action_delete),
                true,
                () -> {
                    CustomProviderStore.delete(this, provider.id);
                    renderProviders();
                    ModernToast.show(this, getString(R.string.custom_provider_deleted));
                });
    }

    /** Создаёт провайдер, автоматически подгружает модели в фоне. */
    private void doAddProvider(String name, String paradigm, String apiKey, String baseURL, String manualModelId) {
        ModernToast.show(this, getString(R.string.custom_provider_fetching));
        CustomProvider provider = new CustomProvider();
        provider.displayName = name.isEmpty() ? (CustomProvider.PARADIGM_ANTHROPIC.equals(paradigm)
                ? "Anthropic" : "OpenAI Compatible") : name;
        provider.paradigm = paradigm;
        provider.apiKey = apiKey;
        provider.baseURL = baseURL;

        new Thread(() -> {
            List<CustomProviderClient.FetchedModel> fetched =
                    CustomProviderClient.fetchModels(provider);
            for (CustomProviderClient.FetchedModel m : fetched) {
                CustomProvider.ProviderModel pm = new CustomProvider.ProviderModel(m.id, m.displayName,
                        m.contextWindow, m.maxOutputTokens, m.inputModalities,
                        m.reasoningLevels, m.reasoningDefault,
                        m.priceInput, m.priceOutput, m.priceCached);
                pm.kind = m.kind;
                provider.models.add(pm);
            }
            // If manual model ID provided and not already fetched — add it
            if (manualModelId != null && !manualModelId.trim().isEmpty()) {
                String trimmedId = manualModelId.trim();
                boolean alreadyExists = false;
                for (CustomProvider.ProviderModel pm : provider.models) {
                    if (trimmedId.equals(pm.requestName)) {
                        alreadyExists = true;
                        break;
                    }
                }
                if (!alreadyExists) {
                    CustomProvider.ProviderModel manual = new CustomProvider.ProviderModel(
                            trimmedId, trimmedId, 128000, 4096, null, null, null, 0, 0, 0);
                    provider.models.add(manual);
                }
            }
            CustomProviderStore.save(this, provider);
            runOnUiThread(() -> {
                renderProviders();
                ModernToast.show(this, fetched.isEmpty()
                        ? getString(R.string.custom_provider_added_no_models)
                        : getString(R.string.custom_provider_added, fetched.size()));
            });
        }, "custom-provider-fetch").start();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(SettingsUI.colorSubtext(this));
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(SettingsUI.dp(this, 2), 0, 0, SettingsUI.dp(this, 4));
        return tv;
    }

    private EditText styledInput(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setHintTextColor(SettingsUI.colorHint(this));
        input.setTextColor(SettingsUI.colorText(this));
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 12),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 12));
        AccentColor.applyCursorTint(input, AccentColor.resolve(this));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsUI.colorSurfaceTonal(this));
        bg.setCornerRadius(SettingsUI.dp(this, 12));
        bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        input.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(lp);
        return input;
    }
}
