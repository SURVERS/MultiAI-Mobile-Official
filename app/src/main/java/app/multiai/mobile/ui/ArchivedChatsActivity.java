package app.multiai.mobile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.models.Chat;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.utils.ChatActions;
import app.multiai.mobile.utils.ChatManager;
import app.multiai.mobile.utils.SecurityHelper;

/** Displays archived chats from both Project and personal Chat stores. */
public class ArchivedChatsActivity extends AppCompatActivity {

    private static final String CHAT_MODE_STORE = "multiai_chatmode_chats";

    private final List<ArchivedItem> items = new ArrayList<>();
    private RecyclerView recycler;
    private LinearLayout emptyState;
    private ArchivedAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        setContentView(R.layout.activity_archived_chats);
        SecurityHelper.applySecure(this);

        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        recycler = findViewById(R.id.recyclerArchivedChats);
        emptyState = findViewById(R.id.emptyState);
        adapter = new ArchivedAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        loadArchivedChats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) loadArchivedChats();
    }

    private void loadArchivedChats() {
        items.clear();
        for (Chat chat : new ChatManager(this).loadArchivedChats()) {
            items.add(new ArchivedItem(chat, false));
        }
        for (Chat chat : new ChatManager(this, CHAT_MODE_STORE).loadArchivedChats()) {
            items.add(new ArchivedItem(chat, true));
        }
        items.sort((left, right) -> {
            if (left.chat.isPinned() != right.chat.isPinned()) {
                return left.chat.isPinned() ? -1 : 1;
            }
            return Long.compare(right.chat.getUpdatedAt(), left.chat.getUpdatedAt());
        });
        adapter.notifyDataSetChanged();
        boolean empty = items.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private ChatManager managerFor(ArchivedItem item) {
        return item.chatMode
                ? new ChatManager(this, CHAT_MODE_STORE)
                : new ChatManager(this);
    }

    private void restoreAndOpen(ArchivedItem item) {
        ChatManager manager = managerFor(item);
        manager.unarchiveChat(item.chat.getId());
        manager.saveActiveChatId(item.chat.getId());
        loadArchivedChats();

        Class<?> target = item.chatMode ? ChatModeActivity.class : ChatActivity.class;
        Intent intent = new Intent(this, target)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ChatActions.EXTRA_CHAT_ID, item.chat.getId())
                .putExtra(ChatActions.EXTRA_CHAT_MODE, item.chatMode);
        startActivity(intent);
    }

    private void restore(ArchivedItem item) {
        managerFor(item).unarchiveChat(item.chat.getId());
        loadArchivedChats();
        ModernToast.show(this, getString(R.string.scr_archived_restored));
    }

    private void requestDelete(ArchivedItem item) {
        String title = ChatActions.safeTitle(item.chat, this);
        ModernDialog.show(this, getString(R.string.scr_archived_delete_title),
                getString(R.string.scr_archived_delete_msg, title),
                getString(R.string.chat_cancel), getString(R.string.action_delete), true, () -> {
                    managerFor(item).deleteChat(item.chat.getId());
                    loadArchivedChats();
                    ModernToast.show(this, getString(R.string.scr_archived_deleted));
                });
    }

    private void showActions(ArchivedItem item) {
        ModernSheet.menu(this, ChatActions.safeTitle(item.chat), Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.action_open), R.drawable.ic_chat, false, "open"),
                new ModernSheet.MenuItem(getString(R.string.action_unarchive), R.drawable.ic_folder_outline, false, "restore"),
                new ModernSheet.MenuItem(getString(R.string.action_delete), R.drawable.ic_trash, true, "delete")
        ), (index, menuItem) -> {
            String action = String.valueOf(menuItem.tag);
            if ("open".equals(action)) restoreAndOpen(item);
            else if ("restore".equals(action)) restore(item);
            else if ("delete".equals(action)) requestDelete(item);
        });
    }

    private static final class ArchivedItem {
        final Chat chat;
        final boolean chatMode;

        ArchivedItem(Chat chat, boolean chatMode) {
            this.chat = chat;
            this.chatMode = chatMode;
        }
    }

    private final class ArchivedAdapter extends RecyclerView.Adapter<ArchivedAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_archived_chat, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ArchivedItem item = items.get(position);
            String title = ChatActions.safeTitle(item.chat);
            holder.title.setText(item.chat.isPinned() ? "📌 " + title : title);
            holder.origin.setText(item.chatMode
                    ? R.string.archive_origin_chat
                    : R.string.archive_origin_project);
            holder.preview.setText(item.chat.getLastMessagePreview());
            String modeCd = getString(item.chatMode
                    ? R.string.scr_archived_cd_chat_mode
                    : R.string.scr_archived_cd_project);
            holder.itemView.setContentDescription(title + ", " + modeCd + ", "
                    + getString(R.string.scr_archived_cd_archived));
            holder.itemView.setOnClickListener(v -> restoreAndOpen(item));
            holder.itemView.setOnLongClickListener(v -> {
                showActions(item);
                return true;
            });
            holder.more.setOnClickListener(v -> showActions(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView origin;
            final TextView preview;
            final ImageButton more;

            Holder(View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.archivedChatTitle);
                origin = itemView.findViewById(R.id.archivedChatOrigin);
                preview = itemView.findViewById(R.id.archivedChatPreview);
                more = itemView.findViewById(R.id.btnArchivedMore);
            }
        }
    }
}
