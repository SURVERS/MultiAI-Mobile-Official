package app.multiai.mobile.adapters;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.multiai.mobile.R;
import app.multiai.mobile.models.Message;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import io.noties.markwon.Markwon;

public class ChatModeMessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final List<Message> messages = new ArrayList<>();
    private boolean[] hiddenExploreChildren = new boolean[0];
    private List<List<app.multiai.mobile.models.ToolCall>> exploreCallsByPosition =
            new ArrayList<>();
    private long indexedPresentationFingerprint;
    private Markwon markwon;
    private MessageActionListener actionListener;
    private OnErrorRetryListener errorRetryListener;
    private RecyclerView recyclerMessages;

    /** Никнейм юзера и имя текущей модели для форматирования при копировании. */
    private String userNickname = "";
    private String currentModelName = "";

    public void setUserNickname(String nickname) {
        this.userNickname = nickname != null ? nickname.trim() : "";
    }

    public void setCurrentModelName(String name) {
        this.currentModelName = name != null ? name.trim() : "";
    }

    /** Текст сообщения пользователя для копирования — без префикса «User:»/никнейма. */
    public String formatUserForCopy(Message message) {
        String content = message.getContent();
        return content != null ? content : "";
    }

    /** Форматирование ответа AI для копирования: «*Thinking*\n…\nAI (модель): ответ». */
    public String formatAiForCopy(Message message) {
        StringBuilder sb = new StringBuilder();
        String thinking = message.getThinkingContent();
        if (thinking != null && !thinking.trim().isEmpty()) {
            sb.append("*Thinking*\n").append(thinking.trim()).append("\n\n");
        }
        String model = currentModelName != null && !currentModelName.isEmpty() ? currentModelName : "AI";
        sb.append("AI (").append(model).append("): ");
        String content = message.getContent();
        if (content != null) {
            if (thinking != null && !thinking.trim().isEmpty()) {
                String trimmedThinking = thinking.trim();
                String trimmedContent = content.trim();
                if (trimmedContent.startsWith(trimmedThinking)) {
                    content = content.substring(content.indexOf(trimmedThinking) + trimmedThinking.length()).trim();
                }
            }
            sb.append(content);
        }
        return sb.toString();
    }

    /** Форматирование даты/времени для заголовка popup-sheet. */
    static String formatMessageDate(Context ctx, long timestamp) {
        if (timestamp <= 0) return "";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar msg = java.util.Calendar.getInstance();
        msg.setTimeInMillis(timestamp);
        boolean sameDay = now.get(java.util.Calendar.YEAR) == msg.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == msg.get(java.util.Calendar.DAY_OF_YEAR);
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        boolean isYesterday = now.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR)
                && yesterday.get(java.util.Calendar.DAY_OF_YEAR) == msg.get(java.util.Calendar.DAY_OF_YEAR);
        java.text.SimpleDateFormat timeFmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        String time = timeFmt.format(msg.getTime());
        if (sameDay) return time;
        if (isYesterday) return ctx.getString(R.string.chat_yesterday) + ", " + time;
        java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault());
        return dateFmt.format(msg.getTime()) + ", " + time;
    }

    /** Показать popup-sheet рядом с сообщением: Скопировать/Выделить текст (без даты и share). */
    private void showMessagePopup(View anchor, Message message) {
        Context ctx = anchor.getContext();
        boolean isUser = message.getType() == Message.TYPE_USER;

        java.util.List<app.multiai.mobile.ui.kit.ModernSheet.MenuItem> items = new java.util.ArrayList<>();
        items.add(new app.multiai.mobile.ui.kit.ModernSheet.MenuItem(
                ctx.getString(R.string.kit_msg_action_copy), R.drawable.ic_action_copy, false, null));
        items.add(new app.multiai.mobile.ui.kit.ModernSheet.MenuItem(
                ctx.getString(R.string.kit_msg_action_select_text), R.drawable.file_document, false, null));
        if (isUser) {
            items.add(new app.multiai.mobile.ui.kit.ModernSheet.MenuItem(
                    ctx.getString(R.string.chat_edit), R.drawable.ic_edit_pencil, false, null));
        }

        app.multiai.mobile.ui.kit.ModernMenu.show(anchor, null, items, (idx, item) -> {
            if (idx == 0) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    String text = isUser ? formatUserForCopy(message) : formatAiForCopy(message);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text));
                    if (ctx instanceof android.app.Activity) {
                        app.multiai.mobile.ui.kit.ModernToast.show((android.app.Activity) ctx,
                                ctx.getString(R.string.kit_msg_copied));
                    }
                }
            } else if (idx == 1) {
                // Выделить текст — bottom sheet с полным текстом для выделения
                showSelectTextSheet(anchor, message);
            } else if (idx == 2 && isUser) {
                // Изменить — enable inline editing mode
                message.setEditing(true);
                int pos = messages.indexOf(message);
                if (pos >= 0) notifyItemChanged(pos);
            }
        });
    }

    /** Показать bottom sheet с полным текстом сообщения для выделения и копирования. */
    private void showSelectTextSheet(View anchor, Message message) {
        Context ctx = anchor.getContext();
        if (!(ctx instanceof android.app.Activity)) return;
        android.app.Activity activity = (android.app.Activity) ctx;

        String fullText = message.getType() == Message.TYPE_USER
                ? formatUserForCopy(message) : formatAiForCopy(message);
        if (fullText == null || fullText.isEmpty()) return;

        // Create bottom sheet dialog
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(activity);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        // Title
        android.widget.TextView title = new android.widget.TextView(activity);
        title.setText(R.string.kit_msg_action_select_text);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(title);

        // Selectable text
        android.widget.TextView textView = new android.widget.TextView(activity);
        textView.setText(fullText);
        textView.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.text_primary));
        textView.setTextSize(15);
        textView.setTextIsSelectable(true);
        textView.setHighlightColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                androidx.core.content.ContextCompat.getColor(activity, R.color.accent), 66));
        textView.setLineSpacing(0, 1.2f);
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(textView, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.45)));
        layout.addView(scroll);

        sheet.setContentView(layout);
        // Expand to half screen
        com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior =
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(
                        (View) layout.getParent());
        if (behavior != null) {
            behavior.setPeekHeight((int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5));
            behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        }
        sheet.show();
    }

    private android.widget.TextView findSelectableTextView(android.view.ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            if (child instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) child;
                if (tv.getText() != null && tv.getText().length() > 0 && tv.getVisibility() == android.view.View.VISIBLE) {
                    return tv;
                }
            }
            if (child instanceof android.view.ViewGroup) {
                android.widget.TextView nested = findSelectableTextView((android.view.ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    // Thumbnail cache: messageId → bitmap. Avoids re-decoding full base64 on every bind.
    private static final int THUMB_MAX_DIM = 512; // px
    private static final android.util.LruCache<String, android.graphics.Bitmap> thumbCache =
            new android.util.LruCache<String, android.graphics.Bitmap>(24) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) { return 1; }
            };

    // Stable like/dislike state keyed by message identity (survives ViewHolder recycling).
    private final java.util.Set<String> likedIds = new java.util.HashSet<>();
    private final java.util.Set<String> dislikedIds = new java.util.HashSet<>();

    private static String identityKey(Message message) {
        if (message == null) return "";
        return Integer.toHexString(System.identityHashCode(message));
    }

    public boolean isLiked(Message message) {
        return likedIds.contains(identityKey(message));
    }

    public boolean isDisliked(Message message) {
        return dislikedIds.contains(identityKey(message));
    }

    public boolean toggleLike(Message message) {
        String key = identityKey(message);
        if (likedIds.contains(key)) {
            likedIds.remove(key);
            return false;
        }
        likedIds.add(key);
        dislikedIds.remove(key);
        return true;
    }

    public boolean toggleDislike(Message message) {
        String key = identityKey(message);
        if (dislikedIds.contains(key)) {
            dislikedIds.remove(key);
            return false;
        }
        dislikedIds.add(key);
        likedIds.remove(key);
        return true;
    }

    public interface MessageActionListener {
        void onCopy(Message message);
        void onLike(Message message);
        void onDislike(Message message);
        void onTts(Message message);
        void onShare(Message message);
        void onMore(Message message, View anchor);
    }

    /** Callback для кнопок «Повторить/Изменить» под сообщением пользователя. */
    public interface OnUserMessageActionListener {
        void onUserMessageRetry(Message message, int position);
        void onUserMessageEdit(Message message, int position);
        /** Called when user finishes inline editing and saves the message. */
        void onUserMessageEdited(Message message, int position, String newContent);
        /** «+» в режиме правки: прикрепить камерой/фото/файлом к редактируемому сообщению. */
        void onUserMessageAttach(Message message, int position, String kind);
    }

    private OnUserMessageActionListener userMessageActionListener;

    public void setUserMessageActionListener(OnUserMessageActionListener listener) {
        this.userMessageActionListener = listener;
    }

    public void setMessageActionListener(MessageActionListener listener) {
        this.actionListener = listener;
    }

    public void setOnErrorRetryListener(OnErrorRetryListener listener) {
        this.errorRetryListener = listener;
    }

    public void setRecyclerView(RecyclerView recyclerView) {
        this.recyclerMessages = recyclerView;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (markwon == null) {
            // LaTeX обязателен и тут: $...$ / $$...$$ должны рендериться
            // в чат-режиме так же, как в ворк-режиме. Inline-LaTeX требует
            // MarkwonInlineParserPlugin — без него build() падает.
            markwon = io.noties.markwon.Markwon.builder(parent.getContext())
                    .usePlugin(io.noties.markwon.inlineparser.MarkwonInlineParserPlugin.create())
                    .usePlugin(io.noties.markwon.ext.latex.JLatexMathPlugin.create(
                            io.noties.markwon.ext.latex.JLatexMathPlugin.builder(
                                    android.util.TypedValue.applyDimension(
                                            android.util.TypedValue.COMPLEX_UNIT_SP,
                                            16f,
                                            parent.getResources().getDisplayMetrics()))
                                    .blocksEnabled(true)
                                    .blocksLegacy(true)
                                    .inlinesEnabled(true)
                                    .build()))
                    .build();
        }

        int layoutId;
        if (viewType == Message.TYPE_USER) {
            layoutId = R.layout.item_chat_mode_message_user;
        } else if (viewType == Message.TYPE_AI_TYPING) {
            layoutId = R.layout.item_chat_mode_message_typing;
        } else if (viewType == Message.TYPE_RECONNECTING) {
            layoutId = R.layout.item_reconnecting;
        } else if (viewType == Message.TYPE_TOOL_EXECUTION) {
            layoutId = R.layout.item_chat_mode_message_tool;
        } else if (viewType == Message.TYPE_ERROR) {
            // Error — отдельная карточка, не просто текст в чате.
            layoutId = R.layout.item_message_error;
        } else {
            layoutId = R.layout.item_chat_mode_message_ai;
        }

        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        if (viewType == Message.TYPE_AI_TYPING) {
            return new TypingViewHolder(view);
        }
        if (viewType == Message.TYPE_RECONNECTING) {
            return new ReconnectingViewHolder(view);
        }
        if (viewType == Message.TYPE_TOOL_EXECUTION) {
            return new ToolViewHolder(view);
        }
        if (viewType == Message.TYPE_ERROR) {
            return new ErrorViewHolder(view);
        }
        return new TextViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message message = messages.get(position);
        boolean hidden = message.isRetryPending()
                || (position >= 0 && position < hiddenExploreChildren.length
                && hiddenExploreChildren[position]);
        ViewGroup.LayoutParams itemParams = holder.itemView.getLayoutParams();
        if (itemParams != null) {
            int wantedHeight = hidden ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (itemParams.height != wantedHeight) {
                itemParams.height = wantedHeight;
                holder.itemView.setLayoutParams(itemParams);
            }
        }
        holder.itemView.setVisibility(hidden ? View.GONE : View.VISIBLE);
        // Root callbacks are cleared on recycle. RecyclerView owns the AI
        // long-press detector so interactive descendants cannot swallow it.
        holder.itemView.setOnLongClickListener(null);
        if (hidden) return;
        if (holder instanceof TextViewHolder) {
            ((TextViewHolder) holder).bind(message);
        } else if (holder instanceof TypingViewHolder) {
            ((TypingViewHolder) holder).bind(message);
        } else if (holder instanceof ReconnectingViewHolder) {
            ((ReconnectingViewHolder) holder).bind(message);
        } else if (holder instanceof ToolViewHolder) {
            List<app.multiai.mobile.models.ToolCall> grouped = position < exploreCallsByPosition.size()
                    ? exploreCallsByPosition.get(position) : null;
            ((ToolViewHolder) holder).bind(message, grouped);
        } else if (holder instanceof ErrorViewHolder) {
            ((ErrorViewHolder) holder).bind(message, errorRetryListener);
        }
        // Long-press popup for both user and AI messages
        boolean canPopup = (message.getType() == Message.TYPE_USER || message.getType() == Message.TYPE_AI)
                && !message.isStreaming();
        holder.itemView.setOnLongClickListener(canPopup ? v -> {
            showMessagePopup(v, message);
            return true;
        } : null);
    }

    // 🔥 Стриминг: обновляем ТОЛЬКО текст без повторного bind (никакого lag)
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof TextViewHolder) {
            Message message = messages.get(position);
            for (Object p : payloads) {
                if ("CONTENT_UPDATE".equals(p)) {
                    ((TextViewHolder) holder).updateContent(message);
                    return;
                }
                if ("THINKING_UPDATE".equals(p)) {
                    ((TextViewHolder) holder).updateThinking(message);
                    return;
                }
            }
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof TextViewHolder) {
            TextViewHolder textHolder = (TextViewHolder) holder;
            textHolder.contentReveal.reset();
            app.multiai.mobile.utils.Telemoji.clearTracking(textHolder.textMessage);
            if (textHolder.textThinking != null) {
                app.multiai.mobile.utils.Telemoji.clearTracking(textHolder.textThinking);
            }
            if (textHolder.thinkingPreview != null) {
                app.multiai.mobile.utils.Telemoji.clearTracking(textHolder.thinkingPreview);
            }
        }
        super.onViewRecycled(holder);
    }

    /** Быстрое обновление контента последнего AI-сообщения без пересоздания ViewHolder. */
    public void notifyLastContentChanged() {
        int idx = messages.size() - 1;
        if (idx >= 0) {
            notifyItemChanged(idx, "CONTENT_UPDATE");
        }
    }

    /** Обновляет видимый streaming-бабл без notifyItemChanged и повторного bind. */
    public void updateMessageContentDirect(Message message, String content) {
        int index = messages.indexOf(message);
        if (index < 0) return;
        message.setContent(content);
        RecyclerView.ViewHolder holder = recyclerMessages != null
                ? recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof TextViewHolder) {
            ((TextViewHolder) holder).updateStreamingContent(content);
        }
    }

    /** Финальный тяжёлый рендер выполняется ровно один раз после стрима. */
    public void finalizeMessageContent(Message message) {
        int index = messages.indexOf(message);
        if (index < 0) return;
        message.setStreaming(false);
        if (message.hasThinking() && message.getThinkingTimeMs() > 0L) {
            message.setThinkingFinished(true);
        }
        RecyclerView.ViewHolder holder = recyclerMessages != null
                ? recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof TextViewHolder) {
            ((TextViewHolder) holder).finishStreaming(message);
        } else {
            notifyItemChanged(index, "CONTENT_UPDATE");
        }
    }

    public void updateMessageThinkingDirect(Message message, String thinking) {
        int index = messages.indexOf(message);
        if (index < 0) return;
        message.setThinkingContent(thinking);
        RecyclerView.ViewHolder holder = recyclerMessages != null
                ? recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof TextViewHolder) {
            ((TextViewHolder) holder).updateThinking(message);
        } else {
            notifyItemChanged(index, "THINKING_UPDATE");
        }
    }

    /** Быстрое обновление thinking-блока последнего AI-сообщения (стрим размышлений). */
    public void notifyLastThinkingChanged() {
        int idx = messages.size() - 1;
        if (idx >= 0) {
            notifyItemChanged(idx, "THINKING_UPDATE");
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void setMessages(List<Message> newMessages) {
        List<Message> source = newMessages != null ? newMessages : Collections.emptyList();
        if (sameMessageSequence(source)
                && presentationFingerprint(source) == indexedPresentationFingerprint) return;
        messages.clear();
        messages.addAll(source);
        rebuildExploreIndex();
        notifyDataSetChanged();
    }

    private boolean sameMessageSequence(List<Message> candidate) {
        if (candidate.size() != messages.size()) return false;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) != candidate.get(i)) return false;
        }
        return true;
    }

    public void addMessage(Message message) {
        int insertedIndex = messages.size();
        messages.add(message);
        rebuildExploreIndex();
        notifyItemInserted(insertedIndex);

        int exploreAnchor = findExploreAnchor(insertedIndex);
        if (exploreAnchor >= 0 && exploreAnchor != insertedIndex) {
            notifyItemChanged(exploreAnchor);
        }

        // Вставка следующего tool/AI-сегмента меняет финальность предыдущего AI.
        if (insertedIndex > 0) {
            Message previous = messages.get(insertedIndex - 1);
            boolean affectsPreviousActionBar = message.getType() == Message.TYPE_TOOL_EXECUTION
                    || message.getType() == Message.TYPE_AI;
            if (previous.getType() == Message.TYPE_AI && affectsPreviousActionBar) {
                notifyItemChanged(insertedIndex - 1);
            }
        }
    }

    /**
     * Вставляет сообщение перед любыми trailing typing-индикаторами, чтобы
     * анимированный логотип оставался в самом низу чата во время стриминга.
     */
    public void insertBeforeTrailingTyping(Message message) {
        int insertAt = messages.size();
        while (insertAt > 0
                && messages.get(insertAt - 1).getType() == Message.TYPE_AI_TYPING) {
            insertAt--;
        }
        messages.add(insertAt, message);
        rebuildExploreIndex();
        notifyItemInserted(insertAt);

        int exploreAnchor = findExploreAnchor(insertAt);
        if (exploreAnchor >= 0 && exploreAnchor != insertAt) {
            notifyItemChanged(exploreAnchor);
        }

        if (insertAt > 0) {
            Message previous = messages.get(insertAt - 1);
            boolean affectsPreviousActionBar = message.getType() == Message.TYPE_TOOL_EXECUTION
                    || message.getType() == Message.TYPE_AI;
            if (previous.getType() == Message.TYPE_AI && affectsPreviousActionBar) {
                notifyItemChanged(insertAt - 1);
            }
        }
    }

    public boolean removeMessage(Message message) {
        int index = messages.indexOf(message);
        if (index < 0) return false;
        messages.remove(index);
        rebuildExploreIndex();
        notifyItemRemoved(index);
        int changed = Math.min(index, messages.size() - 1);
        if (changed >= 0) {
            int exploreAnchor = findExploreAnchor(changed);
            notifyItemChanged(exploreAnchor >= 0 ? exploreAnchor : changed);
        }
        return true;
    }

    public void refreshMessage(Message message) {
        int index = messages.indexOf(message);
        if (index < 0) return;
        rebuildExploreIndex();
        int anchor = findExploreAnchor(index);
        notifyItemChanged(anchor >= 0 ? anchor : index);
    }

    public void refreshMessages(List<Message> changedMessages) {
        if (changedMessages == null || changedMessages.isEmpty()) return;
        rebuildExploreIndex();
        java.util.LinkedHashSet<Integer> anchors = new java.util.LinkedHashSet<>();
        for (Message message : changedMessages) {
            int index = messages.indexOf(message);
            if (index < 0) continue;
            int anchor = findExploreAnchor(index);
            anchors.add(anchor >= 0 ? anchor : index);
        }
        for (Integer anchor : anchors) notifyItemChanged(anchor);
    }

    /** Прямой доступ к списку сообщений (для точечных обновлений). */
    public java.util.List<Message> getMessagesList() {
        return messages;
    }

    public void removeTrailingTypingMessages() {
        int originalCount = messages.size();
        int firstRemoved = originalCount;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getType() == Message.TYPE_AI_TYPING) {
                messages.remove(i);
                firstRemoved = i;
            } else {
                break;
            }
        }
        rebuildExploreIndex();
        if (firstRemoved < originalCount) {
            notifyItemRangeRemoved(firstRemoved, originalCount - firstRemoved);
        }
    }

    /** Removes transient reconnect status rows; they are never persisted in a chat. */
    public void removeReconnectingMessages() {
        boolean removedAny = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getType() == Message.TYPE_RECONNECTING) {
                messages.remove(i);
                notifyItemRemoved(i);
                removedAny = true;
            }
        }
        if (removedAny) {
            rebuildExploreIndex();
        }
    }

    public void showReconnecting(String text) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            int type = messages.get(i).getType();
            if (type == Message.TYPE_AI_TYPING || type == Message.TYPE_RECONNECTING) {
                messages.remove(i);
            }
        }
        messages.add(new Message(text, Message.TYPE_RECONNECTING));
        rebuildExploreIndex();
        notifyDataSetChanged();
    }

    public void showTypingAfterReconnect() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getType() == Message.TYPE_RECONNECTING) messages.remove(i);
        }
        if (messages.isEmpty() || messages.get(messages.size() - 1).getType() != Message.TYPE_AI_TYPING) {
            messages.add(new Message("", Message.TYPE_AI_TYPING));
        }
        rebuildExploreIndex();
        notifyDataSetChanged();
    }

    private void rebuildExploreIndex() {
        hiddenExploreChildren = new boolean[messages.size()];
        exploreCallsByPosition = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) exploreCallsByPosition.add(null);

        int i = 0;
        while (i < messages.size()) {
            Message message = messages.get(i);
            if (message == null || message.getType() != Message.TYPE_TOOL_EXECUTION
                    || !message.hasToolCalls()) {
                i++;
                continue;
            }
            if (!isExploreCard(message)) {
                i++;
                continue;
            }
            List<app.multiai.mobile.models.ToolCall> group = new ArrayList<>();
            int end = i;
            while (end < messages.size()) {
                Message candidate = messages.get(end);
                if (!isExploreCard(candidate)) break;
                List<app.multiai.mobile.models.ToolCall> calls = candidate.getToolCalls();
                group.addAll(calls);
                end++;
            }
            exploreCallsByPosition.set(i, Collections.unmodifiableList(group));
            for (int child = i + 1; child < end; child++) hiddenExploreChildren[child] = true;
            i = end;
        }
        indexedPresentationFingerprint = presentationFingerprint(messages);
    }

    private static boolean isExploreCard(Message message) {
        if (message == null || message.getType() != Message.TYPE_TOOL_EXECUTION
                || !message.hasToolCalls()) return false;
        for (app.multiai.mobile.models.ToolCall call : message.getToolCalls()) {
            if (call == null || !app.multiai.mobile.views.ToolWidgetRenderer
                    .isExploreTool(call.getName())) return false;
        }
        return true;
    }

    private int findExploreAnchor(int position) {
        if (position < 0 || position >= messages.size()) return -1;
        int anchor = position;
        while (anchor > 0 && hiddenExploreChildren[anchor]) anchor--;
        return exploreCallsByPosition.get(anchor) != null ? anchor : -1;
    }

    private static long presentationFingerprint(List<Message> source) {
        long hash = 1125899906842597L;
        for (Message message : source) {
            if (message == null) {
                hash = 31L * hash;
                continue;
            }
            hash = 31L * hash + message.getType();
            hash = 31L * hash + message.getContentSafe().hashCode();
            hash = 31L * hash + java.util.Objects.hashCode(message.getThinkingContent());
            for (app.multiai.mobile.models.ToolCall call : message.getToolCalls()) {
                if (call == null) {
                    hash = 31L * hash;
                    continue;
                }
                hash = 31L * hash + System.identityHashCode(call);
                hash = 31L * hash + java.util.Objects.hashCode(call.getStatus());
                hash = 31L * hash + System.identityHashCode(call.getResult());
            }
        }
        return hash;
    }

    class TextViewHolder extends RecyclerView.ViewHolder {
        private final TextView textMessage;
        private final LinearLayout actionBar;
        private final ImageView btnCopy;
        private final ImageView btnLike;
        private final ImageView btnDislike;
        private final ImageView btnTts;
        private final ImageView btnMore;
        private final View variantPager;
        private final ImageView variantPrev;
        private final ImageView variantNext;
        private final TextView variantIndicator;
        private boolean liked;
        private boolean disliked;
        // Thinking-блок (только в AI-layout; в user-layout == null)
        private final LinearLayout thinkingContainer;
        private final LinearLayout thinkingBody;
        private final TextView textThinking;
        private final TextView thinkingPreview;
        private final app.multiai.mobile.ui.ThinkingWaveIconView thinkingWaveIcon;
        private String displayedThinkingPreview = "";
        private int displayedThinkingParagraphCount;
        private int thinkingPreviewAnimationGeneration;
        // Полноформатный рендер LaTeX/кода (пайплайн Work-режима)
        private final LinearLayout richContentContainer;
        // Вложения (только в user-layout; в AI-layout == null)
        private final androidx.cardview.widget.CardView imageCard;
        private final ImageView imageAttachment;
        private final LinearLayout fileAttachmentCard;
        private final TextView fileAttachmentName;
        private final TextView fileAttachmentSize;

        TextViewHolder(View itemView) {
            super(itemView);
            textMessage = itemView.findViewById(R.id.textMessage);
            actionBar = itemView.findViewById(R.id.actionBar);
            btnCopy = itemView.findViewById(R.id.btnCopy);
            btnLike = itemView.findViewById(R.id.btnLike);
            btnDislike = itemView.findViewById(R.id.btnDislike);
            btnTts = itemView.findViewById(R.id.btnTts);
            btnMore = itemView.findViewById(R.id.btnMore);
            variantPager = itemView.findViewById(R.id.variantPager);
            variantPrev = itemView.findViewById(R.id.variantPrev);
            variantNext = itemView.findViewById(R.id.variantNext);
            variantIndicator = itemView.findViewById(R.id.variantIndicator);
            thinkingContainer = itemView.findViewById(R.id.thinkingContainer);
            thinkingBody = itemView.findViewById(R.id.thinkingBody);
            textThinking = itemView.findViewById(R.id.textThinking);
            thinkingPreview = itemView.findViewById(R.id.thinkingPreview);
            thinkingWaveIcon = itemView.findViewById(R.id.thinkingWaveIcon);
            richContentContainer = itemView.findViewById(R.id.richContentContainer);
            imageCard = itemView.findViewById(R.id.imageCard);
            imageAttachment = itemView.findViewById(R.id.imageAttachment);
            fileAttachmentCard = itemView.findViewById(R.id.fileAttachmentCard);
            fileAttachmentName = itemView.findViewById(R.id.fileAttachmentName);
            fileAttachmentSize = itemView.findViewById(R.id.fileAttachmentSize);
        }

        private boolean isVisuallyBlank(String content) {
            return content == null || content.trim().isEmpty();
        }

        private String cleanThinkingForDisplay(String thinking) {
            if (thinking == null || thinking.isEmpty()) return "";
            return thinking.replaceAll(
                    "(?is)</?(?:think|thinking|reasoning)>", "").trim();
        }

        private String[] thinkingParagraphs(String thinking) {
            if (thinking == null || thinking.trim().isEmpty()) return new String[0];
            String normalized = thinking.replace("\\r\\n", "\\n").trim();
            return normalized.split("\\n\\s*\\n");
        }

        private String thinkingParagraphPreview(String paragraph) {
            if (paragraph == null) return "";
            String firstLine = paragraph.trim().split("\\R", 2)[0]
                    .replaceAll("^[#>*`\\-\\s]+", "")
                    .replaceAll("\\s+", " ").trim();
            return firstLine.length() > 160 ? firstLine.substring(0, 157) + "…" : firstLine;
        }

        private void updateThinkingPreview(Message message, String thinking) {
            if (thinkingPreview == null) return;
            boolean thinkingFinished = message.isThinkingFinished();
            String[] paragraphs = thinkingParagraphs(thinking);
            String preview;
            if (thinkingFinished) {
                preview = itemView.getContext().getString(R.string.chat_thinking_finished,
                        message.getThinkingTimeMs() / 1000f);
            } else if (paragraphs.length > 0) {
                preview = itemView.getContext().getString(R.string.lay_thinking_process);
            } else {
                preview = itemView.getContext().getString(R.string.lay_thinking_process);
            }
            if (thinkingWaveIcon != null) {
                thinkingWaveIcon.setRunning(message.isStreaming() && !thinkingFinished);
            }
            if (preview.equals(displayedThinkingPreview)) return;
            displayedThinkingPreview = preview;
            int generation = ++thinkingPreviewAnimationGeneration;
            thinkingPreview.animate().cancel();
            if (thinkingPreview.getText().length() == 0) {
                thinkingPreview.setAlpha(1f);
                thinkingPreview.setTranslationY(0f);
                thinkingPreview.setText(preview);
                return;
            }

            String next = preview;
            float shift = itemView.getResources().getDisplayMetrics().density * 4f;
            thinkingPreview.animate().alpha(0f).translationY(-shift)
                    .setDuration(100)
                    .withEndAction(() -> {
                        if (generation != thinkingPreviewAnimationGeneration) return;
                        thinkingPreview.setText(next);
                        thinkingPreview.setTranslationY(shift);
                        thinkingPreview.animate().alpha(1f).translationY(0f)
                                .setDuration(150).start();
                    }).start();
        }

        private void updateActionBarVisibility(Message message) {
            if (actionBar == null || message == null) return;
            int index = messages.indexOf(message);
            Message next = index >= 0 && index + 1 < messages.size()
                    ? messages.get(index + 1) : null;
            boolean nextIsToolCard = next != null
                    && next.getType() == Message.TYPE_TOOL_EXECUTION;
            boolean nextIsAssistantSegment = next != null
                    && next.getType() == Message.TYPE_AI;
            boolean visible = message.getType() == Message.TYPE_AI
                    && !message.isStreaming()
                    && !isVisuallyBlank(message.getContent())
                    && !message.hasToolCalls()
                    && !nextIsToolCard
                    && !nextIsAssistantSegment;
            actionBar.setVisibility(visible ? View.VISIBLE : View.GONE);
            app.multiai.mobile.views.MessageSourcesView.bind(actionBar, messages, message, visible);
        }

        private void hideAiContent() {
            app.multiai.mobile.utils.Telemoji.clearTracking(textMessage);
            textMessage.setText("");
            textMessage.setVisibility(View.GONE);
            if (richContentContainer != null) {
                richContentContainer.removeAllViews();
                richContentContainer.setVisibility(View.GONE);
            }
        }

        /** Generated image on an AI message (image-model turns): shown above the text. */
        private void bindAiImage(Message message) {
            androidx.cardview.widget.CardView imageCard = itemView.findViewById(R.id.imageCard);
            android.widget.ImageView imageView = itemView.findViewById(R.id.imageAttachment);
            if (imageCard == null || imageView == null) return;
            if (!message.hasImage()) {
                imageCard.setVisibility(View.GONE);
                return;
            }
            imageCard.setVisibility(View.VISIBLE);
            imageView.setOnClickListener(v -> app.multiai.mobile.utils.GeneratedImageActions.show(itemView.getContext(), message));
            final String cacheKey = message.getId() != null ? message.getId() : String.valueOf(message.hashCode());
            android.graphics.Bitmap cached = thumbCache.get(cacheKey);
            if (cached != null) {
                imageView.setImageBitmap(cached);
                return;
            }
            imageView.setImageDrawable(null);
            final android.widget.ImageView target = imageView;
            final int targetPos = getBindingAdapterPosition();
            new Thread(() -> {
                android.graphics.Bitmap bitmap = decodeImageThumbnail(message, THUMB_MAX_DIM);
                if (bitmap != null) thumbCache.put(cacheKey, bitmap);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (targetPos != getBindingAdapterPosition()) return;
                    if (bitmap != null) {
                        target.setImageBitmap(bitmap);
                    } else {
                        imageCard.setVisibility(View.GONE);
                    }
                });
            }, "ai-img-decode").start();
        }

        void bind(Message message) {
            String content = message.getContent() != null ? message.getContent() : "";
            if (message.getType() == Message.TYPE_ERROR) {
                // Error messages are handled by ErrorViewHolder (card layout).
                // TextViewHolder should never receive TYPE_ERROR.
                textMessage.setText(content);
                textMessage.setTextColor(color(R.color.error));
            } else if (message.getType() == Message.TYPE_AI) {
                textMessage.setTextColor(color(R.color.text_primary));
                bindAiImage(message);
                if (message.hasImage()) {
                    hideAiContent();
                } else {
                    updateContent(message);
                }
            } else {
                textMessage.setTextColor(color(R.color.text_primary));
                if (message.isEditing()) {
                    // ─── Режим правки, 1-в-1 как в Work-режиме ───
                    // Пузырь прячется, вместо него — карточка с многострочным
                    // полем, предупреждением и рядом [+] … [✕ ↑].
                    android.view.ViewGroup itemViewRoot = (android.view.ViewGroup) itemView;
                    View bubble = itemView.findViewById(R.id.userBubbleContainer);
                    if (bubble != null) bubble.setVisibility(View.GONE);
                    android.content.Context ctx = itemView.getContext();
                    int accent = app.multiai.mobile.utils.AccentColor.resolve(ctx);
                    int accentFg = app.multiai.mobile.utils.AccentColor.contrastForeground(accent);

                    android.widget.LinearLayout editCard = itemView.findViewById(R.id.editCard);
                    android.widget.EditText editField = itemView.findViewById(R.id.editMessageField);
                    android.widget.LinearLayout editActionsRow = itemView.findViewById(R.id.editActionsRow);
                    if (editCard == null) {
                        editCard = new android.widget.LinearLayout(ctx);
                        editCard.setId(R.id.editCard);
                        editCard.setVisibility(View.GONE);
                        editCard.setOrientation(android.widget.LinearLayout.VERTICAL);
                        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
                        cardBg.setCornerRadius(dp(20));
                        cardBg.setColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.surface_card));
                        cardBg.setStroke(Math.max(1, dp(1)),
                                androidx.core.content.ContextCompat.getColor(ctx, R.color.stroke));
                        editCard.setBackground(cardBg);
                        android.widget.LinearLayout.LayoutParams cardLp = new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                        cardLp.setMargins(dp(4), dp(2), dp(4), 0);
                        editCard.setLayoutParams(cardLp);

                        editField = new android.widget.EditText(ctx);
                        editField.setId(R.id.editMessageField);
                        editField.setBackground(null);
                        editField.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_primary));
                        editField.setHintTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_hint));
                        editField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                        editField.setMinLines(2);
                        editField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                        int fieldPad = dp(16);
                        editField.setPadding(fieldPad, dp(14), fieldPad, dp(6));
                        editCard.addView(editField, new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                        android.widget.TextView warning = new android.widget.TextView(ctx);
                        warning.setId(R.id.editWarning);
                        warning.setText(R.string.chat_edit_inline_hint);
                        warning.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_hint));
                        warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                        warning.setPadding(fieldPad, 0, fieldPad, dp(12));
                        editCard.addView(warning, new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                        itemViewRoot.addView(editCard);

                        editActionsRow = new android.widget.LinearLayout(ctx);
                        editActionsRow.setId(R.id.editActionsRow);
                        editActionsRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        editActionsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                        rowLp.setMargins(dp(4), dp(8), dp(4), dp(2));
                        editActionsRow.setLayoutParams(rowLp);

                        android.widget.ImageView attachBtn = new android.widget.ImageView(ctx);
                        attachBtn.setId(R.id.editAttachButton);
                        attachBtn.setImageResource(R.drawable.ic_plus);
                        attachBtn.setColorFilter(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary));
                        attachBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
                        attachBtn.setContentDescription(ctx.getString(R.string.chat_edit));
                        editActionsRow.addView(attachBtn, new android.widget.LinearLayout.LayoutParams(dp(28), dp(28)));

                        View spacer = new View(ctx);
                        editActionsRow.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, 1, 1f));

                        android.widget.ImageView cancelBtn = new android.widget.ImageView(ctx);
                        cancelBtn.setId(R.id.editCancelButton);
                        cancelBtn.setImageResource(R.drawable.ic_close);
                        cancelBtn.setColorFilter(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary));
                        int btnPad = dp(8);
                        cancelBtn.setPadding(btnPad, btnPad, btnPad, btnPad);
                        android.graphics.drawable.GradientDrawable cancelBg = new android.graphics.drawable.GradientDrawable();
                        cancelBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        cancelBg.setColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.surface_tonal));
                        cancelBg.setStroke(Math.max(1, dp(1)),
                                androidx.core.content.ContextCompat.getColor(ctx, R.color.stroke));
                        cancelBtn.setBackground(cancelBg);
                        cancelBtn.setContentDescription(ctx.getString(R.string.chat_cancel));
                        android.widget.LinearLayout.LayoutParams cancelLp = new android.widget.LinearLayout.LayoutParams(
                                dp(34), dp(34));
                        cancelLp.rightMargin = dp(8);
                        editActionsRow.addView(cancelBtn, cancelLp);

                        android.widget.ImageView sendBtn = new android.widget.ImageView(ctx);
                        sendBtn.setId(R.id.editSendButton);
                        sendBtn.setImageResource(R.drawable.ic_arrow_up);
                        sendBtn.setColorFilter(accentFg);
                        sendBtn.setPadding(btnPad, btnPad, btnPad, btnPad);
                        android.graphics.drawable.GradientDrawable sendBg = new android.graphics.drawable.GradientDrawable();
                        sendBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        sendBg.setColor(accent);
                        sendBtn.setBackground(sendBg);
                        sendBtn.setContentDescription(ctx.getString(R.string.chat_save));
                        editActionsRow.addView(sendBtn, new android.widget.LinearLayout.LayoutParams(dp(34), dp(34)));
                        itemViewRoot.addView(editActionsRow);
                    }
                    boolean enteringEdit = editCard.getVisibility() != View.VISIBLE
                            || editCard.getTag() != message;
                    editCard.setTag(message);
                    if (enteringEdit) {
                        editField.setText(content);
                        editCard.setVisibility(View.VISIBLE);
                        editActionsRow.setVisibility(View.VISIBLE);
                    }
                    editField.requestFocus();
                    editField.setSelection(editField.getText().length());

                    final android.widget.EditText field = editField;
                    final String originalContent = content;
                    final android.widget.LinearLayout cardBox = editCard;
                    final android.widget.LinearLayout actionsRow = editActionsRow;
                    final View bubbleView = bubble;
                    Runnable saveAction = () -> {
                        String newText = field.getText().toString().trim();
                        message.setEditing(false);
                        cardBox.setVisibility(View.GONE);
                        actionsRow.setVisibility(View.GONE);
                        if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                        message.setContent(newText.isEmpty() ? originalContent : newText);
                        if (userMessageActionListener != null) {
                            int pos = messages.indexOf(message);
                            if (pos >= 0) userMessageActionListener.onUserMessageEdited(message, pos,
                                    newText.isEmpty() ? originalContent : newText);
                        }
                    };
                    Runnable cancelAction = () -> {
                        message.restoreEditAttachmentSnapshot();
                        message.setEditing(false);
                        cardBox.setVisibility(View.GONE);
                        actionsRow.setVisibility(View.GONE);
                        if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                        refreshMessage(message);
                    };
                    View sendBtn = itemView.findViewById(R.id.editSendButton);
                    if (sendBtn != null) sendBtn.setOnClickListener(v -> saveAction.run());
                    View cancelBtn = itemView.findViewById(R.id.editCancelButton);
                    if (cancelBtn != null) cancelBtn.setOnClickListener(v -> cancelAction.run());
                    View attachBtnView = itemView.findViewById(R.id.editAttachButton);
                    if (attachBtnView != null) {
                        attachBtnView.setOnClickListener(v -> {
                            if (userMessageActionListener == null) return;
                            int pos = messages.indexOf(message);
                            app.multiai.mobile.ui.kit.AttachMenuPopup.show(attachBtnView,
                                    new app.multiai.mobile.ui.kit.AttachMenuPopup.Callback() {
                                        @Override public void onCamera() {
                                            userMessageActionListener.onUserMessageAttach(message, pos, "camera");
                                        }
                                        @Override public void onPhoto() {
                                            userMessageActionListener.onUserMessageAttach(message, pos, "photo");
                                        }
                                        @Override public void onFiles() {
                                            userMessageActionListener.onUserMessageAttach(message, pos, "file");
                                        }
                                    },
                                    () -> attachBtnView.animate().rotation(0f).setDuration(160).start());
                            attachBtnView.animate().rotation(45f).setDuration(160).start();
                        });
                    }
                } else {
                    // Hide edit field if not editing
                    View editCardView = itemView.findViewById(R.id.editCard);
                    if (editCardView != null) editCardView.setVisibility(View.GONE);
                    View editActionsRowView = itemView.findViewById(R.id.editActionsRow);
                    if (editActionsRowView != null) editActionsRowView.setVisibility(View.GONE);
                    View bubbleContainer = itemView.findViewById(R.id.userBubbleContainer);
                    if (bubbleContainer != null) bubbleContainer.setVisibility(View.VISIBLE);
                    // Маршрут как в Work-режиме: LaTeX/таблицы → renderToLayout,
                    // иначе markdown в textMessage (голый Telemoji ронял формулы).
                    updateContent(message);
                    // У user-сообщений системного выделения нет (лонг-тап = меню)
                    textMessage.setTextIsSelectable(false);
                    if (richContentContainer != null
                            && richContentContainer.getVisibility() == View.VISIBLE) {
                        disableDeepSelection(richContentContainer);
                    }
                }
            }
            updateActionBarVisibility(message);
            bindVariantPager(message);
            bindThinking(message);
            bindAttachments(message);
            // Attachment-only user messages hide their empty text. AI visibility is
            // owned by updateContent/renderAiContent and must not be overwritten here.
            if (message.getType() == Message.TYPE_USER) {
                boolean attachmentOnly = isVisuallyBlank(content)
                        && (message.hasImage() || message.hasAttachedFile());
                textMessage.setVisibility(attachmentOnly ? View.GONE : View.VISIBLE);
                // Retry/Edit moved to long-press menu
            } else if (message.getType() != Message.TYPE_AI) {
                textMessage.setVisibility(View.VISIBLE);
            }
            // Stable like/disliked state from the adapter-level tracker (survives recycle).
            liked = isLiked(message);
            disliked = isDisliked(message);
            applyActionTints();
            if (btnCopy != null) btnCopy.setOnClickListener(v -> {
                tapFeedback(btnCopy);
                if (actionListener != null) actionListener.onCopy(message);
                showCopiedFeedback();
            });
            if (btnLike != null) btnLike.setOnClickListener(v -> {
                liked = toggleLike(message);
                disliked = false;
                applyActionTints();
                tapFeedback(btnLike);
                // Репорт — только при установке лайка; снятие просто убирает оценку.
                if (liked && actionListener != null) actionListener.onLike(message);
            });
            if (btnDislike != null) btnDislike.setOnClickListener(v -> {
                disliked = toggleDislike(message);
                liked = false;
                applyActionTints();
                tapFeedback(btnDislike);
                // Форма — только при установке дизлайка; снятие просто убирает оценку.
                if (disliked && actionListener != null) actionListener.onDislike(message);
            });
            if (btnTts != null) btnTts.setOnClickListener(v -> { tapFeedback(btnTts); if (actionListener != null) actionListener.onTts(message); });
            if (btnMore != null) btnMore.setOnClickListener(v -> { tapFeedback(btnMore); if (actionListener != null) actionListener.onMore(message, v); });
        }

        /** Текстовое view ответа: приоритет у основного текста, иначе — внутри rich-контейнера. */
        private android.widget.TextView resolveSelectableTarget() {
            if (textMessage != null && textMessage.getVisibility() == android.view.View.VISIBLE
                    && textMessage.getText() != null && textMessage.getText().length() > 0) {
                return textMessage;
            }
            if (richContentContainer != null && richContentContainer.getVisibility() == android.view.View.VISIBLE) {
                android.widget.TextView first = findFirstTextView(richContentContainer);
                if (first != null) {
                    return first;
                }
            }
            return textMessage;
        }

        private android.widget.TextView findFirstTextView(android.view.ViewGroup root) {
            for (int i = 0; i < root.getChildCount(); i++) {
                android.view.View child = root.getChildAt(i);
                if (child instanceof android.widget.TextView) {
                    return (android.widget.TextView) child;
                }
                if (child instanceof android.view.ViewGroup) {
                    android.widget.TextView nested = findFirstTextView((android.view.ViewGroup) child);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            return null;
        }

        private void bindVariantPager(Message message) {
            if (variantPager == null) return;
            int count = message.getVariantCount();
            variantPager.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
            if (count <= 1) return;
            int selected = message.getSelectedVariant();
            variantIndicator.setText((selected + 1) + "/" + count);
            variantPrev.setEnabled(selected > 0);
            variantNext.setEnabled(selected < count - 1);
            variantPrev.setAlpha(selected > 0 ? 1f : 0.35f);
            variantNext.setAlpha(selected < count - 1 ? 1f : 0.35f);
            variantPrev.setOnClickListener(v -> selectVariant(message, selected - 1));
            variantNext.setOnClickListener(v -> selectVariant(message, selected + 1));
        }

        private void selectVariant(Message message, int index) {
            message.selectVariant(index);
            int position = getBindingAdapterPosition();
            RecyclerView.Adapter<?> adapter = getBindingAdapter();
            if (adapter != null && position != RecyclerView.NO_POSITION) adapter.notifyItemChanged(position);
        }

        /** Thinking-карточка строится только из фактического reasoning-текста. */
        private void bindThinking(Message message) {
            if (thinkingContainer == null || thinkingBody == null || textThinking == null) return;
            String thinking = cleanThinkingForDisplay(message.getThinkingContent());
            if (thinking.isEmpty()) {
                thinkingContainer.setVisibility(View.GONE);
                thinkingBody.setVisibility(View.GONE);
                textThinking.setText("");
                if (thinkingWaveIcon != null) thinkingWaveIcon.setRunning(false);
                return;
            }
            thinkingContainer.setVisibility(View.VISIBLE);
            updateThinkingPreview(message, thinking);
            renderThinkingBody(message, thinking);
        }

        /** Вложения (user-layout): карточка фото + чип файла, размер из файла. */
        private void bindAttachments(Message message) {
            if (message.getType() != Message.TYPE_USER) return;
            if (message.isEditing()) {
                if (fileAttachmentCard != null) fileAttachmentCard.setVisibility(View.GONE);
                if (imageCard != null) app.multiai.mobile.views.ImageAttachmentStrip.render(imageCard,
                        message.getImageBase64(), index -> {
                            message.replaceImages(app.multiai.mobile.utils.ImageAttachments.remove(message.getImageBase64(), index));
                            refreshMessage(message);
                        }, message.getAttachedFileName(), message.getAttachedFileContent(), index -> {
                            java.util.List<app.multiai.mobile.utils.FileAttachments.Entry> files =
                                    app.multiai.mobile.utils.FileAttachments.unpack(message.getAttachedFileName(), message.getAttachedFileContent());
                            files.remove(index);
                            message.replaceFiles(files);
                            refreshMessage(message);
                        });
                return;
            }

            if (imageCard != null && imageAttachment != null) {
                View oldStrip = imageCard.findViewWithTag("multi-image-strip");
                if (oldStrip != null) ((android.view.ViewGroup) imageCard).removeView(oldStrip);
                imageAttachment.setVisibility(View.VISIBLE);
                String payload = message.getImageBase64();
                if (payload != null && payload.startsWith("[")) {
                    app.multiai.mobile.views.ImageAttachmentStrip.render(imageCard, payload, null);
                } else if (message.hasImage()) {
                    imageCard.setVisibility(View.VISIBLE);
                    final String cacheKey = message.getId() != null ? message.getId() : String.valueOf(message.hashCode());
                    android.graphics.Bitmap cached = thumbCache.get(cacheKey);
                    if (cached != null) {
                        imageAttachment.setImageBitmap(cached);
                    } else {
                        // Placeholder while decoding off-thread
                        imageAttachment.setImageDrawable(null);
                        final ImageView target = imageAttachment;
                        final int targetPos = getBindingAdapterPosition();
                        new Thread(() -> {
                            android.graphics.Bitmap bitmap = decodeImageThumbnail(message, THUMB_MAX_DIM);
                            if (bitmap != null) thumbCache.put(cacheKey, bitmap);
                            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                            h.post(() -> {
                                // Only apply if the holder is still showing the same message
                                if (bitmap != null && targetPos == getBindingAdapterPosition()) {
                                    target.setImageBitmap(bitmap);
                                } else if (bitmap == null && targetPos == getBindingAdapterPosition()) {
                                    imageCard.setVisibility(View.GONE);
                                }
                            });
                        }, "img-decode").start();
                    }
                } else {
                    imageCard.setVisibility(View.GONE);
                }
            }

            if (fileAttachmentCard != null) {
                if (message.hasAttachedFile()) {
                    fileAttachmentCard.setVisibility(View.VISIBLE);
                    if (fileAttachmentName != null) {
                        fileAttachmentName.setText(message.getAttachedFileName());
                    }
                    if (fileAttachmentSize != null) {
                        long size = 0;
                        String fc = message.getAttachedFileContent();
                        if (fc != null) {
                            size = fc.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        } else if (message.getAttachedFilePath() != null) {
                            java.io.File f = new java.io.File(message.getAttachedFilePath());
                            if (f.exists()) size = f.length();
                        }
                        fileAttachmentSize.setText(formatFileSize(size));
                    }
                } else {
                    fileAttachmentCard.setVisibility(View.GONE);
                }
            }
        }

        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }

        /** Apply action-bar icon tints from semantic color resources. */
        private void applyActionTints() {
            int inactive = color(R.color.action_inactive);
            if (btnCopy != null) btnCopy.setColorFilter(inactive);
            if (btnLike != null) btnLike.setColorFilter(liked ? color(R.color.accent) : inactive);
            if (btnDislike != null) btnDislike.setColorFilter(disliked ? color(R.color.error) : inactive);
            if (btnTts != null) btnTts.setColorFilter(inactive);
            if (btnMore != null) btnMore.setColorFilter(inactive);
        }

        /** Resolve a semantic color resource to an int for the current theme. */
        private int color(int resId) {
            return androidx.core.content.ContextCompat.getColor(itemView.getContext(), resId);
        }

        /** Копирование: кнопка на ~1.2с превращается в галочку акцентного цвета. */
        private void showCopiedFeedback() {
            if (btnCopy == null) return;
            btnCopy.setImageResource(R.drawable.ic_check);
            btnCopy.setColorFilter(color(R.color.accent));
            btnCopy.postDelayed(() -> {
                btnCopy.setImageResource(R.drawable.ic_copy);
                btnCopy.setColorFilter(color(R.color.action_inactive));
            }, 1200);
        }

        /** Short, subtle press feedback (scale dip + restore). */
        private void tapFeedback(View view) {
            if (view == null) return;
            view.animate().cancel();
            view.setScaleX(0.85f);
            view.setScaleY(0.85f);
            view.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(app.multiai.mobile.ui.kit.MotionKit.STANDARD)
                .setInterpolator(app.multiai.mobile.ui.kit.MotionKit.STANDARD_EASING)
                .start();
        }

        private int dp(int value) {
            return Math.round(value * itemView.getResources().getDisplayMetrics().density);
        }

        /** Рекурсивно выключает системное выделение во всех TextView контейнера. */
        private void disableDeepSelection(View view) {            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                if (tv.getText() != null && tv.getText().length() > 0) {
                    tv.setTextIsSelectable(false);
                    tv.setMovementMethod(null);
                }
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    disableDeepSelection(vg.getChildAt(i));
                }
            }
        }

        /** Лёгкое обновление только текста для стриминга — не дёргает listeners и action bar. */
        void updateContent(Message message) {            String content = message.getContent() != null ? message.getContent() : "";
            if (message.getType() == Message.TYPE_AI) {
                String display = app.multiai.mobile.utils.CitationLinker.link(message, content);
                if (message.isStreaming()) {
                    updateStreamingContent(display);
                } else {
                    renderAiContent(display);
                }
                updateActionBarVisibility(message);
            } else {
                // User message: use layout renderer for LaTeX/tables, plain render otherwise
                boolean needsRich = content.contains("```")
                        || content.contains("$$")
                        || content.contains("\\[")
                        || app.multiai.mobile.utils.MarkdownRenderer.containsInlineLatex(content)
                        || app.multiai.mobile.utils.MarkdownRenderer.containsMarkdownTable(content);
                if (needsRich && richContentContainer != null) {
                    textMessage.setVisibility(View.GONE);
                    richContentContainer.setVisibility(View.VISIBLE);
                    richContentContainer.removeAllViews();
                    app.multiai.mobile.utils.MarkdownRenderer.renderToLayout(
                            itemView.getContext(), richContentContainer, content);
                } else {
                    if (richContentContainer != null) {
                        richContentContainer.setVisibility(View.GONE);
                        richContentContainer.removeAllViews();
                    }
                    textMessage.setVisibility(View.VISIBLE);
                    app.multiai.mobile.utils.MarkdownRenderer.render(
                        itemView.getContext(), textMessage, content);
                }
            }
        }

        private final app.multiai.mobile.ui.kit.StreamingReveal contentReveal =
                new app.multiai.mobile.ui.kit.StreamingReveal();

        private void updateStreamingContent(String content) {
            if (isVisuallyBlank(content)) {
                hideAiContent();
                return;
            }
            // Check if content has LaTeX/tables/code that need layout rendering
            boolean needsRich = content.contains("```")
                    || content.contains("$$")
                    || content.contains("\\[")
                    || app.multiai.mobile.utils.MarkdownRenderer.containsInlineLatex(content)
                    || app.multiai.mobile.utils.MarkdownRenderer.containsMarkdownTable(content);
            if (needsRich && richContentContainer != null) {
                // Rich blocks are finalized once; rebuilding their view trees per token blocks input.
                richContentContainer.setVisibility(View.GONE);
                textMessage.setVisibility(View.VISIBLE);
                app.multiai.mobile.utils.Telemoji.clearTracking(textMessage);
                textMessage.setText(content);
            } else {
                if (richContentContainer != null) {
                    richContentContainer.setVisibility(View.GONE);
                    richContentContainer.removeAllViews();
                }
                textMessage.setVisibility(View.VISIBLE);
                app.multiai.mobile.utils.Telemoji.clearTracking(textMessage);
                app.multiai.mobile.utils.MarkdownRenderer.render(itemView.getContext(), textMessage,
                        app.multiai.mobile.utils.StreamingMarkdown.prepare(content));
            }
            contentReveal.update(textMessage);
        }

        private void finishStreaming(Message message) {
            contentReveal.reset();
            updateContent(message);
            updateThinking(message);
            updateActionBarVisibility(message);
        }

        /**
         * AI-контент: если есть LaTeX/код-блоки — полноформатный пайплайн
         * Work-режима (renderToLayout: KaTeX-формулы, кодблоки с кнопкой
         * копирования, Mermaid), иначе — обычный markwon в textMessage.
         * Условие совпадает с ворк-режимом, чтобы рендер был консистентным.
         */
        private void renderAiContent(String content) {
            if (isVisuallyBlank(content)) {
                hideAiContent();
                return;
            }
            boolean needsRich = content.contains("```")
                    || content.contains("$$")
                    || content.contains("\\[")
                    || app.multiai.mobile.utils.MarkdownRenderer.containsInlineLatex(content)
                    || app.multiai.mobile.utils.MarkdownRenderer.containsMarkdownTable(content);
            if (needsRich && richContentContainer != null) {
                app.multiai.mobile.utils.Telemoji.clearTracking(textMessage);
                textMessage.setVisibility(View.GONE);
                richContentContainer.setVisibility(View.VISIBLE);
                app.multiai.mobile.utils.MarkdownRenderer.renderToLayout(
                        itemView.getContext(), richContentContainer, content);
            } else {
                if (richContentContainer != null) {
                    richContentContainer.setVisibility(View.GONE);
                    richContentContainer.removeAllViews();
                }
                textMessage.setVisibility(View.VISIBLE);
                app.multiai.mobile.utils.MarkdownRenderer.render(
                        itemView.getContext(), textMessage, content);
            }
        }

        /** Лёгкое обновление thinking-блока в стриме размышлений. */
        void updateThinking(Message message) {
            if (thinkingContainer == null || thinkingBody == null || textThinking == null) return;
            String thinking = cleanThinkingForDisplay(message.getThinkingContent());
            if (thinking.isEmpty()) {
                thinkingContainer.setVisibility(View.GONE);
                thinkingBody.setVisibility(View.GONE);
                textThinking.setText("");
                if (thinkingWaveIcon != null) thinkingWaveIcon.setRunning(false);
                return;
            }
            thinkingContainer.setVisibility(View.VISIBLE);
            updateThinkingPreview(message, thinking);
            renderThinkingBody(message, thinking);
        }

        private void renderThinkingBody(Message message, String thinking) {
            if (thinkingBody == null || textThinking == null) return;
            boolean expanded = message.isThinkingExpanded();
            thinkingBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
            if (expanded) {
                // Markdown + LaTeX в reasoning, как в Work-режиме
                app.multiai.mobile.utils.MarkdownRenderer.renderSafe(
                        itemView.getContext(), textThinking, thinking);
            }
            ImageView toggle = itemView.findViewById(R.id.thinkingToggle);
            View header = itemView.findViewById(R.id.thinkingHeader);
            if (header != null) {
                header.setOnClickListener(v -> {
                    boolean nowExpanded = !message.isThinkingExpanded();
                    message.setThinkingExpanded(nowExpanded);
                    animateThinkingBody(nowExpanded, cleanThinkingForDisplay(
                            message.getThinkingContent()));
                    if (toggle != null) toggle.animate().rotation(nowExpanded ? 180f : 0f)
                            .setDuration(app.multiai.mobile.ui.kit.MotionKit.duration(220))
                            .setInterpolator(app.multiai.mobile.ui.kit.MotionKit.STANDARD_EASING).start();
                });
            }
            if (toggle != null) toggle.setRotation(expanded ? 180f : 0f);
        }

        private void animateThinkingBody(boolean expand, String thinking) {
            if (thinkingBody == null || textThinking == null) return;
            thinkingBody.animate().cancel();
            float shift = itemView.getResources().getDisplayMetrics().density * 4f;
            if (expand) {
                app.multiai.mobile.utils.MarkdownRenderer.renderSafe(
                        itemView.getContext(), textThinking, thinking);
                textThinking.scrollTo(0, 0);
                thinkingBody.setVisibility(View.VISIBLE);
                thinkingBody.setAlpha(0f);
                thinkingBody.setTranslationY(-shift);
                thinkingBody.animate().alpha(1f).translationY(0f)
                        .setDuration(app.multiai.mobile.ui.kit.MotionKit.duration(260))
                        .setInterpolator(app.multiai.mobile.ui.kit.MotionKit.STANDARD_EASING).start();
            } else {
                thinkingBody.animate().alpha(0f).translationY(-shift)
                        .setDuration(140)
                        .withEndAction(() -> {
                            thinkingBody.setVisibility(View.GONE);
                            thinkingBody.setAlpha(1f);
                            thinkingBody.setTranslationY(0f);
                        }).start();
            }
        }
    }

    static class TypingViewHolder extends RecyclerView.ViewHolder {
        private final app.multiai.mobile.ui.WorkingLogoIconView workingLogo;
        private final View imageGenBox;
        private final app.multiai.mobile.ui.ImageGenShimmerView imageGenShimmer;

        TypingViewHolder(View itemView) {
            super(itemView);
            workingLogo = itemView.findViewById(R.id.workingLogo);
            imageGenBox = itemView.findViewById(R.id.imageGenBox);
            imageGenShimmer = itemView.findViewById(R.id.imageGenShimmer);
        }

        void bind(Message message) {
            boolean imageGenerating = message != null && message.isImageGenerating();
            if (imageGenerating) {
                // Image-model turn: ChatGPT-style shimmer placeholder instead of
                // the MultiAI logo.
                if (workingLogo != null) {
                    workingLogo.setRunning(false);
                    workingLogo.setVisibility(View.GONE);
                }
                if (imageGenBox != null) imageGenBox.setVisibility(View.VISIBLE);
                if (imageGenShimmer != null) imageGenShimmer.setRunning(true);
            } else {
                if (imageGenBox != null) imageGenBox.setVisibility(View.GONE);
                if (imageGenShimmer != null) imageGenShimmer.setRunning(false);
                if (workingLogo != null) {
                    workingLogo.setVisibility(View.VISIBLE);
                    workingLogo.setRunning(true);
                }
            }
        }
    }

    /** Legacy transient reconnect row; no Chat Mode producer creates it. */
    static class ReconnectingViewHolder extends RecyclerView.ViewHolder {
        private final app.multiai.mobile.ui.WorkingLogoIconView workingLogo;
        private final app.multiai.mobile.ui.WaveTextView reconnectingText;

        ReconnectingViewHolder(View itemView) {
            super(itemView);
            workingLogo = itemView.findViewById(R.id.workingLogo);
            reconnectingText = itemView.findViewById(R.id.reconnectingText);
        }

        void bind(Message message) {
            if (workingLogo != null) workingLogo.setRunning(true);
            if (reconnectingText != null) {
                reconnectingText.setText(message.getContentSafe());
                reconnectingText.setWaving(true);
            }
        }
    }

    static class ToolViewHolder extends RecyclerView.ViewHolder {
        private final android.widget.FrameLayout widgetSlot;
        private String lastRenderSignature;

        ToolViewHolder(View itemView) {
            super(itemView);
            widgetSlot = itemView.findViewById(R.id.widgetSlot);
        }

        void bind(Message message, List<app.multiai.mobile.models.ToolCall> groupedCalls) {
            String renderSignature = renderSignature(message, groupedCalls);
            if (renderSignature.equals(lastRenderSignature)) return;
            lastRenderSignature = renderSignature;
            widgetSlot.removeAllViews();
            // Clear any leftover pressed state from the recycled holder
            widgetSlot.setForeground(null);

            View widget = null;
            if (groupedCalls != null && !groupedCalls.isEmpty()) {
                widget = app.multiai.mobile.views.ToolWidgetRenderer.renderExplore(
                        itemView.getContext(), groupedCalls);
            }

            app.multiai.mobile.models.ToolCall toolCall = null;
            if (message.hasToolCalls()) {
                List<app.multiai.mobile.models.ToolCall> calls = message.getToolCalls();
                if (calls != null && !calls.isEmpty()) toolCall = calls.get(calls.size() - 1);
            }
            if (toolCall == null && groupedCalls != null && !groupedCalls.isEmpty()) {
                toolCall = groupedCalls.get(groupedCalls.size() - 1);
            }
            if (widget == null && toolCall != null) {
                widget = app.multiai.mobile.views.ToolWidgetRenderer.render(
                        itemView.getContext(), toolCall);
            }
            if (widget == null) {
                widget = app.multiai.mobile.views.ToolWidgetRenderer.renderLegacyToolExecution(
                        itemView.getContext(), message.getToolName(), message.getToolDetails(),
                        message.isToolCompleted());
            }

            widget.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            widgetSlot.addView(widget);
            widgetSlot.setVisibility(View.VISIBLE);
        }

        private String renderSignature(Message message,
                                       List<app.multiai.mobile.models.ToolCall> groupedCalls) {
            StringBuilder signature = new StringBuilder()
                    .append(System.identityHashCode(message)).append('|');
            List<app.multiai.mobile.models.ToolCall> calls = groupedCalls != null
                    ? groupedCalls : message.getToolCalls();
            for (app.multiai.mobile.models.ToolCall call : calls) {
                if (call == null) continue;
                signature.append(System.identityHashCode(call)).append(':')
                        .append(call.getStatus()).append(':')
                        .append(call.isExpanded()).append(':')
                        .append(call.isExploreExpanded()).append(':')
                        .append(System.identityHashCode(call.getResult())).append('|');
            }
            return signature.toString();
        }
    }

    /** Error card holder — использует item_message_error.xml (как в Project mode). */
    static class ErrorViewHolder extends RecyclerView.ViewHolder {
        private final android.widget.TextView textErrorTitle;
        private final android.widget.TextView textErrorBody;
        private final android.widget.TextView textErrorCode;
        private final View buttonErrorDismiss;
        private final View buttonErrorDetails;
        private final View buttonErrorCopy;
        private final android.widget.TextView textErrorTechnical;
        private final View buttonRetry;
        private final View buttonSupport;
        private final View buttonAuth;

        ErrorViewHolder(@NonNull View itemView) {
            super(itemView);
            textErrorTitle = itemView.findViewById(R.id.textErrorTitle);
            textErrorBody = itemView.findViewById(R.id.textErrorBody);
            textErrorCode = itemView.findViewById(R.id.textErrorCode);
            buttonErrorDismiss = itemView.findViewById(R.id.buttonErrorDismiss);
            buttonErrorDetails = itemView.findViewById(R.id.buttonErrorDetails);
            buttonErrorCopy = itemView.findViewById(R.id.buttonErrorCopy);
            textErrorTechnical = itemView.findViewById(R.id.textErrorTechnical);
            buttonRetry = itemView.findViewById(R.id.buttonRetry);
            buttonSupport = itemView.findViewById(R.id.buttonSupport);
            buttonAuth = itemView.findViewById(R.id.buttonAuth);
        }

        void bind(Message message, OnErrorRetryListener retryListener) {
            android.content.Context ctx = itemView.getContext();
            String title = message.getErrorTitle() != null ? message.getErrorTitle()
                    : ctx.getString(R.string.kit_msg_error_title);
            String body = message.getContent() != null ? message.getContent()
                    : ctx.getString(R.string.kit_msg_error_body);
            String code = message.getErrorCode() != null ? message.getErrorCode()
                    : ctx.getString(R.string.kit_msg_error_default_code);

            if (textErrorTitle != null) textErrorTitle.setText(title);
            if (textErrorBody != null) textErrorBody.setText(body);
            if (textErrorCode != null) {
                textErrorCode.setText(ctx.getString(R.string.kit_msg_error_code_prefix) + code);
            }

            // Крестик: убрать карточку из списка.
            if (buttonErrorDismiss != null) {
                buttonErrorDismiss.setOnClickListener(v -> {
                    RecyclerView.Adapter<?> adapter = getBindingAdapter();
                    ChatModeMessagesAdapter self = adapter instanceof ChatModeMessagesAdapter
                            ? (ChatModeMessagesAdapter) adapter : null;
                    if (self != null) {
                        self.removeMessage(message);
                    }
                });
            }

            // «Подробности»: раскрытие технического блока с сырыми данными ошибки.
            if (buttonErrorDetails != null) {
                boolean expanded = message.isErrorDetailsExpanded();
                if (textErrorTechnical != null) {
                    String tech = message.getErrorTechnical();
                    textErrorTechnical.setText(tech != null && !tech.trim().isEmpty() ? tech
                            : message.getErrorTitle() != null ? message.getErrorTitle() : code);
                    textErrorTechnical.setVisibility(expanded ? View.VISIBLE : View.GONE);
                }
                ((android.widget.TextView) buttonErrorDetails).setText(ctx.getString(
                        expanded ? R.string.kit_msg_error_details_hide : R.string.kit_msg_error_details));
                buttonErrorDetails.setOnClickListener(v -> {
                    message.setErrorDetailsExpanded(!message.isErrorDetailsExpanded());
                    RecyclerView.Adapter<?> adapter = getBindingAdapter();
                    int position = getBindingAdapterPosition();
                    if (adapter instanceof ChatModeMessagesAdapter
                            && position != RecyclerView.NO_POSITION) {
                        ((ChatModeMessagesAdapter) adapter).notifyItemChanged(position);
                    }
                });
            }

            // «Копировать»: сырые данные ошибки в буфер обмена.
            if (buttonErrorCopy != null) {
                buttonErrorCopy.setOnClickListener(v -> {
                    String copyText = message.getErrorTechnical();
                    if (copyText == null || copyText.trim().isEmpty()) {
                        copyText = (message.getErrorTitle() != null ? message.getErrorTitle() + "\n" : "")
                                + (message.getContent() != null ? message.getContent() + "\n" : "")
                                + code;
                    }
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) ctx.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("error", copyText));
                        android.widget.Toast.makeText(ctx, R.string.kit_msg_copied,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }

            boolean isAuthError = "MULTIAI-NOT-AUTHORIZED".equals(code)
                    || "MULTIAI-SESSION-EXPIRED".equals(code);

            // Auth button
            if (buttonAuth != null) {
                if (isAuthError) {
                    buttonAuth.setVisibility(View.VISIBLE);
                    buttonAuth.setOnClickListener(v -> {
                        android.content.Intent intent = new android.content.Intent(ctx,
                                app.multiai.mobile.ui.ProfileActivity.class);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(intent);
                    });
                } else {
                    buttonAuth.setVisibility(View.GONE);
                    buttonAuth.setOnClickListener(null);
                }
            }

            // Retry button
            if (buttonRetry != null) {
                if (isAuthError || !message.isRetryable()) {
                    buttonRetry.setVisibility(View.GONE);
                    buttonRetry.setOnClickListener(null);
                } else {
                    buttonRetry.setVisibility(View.VISIBLE);
                    buttonRetry.setOnClickListener(v -> {
                        if (retryListener != null) retryListener.onRetry(message);
                    });
                }
            }

            // Support button — collect real logcat logs for the support message.
            if (buttonSupport != null) {
                if (isAuthError) {
                    buttonSupport.setVisibility(View.GONE);
                    buttonSupport.setOnClickListener(null);
                } else {
                    buttonSupport.setVisibility(View.VISIBLE);
                    buttonSupport.setOnClickListener(v -> {
                        String log = app.multiai.mobile.utils.LogCollector.collect(50);
                        String tgUrl = app.multiai.mobile.utils.SupportLinkBuilder.build(code, log);
                        try {
                            ctx.startActivity(new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(tgUrl)));
                        } catch (Exception ignored) {
                            android.content.Intent share = new android.content.Intent(
                                    android.content.Intent.ACTION_SEND);
                            share.setType("text/plain");
                            share.putExtra(android.content.Intent.EXTRA_TEXT,
                                    "Здравствуйте! у меня возникла ошибка " + code + ", помогите!\nВот логи ошибки:\n\"" + log + "\"");
                            ctx.startActivity(android.content.Intent.createChooser(share,
                                    ctx.getString(R.string.kit_msg_error_share_support)));
                        }
                    });
                }
            }
        }
    }

    public interface OnErrorRetryListener {
        void onRetry(Message message);
    }

    /** Decode a downsized thumbnail from the message's base64 or file path.
     *  Must be called off the UI thread. */
    private static android.graphics.Bitmap decodeImageThumbnail(Message message, int maxDim) {
        try {
            String base64 = message.getImageBase64();
            if (base64 != null && base64.startsWith("[")) base64 = app.multiai.mobile.utils.ImageAttachments.unpack(base64).get(0);
            byte[] bytes = null;
            if (base64 != null && !base64.isEmpty()) {
                if (base64.startsWith("data:image")) {
                    base64 = base64.substring(base64.indexOf(",") + 1);
                }
                bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            }
            if (bytes != null) {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight, maxDim);
                opts.inJustDecodeBounds = false;
                return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            } else if (message.getImagePath() != null) {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(message.getImagePath(), opts);
                opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight, maxDim);
                opts.inJustDecodeBounds = false;
                return android.graphics.BitmapFactory.decodeFile(message.getImagePath(), opts);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int computeSampleSize(int w, int h, int maxDim) {
        if (w <= 0 || h <= 0) return 1;
        int sample = 1;
        while ((w / sample) > maxDim || (h / sample) > maxDim) sample *= 2;
        return sample;
    }
}
