package app.multiai.mobile.ui.kit;

import android.app.Activity;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;

import java.util.ArrayList;
import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.adapters.ChatModeChatsAdapter;
import app.multiai.mobile.models.Chat;
import app.multiai.mobile.utils.ChatManager;

/**
 * Переиспользуемый чат-режимный сайдбар — тот же, что в ChatModeActivity.
 * Профиль (и другие общие экраны) в чат-режиме открывают эту шторку
 * вместо ворк-дровера, чтобы UI соответствовал текущему режиму.
 *
 * Шторка сама ведёт список чатов (multiai_chatmode_chats), поиск и аватар;
 * навигация наружу — через {@link Listener}.
 */
public class ChatModeSidebar {

    /** Навигация, которую хост-экран должен обработать сам. */
    public interface Listener {
        /** Открыть чат-режимный чат по id (ChatModeActivity + EXTRA_CHAT_ID). */
        void onOpenChat(String chatId);

        /** Новый чат (кнопка «Чат» внизу шторки). */
        void onNewChat();

        /** Воспоминания. */
        void onOpenMemories();

        /** Настройки чат-режима. */
        void onOpenSettings();
    }

    private static final String CHATMODE_PREFS = "multiai_chatmode_chats";
    private static final String PROFILE_PREFS = "multiai_profile";

    private final Activity act;
    private final Listener listener;

    private final android.widget.FrameLayout overlay;
    private final View mainContent;
    private final RecyclerView chatList;
    private final TextView emptyText;
    private final EditText searchInput;
    private final android.widget.LinearLayout searchGroup;
    private final ImageButton searchButton;
    private final ImageView brandLogo;
    private final View brandBlock;
    private final ImageView profileAvatar;

    private final ChatModeChatsAdapter chatsAdapter = new ChatModeChatsAdapter();
    private final ChatManager chatManager;

    private boolean open;
    private boolean searchExpanded;
    private android.animation.ValueAnimator searchWidthAnimator;

    // Edge-swipe state
    private float swipeStartX, swipeStartY;
    private boolean swipeTracking, swipeDragging;

    public ChatModeSidebar(Activity act, Listener listener) {
        this.act = act;
        this.listener = listener;
        this.chatManager = new ChatManager(act, CHATMODE_PREFS);

        overlay = act.findViewById(R.id.chatModeSidebarOverlay);
        if (overlay == null) {
            throw new IllegalStateException("ChatModeSidebar: layout sidebar_chatmode.xml не найден в content view");
        }
        // Контент, который сдвигается вправо при открытии. Если оверлей лежит
        // прямо в DrawerLayout, сдвигать родителя нельзя — уехал бы весь экран.
        View parent = (View) overlay.getParent();
        View content = act.findViewById(R.id.profileScroll);
        mainContent = (content != null) ? content : parent;
        chatList = overlay.findViewById(R.id.sidebarChatList);
        emptyText = overlay.findViewById(R.id.sidebarEmptyText);
        searchInput = overlay.findViewById(R.id.sidebarSearchInput);
        searchGroup = overlay.findViewById(R.id.sidebarSearchGroup);
        searchButton = overlay.findViewById(R.id.sidebarSearch);
        brandLogo = overlay.findViewById(R.id.sidebarBrandLogo);
        brandBlock = overlay.findViewById(R.id.sidebarBrandBlock);
        profileAvatar = overlay.findViewById(R.id.sidebarProfileAvatar);

        // init state (как в ChatModeActivity.initSidebar)
        overlay.setTranslationX(-2000f);
        overlay.setVisibility(View.GONE);

        if (chatList != null) {
            chatList.setLayoutManager(new LinearLayoutManager(act));
            chatList.setAdapter(chatsAdapter);
            chatsAdapter.setOnChatClickListener(chat -> {
                listener.onOpenChat(chat.getId());
            });
        }

        View fabNewChat = overlay.findViewById(R.id.sidebarNewChatFab);
        if (fabNewChat != null) {
            fabNewChat.setOnClickListener(v -> listener.onNewChat());
        }

        if (profileAvatar != null) {
            // Мы уже в профиле — тап по аватарке ничего не делает.
            profileAvatar.setOnClickListener(v -> {});
            loadAvatar();
        }

        if (searchButton != null) {
            searchButton.setTranslationX(-2f);
            searchButton.setOnClickListener(v -> setSearchExpanded(!searchExpanded, true));
        }

        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    updateChats(s.toString());
                }
            });
        }

        View more = overlay.findViewById(R.id.sidebarMore);
        if (more != null) {
            more.setOnClickListener(v -> listener.onOpenMemories());
        }

        View settings = overlay.findViewById(R.id.sidebarSettings);
        if (settings != null) {
            settings.setOnClickListener(v -> listener.onOpenSettings());
        }

        View badge = overlay.findViewById(R.id.modeBadgeContainerSidebar);
        if (badge != null) {
            badge.setOnClickListener(v ->
                    ModeSwitcher.showDropdown(act, badge, ModeSwitcher.MODE_CHAT));
        }
    }

    // ─────────────────── AVATAR ───────────────────

    private void loadAvatar() {
        app.multiai.mobile.network.oauth.OAuthStore oauthStore =
                new app.multiai.mobile.network.oauth.OAuthStore(act);
        String photoUrl = oauthStore.getPicture();
        if (photoUrl == null || photoUrl.trim().isEmpty()) {
            photoUrl = act.getSharedPreferences(PROFILE_PREFS, Activity.MODE_PRIVATE)
                    .getString("photo_url", null);
        }
        if (photoUrl != null && !photoUrl.trim().isEmpty() && !"null".equals(photoUrl)) {
            try {
                profileAvatar.setImageTintList(null);
                profileAvatar.setPadding(0, 0, 0, 0);
                profileAvatar.setTag(true);
                Glide.with(act).load(photoUrl)
                        .transform(new CircleCrop())
                        .placeholder(R.drawable.chat_mode_ic_user)
                        .error(R.drawable.chat_mode_ic_user)
                        .into(profileAvatar);
            } catch (Exception ignored) {}
        } else if (profileAvatar.getTag() != null) {
            Glide.with(act).clear(profileAvatar);
            profileAvatar.setTag(null);
            profileAvatar.setImageResource(R.drawable.chat_mode_ic_user);
            profileAvatar.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(act, R.color.icon_muted)));
            int pad = dp(9);
            profileAvatar.setPadding(pad, pad, pad, pad);
        }
    }

    // ─────────────────── OPEN / CLOSE ───────────────────

    public boolean isOpen() { return open; }

    public void open() {
        if (open) return;
        loadAvatar();
        updateChats(searchInput != null ? searchInput.getText().toString() : "");
        overlay.animate().cancel();
        overlay.setVisibility(View.VISIBLE);
        overlay.setTranslationX(-width());
        open = true;
        overlay.animate()
                .translationX(0f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
        if (mainContent != null && mainContent != overlay) {
            mainContent.animate()
                    .translationX(dp(260))
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
        }
    }

    public void close() {
        if (!open) return;
        setSearchExpanded(false, false);
        open = false;
        overlay.animate().cancel();
        overlay.animate()
                .translationX(-width())
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    overlay.setVisibility(View.GONE);
                    overlay.setTranslationX(-width());
                })
                .start();
        if (mainContent != null && mainContent != overlay) {
            mainContent.animate()
                    .translationX(0f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .start();
        }
    }

    public void toggle() {
        if (open) close();
        else open();
    }

    /** Back-press: returns true if consumed (search collapse or close). */
    public boolean onBackPressed() {
        if (open && searchExpanded) {
            setSearchExpanded(false, true);
            return true;
        }
        if (open) {
            close();
            return true;
        }
        return false;
    }

    // ─────────────────── SWIPE ───────────────────

    /** Call from the host's dispatchTouchEvent; returns true if consumed. */
    public boolean handleSwipe(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeStartX = event.getX();
                swipeStartY = event.getY();
                // Открываем только с левой кромки (в профиле свайп по центру
                // переключает вкладки), но закрывать можно свайпом откуда угодно.
                swipeTracking = swipeStartX <= dp(28) || open;
                swipeDragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!swipeTracking) return false;
                float dx = event.getX() - swipeStartX;
                float dy = event.getY() - swipeStartY;
                if (!swipeDragging) {
                    if (Math.abs(dx) > dp(20) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                        swipeDragging = true;
                    } else {
                        return false;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (swipeDragging) {
                    float finalDx = event.getX() - swipeStartX;
                    if (finalDx > dp(60) && !open) open();
                    else if (finalDx < -dp(60) && open) close();
                    swipeTracking = false;
                    swipeDragging = false;
                    return true;
                }
                swipeTracking = false;
                swipeDragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                swipeTracking = false;
                swipeDragging = false;
                break;
        }
        return false;
    }

    // ─────────────────── CHAT LIST ───────────────────

    public void refreshChats() {
        updateChats(searchInput != null ? searchInput.getText().toString() : "");
    }

    private void updateChats(String query) {
        List<Chat> chats = chatManager.loadActiveChats();
        List<Chat> filtered = new ArrayList<>();
        String q = query != null ? query.trim().toLowerCase() : "";
        for (Chat chat : chats) {
            String title = chat.getTitle() != null ? chat.getTitle().toLowerCase() : "";
            String preview = chat.getLastMessagePreview() != null
                    ? chat.getLastMessagePreview().toLowerCase() : "";
            if (q.isEmpty() || title.contains(q) || preview.contains(q)) {
                filtered.add(chat);
            }
        }
        if (filtered.isEmpty()) {
            if (emptyText != null) emptyText.setVisibility(View.VISIBLE);
            if (chatList != null) chatList.setVisibility(View.GONE);
        } else {
            if (emptyText != null) emptyText.setVisibility(View.GONE);
            if (chatList != null) chatList.setVisibility(View.VISIBLE);
            chatsAdapter.setActiveChatId(chatManager.loadActiveChatId());
            chatsAdapter.setChats(filtered);
        }
    }

    // ─────────────────── SEARCH EXPANSION ───────────────────

    private void setSearchExpanded(boolean expanded, boolean animate) {
        if (searchInput == null || searchGroup == null) return;
        if (searchExpanded == expanded && animate) return;
        searchExpanded = expanded;

        int collapsedWidth = dp(36);
        View header = (View) searchGroup.getParent();
        int headerWidth = header.getWidth() > 0 ? header.getWidth() : width();
        int expandedWidth = Math.max(collapsedWidth,
                headerWidth - header.getPaddingStart() - header.getPaddingEnd());
        int startWidth = searchGroup.getWidth() > 0 ? searchGroup.getWidth() : collapsedWidth;
        int endWidth = expanded ? expandedWidth : collapsedWidth;

        if (searchWidthAnimator != null) {
            searchWidthAnimator.cancel();
            searchWidthAnimator = null;
        }
        searchInput.animate().cancel();
        if (brandLogo != null) brandLogo.animate().cancel();
        if (brandBlock != null) brandBlock.animate().cancel();

        if (!expanded) {
            if (brandLogo != null) brandLogo.setVisibility(View.VISIBLE);
            if (brandBlock != null) brandBlock.setVisibility(View.VISIBLE);
        } else {
            searchInput.setVisibility(View.VISIBLE);
            if (startWidth == collapsedWidth) searchInput.setAlpha(0f);
        }

        if (!animate) {
            setSearchWidth(endWidth);
            settleSearchViews(expanded);
        } else {
            searchWidthAnimator = android.animation.ValueAnimator.ofInt(startWidth, endWidth);
            searchWidthAnimator.setDuration(220);
            searchWidthAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
            searchWidthAnimator.addUpdateListener(anim -> setSearchWidth((int) anim.getAnimatedValue()));
            searchWidthAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (animation != searchWidthAnimator) return;
                    searchWidthAnimator = null;
                    settleSearchViews(searchExpanded);
                }
            });
            searchWidthAnimator.start();

            searchInput.animate()
                    .alpha(expanded ? 1f : 0f)
                    .setDuration(expanded ? 180 : 120)
                    .start();
            animateBrand(brandLogo, expanded);
            animateBrand(brandBlock, expanded);
        }

        if (searchButton != null) {
            searchButton.setImageResource(expanded ? R.drawable.ic_close : R.drawable.ic_magnify);
            searchButton.setContentDescription(act.getString(expanded
                    ? R.string.close_search : R.string.chat_search_hint));
            searchButton.setTranslationX(expanded ? 0f : -2f);
        }

        InputMethodManager imm = (InputMethodManager) act.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (expanded) {
            searchInput.requestFocus();
            searchInput.post(() -> {
                if (searchExpanded && imm != null) {
                    imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        } else {
            searchInput.setText("");
            searchInput.clearFocus();
            if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private void setSearchWidth(int width) {
        ViewGroup.LayoutParams params = searchGroup.getLayoutParams();
        params.width = width;
        searchGroup.setLayoutParams(params);
    }

    private void animateBrand(View view, boolean hide) {
        if (view == null) return;
        view.animate().alpha(hide ? 0f : 1f).setDuration(160).start();
    }

    private void settleSearchViews(boolean expanded) {
        searchInput.animate().cancel();
        searchInput.setAlpha(expanded ? 1f : 0f);
        searchInput.setVisibility(expanded ? View.VISIBLE : View.GONE);
        settleBrand(brandLogo, expanded);
        settleBrand(brandBlock, expanded);
        if (searchButton != null) {
            searchButton.setTranslationX(expanded ? 0f : -2f);
        }
    }

    private void settleBrand(View view, boolean hidden) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(hidden ? 0f : 1f);
        view.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    // ─────────────────── MISC ───────────────────

    private int width() {
        int w = overlay.getWidth();
        return w > 0 ? w : act.getResources().getDisplayMetrics().widthPixels;
    }

    private int dp(float value) {
        return (int) (value * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
