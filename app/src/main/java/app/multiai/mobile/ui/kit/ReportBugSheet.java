package app.multiai.mobile.ui.kit;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.ChatActivity;
import app.multiai.mobile.ui.ChatModeActivity;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppSettings;

/**
 * Shared «Сообщить об ошибке» bottom sheet.
 *
 * <p>Used both by the explicit «Сообщить об ошибке» row in settings and by the
 * shake-to-report gesture in {@code ChatActivity}/{@code ChatModeActivity}. Renders
 * a multiline problem description with a 2000-char counter and a submit button,
 * plus a toggle to disable shake-to-report.
 *
 * <p>Marked final with a single {@link #show(AppCompatActivity)} entry point so the
 * two call sites stay in sync.
 */
public final class ReportBugSheet {

    private ReportBugSheet() {}

    public static void show(AppCompatActivity host) {
        final AppSettings settings = new AppSettings(host);

        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView sub = new TextView(host);
        sub.setText(R.string.kit_report_bug_subtitle);
        sub.setTextColor(ContextCompat.getColor(host, R.color.text_primary));
        sub.setTextSize(14);
        sub.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = SettingsUI.dp(host, 14);
        sub.setLayoutParams(subLp);
        body.addView(sub);

        EditText input = new EditText(host);
        input.setHint(R.string.kit_report_bug_hint);
        input.setHintTextColor(ContextCompat.getColor(host, R.color.text_hint));
        input.setTextColor(ContextCompat.getColor(host, R.color.text_primary));
        input.setTextSize(15);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(5);
        GradientDrawable iBg = new GradientDrawable();
        iBg.setColor(ContextCompat.getColor(host, R.color.surface_tonal));
        iBg.setCornerRadius(SettingsUI.dp(host, 14));
        iBg.setStroke(SettingsUI.dp(host, 1), ContextCompat.getColor(host, R.color.stroke));
        input.setBackground(iBg);
        input.setPadding(SettingsUI.dp(host, 14), SettingsUI.dp(host, 12),
                SettingsUI.dp(host, 14), SettingsUI.dp(host, 12));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        AccentColor.applyCursorTint(input, AccentColor.resolve(host));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = SettingsUI.dp(host, 10);
        input.setLayoutParams(inLp);
        body.addView(input);

        TextView counter = new TextView(host);
        counter.setText("0 / 2000");
        counter.setTextColor(ContextCompat.getColor(host, R.color.text_hint));
        counter.setTextSize(12);
        counter.setGravity(Gravity.END);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = SettingsUI.dp(host, 4);
        counter.setLayoutParams(cLp);
        body.addView(counter);

        TextView disclaimer = new TextView(host);
        String linkWord = host.getString(R.string.kit_report_bug_disclaimer_link);
        SpannableString ss = new SpannableString(
                host.getString(R.string.kit_report_bug_disclaimer));
        int start = ss.length() - linkWord.length();
        ss.setSpan(new UnderlineSpan(), start, ss.length() - 1, 0);
        disclaimer.setText(ss);
        disclaimer.setTextColor(ContextCompat.getColor(host, R.color.text_secondary));
        disclaimer.setTextSize(12);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = SettingsUI.dp(host, 6);
        dLp.bottomMargin = SettingsUI.dp(host, 10);
        disclaimer.setLayoutParams(dLp);
        body.addView(disclaimer);

        // divider
        View div = new View(host);
        div.setBackgroundColor(ContextCompat.getColor(host, R.color.divider));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUI.dp(host, 1));
        divLp.bottomMargin = SettingsUI.dp(host, 6);
        div.setLayoutParams(divLp);
        body.addView(div);

        // Shake toggle row
        LinearLayout shakeRow = new LinearLayout(host);
        shakeRow.setOrientation(LinearLayout.HORIZONTAL);
        shakeRow.setGravity(Gravity.CENTER_VERTICAL);
        shakeRow.setPadding(0, SettingsUI.dp(host, 10), 0, SettingsUI.dp(host, 10));

        LinearLayout shakeCol = new LinearLayout(host);
        shakeCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams shakeColLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        shakeCol.setLayoutParams(shakeColLp);

        TextView shakeTitle = new TextView(host);
        shakeTitle.setText(R.string.kit_report_bug_shake_title);
        shakeTitle.setTextColor(ContextCompat.getColor(host, R.color.text_primary));
        shakeTitle.setTextSize(15);
        shakeCol.addView(shakeTitle);

        TextView shakeSub = new TextView(host);
        shakeSub.setText(R.string.kit_report_bug_shake_subtitle);
        shakeSub.setTextColor(ContextCompat.getColor(host, R.color.text_secondary));
        shakeSub.setTextSize(12);
        shakeCol.addView(shakeSub);

        shakeRow.addView(shakeCol);

        android.widget.CompoundButton sw =
                SettingsUI.createChatGptSwitch(host,
                        host.getString(R.string.kit_report_bug_shake_title),
                        settings.isShakeToReportEnabled());
        int accent = AccentColor.resolve(host);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            settings.setShakeToReportEnabled(isChecked);
            // Apply immediately to any live ShakeDetector so the change takes
            // effect without an Activity resume (the sheet stays open).
            if (host instanceof ChatModeActivity) {
                ((ChatModeActivity) host).applyShakeEnabled(isChecked);
            } else if (host instanceof ChatActivity) {
                ((ChatActivity) host).applyShakeEnabled(isChecked);
            }
        });
        shakeRow.addView(sw);
        body.addView(shakeRow);

        // Submit button (disabled initially)
        TextView submit = new TextView(host);
        submit.setText(R.string.send);
        submit.setTextColor(ContextCompat.getColor(host, R.color.control_disabled_text));
        submit.setTextSize(16);
        submit.setGravity(Gravity.CENTER);
        GradientDrawable sBg = new GradientDrawable();
        sBg.setColor(ContextCompat.getColor(host, R.color.control_disabled));
        sBg.setCornerRadius(SettingsUI.dp(host, 14));
        submit.setBackground(sBg);
        submit.setPadding(0, SettingsUI.dp(host, 14), 0, SettingsUI.dp(host, 14));
        submit.setEnabled(false);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = SettingsUI.dp(host, 14);
        submit.setLayoutParams(sLp);

        final Dialog sheet = ModernSheet.custom(host, host.getString(R.string.kit_report_bug_title), body);
        submit.setOnClickListener(v -> {
            if (!submit.isEnabled()) return;
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            // Реальная отправка на бэкенд (раньше кнопка была заглушкой:
            // только тост, без запроса — см. ревью 2026-09-16).
            submit.setEnabled(false);
            app.multiai.mobile.network.BackendApi.get().sendFeedback(
                    host, "bug_report", text, ok -> host.runOnUiThread(() -> {
                        ModernToast.show(host, host.getString(ok
                                ? R.string.kit_report_bug_submitted
                                : R.string.report_bug_failed));
                        sheet.dismiss();
                    }));
        });
        body.addView(submit);

        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                counter.setText(s.length() + " / 2000");
                boolean enabled = s.toString().trim().length() > 0;
                submit.setEnabled(enabled);
                sBg.setColor(enabled ? accent
                        : ContextCompat.getColor(host, R.color.control_disabled));
                submit.setTextColor(enabled ? AccentColor.contrastForeground(accent)
                        : ContextCompat.getColor(host, R.color.control_disabled_text));
                submit.setAlpha(enabled ? 1f : 0.82f);
            }
            public void afterTextChanged(android.text.Editable s) {}
        });
    }
}
