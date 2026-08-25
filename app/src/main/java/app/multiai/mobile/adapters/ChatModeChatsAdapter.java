package app.multiai.mobile.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.models.Chat;

public class ChatModeChatsAdapter extends RecyclerView.Adapter<ChatModeChatsAdapter.ChatViewHolder> {
    private final List<Chat> chats = new ArrayList<>();
    private String activeChatId;
    private OnChatClickListener listener;
    private OnChatLongClickListener longClickListener;

    public interface OnChatClickListener {
        void onChatClick(Chat chat);
    }

    public interface OnChatLongClickListener {
        void onChatLongClick(Chat chat, View anchor);
    }

    public void setOnChatClickListener(OnChatClickListener listener) {
        this.listener = listener;
    }

    public void setOnChatLongClickListener(OnChatLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setChats(List<Chat> newChats) {
        chats.clear();
        if (newChats != null) {
            chats.addAll(newChats);
        }
        notifyDataSetChanged();
    }

    public void setActiveChatId(String activeChatId) {
        this.activeChatId = activeChatId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_mode_sidebar_chat, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        holder.bind(chats.get(position));
    }

    @Override
    public int getItemCount() {
        return chats.size();
    }

    class ChatViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView preview;
        private final android.widget.ImageView pinIcon;

        ChatViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.chatTitle);
            preview = itemView.findViewById(R.id.chatPreview);
            pinIcon = itemView.findViewById(R.id.chatPinIcon);
        }

        void bind(Chat chat) {
            String chatTitle = chat.getTitle();
            boolean pinned = chat.isPinned();
            String displayTitle = chatTitle == null || chatTitle.trim().isEmpty()
                    ? itemView.getContext().getString(app.multiai.mobile.R.string.chat_new_chat)
                    : chatTitle;
            title.setText(displayTitle);
            preview.setText(chat.getLastMessagePreview());

            // Custom pin icon (not emoji) — visible only for pinned chats.
            if (pinIcon != null) {
                pinIcon.setVisibility(pinned ? android.view.View.VISIBLE : android.view.View.GONE);
            }

            // Pinned chats get a surface_tonal background so they stand out from
            // regular rows. Active chat always takes priority with its selected bg.
            if (chat.getId().equals(activeChatId)) {
                itemView.setBackgroundResource(R.drawable.chat_mode_sidebar_chat_selected_bg);
            } else if (pinned) {
                android.graphics.drawable.GradientDrawable tonal = new android.graphics.drawable.GradientDrawable();
                tonal.setColor(androidx.core.content.ContextCompat.getColor(itemView.getContext(),
                        app.multiai.mobile.R.color.surface_tonal));
                tonal.setCornerRadius(app.multiai.mobile.ui.kit.UiKit.dp(itemView.getContext(), 12));
                itemView.setBackground(tonal);
            } else {
                itemView.setBackgroundResource(R.drawable.chat_mode_sidebar_chat_bg);
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onChatClick(chat);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onChatLongClick(chat, v);
                    return true;
                }
                return false;
            });
        }
    }
}
