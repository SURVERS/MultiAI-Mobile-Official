package app.multiai.mobile.adapters;

import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.models.Chat;

/**
 * Адаптер для списка чатов в drawer.
 *
 * <p>Long-press on a row delegates to {@link OnChatClickListener#onChatLongPress(Chat, View)},
 * whose default implementation is a no-op so the host activity owns the full
 * {@link app.multiai.mobile.ui.kit.ModernSheet} actions sheet (Share, Pin/Unpin, Move,
 * Loaded files, Search, Home shortcut, Archive/Unarchive, Delete).
 */
public class ChatsAdapter extends RecyclerView.Adapter<ChatsAdapter.ChatViewHolder> {

    private List<Chat> chats = new ArrayList<>();
    private String activeChatId;
    private OnChatClickListener listener;

    public interface OnChatClickListener {
        void onChatClick(Chat chat);
        void onDeleteChat(Chat chat);
        void onRenameChat(Chat chat);

        /** Long-press on a chat row; {@code anchor} is the row view for anchoring menus. */
        default void onChatLongPress(Chat chat, View anchor) {}

        /** Toggle pin state. Default falls back to rename handler (no-op when unset). */
        default void onTogglePin(Chat chat) {}

        /** Share the whole chat as text. */
        default void onShareChat(Chat chat) {}

        /** Move chat to Chat Mode. */
        default void onMoveToChatMode(Chat chat) {}

        /** Show loaded files for the chat. */
        default void onShowChatFiles(Chat chat) {}

        /** Open search-in-chat. */
        default void onSearchInChat(Chat chat) {}

        /** Request adding a home-screen shortcut. */
        default void onAddHomeShortcut(Chat chat) {}

        /** Archive or unarchive depending on current state. */
        default void onArchiveChat(Chat chat) {}
    }

    public void setOnChatClickListener(OnChatClickListener listener) {
        this.listener = listener;
    }

    public void setChats(List<Chat> chats) {
        this.chats = chats;
        notifyDataSetChanged();
    }

    public void setActiveChatId(String chatId) {
        this.activeChatId = chatId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_list, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        Chat chat = chats.get(position);
        holder.bind(chat);
    }

    @Override
    public int getItemCount() {
        return chats.size();
    }

    @Override
    public void onViewRecycled(@NonNull ChatViewHolder holder) {
        holder.cancelPendingHold();
        super.onViewRecycled(holder);
    }

    class ChatViewHolder extends RecyclerView.ViewHolder {
        private static final long HOLD_TO_OPEN_MS = 1000L;

        TextView chatTitle;
        TextView chatPreview;
        ImageButton btnDelete;
        private final int touchSlop;
        private float downX;
        private float downY;
        private boolean holdTriggered;
        private Chat boundChat;
        private final Runnable holdRunnable = () -> {
            if (boundChat == null || listener == null) return;
            holdTriggered = true;
            itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onChatLongPress(boundChat, itemView);
        };

        ChatViewHolder(View itemView) {
            super(itemView);
            chatTitle = itemView.findViewById(R.id.chatTitle);
            chatPreview = itemView.findViewById(R.id.chatPreview);
            btnDelete = itemView.findViewById(R.id.btnDeleteChat);
            touchSlop = ViewConfiguration.get(itemView.getContext()).getScaledTouchSlop();
        }

        void bind(Chat chat) {
            boundChat = chat;
            itemView.removeCallbacks(holdRunnable);
            holdTriggered = false;
            // Title gets a pin indicator prefix when pinned so the row mirrors its state.
            String title = chat.getTitle();
            chatTitle.setText(chat.isPinned() ? "📌 " + title : title);
            chatPreview.setText(chat.getLastMessagePreview());

            // Accessibility: announce pinned/archived state alongside the title.
            android.content.Context a11yCtx = itemView.getContext();
            StringBuilder desc = new StringBuilder(title == null ? a11yCtx.getString(app.multiai.mobile.R.string.chat) : title);
            if (chat.isPinned()) desc.append(", ").append(a11yCtx.getString(app.multiai.mobile.R.string.chat_a11y_pinned));
            if (chat.isArchived()) desc.append(", ").append(a11yCtx.getString(app.multiai.mobile.R.string.chat_a11y_archived));
            itemView.setContentDescription(desc.toString());

            // Подсветка активного чата
            if (chat.getId().equals(activeChatId)) {
                itemView.setBackgroundColor(0x1A9D4EDD); // Полупрозрачный фиолетовый
            } else {
                itemView.setBackgroundColor(0x00000000); // Прозрачный
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onChatClick(chat);
            });

            // 1-second hold opens the action sheet. The system long-press fires
            // at ~500ms, so we disable it entirely and use our own Handler-based
            // timer. onTouch returns false (doesn't consume), letting click work.
            itemView.setLongClickable(false);
            itemView.setOnLongClickListener(null);
            itemView.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        holdTriggered = false;
                        v.removeCallbacks(holdRunnable);
                        v.postDelayed(holdRunnable, HOLD_TO_OPEN_MS);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - downX) > touchSlop
                                || Math.abs(event.getRawY() - downY) > touchSlop) {
                            v.removeCallbacks(holdRunnable);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(holdRunnable);
                        break;
                }
                return false;
            });

            btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteChat(chat);
                }
            });
        }

        void cancelPendingHold() {
            itemView.removeCallbacks(holdRunnable);
            holdTriggered = false;
            boundChat = null;
        }
    }
}
