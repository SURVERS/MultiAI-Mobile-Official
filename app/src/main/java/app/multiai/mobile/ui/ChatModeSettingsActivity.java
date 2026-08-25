package app.multiai.mobile.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;

import java.util.Arrays;

import app.multiai.mobile.R;
import app.multiai.mobile.network.oauth.OAuthLoginController;
import app.multiai.mobile.network.oauth.OAuthStore;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernMenu;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.kit.ReportBugSheet;
import app.multiai.mobile.ui.kit.UiKit;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppSettings;

/**
 * ChatGPT-style Settings экран (главная).
 * Группы: Мой MultiAI (Персонализация / Память) → Учётная запись (Рабочая / Email)
 *   → Внешний вид (Тема / Акцент) → Общее / Данные / Безопасность / Сообщить / Информация → Выход
 */
public class ChatModeSettingsActivity extends AppCompatActivity {

    private static final String PREFS = "multiai_profile";
    private static final String AVATAR_FILE = "user_avatar.jpg";

    private TextView workspaceValue;
    private TextView themeValue;
    private TextView emailValue;
    private ImageView avatarView;
    private TextView nameTv;
    private boolean profileReceiverRegistered;
    private final BroadcastReceiver profileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshProfileHeader();
        }
    };

    private final androidx.activity.result.ActivityResultLauncher<Intent> pickAvatarLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null
                                && result.getData().getData() != null) {
                            saveAvatar(result.getData().getData());
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(this, getString(R.string.settings_home_title), false);
        LinearLayout root = scaffold.content;
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(220).start();

        // ── Профиль из OAuth store (name/email/telegram/аватар сервера) ──
        app.multiai.mobile.network.oauth.OAuthStore store =
                new app.multiai.mobile.network.oauth.OAuthStore(this);
        String oauthName = store.getName();
        boolean isTelegram = store.isTelegram();
        String oauthEmail = store.getEmail();
        String tgUsername = store.getPreferredUsername();
        String serverAvatar = store.getPicture();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String display = prefs.getString("display_name", null);
        String userName = (display != null && !display.isEmpty()) ? display
                : (oauthName != null && !oauthName.isEmpty() ? oauthName : "User");
        String localAvatar = prefs.getString("avatar_uri", null);
        String avatarUrl = (localAvatar != null && !localAvatar.isEmpty()) ? localAvatar : serverAvatar;

        // Контакт: Telegram юзернейм (иконка тг) или email
        boolean showTelegram = isTelegram && tgUsername != null && !tgUsername.isEmpty();
        String contact = showTelegram ? tgUsername
                : (oauthEmail != null && !oauthEmail.isEmpty() ? oauthEmail : null);

        // ── Profile header (аватар + имя) ──
        root.addView(buildProfileHeader(userName, avatarUrl));

        // ── Мой MultiAI ──
        LinearLayout myCard = SettingsUI.card(this);
        myCard.addView(SettingsUI.row(this, R.drawable.ic_s_sparkle, getString(R.string.personalization), null, () ->
                openActivity(PersonalizationActivity.class)));
        myCard.addView(SettingsUI.divider(this));
        myCard.addView(SettingsUI.row(this, R.drawable.ic_s_memory, getString(R.string.memory), null, () ->
                openActivity(MemorySettingsActivity.class)));
        myCard.addView(SettingsUI.divider(this));
        myCard.addView(SettingsUI.row(this, R.drawable.ic_s_sparkle, getString(R.string.subagents_title),
                getString(R.string.subagents_subtitle), () -> openActivity(SubagentsActivity.class)));
        myCard.addView(SettingsUI.divider(this));
        myCard.addView(SettingsUI.row(this, R.drawable.ic_volume, getString(R.string.voice_title),
                getString(R.string.voice_subtitle), () -> openActivity(VoiceSettingsActivity.class)));
        root.addView(myCard);

        // ── Учётная запись ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout accountCard = SettingsUI.card(this);
        workspaceValue = new TextView(this);
        accountCard.addView(buildWorkspaceRow());
        accountCard.addView(SettingsUI.divider(this));
        String contactLabel = showTelegram ? "Telegram" : getString(R.string.email);
        String contactValue = contact != null ? contact : "—";
        // Telegram-аккаунт: ведущая иконка строки — самолётик, без дублирующей compound-иконки.
        View contactRow = SettingsUI.row(this,
                showTelegram ? R.drawable.ic_telegram : R.drawable.ic_s_mail,
                contactLabel, contactValue, null);
        accountCard.addView(contactRow);
        root.addView(accountCard);

        // ── Внешний вид ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout appearCard = SettingsUI.card(this);
        appearCard.addView(buildAccentRow());
        appearCard.addView(SettingsUI.divider(this));
        appearCard.addView(buildThemeRow());
        root.addView(appearCard);

        // ── Общее / Данные / Безопасность / Сообщить / Информация ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout generalCard = SettingsUI.card(this);
        generalCard.addView(SettingsUI.row(this, R.drawable.ic_s_gear, getString(R.string.general_title), null, () ->
                openActivity(GeneralSettingsActivity.class)));
        generalCard.addView(SettingsUI.divider(this));
        generalCard.addView(SettingsUI.row(this, R.drawable.ic_s_database, getString(R.string.data_controls), null, () ->
                openActivity(DataControlsActivity.class)));
        generalCard.addView(SettingsUI.divider(this));
        generalCard.addView(SettingsUI.row(this, R.drawable.ic_s_shield, getString(R.string.security), null, () ->
                openActivity(SecuritySettingsActivity.class)));
        generalCard.addView(SettingsUI.divider(this));
        generalCard.addView(SettingsUI.row(this, R.drawable.ic_s_bug, getString(R.string.report_bug), null,
                this::showReportBugSheet));
        generalCard.addView(SettingsUI.divider(this));
        generalCard.addView(SettingsUI.row(this, R.drawable.ic_s_info, getString(R.string.about), null, () ->
                openActivity(AboutActivity.class)));
        root.addView(generalCard);

        // ── Выход ──
        root.addView(SettingsUI.sectionGap(this));
        LinearLayout logoutCard = SettingsUI.card(this);
        logoutCard.addView(buildLogoutRow());
        root.addView(logoutCard);
    }

    /** Кнопка выхода с иконкой logout + красный текст */
    private View buildLogoutRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 16),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 16));
        row.setClickable(true);
        row.setFocusable(true);
        android.content.res.ColorStateList csl = new android.content.res.ColorStateList(
                new int[][]{new int[]{}}, new int[]{SettingsUI.colorRipple(this)});
        row.setBackground(new android.graphics.drawable.RippleDrawable(csl, null, null));
        row.setOnClickListener(v -> confirmLogout());

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_s_logout);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 24), SettingsUI.dp(this, 24));
        icon.setLayoutParams(iLp);
        row.addView(icon);

        TextView t = new TextView(this);
        t.setText(R.string.logout);
        t.setTextColor(SettingsUI.colorDanger(this));
        t.setTextSize(16);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tLp.leftMargin = SettingsUI.dp(this, 14);
        t.setLayoutParams(tLp);
        row.addView(t);
        return row;
    }

    // ─────────── PROFILE HEADER ───────────

    private View buildProfileHeader(String userName, String photoUrl) {
        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.VERTICAL);
        profile.setGravity(Gravity.CENTER_HORIZONTAL);
        profile.setPadding(0, SettingsUI.dp(this, 8), 0, SettingsUI.dp(this, 24));

        FrameLayout avatarFrame = new FrameLayout(this);
        LinearLayout.LayoutParams afLp = new LinearLayout.LayoutParams(
                SettingsUI.dp(this, 72), SettingsUI.dp(this, 72));
        afLp.gravity = Gravity.CENTER_HORIZONTAL;
        avatarFrame.setLayoutParams(afLp);

        avatarView = new ImageView(this);
        avatarView.setImageResource(R.drawable.chat_mode_ic_user);
        avatarView.setColorFilter(ContextCompat.getColor(this, R.color.icon_muted));
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        avatarView.setBackground(bg);
        avatarView.setClipToOutline(true);
        avatarView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatarFrame.addView(avatarView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (photoUrl != null && !photoUrl.trim().isEmpty()) {
            avatarView.clearColorFilter();
            avatarView.setPadding(0, 0, 0, 0);
            Glide.with(this).load(photoUrl).transform(new CircleCrop())
                    .placeholder(R.drawable.chat_mode_ic_user)
                    .error(R.drawable.chat_mode_ic_user).into(avatarView);
        }

        // Плашка редактирования: тап по аватару = выбор из галереи
        ImageView edit = new ImageView(this);
        edit.setImageResource(R.drawable.ic_photo);
        edit.setColorFilter(ContextCompat.getColor(this, R.color.icon));
        GradientDrawable editBg = new GradientDrawable();
        editBg.setShape(GradientDrawable.OVAL);
        editBg.setColor(ContextCompat.getColor(this, R.color.surface_elevated));
        editBg.setStroke(SettingsUI.dp(this, 1), ContextCompat.getColor(this, R.color.stroke));
        edit.setBackground(editBg);
        edit.setPadding(SettingsUI.dp(this, 4), SettingsUI.dp(this, 4),
                SettingsUI.dp(this, 4), SettingsUI.dp(this, 4));
        FrameLayout.LayoutParams eLp = new FrameLayout.LayoutParams(
                SettingsUI.dp(this, 24), SettingsUI.dp(this, 24));
        eLp.gravity = Gravity.BOTTOM | Gravity.END;
        edit.setLayoutParams(eLp);
        avatarFrame.addView(edit);

        profile.addView(avatarFrame);

        nameTv = new TextView(this);
        nameTv.setText(userName);
        nameTv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        nameTv.setTextSize(18);
        nameTv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = SettingsUI.dp(this, 12);
        nLp.gravity = Gravity.CENTER_HORIZONTAL;
        nameTv.setLayoutParams(nLp);
        profile.addView(nameTv);

        View.OnClickListener editName = v -> showNameDialog(nameTv);
        nameTv.setOnClickListener(editName);
        View.OnClickListener editAvatar = v -> pickAvatarFromGallery();
        avatarFrame.setOnClickListener(editAvatar);
        edit.setOnClickListener(editAvatar);

        return profile;
    }

    /** Открыть системную галерею для выбора аватарки. */
    private void pickAvatarFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "image/*");
        try {
            pickAvatarLauncher.launch(intent);
        } catch (Exception e) {
            ModernToast.show(this, "Галерея недоступна");
        }
    }

    /** Скопировать выбранную картинку в приватное хранилище и показать её. */
    private void saveAvatar(Uri source) {
        new Thread(() -> {
            try {
                java.io.File out = new java.io.File(getFilesDir(), AVATAR_FILE);
                try (java.io.InputStream in = getContentResolver().openInputStream(source);
                     java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                String uri = android.net.Uri.fromFile(out).toString();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("avatar_uri", uri).apply();
                runOnUiThread(() -> {
                    if (avatarView != null) {
                        avatarView.clearColorFilter();
                        avatarView.setPadding(0, 0, 0, 0);
                        Glide.with(this).load(out).transform(new CircleCrop())
                                .into(avatarView);
                    }
                    ModernToast.show(this, "Аватарка обновлена");
                });
            } catch (Exception e) {
                runOnUiThread(() -> ModernToast.show(this, "Не удалось сохранить аватарку"));
            }
        }).start();
    }

    // ─────────── WORKSPACE (Личная / Рабочая) ───────────

    private View buildWorkspaceRow() {
        AppSettings s = new AppSettings(this);
        String current = s.isChatMode() ? getString(R.string.scr_workspace_personal) : getString(R.string.scr_workspace_work);

        View row = SettingsUI.rowWithTrailing(this, R.drawable.ic_s_briefcase,
                getString(R.string.scr_workspace), current, null, () -> {});
        workspaceValue = (TextView) ((LinearLayout) ((LinearLayout) row).getChildAt(1)).getChildAt(1);

        row.setOnClickListener(v -> ModernMenu.show(v, getString(R.string.scr_workspace),
                Arrays.asList(
                        new ModernSheet.MenuItem(getString(R.string.scr_workspace_personal_full)),
                        new ModernSheet.MenuItem(getString(R.string.scr_workspace_work_full))),
                (idx, item) -> {
                    Class<?> target;
                    if (idx == 0) {
                        s.setChatMode("chat");
                        workspaceValue.setText(getString(R.string.scr_workspace_personal));
                        target = ChatModeActivity.class;
                    } else {
                        s.setChatMode("project");
                        workspaceValue.setText(getString(R.string.scr_workspace_work));
                        target = ChatActivity.class;
                    }
                    Intent intent = new Intent(this, target)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                }));
        return row;
    }

    // ─────────── THEME / ACCENT ───────────

    private View buildThemeRow() {
        AppSettings s = new AppSettings(this);
        String current = themeLabel(s.getTheme());
        View row = SettingsUI.rowWithTrailing(this, R.drawable.ic_s_sun,
                getString(R.string.scr_appearance), current, null, () -> {});
        themeValue = (TextView) ((LinearLayout) ((LinearLayout) row).getChildAt(1)).getChildAt(1);

        row.setOnClickListener(v -> ModernMenu.show(v, getString(R.string.scr_appearance),
                Arrays.asList(
                        new ModernSheet.MenuItem(getString(R.string.scr_theme_system)),
                        new ModernSheet.MenuItem(getString(R.string.scr_theme_light)),
                        new ModernSheet.MenuItem(getString(R.string.scr_theme_dark))),
                (idx, item) -> {
                    String val = idx == 0 ? "system" : idx == 1 ? "light" : "dark";
                    if (val.equals(s.getTheme())) return;
                    s.setTheme(val);
                    themeValue.setText(themeLabel(val));
                    animateAppearanceChange(() -> app.multiai.mobile.MultiAIApp.applyTheme(val));
                }));
        return row;
    }

    private void animateAppearanceChange(Runnable change) {
        View content = findViewById(android.R.id.content);
        if (content == null) {
            change.run();
            return;
        }
        content.animate().cancel();
        content.animate().alpha(0f).scaleX(0.99f).scaleY(0.99f)
                .setDuration(160)
                .withEndAction(change)
                .start();
    }

    private String themeLabel(String v) {
        switch (v) {
            case "light": return getString(R.string.scr_theme_light);
            case "dark": return getString(R.string.scr_theme_dark);
            default: return getString(R.string.scr_theme_system);
        }
    }

    private View buildAccentRow() {
        AppSettings s = new AppSettings(this);
        String currentKey = s.getAccent();
        View row = SettingsUI.rowWithTrailing(this, R.drawable.ic_s_palette,
                getString(R.string.scr_accent_color), AccentColor.label(currentKey, this), null, () -> {});
        row.setOnClickListener(v -> showAccentPopup(row));
        return row;
    }

    /**
     * Custom accent-picker dropdown. Unlike {@link ModernMenu} (plain text rows),
     * each row here shows a CIRCULAR COLOR DOT next to the label, and the currently
     * selected accent has a checkmark beside it. Built as a {@link PopupWindow}
     * anchored below the tapped row with a fade-in animation, mirroring ModernMenu's
     * look but with richer row content.
     */
    private void showAccentPopup(View anchor) {
        final AppSettings s = new AppSettings(this);
        final String currentKey = s.getAccent();
        final boolean dark = AccentColor.isDark(this);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 6);
        list.setPadding(pad, pad, pad, pad);
        list.setBackground(ContextCompat.getDrawable(this, R.drawable.kit_toast_bg));
        list.setElevation(UiKit.dp(this, 12));

        // Title row, same styling as ModernMenu's title.
        TextView title = UiKit.text(this, getString(R.string.scr_accent_color), 12, R.color.text_hint, true);
        title.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 8), UiKit.dp(this, 10), UiKit.dp(this, 4));
        list.addView(title);

        final PopupWindow[] popupRef = new PopupWindow[1];
        final String[] keys = AccentColor.keys();
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            list.addView(accentPopupRow(key, AccentColor.label(key, this),
                    AccentColor.previewColor(key, dark),
                    key.equals(currentKey),
                    v -> {
                        s.setAccent(key);
                        // Update the row's trailing label, then recreate to apply the accent.
                        try {
                            ((TextView) ((LinearLayout) ((LinearLayout) anchor).getChildAt(1)).getChildAt(1))
                                    .setText(AccentColor.label(key, this));
                        } catch (Exception ignored) {
                        }
                        if (popupRef[0] != null) popupRef[0].dismiss();
                        recreate();
                    }));
        }

        // Measure the list so the PopupWindow wraps its content.
        int widthSpec = View.MeasureSpec.makeMeasureSpec(UiKit.dp(this, 240), View.MeasureSpec.AT_MOST);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        list.measure(widthSpec, heightSpec);
        int contentW = list.getMeasuredWidth();
        int contentH = list.getMeasuredHeight();

        final PopupWindow popup = new PopupWindow(list, contentW, contentH, true);
        popupRef[0] = popup;
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(UiKit.dp(this, 12));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        // Anchor below the row, aligned to the RIGHT edge of the anchor
        // (bottom-right anchored dropdown). Falls back to above the row if
        // there isn't enough room below, matching ModernMenu's flip logic.
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int anchorBottom = loc[1] + anchor.getHeight();
        int displayH = getResources().getDisplayMetrics().heightPixels;
        int y;
        if (anchorBottom + contentH + UiKit.dp(this, 16) > displayH) {
            y = -anchor.getHeight() - contentH;
        } else {
            y = 0;
        }
        // Push the popup right so its right edge lines up with the anchor's
        // right edge (END alignment) instead of defaulting to the left.
        int x = anchor.getWidth() - contentW;
        popup.showAsDropDown(anchor, x, y, Gravity.START);

        // Fade-in + unfold animation, matching ModernMenu's feel.
        list.setPivotY(0f);
        list.setAlpha(0f);
        list.setScaleY(0.92f);
        list.animate()
                .alpha(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    /**
     * One row of the accent popup: [color dot] [label] [checkmark if selected].
     * The dot is a 18dp View with an Oval GradientDrawable of the accent's preview
     * color; the selected row draws a contrasting ring around the dot and shows a
     * checkmark on the trailing side.
     */
    private View accentPopupRow(String key, String label, int dotColor,
                                 boolean selected, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 44));
        int padV = UiKit.dp(this, 10);
        int padH = UiKit.dp(this, 10);
        row.setPadding(padH, padV, padH, padV);
        row.setBackground(UiKit.rippleRounded(this, UiKit.RADIUS_SM,
                ContextCompat.getColor(this, R.color.ripple)));
        row.setOnClickListener(v -> {
            if (click != null) click.onClick(v);
        });

        // Color dot — 18dp oval. When selected, add a 2dp ring around the filled
        // dot so the chosen accent reads as active. A near-black dot (e.g. the
        // "black" accent, always #000000) would vanish on a dark surface, so give
        // it a contrasting outline instead of a self-colored ring.
        View dot = new View(this);
        int dotSize = UiKit.dp(this, 18);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(dotColor);
        int strokeColor;
        int strokeWidth;
        if (selected) {
            strokeWidth = UiKit.dp(this, 2);
            strokeColor = AccentColor.contrastForeground(dotColor) == Color.WHITE
                    ? ContextCompat.getColor(this, R.color.stroke) : dotColor;
        } else if (AccentColor.contrastForeground(dotColor) == Color.WHITE) {
            // Dark unselected dot — thin contrasting hairline so it stays visible.
            strokeWidth = UiKit.dp(this, 1);
            strokeColor = ContextCompat.getColor(this, R.color.stroke);
        } else {
            strokeWidth = 0;
            strokeColor = 0;
        }
        if (strokeWidth > 0) dotBg.setStroke(strokeWidth, strokeColor);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dot.setLayoutParams(dotLp);
        row.addView(dot);

        TextView labelTv = UiKit.text(this, label, 14, R.color.text_primary, selected);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMarginStart(UiKit.dp(this, 12));
        labelTv.setLayoutParams(labelLp);
        row.addView(labelTv);

        // Trailing checkmark for the selected accent.
        if (selected) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(AccentColor.previewColor(key, AccentColor.isDark(this)));
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                    UiKit.dp(this, 18), UiKit.dp(this, 18));
            check.setLayoutParams(checkLp);
            row.addView(check);
        }

        return row;
    }

    // ─────────── HELPERS ───────────

    private void openActivity(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    private void showNameDialog(TextView nameTv) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(nameTv.getText());
        input.setSelectAllOnFocus(true);
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_hint));
        input.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        bg.setCornerRadius(SettingsUI.dp(this, 14));
        bg.setStroke(SettingsUI.dp(this, 1), ContextCompat.getColor(this, R.color.stroke));
        input.setBackground(bg);
        input.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 12),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 12));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(SettingsUI.dp(this, 20), SettingsUI.dp(this, 8),
                SettingsUI.dp(this, 20), 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ModernDialog.show(this, getString(R.string.scr_rename_dialog_title), null, wrap,
                getString(R.string.chat_cancel), getString(R.string.chat_save), false, () -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "User";
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("display_name", name).apply();
                    nameTv.setText(name);
                });
    }

    private void confirmLogout() {
        ModernDialog.show(this, getString(R.string.scr_logout_dialog_title),
                getString(R.string.scr_logout_dialog_message),
                getString(R.string.chat_cancel), getString(R.string.logout), false, () -> {
                    app.multiai.mobile.network.oauth.OAuthLoginController oauth =
                            new app.multiai.mobile.network.oauth.OAuthLoginController(this);
                    oauth.logout(wiped -> {
                        if (!wiped) {
                            ModernToast.show(this,
                                    getString(R.string.scr_profile_signout_failed));
                            return;
                        }
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().commit();
                        Intent intent = new Intent(this, OnboardingActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    });
                });
    }

    private void showReportBugSheet() {
        ReportBugSheet.show(this);
    }

    private void refreshProfileHeader() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        OAuthStore store = new OAuthStore(this);
        String displayName = prefs.getString("display_name", null);
        String oauthName = store.getName();
        if (nameTv != null) {
            nameTv.setText(displayName != null && !displayName.isEmpty()
                    ? displayName
                    : oauthName != null && !oauthName.isEmpty() ? oauthName : "User");
        }
        if (avatarView == null) return;
        String localAvatar = prefs.getString("avatar_uri", null);
        String avatarUrl = localAvatar != null && !localAvatar.isEmpty()
                ? localAvatar : store.getPicture();
        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
            avatarView.clearColorFilter();
            avatarView.setPadding(0, 0, 0, 0);
            Glide.with(this).load(avatarUrl).transform(new CircleCrop())
                    .placeholder(R.drawable.chat_mode_ic_user)
                    .error(R.drawable.chat_mode_ic_user).into(avatarView);
        } else {
            Glide.with(this).clear(avatarView);
            avatarView.setImageResource(R.drawable.chat_mode_ic_user);
            avatarView.setColorFilter(ContextCompat.getColor(this, R.color.icon_muted));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!profileReceiverRegistered) {
            ContextCompat.registerReceiver(this, profileReceiver,
                    new IntentFilter(OAuthLoginController.ACTION_PROFILE_UPDATED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            profileReceiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (profileReceiverRegistered) {
            unregisterReceiver(profileReceiver);
            profileReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfileHeader();
        // Обновляем ярлычок workspace (если меняли)
        if (workspaceValue != null) {
            AppSettings s = new AppSettings(this);
            workspaceValue.setText(s.isChatMode() ? getString(R.string.scr_workspace_personal) : getString(R.string.scr_workspace_work));
        }
    }
}
