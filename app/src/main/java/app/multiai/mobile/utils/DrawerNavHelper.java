package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.drawerlayout.widget.DrawerLayout;

import app.multiai.mobile.R;

/**
 * Wires the custom drawer navigation rows defined in drawer_navigation.xml.
 *
 * The drawer no longer uses NavigationView; instead each item is an include of
 * drawer_nav_item.xml carrying a stable id (nav_chat, nav_terminal, nav_files,
 * nav_editor, nav_history, nav_settings, nav_profile). This helper:
 *   - sets the icon / title / subtitle text for every row,
 *   - marks exactly one row as selected (the current screen),
 *   - attaches the navigation click listeners,
 *   - toggles visibility of the non-chat rows when Chat Mode is enabled.
 *
 * Each Activity passes its NavTarget so the helper knows which row is active
 * and which Intents to fire. Activities that do not navigate on item click
 * (e.g. ChatActivity keeps the drawer open) can pass a callback that simply
 * closes the drawer.
 */
public final class DrawerNavHelper {

    public enum NavTarget { CHAT, TERMINAL, FILES, EDITOR, PROFILE, HISTORY }

    /** Fired when a nav row is tapped; return true to consume. */
    public interface NavCallback {
        /**
         * @param target the row that was tapped
         * @return true if the tap was handled (drawer will be closed afterwards)
         */
        boolean onNavClicked(NavTarget target);
    }

    private DrawerNavHelper() { }

    /**
     * Configure the drawer. Uses the host Activity's findViewById, so it works
     * regardless of which content view the drawer was included into.
     *
     * @param host          the Activity hosting the drawer
     * @param active        the row to highlight as the current screen
     * @param callback      navigation handler
     * @param settingsClick listener for the Settings row (null = no-op)
     * @param profileClick  listener for the Profile row (null = no-op)
     */
    public static void setup(Activity host, NavTarget active, NavCallback callback,
                             View.OnClickListener settingsClick,
                             View.OnClickListener profileClick) {
        // Версия приложения в футере drawer вместо хардкода «1.3».
        TextView drawerVersion = host.findViewById(R.id.drawerVersionLabel);
        if (drawerVersion != null) {
            drawerVersion.setText(host.getString(R.string.drawer_version,
                    app.multiai.mobile.BuildConfig.VERSION_NAME));
        }
        configureRow(host, R.id.nav_chat, R.drawable.ic_chat,
                R.string.chat, R.string.nav_subtitle_chat,
                NavTarget.CHAT, active, callback);
        configureRow(host, R.id.nav_terminal, R.drawable.ic_terminal,
                R.string.terminal, R.string.nav_subtitle_terminal,
                NavTarget.TERMINAL, active, callback);
        configureRow(host, R.id.nav_files, R.drawable.ic_folder_outline,
                R.string.files, R.string.nav_subtitle_files,
                NavTarget.FILES, active, callback);
        configureRow(host, R.id.nav_editor, R.drawable.ic_editor,
                R.string.editor, R.string.nav_subtitle_editor,
                NavTarget.EDITOR, active, callback);
        configureRow(host, R.id.nav_history, R.drawable.ic_history,
                R.string.nav_history, R.string.nav_subtitle_history,
                NavTarget.HISTORY, active, callback);
        View accessRow = host.findViewById(R.id.nav_access);
        if (accessRow != null) {
            ImageView accessIcon = accessRow.findViewById(R.id.navItemIcon);
            TextView accessTitle = accessRow.findViewById(R.id.navItemTitle);
            TextView accessSubtitle = accessRow.findViewById(R.id.navItemSubtitle);
            if (accessIcon != null) accessIcon.setImageResource(R.drawable.ic_security);
            if (accessTitle != null) accessTitle.setText(R.string.work_access_title);
            if (accessSubtitle != null) accessSubtitle.setText(R.string.work_access_subtitle);
            accessRow.setOnClickListener(v -> {
                View drawer = host.findViewById(R.id.drawerFrame);
                if (drawer != null && drawer.getParent() instanceof DrawerLayout) {
                    ((DrawerLayout) drawer.getParent()).closeDrawer(drawer);
                }
                app.multiai.mobile.utils.WorkModeAccess.showModePicker(host);
            });
        }
        configureRow(host, R.id.nav_settings, R.drawable.ic_settings,
                R.string.settings, R.string.nav_subtitle_settings,
                null, active, settingsClick);
        configureRow(host, R.id.nav_profile, R.drawable.chat_mode_ic_user,
                R.string.profile, R.string.nav_subtitle_profile,
                null, active, profileClick);

        // ── WORK / CHAT badge in the drawer header ──
        // Shows the current mode as a superscript badge next to "MultiAI".
        // Only the badge is clickable — it opens the mode-switch dropdown.
        View badgeContainer = host.findViewById(R.id.modeBadgeContainer);
        TextView badgeText = host.findViewById(R.id.modeBadgeText);
        if (badgeContainer != null && badgeText != null) {
            // Режим берём из настроек: этот дровер юзают не только ворк-экраны,
            // но и Profile, который открыт и в чат-режиме — там дровер должен
            // выглядеть как чат-режим (бейдж CHAT, без ворк-строк).
            boolean isChatMode = new app.multiai.mobile.utils.AppSettings(host).isChatMode();
            String mode = isChatMode
                    ? app.multiai.mobile.ui.kit.ModeSwitcher.MODE_CHAT
                    : app.multiai.mobile.ui.kit.ModeSwitcher.MODE_WORK;
            // Контейнер бейджа всегда видим — в режиме чата он не должен
            // пропадать (fix: ранее мог обрезаться/терять контраст).
            badgeContainer.setVisibility(View.VISIBLE);
            badgeText.setVisibility(View.VISIBLE);
            badgeText.setText(isChatMode ? "CHAT" : "WORK");
            badgeText.setBackgroundResource(isChatMode
                    ? app.multiai.mobile.R.drawable.mode_badge_chat
                    : app.multiai.mobile.R.drawable.mode_badge_work);
            // В режиме чата берём цвет текста повыше (text_primary) для контраста
            // на тонированном фоне — иначе бейдж читался плохо.
            badgeText.setTextColor(isChatMode
                    ? androidx.core.content.ContextCompat.getColor(host, app.multiai.mobile.R.color.text_primary)
                    : android.graphics.Color.BLACK);
            badgeContainer.setOnClickListener(v ->
                    app.multiai.mobile.ui.kit.ModeSwitcher.showDropdown(host, badgeContainer, mode));
        }

        // ── Brand avatar click: go to the chat of the current mode ──
        // In Chat Mode → ChatModeActivity (chat-mode chat).
        // In Work Mode → ChatActivity (work-mode chat).
        // Only wired inside the drawer — nowhere else.
        View brandAvatar = host.findViewById(R.id.drawerBrandAvatar);
        if (brandAvatar != null) {
            brandAvatar.setOnClickListener(v -> {
                final View drawer = host.findViewById(R.id.drawerFrame);
                final DrawerLayout parent = (drawer != null
                        && drawer.getParent() instanceof DrawerLayout)
                        ? (DrawerLayout) drawer.getParent() : null;
                boolean isChatMode = new app.multiai.mobile.utils.AppSettings(host).isChatMode();
                final Intent intent = new Intent(host,
                        isChatMode ? app.multiai.mobile.ui.ChatModeActivity.class
                                   : app.multiai.mobile.ui.ChatActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (parent != null) {
                    DrawerLayout.SimpleDrawerListener closer =
                            new DrawerLayout.SimpleDrawerListener() {
                        @Override
                        public void onDrawerClosed(View d) {
                            parent.removeDrawerListener(this);
                            host.startActivity(intent);
                        }
                    };
                    parent.addDrawerListener(closer);
                    parent.closeDrawer(drawer);
                } else {
                    host.startActivity(intent);
                }
            });
        }

        // Dismiss the mode dropdown whenever the drawer starts closing, otherwise
        // the PopupWindow survives the drawer slide-out and floats over the
        // content as a stray white rectangle (the anchored badge is gone).
        // ModeSwitcher tracks the live popup itself; we just trigger dismissal.
        final View drawer = host.findViewById(R.id.drawerFrame);
        if (drawer != null && drawer.getParent() instanceof DrawerLayout) {
            final DrawerLayout parent = (DrawerLayout) drawer.getParent();
            DrawerLayout.SimpleDrawerListener dropdownGuard =
                    new DrawerLayout.SimpleDrawerListener() {
                @Override
                public void onDrawerStateChanged(int newState) {
                    // DRAGGING / SETTLING both mean the user is leaving the badge
                    // area (drag or programmatic close). Close the popup in either
                    // case so it can never dangle once the drawer has slid shut.
                    if (newState == DrawerLayout.STATE_DRAGGING
                            || newState == DrawerLayout.STATE_SETTLING) {
                        app.multiai.mobile.ui.kit.ModeSwitcher.dismissVisibleDropdown();
                    }
                }
            };
            // Avoid attaching the same listener twice if setup() runs again.
            parent.removeDrawerListener(dropdownGuard);
            parent.addDrawerListener(dropdownGuard);
        }

        // В чат-режиме прячем ворк-строки (терминал/файлы/редактор) — дровер
        // Profile и других общих экранов должен соответствовать текущему режиму.
        setChatMode(host, new app.multiai.mobile.utils.AppSettings(host).isChatMode());
    }

    /**
     * Show or hide the non-chat navigation rows depending on Chat Mode.
     * In Chat Mode only Chat / Settings / Profile remain visible.
     */
    public static void setChatMode(Activity host, boolean chatMode) {
        setRowVisible(host, R.id.nav_terminal, !chatMode);
        setRowVisible(host, R.id.nav_files, !chatMode);
        setRowVisible(host, R.id.nav_editor, !chatMode);
        // История дровера показывает чаты Project-режима (дефолтный ChatManager) —
        // в чат-режиме это чужой контент, чат-режим живёт в своём сайдбаре.
        setRowVisible(host, R.id.nav_history, !chatMode);
        setRowVisible(host, R.id.nav_access, !chatMode);
    }

    private static void configureRow(Activity host, int rowId, int iconRes,
                                     int titleRes, int subtitleRes,
                                     NavTarget target, NavTarget active,
                                     Object listener) {
        View row = host.findViewById(rowId);
        if (row == null) return;

        ImageView icon = row.findViewById(R.id.navItemIcon);
        TextView title = row.findViewById(R.id.navItemTitle);
        TextView subtitle = row.findViewById(R.id.navItemSubtitle);
        if (icon != null) icon.setImageResource(iconRes);
        if (title != null) title.setText(titleRes);
        if (subtitle != null) subtitle.setText(subtitleRes);

        row.setSelected(target == active);

        if (listener instanceof NavCallback && target != null) {
            NavCallback cb = (NavCallback) listener;
            row.setOnClickListener(v -> {
                final View drawer = host.findViewById(R.id.drawerFrame);
                final DrawerLayout parent = (drawer != null
                        && drawer.getParent() instanceof DrawerLayout)
                        ? (DrawerLayout) drawer.getParent() : null;

                // Special-case HISTORY: toggle an expandable chat list in-place.
                // The chevron rotates 90° (right → down) and recent chats appear below.
                if (target == NavTarget.HISTORY) {
                    toggleHistoryExpansion(host, row);
                    return;
                }

                // Close the drawer FIRST so its slide-out animation plays, then
                // run the navigation callback only after the drawer has finished
                // closing. Previously startActivity/finish fired synchronously
                // inside onNavClicked, killing the activity before the close
                // animation could run — making the drawer vanish instantly.
                if (parent != null) {
                    DrawerLayout.SimpleDrawerListener closer =
                            new DrawerLayout.SimpleDrawerListener() {
                        @Override
                        public void onDrawerClosed(View d) {
                            parent.removeDrawerListener(this);
                            cb.onNavClicked(target);
                        }
                    };
                    parent.addDrawerListener(closer);
                    parent.closeDrawer(drawer);
                } else {
                    // No drawer available (shouldn't happen) — fall back to the
                    // original immediate behaviour so navigation still works.
                    cb.onNavClicked(target);
                }
            });
        } else if (listener instanceof View.OnClickListener) {
            row.setOnClickListener((View.OnClickListener) listener);
        }
    }

    private static void setRowVisible(Activity host, int rowId, boolean visible) {
        View row = host.findViewById(rowId);
        if (row != null) row.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ─────────────────── History expansion ───────────────────

    /**
     * Toggles the expandable recent-chats list under the "История" row.
     * The chevron rotates 90° (right → down) with a spring animation; the chat
     * list container expands/collapses below it.
     */
    /** Refresh the expanded history list if it's currently visible. */
    public static void refreshHistory(Activity host) {
        if (host == null) return;
        ViewGroup container = (ViewGroup) host.findViewById(R.id.historyExpandContainer);
        // Обновляем и когда контейнер виден, и когда он ещё/уже наполнен строками
        // (например, в момент анимации сворачивания): иначе удалённый/переименованный
        // чат оставался в списке до ручного «свернуть → развернуть».
        if (container == null) return;
        if (container.getVisibility() != View.VISIBLE && container.getChildCount() == 0) return;
        container.removeAllViews();
        populateHistoryList(host, container);
    }

    private static void toggleHistoryExpansion(Activity host, View historyRow) {
        final ViewGroup container = (ViewGroup) host.findViewById(R.id.historyExpandContainer);
        if (container == null) return;

        ImageView chevron = historyRow.findViewById(R.id.navItemChevron);
        boolean expanding = container.getVisibility() != View.VISIBLE;

        if (expanding) {
            // Populate with recent chats before revealing.
            populateHistoryList(host, container);
            container.setVisibility(View.VISIBLE);
            container.setAlpha(0f);
            container.setScaleY(0.9f);
            container.setPivotY(0f);
            container.animate().alpha(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .start();
            if (chevron != null) chevron.animate().rotation(90f).setDuration(220).start();
        } else {
            container.animate().alpha(0f).scaleY(0.9f).setDuration(160)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        container.setVisibility(View.GONE);
                        container.removeAllViews();
                    })
                    .start();
            if (chevron != null) chevron.animate().rotation(0f).setDuration(220).start();
        }
    }

    /** Builds clickable rows for each recent chat inside the expansion container. */
    private static void populateHistoryList(Activity host, ViewGroup container) {
        java.util.List<app.multiai.mobile.models.Chat> chats =
                new app.multiai.mobile.utils.ChatManager(host).loadChats();
        if (chats == null || chats.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(host);
            empty.setText(host.getString(app.multiai.mobile.R.string.scr_drawer_history_empty));
            empty.setTextColor(androidx.core.content.ContextCompat.getColor(host, app.multiai.mobile.R.color.text_hint));
            empty.setTextSize(13);
            empty.setPadding(0, app.multiai.mobile.ui.kit.UiKit.dp(host, 10), 0, app.multiai.mobile.ui.kit.UiKit.dp(host, 10));
            container.addView(empty);
            return;
        }
        // Build display list: pinned chats first (already sorted to the front by
        // ChatManager.sortPinnedFirst), then non-pinned by recency. The old code
        // iterated backwards which put pinned chats at the bottom.
        java.util.List<app.multiai.mobile.models.Chat> displayList = new java.util.ArrayList<>();
        // Pinned first — iterate forward so index-0 (most recently pinned) is on top.
        for (app.multiai.mobile.models.Chat c : chats) {
            if (c.isPinned()) displayList.add(c);
        }
        // Non-pinned by recency (newest first = iterate backwards).
        for (int i = chats.size() - 1; i >= 0; i--) {
            if (!chats.get(i).isPinned()) displayList.add(chats.get(i));
        }

        int count = Math.min(displayList.size(), 10);
        for (int idx = 0; idx < count; idx++) {
            app.multiai.mobile.models.Chat chat = displayList.get(idx);
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            boolean pinned = chat.isPinned();

            TextView titleTv = new TextView(host);
            String title = chat.getTitle() != null ? chat.getTitle() : host.getString(app.multiai.mobile.R.string.scr_drawer_history_chat_default);
            titleTv.setText(title);
            titleTv.setTextColor(androidx.core.content.ContextCompat.getColor(host, pinned
                    ? app.multiai.mobile.R.color.text_primary
                    : app.multiai.mobile.R.color.text_secondary));
            titleTv.setTextSize(13);
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            row.addView(titleTv, titleLp);

            // Pin icon (shown only when pinned).
            if (pinned) {
                android.widget.ImageView pinIcon = new android.widget.ImageView(host);
                pinIcon.setImageResource(app.multiai.mobile.R.drawable.ic_pin);
                pinIcon.setColorFilter(androidx.core.content.ContextCompat.getColor(host, app.multiai.mobile.R.color.text_hint));
                LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                        app.multiai.mobile.ui.kit.UiKit.dp(host, 14), app.multiai.mobile.ui.kit.UiKit.dp(host, 14));
                pinLp.setMarginStart(app.multiai.mobile.ui.kit.UiKit.dp(host, 8));
                pinIcon.setLayoutParams(pinLp);
                row.addView(pinIcon);
            }

            int padV = app.multiai.mobile.ui.kit.UiKit.dp(host, 9);
            row.setPadding(app.multiai.mobile.ui.kit.UiKit.dp(host, 4), padV, app.multiai.mobile.ui.kit.UiKit.dp(host, 4), padV);
            // Pinned rows get a subtle surface_tonal background so they stand out.
            int bgColor = pinned
                    ? androidx.core.content.ContextCompat.getColor(host, app.multiai.mobile.R.color.surface_tonal)
                    : 0;
            row.setBackgroundColor(bgColor);
            row.setBackground(app.multiai.mobile.ui.kit.UiKit.rippleRounded(host, 10,
                    androidx.core.content.ContextCompat.getColor(host, app.multiai.mobile.R.color.ripple)));
            if (pinned) {
                // Re-apply tonal tint under the ripple.
                android.graphics.drawable.Drawable ripple = row.getBackground();
                android.graphics.drawable.GradientDrawable tonalBg = new android.graphics.drawable.GradientDrawable();
                tonalBg.setCornerRadius(app.multiai.mobile.ui.kit.UiKit.dp(host, 10));
                tonalBg.setColor(bgColor);
                android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{tonalBg, ripple});
                row.setBackground(layers);
            }
            final boolean finalPinned = pinned;
            row.setOnClickListener(v -> {
                // Close the drawer, then let the host open the chat.
                View drawer = host.findViewById(R.id.drawerFrame);
                if (drawer != null && drawer.getParent() instanceof DrawerLayout) {
                    ((DrawerLayout) drawer.getParent()).closeDrawer(drawer);
                }
                // ChatActivity singleTop → onNewIntent: id обязан ехать в extra.
                // Раньше писали в prefs «open_chat_id», который никто не читал —
                // тап по чату в истории молча ничего не переключал.
                android.content.Intent intent = new android.content.Intent(host, app.multiai.mobile.ui.ChatActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(app.multiai.mobile.utils.ChatActions.EXTRA_CHAT_ID, chat.getId());
                host.startActivity(intent);
            });

            // Long-press on a history row → full action sheet, same as Work Mode
            // long-press on a ChatsAdapter row.
            row.setOnLongClickListener(v -> {
                if (host instanceof app.multiai.mobile.ui.ChatActivity) {
                    ((app.multiai.mobile.ui.ChatActivity) host).showChatActionsForHistory(chat);
                    return true;
                }
                return false;
            });
            container.addView(row);
        }

        // If there are more chats than shown, add a "Show more" button.
        if (displayList.size() > count) {
            android.widget.TextView moreBtn = new android.widget.TextView(host);
            int remaining = displayList.size() - count;
            moreBtn.setText(host.getString(app.multiai.mobile.R.string.scr_drawer_history_more, remaining));
            moreBtn.setTextColor(androidx.core.content.ContextCompat.getColor(host, app.multiai.mobile.R.color.text_hint));
            moreBtn.setTextSize(12);
            moreBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            int padV = app.multiai.mobile.ui.kit.UiKit.dp(host, 8);
            moreBtn.setPadding(app.multiai.mobile.ui.kit.UiKit.dp(host, 4), padV, app.multiai.mobile.ui.kit.UiKit.dp(host, 4), padV);
            moreBtn.setOnClickListener(v -> {
                // Replace the limited list with ALL chats using the same sort order.
                container.removeAllViews();
                for (int di = 0; di < displayList.size(); di++) {
                    app.multiai.mobile.models.Chat chat = displayList.get(di);
                    android.widget.TextView row = new android.widget.TextView(host);
                    row.setText(chat.getTitle() != null ? chat.getTitle() : host.getString(app.multiai.mobile.R.string.scr_drawer_history_chat_default));
                    row.setTextColor(androidx.core.content.ContextCompat.getColor(host, app.multiai.mobile.R.color.text_secondary));
                    row.setTextSize(13);
                    row.setMaxLines(1);
                    row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    int rpV = app.multiai.mobile.ui.kit.UiKit.dp(host, 9);
                    row.setPadding(app.multiai.mobile.ui.kit.UiKit.dp(host, 4), rpV, app.multiai.mobile.ui.kit.UiKit.dp(host, 4), rpV);
                    row.setBackground(app.multiai.mobile.ui.kit.UiKit.rippleRounded(host, 10,
                            androidx.core.content.ContextCompat.getColor(host, app.multiai.mobile.R.color.ripple)));
                    row.setOnClickListener(v2 -> {
                        View drawer = host.findViewById(R.id.drawerFrame);
                        if (drawer != null && drawer.getParent() instanceof DrawerLayout) {
                            ((DrawerLayout) drawer.getParent()).closeDrawer(drawer);
                        }
                        android.content.Intent intent = new android.content.Intent(host, app.multiai.mobile.ui.ChatActivity.class)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(app.multiai.mobile.utils.ChatActions.EXTRA_CHAT_ID, chat.getId());
                        host.startActivity(intent);
                    });
                    container.addView(row);
                }
            });
            container.addView(moreBtn);
        }
    }
}
