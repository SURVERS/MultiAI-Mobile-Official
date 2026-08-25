package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.multiai.mobile.R;
import app.multiai.mobile.models.Chat;
import app.multiai.mobile.models.Message;
import app.multiai.mobile.ui.ChatActivity;
import app.multiai.mobile.ui.ChatModeActivity;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;

/** Shared non-visual chat actions used by both work and chat mode menus. */
public final class ChatActions {

    public static final String EXTRA_CHAT_ID = "open_chat_id";
    public static final String EXTRA_CHAT_MODE = "open_chat_mode";

    private ChatActions() {}

    public static void share(Activity host, String title, List<Message> messages) {
        StringBuilder text = new StringBuilder();
        if (title != null && !title.trim().isEmpty()) text.append(title.trim()).append("\n\n");
        if (messages != null) {
            for (Message message : messages) {
                if (message == null || message.getContent() == null
                        || message.getContent().trim().isEmpty()) continue;
                text.append(message.getType() == Message.TYPE_USER
                                ? host.getString(R.string.chat_share_user_prefix) + " "
                                : host.getString(R.string.chat_share_ai_prefix) + " ")
                        .append(message.getContent().trim()).append("\n\n");
            }
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT,
                title == null ? host.getString(R.string.chat_share_default_title) : title);
        intent.putExtra(Intent.EXTRA_TEXT, text.toString().trim());
        host.startActivity(Intent.createChooser(intent, host.getString(R.string.chat_share_chooser)));
    }

    public static List<String> collectFiles(List<Message> messages) {
        Set<String> names = new LinkedHashSet<>();
        if (messages != null) {
            for (Message message : messages) {
                if (message == null) continue;
                if (message.hasAttachedFile()) names.add(message.getAttachedFileName());
                if (message.getImageName() != null && !message.getImageName().trim().isEmpty()) {
                    names.add(message.getImageName().trim());
                }
            }
        }
        return new ArrayList<>(names);
    }

    public static void showFiles(Activity host, List<Message> messages) {
        List<String> names = collectFiles(messages);
        if (names.isEmpty()) {
            ModernToast.show(host, host.getString(R.string.chat_no_files));
            return;
        }
        List<ModernSheet.MenuItem> items = new ArrayList<>();
        for (String name : names) {
            items.add(new ModernSheet.MenuItem(name, R.drawable.ic_file_outline, false, null));
        }
        ModernSheet.menu(host, host.getString(R.string.chat_files_title), items, (index, item) -> {});
    }

    public static void requestHomeShortcut(Activity host, Chat chat, boolean chatMode) {
        if (chat == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            ModernToast.show(host, host.getString(R.string.chat_shortcut_unsupported));
            return;
        }
        ShortcutManager manager = host.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            ModernToast.show(host, host.getString(R.string.chat_shortcut_launcher_unsupported));
            return;
        }
        Class<?> target = chatMode ? ChatModeActivity.class : ChatActivity.class;
        Intent open = new Intent(host, target)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_CHAT_ID, chat.getId())
                .putExtra(EXTRA_CHAT_MODE, chatMode)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String title = safeTitle(chat);
        ShortcutInfo info = new ShortcutInfo.Builder(host,
                "chat_" + (chatMode ? "personal_" : "work_") + chat.getId())
                .setShortLabel(title.length() > 20 ? title.substring(0, 20) : title)
                .setLongLabel(title)
                .setIcon(Icon.createWithResource(host, R.mipmap.ic_launcher))
                .setIntent(open)
                .build();
        manager.requestPinShortcut(info, null);
    }

    public static String safeTitle(Chat chat) {
        return safeTitle(chat, null);
    }

    /** Returns the chat title, or a localized default when missing. */
    public static String safeTitle(Chat chat, android.content.Context ctx) {
        if (chat == null || chat.getTitle() == null || chat.getTitle().trim().isEmpty()) {
            if (ctx != null) return ctx.getString(R.string.chat_safe_title_default);
            return "New chat";
        }
        return chat.getTitle().trim();
    }

    public static List<Integer> findMatches(List<Message> messages, String query) {
        List<Integer> result = new ArrayList<>();
        if (messages == null || query == null || query.trim().isEmpty()) return result;
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < messages.size(); i++) {
            String content = messages.get(i).getContent();
            if (content != null && content.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                result.add(i);
            }
        }
        return result;
    }

    public static String matchLabel(Message message) {
        return matchLabel(message, null);
    }

    public static String matchLabel(Message message, android.content.Context ctx) {
        if (message == null) {
            return ctx != null ? ctx.getString(R.string.chat_message_label) : "Message";
        }
        String content = message.getContent() == null ? "" : message.getContent().trim()
                .replace('\n', ' ');
        if (content.length() > 64) content = content.substring(0, 64) + "…";
        String prefix = message.getType() == Message.TYPE_USER
                ? (ctx != null ? ctx.getString(R.string.chat_match_user_prefix) : "You ·")
                : (ctx != null ? ctx.getString(R.string.chat_match_ai_prefix) : "MultiAI ·");
        return prefix + " "
                + (content.isEmpty()
                    ? (ctx != null ? ctx.getString(R.string.chat_message_label) : "Message")
                    : content);
    }
}
