package app.multiai.mobile.adapters;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import app.multiai.mobile.R;
import app.multiai.mobile.models.Message;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {
    
    private List<Message> messages = new ArrayList<>();
    /**
     * Precomputed presentation state for restored tool workflows. Rebuilt in one
     * forward pass whenever the message structure/tool ownership changes, so bind
     * never scans the rest of a long chat.
     */
    private boolean[] hiddenWorkflowChildren = new boolean[0];
    private boolean[] suppressOwnToolCalls = new boolean[0];
    private boolean[] workflowSummaryHeaders = new boolean[0];
    private List<List<app.multiai.mobile.models.ToolCall>> workflowCallsByPosition =
            new ArrayList<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private OnErrorRetryListener errorRetryListener;

    // Thumbnail cache: messageId → bitmap. Avoids re-decoding full base64 on every bind.
    private static final int THUMB_MAX_DIM = 512;
    private static final android.util.LruCache<String, android.graphics.Bitmap> thumbCache =
            new android.util.LruCache<String, android.graphics.Bitmap>(24) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) { return 1; }
            };

    public interface OnErrorRetryListener {
        void onRetry(Message errorMessage);
    }

    public interface MessageActionListener {
        void onCopy(Message message);
        void onLike(Message message);
        void onDislike(Message message);
        void onTts(Message message);
        void onShare(Message message);
        void onMore(Message message, View anchor);
        void onEditUserMessage(Message message, int position);
    }

    private MessageActionListener actionListener;

    // Stable like/dislike state keyed by message identity (survives ViewHolder recycling).
    private final java.util.Set<String> likedIds = new java.util.HashSet<>();
    private final java.util.Set<String> dislikedIds = new java.util.HashSet<>();

    private static String identityKey(Message message) {
        if (message == null) return "";
        // Message has no id field; use identity hash code so each message instance
        // has a stable key for the duration it lives in the list.
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
        dislikedIds.remove(key); // like clears dislike
        return true;
    }

    public boolean toggleDislike(Message message) {
        String key = identityKey(message);
        if (dislikedIds.contains(key)) {
            dislikedIds.remove(key);
            return false;
        }
        dislikedIds.add(key);
        likedIds.remove(key); // dislike clears like
        return true;
    }

    public void setMessageActionListener(MessageActionListener listener) {
        this.actionListener = listener;
    }

    public void setOnErrorRetryListener(OnErrorRetryListener errorRetryListener) {
        this.errorRetryListener = errorRetryListener;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType();
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId;
        
        if (viewType == Message.TYPE_USER) {
            layoutId = R.layout.item_message_user;
        } else if (viewType == Message.TYPE_AI_TYPING) {
            layoutId = R.layout.item_message_ai_typing;
        } else if (viewType == Message.TYPE_RECONNECTING) {
            layoutId = R.layout.item_reconnecting;
        } else if (viewType == Message.TYPE_ERROR) {
            layoutId = R.layout.item_message_error;
        } else if (viewType == Message.TYPE_TOOL_EXECUTION) {
            layoutId = R.layout.item_tool_widget;
        } else {
            layoutId = R.layout.item_message_ai;
        }
        
        View view = LayoutInflater.from(parent.getContext())
            .inflate(layoutId, parent, false);
        return new MessageViewHolder(view, viewType);
    }

    private int lastAnimatedPosition = -1;

    @Override
    @android.annotation.SuppressLint("RecyclerView")
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        boolean workflowChild = isToolWorkflowChild(position);
        boolean hidden = workflowChild || message.isRetryPending();
        ViewGroup.LayoutParams itemParams = holder.itemView.getLayoutParams();
        if (itemParams != null) {
            int wantedHeight = hidden ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (itemParams.height != wantedHeight) {
                itemParams.height = wantedHeight;
                holder.itemView.setLayoutParams(itemParams);
            }
        }
        holder.itemView.setVisibility(hidden ? View.GONE : View.VISIBLE);
        if (!hidden) {
            holder.itemView.setOnLongClickListener(null);
            holder.bind(message, collectWorkflowCalls(position), shouldSuppressOwnToolCalls(position),
                    shouldShowWorkflowSummary(position));
        }
        // 🔥 Легковесная анимация входа: только fade-in, никаких scale/translate
        // которые тормозят стриминг и выглядят дёрганно
        if (position > lastAnimatedPosition) {
            lastAnimatedPosition = position;
            int type = messages.get(position).getType();
            // AI_TYPING и streaming-сообщения не анимируем — они и так динамические
            if (type != Message.TYPE_AI_TYPING) {
                holder.itemView.setAlpha(0f);
                holder.itemView.animate()
                        .alpha(1f)
                        .setDuration(160)
                        .setInterpolator(new android.view.animation.LinearInterpolator())
                        .start();
            }
        }
    }

    private boolean isToolWorkflowChild(int position) {
        return position >= 0 && position < hiddenWorkflowChildren.length
                && hiddenWorkflowChildren[position];
    }

    public int getLastVisibleMessagePosition() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!isToolWorkflowChild(i) && message != null && !message.isRetryPending()) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private List<app.multiai.mobile.models.ToolCall> collectWorkflowCalls(int position) {
        if (position < 0 || position >= workflowCallsByPosition.size()) {
            return null;
        }
        return workflowCallsByPosition.get(position);
    }

    private boolean shouldSuppressOwnToolCalls(int position) {
        return position >= 0 && position < suppressOwnToolCalls.length
                && suppressOwnToolCalls[position];
    }

    private boolean shouldShowWorkflowSummary(int position) {
        return position >= 0 && position < workflowSummaryHeaders.length
                && workflowSummaryHeaders[position];
    }

    private static boolean hasVisibleAssistantText(Message message) {
        if (message == null || message.getType() != Message.TYPE_AI) return false;
        String content = message.getContentSafe();
        if (content.isEmpty()) return false;
        String visible = content
                .replaceAll("(?is)<(?:think|thinking|reasoning)>.*?</(?:think|thinking|reasoning)>", "")
                .replaceAll("(?is)</?(?:think|thinking|reasoning)>", "");
        return !visible.trim().isEmpty();
    }

    private void rebuildWorkflowIndex() {
        int count = messages.size();
        hiddenWorkflowChildren = new boolean[count];
        suppressOwnToolCalls = new boolean[count];
        workflowSummaryHeaders = new boolean[count];
        workflowCallsByPosition = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            workflowCallsByPosition.add(null);
        }

        int turnStart = 0;
        for (int i = 0; i <= count; i++) {
            boolean turnBoundary = i == count
                    || (messages.get(i) != null && messages.get(i).getType() == Message.TYPE_USER);
            if (!turnBoundary) continue;
            indexWorkflowTurn(turnStart, i);
            turnStart = i + 1;
        }
    }

    /** Index one user-turn segment [start, end) in chronological order. */
    private void indexWorkflowTurn(int start, int end) {
        if (start >= end) return;

        // New Work Mode turns already carry their exact chronological order in
        // Message.workflowSegments. Never run the legacy cross-message anchoring
        // on top of them: doing so rendered the same Explore calls beside later
        // assistant bubbles and made them jump across reasoning blocks.
        boolean hasOrderedWorkflow = false;
        for (int i = start; i < end; i++) {
            Message message = messages.get(i);
            List<app.multiai.mobile.models.WorkflowSegment> segments =
                    message != null ? message.getWorkflowSegments() : null;
            if (segments != null && !segments.isEmpty()) {
                hasOrderedWorkflow = true;
                if (message.isToolWorkflowSummary()) {
                    workflowSummaryHeaders[i] = true;
                }
            }
        }
        if (hasOrderedWorkflow) {
            java.util.HashSet<String> canonicalIds = new java.util.HashSet<>();
            for (int i = start; i < end; i++) {
                Message owner = messages.get(i);
                List<app.multiai.mobile.models.WorkflowSegment> segments =
                        owner != null ? owner.getWorkflowSegments() : null;
                if (segments == null) continue;
                for (app.multiai.mobile.models.WorkflowSegment segment : segments) {
                    if (segment == null || segment.getToolCalls() == null) continue;
                    for (app.multiai.mobile.models.ToolCall call : segment.getToolCalls()) {
                        if (call != null && call.getId() != null) canonicalIds.add(call.getId());
                    }
                }
            }
            for (int i = start; i < end; i++) {
                Message child = messages.get(i);
                if (child == null || child.getType() != Message.TYPE_AI
                        || child.getWorkflowSegments() != null && !child.getWorkflowSegments().isEmpty()) {
                    continue;
                }
                boolean duplicateOnly = child.hasToolCalls();
                for (app.multiai.mobile.models.ToolCall call : child.getToolCalls()) {
                    if (call == null || call.getId() == null || !canonicalIds.contains(call.getId())) {
                        duplicateOnly = false;
                        break;
                    }
                }
                if (duplicateOnly) {
                    suppressOwnToolCalls[i] = true;
                    if (!hasVisibleAssistantText(child) && !child.hasThinking()) {
                        hiddenWorkflowChildren[i] = true;
                    }
                }
            }
            return;
        }

        int summaryPosition = -1;
        for (int i = start; i < end; i++) {
            Message message = messages.get(i);
            if (message != null && message.getType() == Message.TYPE_AI
                    && message.isToolWorkflowSummary()) {
                // Old restored chats may contain more than one marker. The last
                // marker owns the whole completed workflow; earlier ones remain
                // ordinary assistant segments and cannot create duplicate cards.
                summaryPosition = i;
            }
        }
        if (summaryPosition >= 0) {
            // Persisted chats can contain several summary markers from retries
            // or follow-up rounds. Only the canonical last marker owns the
            // visible header; keep the stored model flags untouched.
            workflowSummaryHeaders[summaryPosition] = true;
        }
        List<app.multiai.mobile.models.ToolCall> pendingCalls = new ArrayList<>();
        int scanEnd = summaryPosition >= 0 ? summaryPosition : end - 1;
        int lastPendingOwner = -1;
        for (int i = start; i <= scanEnd; i++) {
            Message message = messages.get(i);
            if (message == null || message.getType() != Message.TYPE_AI) continue;

            boolean visibleText = hasVisibleAssistantText(message);
            boolean hasCalls = message.hasToolCalls();
            if (visibleText) {
                List<app.multiai.mobile.models.ToolCall> anchored = new ArrayList<>();
                if (!pendingCalls.isEmpty()) {
                    // The bubble is physically above workflowArea in the message
                    // layout. Anchor the completed batch to this visible message
                    // so a sentence can separate Explore groups in one turn.
                    anchored.addAll(pendingCalls);
                    pendingCalls.clear();
                }
                if (hasCalls) {
                    // Calls emitted in the same response round belong to the
                    // same visible anchor. The layout cannot place them after
                    // that bubble without synthetic rows.
                    anchored.addAll(message.getToolCalls());
                }
                if (!anchored.isEmpty()) {
                    workflowCallsByPosition.set(i,
                            Collections.unmodifiableList(anchored));
                }
            } else if (i == summaryPosition) {
                List<app.multiai.mobile.models.ToolCall> finalCalls = new ArrayList<>(pendingCalls);
                if (hasCalls) finalCalls.addAll(message.getToolCalls());
                if (!finalCalls.isEmpty()) {
                    workflowCallsByPosition.set(i,
                            Collections.unmodifiableList(finalCalls));
                }
            } else if (hasCalls) {
                suppressOwnToolCalls[i] = true;
                lastPendingOwner = i;
                pendingCalls.addAll(message.getToolCalls());
            }
        }
        if (!pendingCalls.isEmpty() && summaryPosition < 0 && lastPendingOwner >= 0) {
            workflowCallsByPosition.set(lastPendingOwner,
                    Collections.unmodifiableList(new ArrayList<>(pendingCalls)));
            hiddenWorkflowChildren[lastPendingOwner] = false;
            suppressOwnToolCalls[lastPendingOwner] = false;
        }

        // Any tool-only source whose calls were assigned to a later visible
        // anchor is hidden after the forward pass. This avoids treating its raw
        // content/thinking as a visible separator.
        for (int i = start; i <= scanEnd; i++) {
            Message message = messages.get(i);
            if (message == null || !message.hasToolCalls()) continue;
            if (workflowCallsByPosition.get(i) == null && !hasVisibleAssistantText(message)
                    && !message.hasThinking()) {
                hiddenWorkflowChildren[i] = true;
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty() || position < 0 || position >= messages.size()
                || isToolWorkflowChild(position)) {
            onBindViewHolder(holder, position);
            return;
        }
        Message message = messages.get(position);
        for (Object payload : payloads) {
            if ("workflow_changed".equals(payload)) {
                onBindViewHolder(holder, position);
                return;
            }
            if ("content_only".equals(payload)) {
                // Content can change a tool-only item into a visible anchor.
                // Such messages need the rebuilt workflow presentation.
                if (message.hasToolCalls()) {
                    onBindViewHolder(holder, position);
                    return;
                }
                holder.updateContentOnly(message);
            } else if ("thinking_only".equals(payload)) {
                holder.updateThinkingOnly(message);
            } else if ("segments_changed".equals(payload)) {
                // Workflow segments / working state changed while the row was
                // offscreen — re-render just the workflow area.
                if (message.getWorkflowSegments() != null
                        && !message.getWorkflowSegments().isEmpty()) {
                    holder.renderWorkflowSegments(message);
                } else {
                    onBindViewHolder(holder, position);
                    return;
                }
            } else {
                onBindViewHolder(holder, position);
                return;
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public int indexOfMessage(Message msg) {
        return messages.indexOf(msg);
    }

    public Message getMessageAt(int position) {
        if (position >= 0 && position < messages.size()) {
            return messages.get(position);
        }
        return null;
    }

    public void addMessage(Message message) {
        int oldCount = messages.size();
        messages.add(message);
        rebuildWorkflowIndex();
        int inserted = messages.size() - 1;
        notifyItemInserted(inserted);
        if (oldCount > 0 && (message != null
                && (message.hasToolCalls() || message.isToolWorkflowSummary()
                || message.getType() == Message.TYPE_AI))) {
            notifyItemRangeChanged(Math.max(0, inserted - 1), 2, "workflow_changed");
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
        rebuildWorkflowIndex();
        notifyItemInserted(insertAt);
        if (message != null
                && (message.hasToolCalls() || message.isToolWorkflowSummary()
                || message.getType() == Message.TYPE_AI)) {
            notifyItemRangeChanged(Math.max(0, insertAt - 1), 2, "workflow_changed");
        }
    }

    public boolean removeMessage(Message message) {
        int index = messages.indexOf(message);
        if (index < 0) {
            return false;
        }
        messages.remove(index);
        rebuildWorkflowIndex();
        notifyItemRemoved(index);
        if (message != null && (message.hasToolCalls() || message.isToolWorkflowSummary())) {
            notifyItemRangeChanged(Math.max(0, index - 1),
                    Math.min(2, messages.size() - Math.max(0, index - 1)), "workflow_changed");
        }
        return true;
    }

    /**
     * Установить список сообщений (для загрузки чата)
     */
    public void setMessages(List<Message> newMessages) {
        List<Message> source = newMessages != null ? newMessages : Collections.emptyList();
        if (sameMessageSequence(source)) return;
        this.messages = new ArrayList<>(source);
        rebuildWorkflowIndex();
        lastAnimatedPosition = messages.size() - 1;
        notifyDataSetChanged();
    }

    private boolean sameMessageSequence(List<Message> candidate) {
        if (candidate == null || candidate.size() != messages.size()) return false;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) != candidate.get(i)) return false;
        }
        return true;
    }

    /**
     * Получить список сообщений
     */
    public List<Message> getMessages() {
        return messages;
    }

    public void refreshAllMessages() {
        rebuildWorkflowIndex();
        if (!messages.isEmpty()) {
            notifyItemRangeChanged(0, messages.size(), "workflow_changed");
        }
    }

    public void updateLastMessage(String content) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            lastMessage.setContent(content);
            // Используем payload чтобы обновить ТОЛЬКО content, без перерисовки thinking
            notifyItemChanged(lastIndex, "content_only");
        }
    }

    public void updateLastMessageThinking(String thinking) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            lastMessage.setThinkingContent(thinking);
            // Используем payload чтобы обновить ТОЛЬКО thinking
            notifyItemChanged(lastIndex, "thinking_only");
        }
    }

    // Прямое обновление TextView без notifyItemChanged (для анимации)
    public void updateLastMessageContentDirect(String content) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            lastMessage.setContent(content);
            
            // Находим ViewHolder и обновляем напрямую
            RecyclerView.ViewHolder holder = recyclerMessages != null ? 
                recyclerMessages.findViewHolderForAdapterPosition(lastIndex) : null;
            if (holder instanceof MessageViewHolder) {
                ((MessageViewHolder) holder).updateContentTextDirect(lastMessage, content);
            }
        }
    }
    
    // 🔥 Финальное обновление с рендерингом markdown (вызывается после завершения streaming)
    public void finalizeLastMessageContent() {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            
            // Находим ViewHolder и применяем markdown
            RecyclerView.ViewHolder holder = recyclerMessages != null ? 
                recyclerMessages.findViewHolderForAdapterPosition(lastIndex) : null;
            if (holder instanceof MessageViewHolder) {
                ((MessageViewHolder) holder).updateContentOnly(lastMessage);
            }
        }
    }

    // Прямое обновление thinking TextView без notifyItemChanged (для анимации)
    public void updateLastMessageThinkingDirect(String thinking) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            lastMessage.setThinkingContent(thinking);
            
            // Находим ViewHolder и обновляем напрямую
            RecyclerView.ViewHolder holder = recyclerMessages != null ? 
                recyclerMessages.findViewHolderForAdapterPosition(lastIndex) : null;
            if (holder instanceof MessageViewHolder) {
                ((MessageViewHolder) holder).updateThinkingTextDirect(lastMessage);
            }
        }
    }

    public void updateLastThinkingTimeDirect(long thinkingTimeMs) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            lastMessage.setThinkingTimeMs(thinkingTimeMs);

            RecyclerView.ViewHolder holder = recyclerMessages != null ?
                recyclerMessages.findViewHolderForAdapterPosition(lastIndex) : null;
            if (holder instanceof MessageViewHolder) {
                ((MessageViewHolder) holder).updateThinkingOnly(lastMessage);
            }
        }
    }

    public void updateMessageContentDirect(Message message, String content) {
        int index = indexOfMessage(message);
        if (index < 0) return;

        boolean wasVisible = hasVisibleAssistantText(message);
        message.setContent(content);
        if (wasVisible != hasVisibleAssistantText(message)) {
            rebuildWorkflowIndex();
            notifyDataSetChanged();
            return;
        }
        RecyclerView.ViewHolder holder = recyclerMessages != null ?
            recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof MessageViewHolder) {
            ((MessageViewHolder) holder).updateContentTextDirect(message, content);
        } else {
            notifyItemChanged(index, "content_only");
        }
    }

    public void updateMessageThinkingDirect(Message message, String thinking) {
        int index = indexOfMessage(message);
        if (index < 0) return;

        message.setThinkingContent(thinking);
        RecyclerView.ViewHolder holder = recyclerMessages != null ?
            recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof MessageViewHolder) {
            ((MessageViewHolder) holder).updateThinkingTextDirect(message);
        } else {
            notifyItemChanged(index, "thinking_only");
        }
    }

    public void updateMessageThinkingTimeDirect(Message message, long thinkingTimeMs) {
        int index = indexOfMessage(message);
        if (index < 0) return;

        message.setThinkingTimeMs(thinkingTimeMs);
        RecyclerView.ViewHolder holder = recyclerMessages != null ?
            recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof MessageViewHolder) {
            ((MessageViewHolder) holder).updateThinkingOnly(message);
        } else {
            notifyItemChanged(index, "thinking_only");
        }
    }

    public void refreshMessage(Message message) {
        int index = indexOfMessage(message);
        if (index < 0) return;
        boolean[] oldHidden = hiddenWorkflowChildren;
        boolean[] oldSuppressed = suppressOwnToolCalls;
        boolean[] oldSummaries = workflowSummaryHeaders;
        List<List<app.multiai.mobile.models.ToolCall>> oldCalls = workflowCallsByPosition;
        rebuildWorkflowIndex();
        java.util.LinkedHashSet<Integer> changed = new java.util.LinkedHashSet<>();
        changed.add(index);
        List<app.multiai.mobile.models.ToolCall> ownedCalls = message.getToolCalls();
        for (int i = 0; i < messages.size(); i++) {
            if (i < oldHidden.length && (oldHidden[i] != hiddenWorkflowChildren[i]
                    || oldSuppressed[i] != suppressOwnToolCalls[i]
                    || oldSummaries[i] != workflowSummaryHeaders[i]
                    || !sameToolCallSequence(oldCalls.get(i), workflowCallsByPosition.get(i)))) {
                changed.add(i);
            }
            if (containsAnyIdentity(i < oldCalls.size() ? oldCalls.get(i) : null, ownedCalls)
                    || containsAnyIdentity(workflowCallsByPosition.get(i), ownedCalls)) {
                changed.add(i);
            }
        }
        for (Integer changedPosition : changed) {
            notifyItemChanged(changedPosition, "workflow_changed");
        }
    }

    private static boolean containsAnyIdentity(
            List<app.multiai.mobile.models.ToolCall> haystack,
            List<app.multiai.mobile.models.ToolCall> needles) {
        if (haystack == null || needles == null || haystack.isEmpty() || needles.isEmpty()) {
            return false;
        }
        for (app.multiai.mobile.models.ToolCall hay : haystack) {
            for (app.multiai.mobile.models.ToolCall needle : needles) {
                if (hay == needle) return true;
            }
        }
        return false;
    }

    /**
     * Live update of the workflow summary header while the turn is in progress.
     * Shows "Работает уже Xс"; the card stays auto-opened unless the user
     * collapsed it manually. Called from ChatActivity's workflow timer every 200ms.
     */
    public void updateWorkflowWorking(Message message, String workingText) {
        int index = indexOfMessage(message);
        if (index < 0) return;
        RecyclerView.ViewHolder holder = recyclerMessages != null
                ? recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof MessageViewHolder) {
            ((MessageViewHolder) holder).updateWorkflowWorkingDirect(message, workingText);
        } else if (!isToolWorkflowChild(index)) {
            // Offscreen: schedule a payload rebind so the row picks up the
            // new working state when it scrolls back into view.
            notifyItemChanged(index, "segments_changed");
        }
    }

    /**
     * Direct update of workflow segments (thinking + tool calls) without
     * notifyItemChanged — avoids RecyclerView rebind that breaks streaming.
     * Renders segments into toolCallsContainer in order. Offscreen rows get
     * a payload notify as fallback: the next bind rebuilds the segments.
     */
    public void updateWorkflowSegmentsDirect(Message message) {
        int index = indexOfMessage(message);
        if (index < 0) return;
        RecyclerView.ViewHolder holder = recyclerMessages != null
                ? recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof MessageViewHolder) {
            ((MessageViewHolder) holder).renderWorkflowSegments(message);
        } else if (!isToolWorkflowChild(index)) {
            notifyItemChanged(index, "segments_changed");
        }
    }

    public void collapseWorkflowAfterCompletion(Message message) {
        if (message == null) return;
        message.setToolWorkflowExpanded(false);
        int index = indexOfMessage(message);
        if (index < 0) return;
        RecyclerView.ViewHolder holder = recyclerMessages != null
                ? recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof MessageViewHolder) {
            holder.itemView.post(() -> {
                int currentIndex = indexOfMessage(message);
                if (currentIndex < 0
                        || holder.getBindingAdapterPosition() != currentIndex) {
                    return;
                }
                ((MessageViewHolder) holder).collapseWorkflowDirect();
            });
        } else {
            notifyItemChanged(index, "segments_changed");
        }
    }

    private static boolean sameToolCallSequence(
            List<app.multiai.mobile.models.ToolCall> first,
            List<app.multiai.mobile.models.ToolCall> second) {
        if (first == second) return true;
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i) != second.get(i)) return false;
        }
        return true;
    }

    public void finalizeMessageContent(Message message) {
        int index = indexOfMessage(message);
        if (index < 0) return;

        message.setStreaming(false);
        if (message.hasThinking() && message.getThinkingTimeMs() > 0L) {
            message.setThinkingFinished(true);
        }

        String content = message.getContent() != null ? message.getContent() : "";
        int cp = content.codePointCount(0, content.length());
        message.setRevealStartCp(cp);
        message.setRevealEndCp(cp);
        message.setGhostStartCp(cp);

        RecyclerView.ViewHolder holder = recyclerMessages != null ?
            recyclerMessages.findViewHolderForAdapterPosition(index) : null;
        if (holder instanceof MessageViewHolder) {
            ((MessageViewHolder) holder).updateContentOnly(message);
        } else {
            notifyItemChanged(index, "content_only");
        }
    }

    // Сохраняем ссылку на RecyclerView для прямого доступа к ViewHolder.
    private RecyclerView recyclerMessages;

    public void setRecyclerView(RecyclerView recyclerView) {
        this.recyclerMessages = recyclerView;
    }

    public void appendToLastMessage(String chunk) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            String current = lastMessage.getContent();
            lastMessage.setContent(current + chunk);
            notifyItemChanged(lastIndex);
        }
    }

    public void appendToLastThinking(String chunk) {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message lastMessage = messages.get(lastIndex);
            String current = lastMessage.getThinkingContent() != null ? lastMessage.getThinkingContent() : "";
            lastMessage.setThinkingContent(current + chunk);
            notifyItemChanged(lastIndex);
        }
    }

    public void removeLastMessage() {
        if (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            messages.remove(lastIndex);
            rebuildWorkflowIndex();
            notifyItemRemoved(lastIndex);
        }
    }

    public void removeTrailingTypingMessages() {
        int originalCount = messages.size();
        int firstRemoved = messages.size();
        while (!messages.isEmpty()) {
            int lastIndex = messages.size() - 1;
            Message message = messages.get(lastIndex);
            if (message.getType() != Message.TYPE_AI_TYPING) {
                break;
            }
            messages.remove(lastIndex);
            firstRemoved = lastIndex;
        }
        if (firstRemoved < originalCount) {
            rebuildWorkflowIndex();
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
            rebuildWorkflowIndex();
        }
    }

    public void updateReconnectingText(String text) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getType() == Message.TYPE_RECONNECTING) {
                m.setContent(text);
                notifyItemChanged(i);
            }
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
        rebuildWorkflowIndex();
        notifyDataSetChanged();
    }

    public void showTypingAfterReconnect() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getType() == Message.TYPE_RECONNECTING) messages.remove(i);
        }
        if (messages.isEmpty() || messages.get(messages.size() - 1).getType() != Message.TYPE_AI_TYPING) {
            messages.add(new Message("", Message.TYPE_AI_TYPING));
        }
        rebuildWorkflowIndex();
        notifyDataSetChanged();
    }

    /**
     * Detach is NOT a lifecycle end for a live row.
     *
     * <p>Scrolling a still-working row out of view detaches it into the
     * RecyclerView view cache, and coming back re-attaches it without a rebind.
     * Clearing the running flag here left the working logo/wave frozen for the
     * rest of the turn. The animated views stop their own animators on detach
     * and revive them on attach, so nothing needs to be cleared at this point;
     * a genuinely recycled holder is handled by onViewRecycled below.</p>
     */
    @Override
    public void onViewDetachedFromWindow(@NonNull MessageViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull MessageViewHolder holder) {
        holder.clearTransientAnimations();
        if (holder.textMessage != null) {
            app.multiai.mobile.utils.Telemoji.clearTracking(holder.textMessage);
        }
        if (holder.textThinking != null) {
            app.multiai.mobile.utils.Telemoji.clearTracking(holder.textThinking);
        }
        if (holder.thinkingPreview != null) {
            app.multiai.mobile.utils.Telemoji.clearTracking(holder.thinkingPreview);
        }
        holder.resetRenderCache();
        super.onViewRecycled(holder);
    }
    
    // 🔥 Обновляем статус tool execution (spinner → галочка)
    public void updateToolExecutionStatus(int position, boolean completed) {
        if (position >= 0 && position < messages.size()) {
            Message message = messages.get(position);
            if (message.getType() == Message.TYPE_TOOL_EXECUTION) {
                message.setToolCompleted(completed);
                notifyItemChanged(position);
            }
        }
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        private TextView textMessage;
        private TextView textThinking;
        private TextView thinkingPreview;
        private app.multiai.mobile.ui.ThinkingWaveIconView thinkingWaveIcon;
        private String displayedThinkingPreview = "";
        private int displayedThinkingParagraphCount;
        private int thinkingPreviewAnimationGeneration;
        private LinearLayout thinkingContainer;
        private LinearLayout thinkingHeader;
        private ImageView thinkingToggle;
        private LinearLayout codeBlocksContainer;
        private LinearLayout bubbleContainer;
        private LinearLayout workflowArea;
        private LinearLayout workflowSummaryHeader;
        private TextView workflowSummaryText;
        private ImageView workflowSummaryIcon;
        private ImageView workflowSummaryToggle;
        private List<app.multiai.mobile.models.ToolCall> boundWorkflowCalls;
        private boolean hasBoundWorkflowCalls;
        private boolean suppressBoundToolCalls;
        private boolean showBoundWorkflowSummary;
        private String lastToolRenderSignature;
        /** Структурная сигнатура сегментного рендера (включая статусы/expanded). */
        private String lastWorkflowStructureSignature;
        /** TextView хвостового thinking-сегмента для точечных обновлений токенов. */
        private TextView activeThinkingTextView;
        private TextView activeThinkingPreviewView;
        private TextView activeContentTextView;
        private String activeContentTarget = "";
        private int activeContentShownCp;
        private long activeContentRevealStartedAt;
        private Runnable activeContentRevealTask;
        private app.multiai.mobile.ui.WorkingLogoIconView workingLogo;
        private app.multiai.mobile.ui.WorkingLogoIconView reconnectingLogo;
        private app.multiai.mobile.ui.WaveTextView reconnectingText;
        // Контейнер современного renderer'а для исторических tool-сообщений.
        private android.widget.FrameLayout toolWidgetSlot;
        private TextView textErrorTitle;
        private TextView textErrorBody;
        private TextView textErrorCode;
        private View buttonRetry;
        private View buttonSupport;
        private View buttonAuth;
        private View actionsRow;
        private int viewType;
        // AI action bar
        private LinearLayout actionBar;
        private ImageView btnCopy, btnLike, btnDislike, btnTts, btnMore;
        private View variantPager;
        private ImageView variantPrev, variantNext;
        private TextView variantIndicator;
        private boolean liked = false, disliked = false;

        // Semantic color resource ids used across tool/action rendering.
        private static final int actionInactive = R.color.action_inactive;
        private static final int textPrimary = R.color.text_primary;
        private static final int textSecondary = R.color.text_secondary;
        private static final int accentColor = R.color.accent;
        private static final int errorColor = R.color.error;
        private static final int successColor = R.color.success;
        private static final int codeBg = R.color.code_bg;
        private static final int codeText = R.color.code_text;
        private static final int syntaxString = R.color.syntax_string;
        private static final int dividerColor = R.color.divider;

        /** Resolve a semantic color resource to an int for the current theme. */
        private int color(int resId) {
            return androidx.core.content.ContextCompat.getColor(itemView.getContext(), resId);
        }

        MessageViewHolder(@NonNull View itemView, int viewType) {
            super(itemView);
            this.viewType = viewType;
            
            if (viewType == Message.TYPE_AI_TYPING) {
                workingLogo = itemView.findViewById(R.id.workingLogo);
            } else if (viewType == Message.TYPE_RECONNECTING) {
                reconnectingLogo = itemView.findViewById(R.id.workingLogo);
                reconnectingText = itemView.findViewById(R.id.reconnectingText);
            } else if (viewType == Message.TYPE_ERROR) {
                textErrorTitle = itemView.findViewById(R.id.textErrorTitle);
                textErrorBody = itemView.findViewById(R.id.textErrorBody);
                textErrorCode = itemView.findViewById(R.id.textErrorCode);
                buttonRetry = itemView.findViewById(R.id.buttonRetry);
                buttonSupport = itemView.findViewById(R.id.buttonSupport);
                buttonAuth = itemView.findViewById(R.id.buttonAuth);
                actionsRow = itemView.findViewById(R.id.actionsRow);
            } else if (viewType == Message.TYPE_AI) {
                textMessage = itemView.findViewById(R.id.textMessage);
                textThinking = itemView.findViewById(R.id.textThinking);
                thinkingPreview = itemView.findViewById(R.id.thinkingPreview);
                thinkingWaveIcon = itemView.findViewById(R.id.thinkingWaveIcon);
                thinkingContainer = itemView.findViewById(R.id.thinkingContainer);
                thinkingHeader = itemView.findViewById(R.id.thinkingHeader);
                thinkingToggle = itemView.findViewById(R.id.thinkingToggle);
                codeBlocksContainer = itemView.findViewById(R.id.codeBlocksContainer);
                bubbleContainer = itemView.findViewById(R.id.bubbleContainer);
                workflowArea = itemView.findViewById(R.id.workflowArea);
                workflowSummaryHeader = itemView.findViewById(R.id.workflowSummaryHeader);
                workflowSummaryText = itemView.findViewById(R.id.workflowSummaryText);
                workflowSummaryIcon = itemView.findViewById(R.id.workflowSummaryIcon);
                workflowSummaryToggle = itemView.findViewById(R.id.workflowSummaryToggle);
            } else if (viewType == Message.TYPE_TOOL_EXECUTION) {
                toolWidgetSlot = itemView.findViewById(R.id.toolWidgetSlot);
            } else {
                // User message
                textMessage = itemView.findViewById(R.id.textMessage);
            }

            // AI action bar buttons
            if (viewType == Message.TYPE_AI) {
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
            }
        }

        void bind(Message message, List<app.multiai.mobile.models.ToolCall> workflowCalls,
                  boolean suppressOwnToolCalls, boolean showWorkflowSummary) {
            clearTransientAnimations();
            resetRenderCache();
            boundWorkflowCalls = workflowCalls;
            hasBoundWorkflowCalls = workflowCalls != null;
            suppressBoundToolCalls = suppressOwnToolCalls;
            showBoundWorkflowSummary = showWorkflowSummary;

            if (viewType == Message.TYPE_AI_TYPING) {
                // Анимированный логотип MultiAI: бегущая светяшка + флип
                if (workingLogo != null) {
                    workingLogo.setRunning(true);
                }
            } else if (viewType == Message.TYPE_RECONNECTING) {
                if (reconnectingLogo != null) {
                    reconnectingLogo.setRunning(true);
                }
                if (reconnectingText != null) {
                    reconnectingText.setText(message.getContentSafe());
                    reconnectingText.setWaving(true);
                }
            } else if (viewType == Message.TYPE_ERROR) {
                bindErrorMessage(message);
            } else if (viewType == Message.TYPE_AI) {
                if (message.isStreaming()) {
                    // During streaming: update workflow area FIRST so it stays
                    // on top, then content — avoids bubble pushing workflow down
                    updateToolCalls(message);
                    updateThinkingOnly(message);
                    updateContentTextDirect(message,
                            message.getContent() != null ? message.getContent() : "");
                } else {
                    updateContentOnly(message);
                    updateThinkingOnly(message);
                }
                updateActionBarVisibility(message);
                bindVariantPager(message);
            } else if (viewType == Message.TYPE_TOOL_EXECUTION) {
                // 🔥 Tool execution UI
                bindToolExecution(message);
            } else if (viewType == Message.TYPE_USER) {
                // 🔥 User message with optional image
                bindUserMessage(message);
                // Long-press context menu for user messages
                itemView.setOnLongClickListener(v -> {
                    showUserContextMenu(message);
                    return true;
                });
            } else if (textMessage != null) {
                // Рендерим markdown для AI сообщений
                if (message.getType() == Message.TYPE_AI) {
                    app.multiai.mobile.utils.MarkdownRenderer.render(
                        itemView.getContext(),
                        textMessage,
                        message.getContent()
                    );
                } else {
                    app.multiai.mobile.utils.Telemoji.apply(
                        itemView.getContext(), textMessage, message.getContent());
                }
            }
        }

        void clearTransientAnimations() {
            if (workingLogo != null) {
                workingLogo.setRunning(false);
            }
            if (reconnectingLogo != null) {
                reconnectingLogo.setRunning(false);
            }
            if (reconnectingText != null) {
                reconnectingText.setWaving(false);
            }
        }

        /**
         * Сбрасывает кэш рендера workflow. Вызывается при перезапуске holder'а
         * (recycle/rebind), иначе дедуп-сигнатура от прошлого сообщения
         * блокирует первый рендер нового — свёрнутое оставалось бы свёрнутым
         * и наоборот.
         */
        void resetRenderCache() {
            lastToolRenderSignature = null;
            lastWorkflowStructureSignature = null;
            activeThinkingTextView = null;
            activeThinkingPreviewView = null;
            activeContentTextView = null;
            if (activeContentRevealTask != null) handler.removeCallbacks(activeContentRevealTask);
            activeContentRevealTask = null;
            activeContentTarget = "";
            activeContentShownCp = 0;
            activeContentRevealStartedAt = 0L;
            displayedThinkingPreview = "";
            displayedThinkingParagraphCount = 0;
        }

        void bindErrorMessage(Message message) {
            if (textErrorTitle != null) {
                String title = message.getErrorTitle() != null ? message.getErrorTitle()
                        : itemView.getContext().getString(R.string.kit_msg_error_title);
                textErrorTitle.setText(title);
            }

            if (textErrorBody != null) {
                textErrorBody.setText(message.getContent() != null ? message.getContent()
                        : itemView.getContext().getString(R.string.kit_msg_error_body));
            }

            if (textErrorCode != null) {
                String code = message.getErrorCode() != null ? message.getErrorCode()
                        : itemView.getContext().getString(R.string.kit_msg_error_default_code);
                textErrorCode.setText(itemView.getContext().getString(R.string.kit_msg_error_code_prefix) + code);
            }

            // Auth errors: show "Sign in with MultiAI" button, hide retry + support.
            String code = message.getErrorCode();
            boolean isAuthError = "MULTIAI-NOT-AUTHORIZED".equals(code)
                    || "MULTIAI-SESSION-EXPIRED".equals(code);
            if (buttonAuth != null) {
                if (isAuthError) {
                    buttonAuth.setVisibility(View.VISIBLE);
                    buttonAuth.setOnClickListener(v -> {
                        android.content.Context ctx = itemView.getContext();
                        Intent intent = new Intent(ctx, app.multiai.mobile.ui.ProfileActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(intent);
                    });
                } else {
                    buttonAuth.setVisibility(View.GONE);
                    buttonAuth.setOnClickListener(null);
                }
            }

            if (buttonRetry != null) {
                if (isAuthError) {
                    // Auth errors don't use retry — the auth button replaces it.
                    buttonRetry.setVisibility(View.GONE);
                    buttonRetry.setOnClickListener(null);
                } else if (message.isRetryable()) {
                    buttonRetry.setVisibility(View.VISIBLE);
                    // Subtle pulse animation on retry button
                    buttonRetry.setScaleX(0.85f);
                    buttonRetry.setScaleY(0.85f);
                    buttonRetry.setAlpha(0.6f);
                    buttonRetry.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(350).setStartDelay(200)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                    buttonRetry.setOnClickListener(v -> {
                        RecyclerView.Adapter<?> adapter = getBindingAdapter();
                        if (adapter instanceof MessagesAdapter) {
                            OnErrorRetryListener retryListener = ((MessagesAdapter) adapter).errorRetryListener;
                            if (retryListener != null) {
                                retryListener.onRetry(message);
                            }
                        }
                    });
                } else {
                    buttonRetry.setVisibility(View.GONE);
                    buttonRetry.setOnClickListener(null);
                }
            }

            // Auth errors don't need support — they're self-service.
            if (buttonSupport != null) {
                if (isAuthError) {
                    buttonSupport.setVisibility(View.GONE);
                    buttonSupport.setOnClickListener(null);
                } else {
                    buttonSupport.setVisibility(View.VISIBLE);
                    buttonSupport.setOnClickListener(v -> {
                        android.content.Context ctx = itemView.getContext();
                        String errCode = message.getErrorCode() != null ? message.getErrorCode()
                                : ctx.getString(R.string.kit_msg_error_fallback_code);
                        // Collect real logcat logs for support.
                        String log = app.multiai.mobile.utils.LogCollector.collect(50);
                        String tgUrl = app.multiai.mobile.utils.SupportLinkBuilder.build(errCode, log);
                        Intent openTg = new Intent(Intent.ACTION_VIEW, Uri.parse(tgUrl));
                        openTg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            ctx.startActivity(openTg);
                        } catch (ActivityNotFoundException ignored) {
                            // Fallback to a share-intent (e.g. user has no browser/Telegram installed)
                            Intent share = new Intent(Intent.ACTION_SEND);
                            share.setType("text/plain");
                            share.putExtra(Intent.EXTRA_TEXT, "Здравствуйте! у меня возникла ошибка " + errCode + ", помогите!\nВот логи ошибки:\n\"" + log + "\"");
                            ctx.startActivity(Intent.createChooser(share,
                                    ctx.getString(R.string.kit_msg_error_share_support)));
                        }
                    });
                }
            }
        }
        
        // 🔥 Bind user message with image support
        void bindUserMessage(Message message) {
            if (textMessage == null) return;
            
            String content = message.getContent();
            boolean hasFile = message.hasAttachedFile();
            if (content != null && !content.isEmpty()) {
                textMessage.setVisibility(View.VISIBLE);
                app.multiai.mobile.utils.Telemoji.apply(
                    itemView.getContext(), textMessage, content);
            } else if (!hasFile && !message.hasImage()) {
                // No text, no file, no image — still show empty bubble
                textMessage.setVisibility(View.VISIBLE);
                app.multiai.mobile.utils.Telemoji.clearTracking(textMessage);
                textMessage.setText("");
            } else {
                textMessage.setVisibility(View.GONE);
            }
            
            // File attachment card
            android.widget.LinearLayout fileCard = itemView.findViewById(R.id.fileAttachmentCard);
            if (fileCard != null) {
                if (message.hasAttachedFile()) {
                    fileCard.setVisibility(View.VISIBLE);
                    android.widget.TextView fileName = itemView.findViewById(R.id.fileAttachmentName);
                    android.widget.TextView fileSize = itemView.findViewById(R.id.fileAttachmentSize);
                    if (fileName != null) fileName.setText(message.getAttachedFileName());
                    if (fileSize != null) {
                        // Размер: из контента в памяти ИЛИ из файла на диске
                        // (после persistAttachment контент выгружен, остаётся путь).
                        long size = 0;
                        String fc = message.getAttachedFileContent();
                        if (fc != null) {
                            size = fc.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        } else {
                            String fp = message.getAttachedFilePath();
                            if (fp != null) {
                                java.io.File f = new java.io.File(fp);
                                if (f.exists()) size = f.length();
                            }
                        }
                        fileSize.setText(formatFileSize(size));
                    }
                } else {
                    fileCard.setVisibility(View.GONE);
                }
            }

            // Image attachment
            androidx.cardview.widget.CardView imageCard = itemView.findViewById(R.id.imageCard);
            android.widget.ImageView imageView = itemView.findViewById(R.id.imageAttachment);

            if (imageCard != null && imageView != null) {
                if (message.hasImage()) {
                    imageCard.setVisibility(View.VISIBLE);
                    final String cacheKey = message.getId() != null ? message.getId() : String.valueOf(message.hashCode());
                    android.graphics.Bitmap cached = thumbCache.get(cacheKey);
                    if (cached != null) {
                        imageView.setImageBitmap(cached);
                    } else {
                        imageView.setImageDrawable(null);
                        final ImageView target = imageView;
                        final int targetPos = getBindingAdapterPosition();
                        new Thread(() -> {
                            android.graphics.Bitmap bitmap = decodeImageThumbnail(message, THUMB_MAX_DIM);
                            if (bitmap != null) thumbCache.put(cacheKey, bitmap);
                            handler.post(() -> {
                                if (targetPos != getBindingAdapterPosition()) return;
                                if (bitmap != null) {
                                    target.setImageBitmap(bitmap);
                                } else {
                                    imageCard.setVisibility(View.GONE);
                                }
                            });
                        }, "img-decode").start();
                    }
                } else {
                    imageCard.setVisibility(View.GONE);
                }
            }
        }
        
        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }

        /** Отрисовывает старое standalone tool-сообщение тем же современным renderer'ом. */
        void bindToolExecution(Message message) {
            if (toolWidgetSlot == null) return;
            toolWidgetSlot.removeAllViews();

            View widget = null;
            if (message.hasToolCalls()) {
                List<app.multiai.mobile.models.ToolCall> calls = message.getToolCalls();
                if (calls != null && !calls.isEmpty()) {
                    app.multiai.mobile.models.ToolCall latest = calls.get(calls.size() - 1);
                    if (latest != null) {
                        widget = app.multiai.mobile.views.ToolWidgetRenderer.render(
                                itemView.getContext(), latest);
                    }
                }
            }
            if (widget == null) {
                widget = app.multiai.mobile.views.ToolWidgetRenderer.renderLegacyToolExecution(
                        itemView.getContext(), message.getToolName(), message.getToolDetails(),
                        message.isToolCompleted());
            }
            widget.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            toolWidgetSlot.addView(widget);
        }

        private void updateWorkflowSummaryIcon(boolean working) {
            if (workflowSummaryIcon == null) return;
            int drawable = working ? R.drawable.ic_reasoning_spark : R.drawable.ic_check;
            if (workflowSummaryIcon instanceof app.multiai.mobile.ui.ThinkingWaveIconView) {
                app.multiai.mobile.ui.ThinkingWaveIconView animated =
                        (app.multiai.mobile.ui.ThinkingWaveIconView) workflowSummaryIcon;
                animated.setStatusDrawable(drawable);
                animated.setRunning(working);
            } else {
                workflowSummaryIcon.setImageResource(drawable);
            }
            if (workflowSummaryIcon.getDrawable() != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTint(
                        workflowSummaryIcon.getDrawable(),
                        androidx.core.content.ContextCompat.getColor(
                                itemView.getContext(), R.color.icon_muted));
            }
        }

        /**
         * Direct update of the workflow summary header during the live turn.
         * Shows "Работает уже Xс" and keeps the card expanded (auto-open)
         * unless the user manually collapsed it — manual state survives.
         * No notifyItemChanged — avoids RecyclerView jitter on every tick.
         */
        void updateWorkflowWorkingDirect(Message message, String workingText) {
            if (viewType != Message.TYPE_AI) return;
            updateWorkflowSummaryIcon(true);
            boolean expanded = message.computeWorkflowExpanded(false);
            if (workflowArea != null && workflowArea.getVisibility() != View.VISIBLE) {
                workflowArea.setVisibility(View.VISIBLE);
            }
            if (workflowSummaryHeader != null && workflowSummaryHeader.getVisibility() != View.VISIBLE) {
                workflowSummaryHeader.setVisibility(View.VISIBLE);
            }
            // Only update text if it changed — avoids jitter on every tick
            if (workflowSummaryText != null && !workingText.equals(workflowSummaryText.getText().toString())) {
                workflowSummaryText.setText(workingText);
            }
            // Render segments if present (handles toggle, thinking, tool calls)
            List<app.multiai.mobile.models.WorkflowSegment> segments = message.getWorkflowSegments();
            if (segments != null && !segments.isEmpty()) {
                renderWorkflowSegments(message);
            } else {
                // No segments — hide static thinking, respect manual collapse
                if (thinkingContainer != null) thinkingContainer.setVisibility(View.GONE);
                LinearLayout toolCallsContainer = itemView.findViewById(R.id.toolCallsContainer);
                if (toolCallsContainer != null) {
                    toolCallsContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
                }
                if (workflowSummaryToggle != null) {
                    workflowSummaryToggle.setRotation(expanded ? 0f : -90f);
                }
            }
            // Hide bubble while working with no content yet
            String content = message.getContentSafe();
            if (content.isEmpty() && bubbleContainer != null) {
                bubbleContainer.setVisibility(View.GONE);
            }
        }

        /**
         * Renders workflow segments (thinking + tool calls) into toolCallsContainer
         * in the correct order. Does NOT call notifyItemChanged — direct view update.
         * Called during streaming to keep segments in sync without rebind.
         *
         * Repair notes:
         * - Expanded state comes from Message.computeWorkflowExpanded: the card
         *   auto-opens while the turn is working and after completion, unless
         *   the user explicitly collapsed it (manual state survives re-renders).
         * - If only the trailing (active) thinking segment grew, its TextView
         *   is patched in place — no removeAllViews and no widget re-inflation
         *   on every streamed token.
         */
        void renderWorkflowSegments(Message message) {
            if (viewType != Message.TYPE_AI) return;
            LinearLayout toolCallsContainer = itemView.findViewById(R.id.toolCallsContainer);
            if (toolCallsContainer == null) return;

            List<app.multiai.mobile.models.WorkflowSegment> segments = message.getWorkflowSegments();
            if (segments == null || segments.isEmpty()) {
                toolCallsContainer.setVisibility(View.GONE);
                if (thinkingContainer != null) thinkingContainer.setVisibility(View.GONE);
                lastWorkflowStructureSignature = null;
                lastToolRenderSignature = null;
                activeThinkingTextView = null;
                activeThinkingPreviewView = null;
                activeContentTextView = null;
                return;
            }

            boolean working = message.isWorkingState();
            boolean summary = message.isToolWorkflowSummary() || showBoundWorkflowSummary;
            updateWorkflowSummaryIcon(working);
            if (workflowSummaryText != null) {
                if (working) {
                    workflowSummaryText.setText(itemView.getContext().getString(
                            R.string.chat_workflow_working,
                            formatDuration(itemView.getContext(), message.getTurnDurationMs())));
                } else if (summary) {
                    workflowSummaryText.setText(itemView.getContext().getString(
                            R.string.chat_workflow_completed,
                            formatDuration(itemView.getContext(), message.getTurnDurationMs())));
                }
            }
            // Auto-open during work / after completion unless manually collapsed
            boolean expanded = message.computeWorkflowExpanded(summary);

            // Show workflowArea
            if (workflowArea != null) workflowArea.setVisibility(View.VISIBLE);

            // Hide static thinkingContainer — segments provide their own
            if (thinkingContainer != null) thinkingContainer.setVisibility(View.GONE);

            // Dedup by structure signature (status/result/expanded aware)
            String sig = segmentStructureSignature(segments, summary, expanded) + (working ? "W" : "w");
            if (sig.equals(lastWorkflowStructureSignature)) {
                // Targeted update: same structure and only the trailing
                // thinking text can differ — patch its TextView in place.
                app.multiai.mobile.models.WorkflowSegment lastSeg =
                        segments.get(segments.size() - 1);
                if (lastSeg != null
                        && lastSeg.getType() == app.multiai.mobile.models.WorkflowSegment.TYPE_THINKING
                        && activeThinkingTextView != null) {
                    updateActiveThinkingSegment(lastSeg);
                } else if (lastSeg != null
                        && lastSeg.getType() == app.multiai.mobile.models.WorkflowSegment.TYPE_CONTENT
                        && activeContentTextView != null) {
                    revealActiveContent(lastSeg.getContentText());
                }
                return;
            }
            lastWorkflowStructureSignature = sig;
            lastToolRenderSignature = null; // different render path now
            activeThinkingTextView = null;
            activeThinkingPreviewView = null;
            activeContentTextView = null;

            toolCallsContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toolCallsContainer.removeAllViews();

            for (int segIdx = 0; segIdx < segments.size(); segIdx++) {
                app.multiai.mobile.models.WorkflowSegment seg = segments.get(segIdx);
                if (seg == null) continue;
                if (seg.getType() == app.multiai.mobile.models.WorkflowSegment.TYPE_THINKING) {
                    String text = seg.getVisibleThinkingText();
                    if (text.isEmpty()) continue;
                    boolean trailing = (segIdx == segments.size() - 1);
                    View thinkingView = createThinkingSegmentView(seg, trailing);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    if (toolCallsContainer.getChildCount() > 0) lp.topMargin = dp(6);
                    thinkingView.setLayoutParams(lp);
                    thinkingView.setVisibility(expanded ? View.VISIBLE : View.GONE);
                    toolCallsContainer.addView(thinkingView);
                } else if (seg.getType() == app.multiai.mobile.models.WorkflowSegment.TYPE_CONTENT) {
                    String content = seg.getContentText();
                    if (content == null || content.trim().isEmpty()) continue;
                    boolean streamingTail = working && segIdx == segments.size() - 1;
                    // Finished segments with code blocks, LaTeX or tables need the
                    // layout renderer. Rendering them into a plain TextView made
                    // Markwon draw its legacy table instead of the new card.
                    if (!streamingTail && needsLayoutRender(content)) {
                        LinearLayout richContent = new LinearLayout(itemView.getContext());
                        richContent.setOrientation(LinearLayout.VERTICAL);
                        app.multiai.mobile.utils.MarkdownRenderer.renderToLayout(
                                itemView.getContext(), richContent, content);
                        LinearLayout.LayoutParams richLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        if (toolCallsContainer.getChildCount() > 0) richLp.topMargin = dp(8);
                        richContent.setLayoutParams(richLp);
                        richContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
                        toolCallsContainer.addView(richContent);
                        continue;
                    }
                    TextView contentView = new TextView(itemView.getContext());
                    contentView.setTextColor(color(textPrimary));
                    contentView.setTextSize(16f);
                    contentView.setLineSpacing(0f, 1.12f);
                    contentView.setTextIsSelectable(true);
                    boolean activeStreamingContent = streamingTail;
                    if (activeStreamingContent) {
                        contentView.setText("");
                    } else {
                        app.multiai.mobile.utils.MarkdownRenderer.render(
                                itemView.getContext(), contentView, content);
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    if (toolCallsContainer.getChildCount() > 0) lp.topMargin = dp(8);
                    contentView.setLayoutParams(lp);
                    contentView.setVisibility(expanded ? View.VISIBLE : View.GONE);
                    toolCallsContainer.addView(contentView);
                    if (segIdx == segments.size() - 1) {
                        activeContentTextView = contentView;
                        activeContentTarget = "";
                        activeContentShownCp = activeStreamingContent ? 0
                                : content.codePointCount(0, content.length());
                        if (activeStreamingContent) revealActiveContent(content);
                    }
                } else if (seg.getType() == app.multiai.mobile.models.WorkflowSegment.TYPE_TOOL_CALLS) {
                    List<app.multiai.mobile.models.ToolCall> segCalls = seg.getToolCalls();
                    if (segCalls == null || segCalls.isEmpty()) continue;
                    for (int i = 0; i < segCalls.size(); ) {
                        app.multiai.mobile.models.ToolCall tc = segCalls.get(i);
                        if (tc == null) { i++; continue; }
                        if (app.multiai.mobile.views.ToolWidgetRenderer.isExploreTool(tc.getName())) {
                            List<app.multiai.mobile.models.ToolCall> exploreCalls = new ArrayList<>();
                            int end = i;
                            while (end < segCalls.size() && segCalls.get(end) != null
                                    && app.multiai.mobile.views.ToolWidgetRenderer
                                    .isExploreTool(segCalls.get(end).getName())) {
                                exploreCalls.add(segCalls.get(end++));
                            }
                            View explore = app.multiai.mobile.views.ToolWidgetRenderer.renderExplore(
                                    itemView.getContext(), exploreCalls);
                            if (explore != null) {
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                                if (toolCallsContainer.getChildCount() > 0) lp.topMargin = dp(6);
                                explore.setLayoutParams(lp);
                                explore.setVisibility(expanded ? View.VISIBLE : View.GONE);
                                toolCallsContainer.addView(explore);
                            } else {
                                for (app.multiai.mobile.models.ToolCall exploreCall : exploreCalls) {
                                    View toolView = app.multiai.mobile.views.ToolWidgetRenderer.render(
                                            itemView.getContext(), exploreCall);
                                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                                    if (toolCallsContainer.getChildCount() > 0) lp.topMargin = dp(6);
                                    toolView.setLayoutParams(lp);
                                    toolView.setVisibility(expanded ? View.VISIBLE : View.GONE);
                                    toolCallsContainer.addView(toolView);
                                }
                            }
                            i = end;
                            continue;
                        }
                        View widget = app.multiai.mobile.views.ToolWidgetRenderer.render(
                                itemView.getContext(), tc);
                        View toolView = widget;
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        if (toolCallsContainer.getChildCount() > 0) lp.topMargin = dp(6);
                        toolView.setLayoutParams(lp);
                        toolView.setVisibility(expanded ? View.VISIBLE : View.GONE);
                        toolCallsContainer.addView(toolView);
                        i++;
                    }
                    // A tool segment after a thinking segment closes it — only
                    // the TRAILING thinking segment may be patched in place.
                    activeThinkingTextView = null;
                    activeThinkingPreviewView = null;
                }
            }

            // Setup toggle on summary header
            if (workflowSummaryHeader != null && (summary || working)) {
                workflowSummaryHeader.setVisibility(View.VISIBLE);
                if (workflowSummaryToggle != null) {
                    workflowSummaryToggle.setRotation(expanded ? 0f : -90f);
                }
                workflowSummaryHeader.setOnClickListener(v -> {
                    message.toggleToolWorkflowExpanded();
                    message.setWorkflowCollapsedManually(!message.isToolWorkflowExpanded());
                    boolean open = message.isToolWorkflowExpanded();
                    toolCallsContainer.setVisibility(open ? View.VISIBLE : View.GONE);
                    for (int i = 0; i < toolCallsContainer.getChildCount(); i++) {
                        toolCallsContainer.getChildAt(i).setVisibility(open ? View.VISIBLE : View.GONE);
                    }
                    if (workflowSummaryToggle != null) {
                        workflowSummaryToggle.animate().rotation(open ? 0f : -90f).setDuration(150).start();
                    }
                });
            }
        }

        private void collapseWorkflowDirect() {
            LinearLayout container = itemView.findViewById(R.id.toolCallsContainer);
            if (container == null || container.getVisibility() != View.VISIBLE) return;
            if (workflowSummaryToggle != null) {
                workflowSummaryToggle.animate().rotation(-90f).setDuration(220L).start();
            }
            container.animate().cancel();
            container.animate().alpha(0f).translationY(-dp(4)).setDuration(220L)
                    .withEndAction(() -> {
                        container.setVisibility(View.GONE);
                        container.setAlpha(1f);
                        container.setTranslationY(0f);
                    }).start();
        }

        private void revealActiveContent(String target) {
            if (activeContentTextView == null || target == null) return;
            if (!target.startsWith(activeContentTarget)) {
                activeContentShownCp = 0;
                activeContentRevealStartedAt = 0L;
            }
            activeContentTarget = target;
            if (activeContentRevealStartedAt == 0L) {
                activeContentRevealStartedAt = android.os.SystemClock.uptimeMillis();
            }
            if (activeContentRevealTask != null) return;
            activeContentRevealTask = new Runnable() {
                @Override public void run() {
                    if (activeContentTextView == null) {
                        activeContentRevealTask = null;
                        return;
                    }
                    int totalCp = activeContentTarget.codePointCount(0, activeContentTarget.length());
                    if (activeContentShownCp >= totalCp) {
                        activeContentRevealTask = null;
                        return;
                    }
                    long elapsed = android.os.SystemClock.uptimeMillis()
                            - activeContentRevealStartedAt;
                    int timedTarget = (int) Math.floor(totalCp * Math.min(1d, elapsed / 1000d));
                    if (timedTarget > activeContentShownCp) activeContentShownCp = timedTarget;
                    int end = activeContentTarget.offsetByCodePoints(0, activeContentShownCp);
                    app.multiai.mobile.utils.Telemoji.clearTracking(activeContentTextView);
                    activeContentTextView.setText(activeContentTarget.substring(0, end));
                    handler.postDelayed(this, 16L);
                }
            };
            handler.post(activeContentRevealTask);
        }

        /**
         * Patch-in-place update of the trailing thinking segment: only its
         * TextView and preview change while tokens stream in. No container
         * rebuild, no widget re-inflation.
         */
        private void updateActiveThinkingSegment(app.multiai.mobile.models.WorkflowSegment seg) {
            String cleaned = seg.getVisibleThinkingText();
            if (cleaned.isEmpty()) return;
            if (!cleaned.contentEquals(activeThinkingTextView.getText())) {
                app.multiai.mobile.utils.Telemoji.clearTracking(activeThinkingTextView);
                activeThinkingTextView.setText(cleaned);
            }
            if (activeThinkingPreviewView != null) {
                String preview = cleaned.replaceAll("\\s+", " ").trim();
                if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
                if (!preview.isEmpty() && !preview.contentEquals(activeThinkingPreviewView.getText())) {
                    app.multiai.mobile.utils.Telemoji.clearTracking(activeThinkingPreviewView);
                    activeThinkingPreviewView.setText(preview);
                }
            }
        }

        // Обновляем ТОЛЬКО content (без thinking)
        void updateContentOnly(Message message) {
            if (viewType != Message.TYPE_AI) return;
            
            String content = cleanContentForDisplay(message.getContent());
            
            
            boolean needsLayoutRender = needsLayoutRender(content);

            List<app.multiai.mobile.models.WorkflowSegment> workflowSegments =
                    message.getWorkflowSegments();
            if (workflowSegments != null && !workflowSegments.isEmpty()
                    && !message.isStandaloneContentVisible()) {
                if (bubbleContainer != null) bubbleContainer.setVisibility(View.GONE);
                renderWorkflowSegments(message);
                updateActionBarVisibility(message);
                return;
            }

            // Everything renders inside the unified bubble
            if (!isVisuallyBlank(content)) {
                if (bubbleContainer != null) bubbleContainer.setVisibility(View.VISIBLE);
                if (!needsLayoutRender) {
                    // Simple text — render into textMessage
                    if (textMessage != null) {
                        textMessage.setVisibility(View.VISIBLE);
                        app.multiai.mobile.utils.MarkdownRenderer.render(
                            itemView.getContext(),
                            textMessage,
                            content
                        );
                    }
                    if (codeBlocksContainer != null) {
                        codeBlocksContainer.setVisibility(View.GONE);
                    }
                } else {
                    // Mixed content with code blocks — render into codeBlocksContainer
                    if (textMessage != null) {
                        app.multiai.mobile.utils.Telemoji.clearTracking(textMessage);
                        textMessage.setVisibility(View.GONE);
                    }
                    if (codeBlocksContainer != null) {
                        codeBlocksContainer.setVisibility(View.VISIBLE);
                        app.multiai.mobile.utils.MarkdownRenderer.renderToLayout(
                            itemView.getContext(),
                            codeBlocksContainer,
                            content
                        );
                    }
                }
            } else {
                if (bubbleContainer != null) bubbleContainer.setVisibility(View.GONE);
                if (textMessage != null) {
                    app.multiai.mobile.utils.Telemoji.clearTracking(textMessage);
                    textMessage.setText("");
                    textMessage.setVisibility(View.GONE);
                }
                if (codeBlocksContainer != null) {
                    codeBlocksContainer.removeAllViews();
                    codeBlocksContainer.setVisibility(View.GONE);
                }
            }
            
            // Отображаем tool calls если есть
            updateToolCalls(message);
            
            updateActionBarVisibility(message);
        }
        
        // Обновляем tool calls
        void updateToolCalls(Message message) {
            if (viewType != Message.TYPE_AI) return;
            updateEmptyBubbleVisibility(message);

            LinearLayout toolCallsContainer = itemView.findViewById(R.id.toolCallsContainer);
            if (toolCallsContainer == null) return;

            List<app.multiai.mobile.models.ToolCall> calls = hasBoundWorkflowCalls
                    ? boundWorkflowCalls
                    : suppressBoundToolCalls ? Collections.emptyList() : message.getToolCalls();
            boolean summary = showBoundWorkflowSummary;
            boolean hasCalls = calls != null && !calls.isEmpty();
            boolean hasThinking = message.hasThinking()
                    && !isVisuallyBlank(stripThinkingTagTokens(message.getThinkingContent()));
            boolean working = message.isWorkingState();
            updateWorkflowSummaryIcon(working);
            List<app.multiai.mobile.models.WorkflowSegment> segments = message.getWorkflowSegments();
            boolean hasSegments = segments != null && !segments.isEmpty();
            boolean hasWorkflowContent = hasCalls || summary || hasThinking || working || hasSegments;
            if (workflowArea != null) {
                workflowArea.setVisibility(hasWorkflowContent ? View.VISIBLE : View.GONE);
            }

            // === Segment-based rendering (unified path) ===
            // If we have ordered workflow segments, use renderWorkflowSegments
            // for both working and completed states. This handles:
            // - thinking → explore → thinking → explore ordering
            // - toggle expand/collapse
            // - "Работает уже" / "Выполнено за" header
            if (hasSegments) {
                if (working && workflowSummaryText != null) {
                    workflowSummaryText.setText(itemView.getContext().getString(
                            R.string.chat_workflow_working,
                            formatDuration(itemView.getContext(), message.getTurnDurationMs())));
                } else if (summary && workflowSummaryText != null) {
                    workflowSummaryText.setText(itemView.getContext().getString(
                            R.string.chat_workflow_completed,
                            formatDuration(itemView.getContext(), message.getTurnDurationMs())));
                }
                renderWorkflowSegments(message);
                return;
            }

            // === Legacy rendering (no segments) ===
            if (working) {
                if (workflowSummaryHeader != null) workflowSummaryHeader.setVisibility(View.VISIBLE);
                if (workflowSummaryText != null) {
                    workflowSummaryText.setText(itemView.getContext().getString(
                            R.string.chat_workflow_working,
                            formatDuration(itemView.getContext(), message.getTurnDurationMs())));
                }
                if (workflowSummaryToggle != null) workflowSummaryToggle.setRotation(-90f);
                if (thinkingContainer != null) thinkingContainer.setVisibility(View.GONE);
                toolCallsContainer.setVisibility(View.GONE);
                return;
            }
            if (workflowSummaryHeader != null) {
                workflowSummaryHeader.setVisibility(summary ? View.VISIBLE : View.GONE);
            }
            if (summary && workflowSummaryText != null) {
                workflowSummaryText.setText(itemView.getContext().getString(
                        R.string.chat_workflow_completed,
                        formatDuration(itemView.getContext(), message.getTurnDurationMs())));
                // Auto-open on completion unless the user collapsed it manually
                // (manual state survives re-renders and repeated binds).
                boolean expanded = message.computeWorkflowExpanded(true);
                toolCallsContainer.setVisibility(expanded && hasCalls ? View.VISIBLE : View.GONE);
                boolean thinkingHasContent = hasThinking
                        && !isVisuallyBlank(stripThinkingTagTokens(message.getThinkingContent()));
                if (thinkingContainer != null) {
                    thinkingContainer.setVisibility(expanded && thinkingHasContent ? View.VISIBLE : View.GONE);
                }
                if (workflowSummaryToggle != null) {
                    workflowSummaryToggle.setRotation(expanded ? 0f : -90f);
                }
                workflowSummaryHeader.setOnClickListener(v -> {
                    message.toggleToolWorkflowExpanded();
                    message.setWorkflowCollapsedManually(!message.isToolWorkflowExpanded());
                    boolean open = message.isToolWorkflowExpanded();
                    toolCallsContainer.setVisibility(open && hasCalls ? View.VISIBLE : View.GONE);
                    if (thinkingContainer != null) {
                        thinkingContainer.setVisibility(open && thinkingHasContent ? View.VISIBLE : View.GONE);
                    }
                    itemView.requestLayout();
                    ViewParent parent = itemView.getParent();
                    if (parent instanceof RecyclerView) {
                        RecyclerView recycler = (RecyclerView) parent;
                        recycler.requestLayout();
                        recycler.invalidateItemDecorations();
                    }
                    if (workflowSummaryToggle != null) {
                        workflowSummaryToggle.animate().rotation(open ? 0f : -90f).setDuration(150).start();
                    }
                });
            } else if (workflowSummaryHeader != null) {
                workflowSummaryHeader.setOnClickListener(null);
                if (thinkingContainer != null) {
                    boolean thinkingHasContent = hasThinking
                            && !isVisuallyBlank(stripThinkingTagTokens(message.getThinkingContent()));
                    thinkingContainer.setVisibility(thinkingHasContent ? View.VISIBLE : View.GONE);
                }
            }

            String renderSignature = toolRenderSignature(calls, summary, message.isToolWorkflowExpanded());
            if (renderSignature.equals(lastToolRenderSignature)) return;
            lastToolRenderSignature = renderSignature;

            if (hasCalls) {
                if (!summary) toolCallsContainer.setVisibility(View.VISIBLE);
                toolCallsContainer.removeAllViews();
                for (int i = 0; i < calls.size();) {
                    app.multiai.mobile.models.ToolCall tc = calls.get(i);
                    if (tc == null) { i++; continue; }
                    if (app.multiai.mobile.views.ToolWidgetRenderer.isExploreTool(tc.getName())) {
                        List<app.multiai.mobile.models.ToolCall> exploreCalls = new ArrayList<>();
                        int end = i;
                        while (end < calls.size() && calls.get(end) != null
                                && app.multiai.mobile.views.ToolWidgetRenderer
                                .isExploreTool(calls.get(end).getName())) {
                            exploreCalls.add(calls.get(end++));
                        }
                        View explore = app.multiai.mobile.views.ToolWidgetRenderer.renderExplore(
                                itemView.getContext(), exploreCalls);
                        if (explore != null) {
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            if (toolCallsContainer.getChildCount() > 0) lp.topMargin = dp(6);
                            explore.setLayoutParams(lp);
                            toolCallsContainer.addView(explore);
                        } else {
                            for (app.multiai.mobile.models.ToolCall exploreCall : exploreCalls) {
                                View toolView = app.multiai.mobile.views.ToolWidgetRenderer.render(
                                        itemView.getContext(), exploreCall);
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                                if (toolCallsContainer.getChildCount() > 0) lp.topMargin = dp(6);
                                toolView.setLayoutParams(lp);
                                toolCallsContainer.addView(toolView);
                            }
                        }
                        i = end;
                        continue;
                    }
                    View widget = app.multiai.mobile.views.ToolWidgetRenderer.render(
                            itemView.getContext(), tc);
                    View toolView = widget;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    if (toolCallsContainer.getChildCount() > 0) lp.topMargin = dp(6);
                    toolView.setLayoutParams(lp);
                    toolCallsContainer.addView(toolView);
                    i++;
                }
            } else {
                toolCallsContainer.setVisibility(View.GONE);
            }
        }

        /** Создаёт view для thinking-сегмента; expanded-состояние живёт в сегменте. */
        private View createThinkingSegmentView(app.multiai.mobile.models.WorkflowSegment seg,
                                               boolean trailing) {
            View view = android.view.LayoutInflater.from(itemView.getContext())
                    .inflate(R.layout.item_workflow_thinking, null, false);
            TextView textThinking = view.findViewById(R.id.textThinking);
            TextView thinkingPreview = view.findViewById(R.id.thinkingPreview);
            View thinkingHeader = view.findViewById(R.id.thinkingHeader);
            ImageView thinkingToggle = view.findViewById(R.id.thinkingToggle);
            final View thinkingScroll = view.findViewById(R.id.thinkingScroll);

            String cleaned = seg.getVisibleThinkingText();
            if (textThinking != null) {
                app.multiai.mobile.utils.Telemoji.apply(
                        itemView.getContext(), textThinking, cleaned);
            }
            if (thinkingPreview != null) {
                String preview = cleaned.replaceAll("\\s+", " ").trim();
                if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
                if (!preview.isEmpty()) {
                    app.multiai.mobile.utils.Telemoji.apply(
                            itemView.getContext(), thinkingPreview, preview);
                }
            }
            // Expand/collapse state is stored on the segment (transient) so it
            // survives re-renders while tokens keep streaming in. Always reset the
            // nested scroll position when opening: recycled/bound cards must start
            // at the first reasoning line instead of looking as if the beginning
            // was clipped.
            if (thinkingScroll != null) {
                boolean open = seg.isThinkingExpanded();
                thinkingScroll.setVisibility(open ? View.VISIBLE : View.GONE);
                if (open) thinkingScroll.post(() -> thinkingScroll.scrollTo(0, 0));
            }
            if (thinkingToggle != null) {
                thinkingToggle.setRotation(seg.isThinkingExpanded() ? 0f : -90f);
            }
            if (thinkingHeader != null) {
                thinkingHeader.setOnClickListener(v -> {
                    seg.toggleThinkingExpanded();
                    boolean open = seg.isThinkingExpanded();
                    if (thinkingScroll != null) {
                        thinkingScroll.setVisibility(open ? View.VISIBLE : View.GONE);
                        if (open) thinkingScroll.post(() -> thinkingScroll.scrollTo(0, 0));
                    }
                    if (thinkingToggle != null) {
                        thinkingToggle.setRotation(open ? 0f : -90f);
                    }
                });
            }
            if (trailing) {
                // Remember for patch-in-place token updates (no rebuilds).
                activeThinkingTextView = textThinking;
                activeThinkingPreviewView = thinkingPreview;
            }
            return view;
        }

        /**
         * Сигнатура структуры workflow: типы/порядок сегментов, статусы,
         * результаты и expanded-состояния tool calls. Длина текста trailing
         * thinking-сегмента НЕ входит — его обновление идёт точечно через
         * updateActiveThinkingSegment, без пересборки контейнера.
         */
        private String segmentStructureSignature(List<app.multiai.mobile.models.WorkflowSegment> segments,
                                                 boolean summary, boolean expanded) {
            StringBuilder sb = new StringBuilder();
            sb.append(summary ? "S" : "s").append(expanded ? "E" : "e").append(":");
            int lastIdx = -1;
            for (int i = segments.size() - 1; i >= 0; i--) {
                if (segments.get(i) != null) { lastIdx = i; break; }
            }
            for (int i = 0; i < segments.size(); i++) {
                app.multiai.mobile.models.WorkflowSegment seg = segments.get(i);
                if (seg == null) { sb.append("n;"); continue; }
                sb.append(seg.getType()).append(",");
                if (seg.getType() == app.multiai.mobile.models.WorkflowSegment.TYPE_THINKING) {
                    if (i == lastIdx) {
                        sb.append("T;"); // trailing: text patched in place
                    } else {
                        String t = seg.getThinkingText();
                        sb.append(t == null ? 0 : t.length()).append(";");
                    }
                } else if (seg.getType() == app.multiai.mobile.models.WorkflowSegment.TYPE_CONTENT) {
                    if (i == lastIdx) sb.append("C;");
                    else {
                        String content = seg.getContentText();
                        sb.append(content == null ? 0 : content.hashCode()).append(";");
                    }
                } else {
                    List<app.multiai.mobile.models.ToolCall> calls = seg.getToolCalls();
                    if (calls == null) { sb.append("null;"); continue; }
                    sb.append(calls.size()).append("[");
                    for (app.multiai.mobile.models.ToolCall call : calls) {
                        if (call == null) { sb.append("n,"); continue; }
                        sb.append(System.identityHashCode(call)).append(':')
                                .append(call.getName()).append(':').append(call.getStatus()).append(':')
                                .append(call.isExpanded()).append(':').append(call.isExploreExpanded()).append(':');
                        org.json.JSONObject result = call.getResult();
                        sb.append(result == null ? 0 : result.toString().hashCode());
                        sb.append(',');
                    }
                    sb.append("];");
                }
            }
            return sb.toString();
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

        private String toolRenderSignature(List<app.multiai.mobile.models.ToolCall> calls,
                                           boolean summary, boolean workflowExpanded) {
            StringBuilder signature = new StringBuilder(summary ? "summary|" : "tools|")
                    .append(workflowExpanded).append('|');
            if (calls == null) return signature.append("null").toString();
            for (app.multiai.mobile.models.ToolCall call : calls) {
                if (call == null) {
                    signature.append("separator|");
                    continue;
                }
                signature.append(System.identityHashCode(call)).append(':')
                        .append(call.getName()).append(':').append(call.getStatus()).append(':')
                        .append(call.isExpanded()).append(':').append(call.isExploreExpanded()).append(':');
                org.json.JSONObject result = call.getResult();
                if (result != null) signature.append(result.toString());
                signature.append('|');
            }
            return signature.toString();
        }

        private static String formatDuration(android.content.Context context, long durationMs) {
            long totalSeconds = Math.max(1L, Math.round(durationMs / 1000.0));
            if (totalSeconds < 60) {
                int seconds = (int) totalSeconds;
                return context.getResources().getQuantityString(
                        R.plurals.ide_duration_seconds, seconds, seconds);
            }
            int minutes = (int) (totalSeconds / 60);
            int seconds = (int) (totalSeconds % 60);
            String minuteText = context.getResources().getQuantityString(
                    R.plurals.ide_duration_minutes, minutes, minutes);
            if (seconds == 0) return minuteText;
            return minuteText + " " + context.getResources().getQuantityString(
                    R.plurals.ide_duration_seconds, seconds, seconds);
        }
        

        // Парсим контент на части: текст и код блоки
        private static class ContentPart {
            boolean isCode;
            String content;
            String language;
            
            ContentPart(boolean isCode, String content, String language) {
                this.isCode = isCode;
                this.content = content;
                this.language = language;
            }
        }
        
        private java.util.List<ContentPart> parseContentParts(String content) {
            java.util.List<ContentPart> parts = new java.util.ArrayList<>();
            
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "```(\\w*)\\n([\\s\\S]*?)```", 
                java.util.regex.Pattern.MULTILINE
            );
            java.util.regex.Matcher matcher = pattern.matcher(content);
            
            int lastEnd = 0;
            while (matcher.find()) {
                // Текст перед кодом
                if (matcher.start() > lastEnd) {
                    String text = content.substring(lastEnd, matcher.start()).trim();
                    if (!text.isEmpty()) {
                        parts.add(new ContentPart(false, text, ""));
                    }
                }
                
                // Код блок
                String language = matcher.group(1);
                String code = matcher.group(2);
                parts.add(new ContentPart(true, code, language));
                
                lastEnd = matcher.end();
            }
            
            // Текст после последнего кода
            if (lastEnd < content.length()) {
                String text = content.substring(lastEnd).trim();
                if (!text.isEmpty()) {
                    parts.add(new ContentPart(false, text, ""));
                }
            }
            
            // Если нет кода - весь контент как текст
            if (parts.isEmpty() && !content.isEmpty()) {
                parts.add(new ContentPart(false, content, ""));
            }
            
            return parts;
        }

        private String[] thinkingParagraphs(String thinking) {
            if (thinking == null || thinking.trim().isEmpty()) return new String[0];
            return thinking.trim().split("\\n\\s*\\n");
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
            boolean finished = message.isThinkingFinished();
            String[] paragraphs = thinkingParagraphs(thinking);
            int paragraphCount = finished ? -1 : paragraphs.length;
            String preview = finished
                    ? itemView.getContext().getString(R.string.chat_thinking_finished,
                            message.getThinkingTimeMs() / 1000f)
                    : paragraphCount == 0 ? ""
                            : thinkingParagraphPreview(paragraphs[paragraphCount - 1]);
            if (preview.isEmpty()) preview = itemView.getContext().getString(R.string.lay_thinking_process);
            if (thinkingWaveIcon != null) thinkingWaveIcon.setRunning(message.isStreaming() && !finished);
            if (finished) {
                ++thinkingPreviewAnimationGeneration;
                thinkingPreview.animate().cancel();
                displayedThinkingPreview = preview;
                displayedThinkingParagraphCount = -1;
                thinkingPreview.setAlpha(1f);
                thinkingPreview.setTranslationY(0f);
                thinkingPreview.setText(preview);
                return;
            }
            if (preview.equals(displayedThinkingPreview)) return;

            boolean sameParagraph = paragraphCount == displayedThinkingParagraphCount;
            displayedThinkingPreview = preview;
            displayedThinkingParagraphCount = paragraphCount;
            int generation = ++thinkingPreviewAnimationGeneration;
            thinkingPreview.animate().cancel();
            if (sameParagraph || thinkingPreview.getText().length() == 0) {
                thinkingPreview.setAlpha(1f);
                thinkingPreview.setTranslationY(0f);
                thinkingPreview.setText(preview);
                return;
            }
            String next = preview;
            float shift = itemView.getResources().getDisplayMetrics().density * 4f;
            thinkingPreview.animate().alpha(0f).translationY(-shift).setDuration(100)
                    .withEndAction(() -> {
                        if (generation != thinkingPreviewAnimationGeneration) return;
                        thinkingPreview.setText(next);
                        thinkingPreview.setTranslationY(shift);
                        thinkingPreview.animate().alpha(1f).translationY(0f)
                                .setDuration(150).start();
                    }).start();
        }

        // Обновляем ТОЛЬКО thinking (без content)
        void updateThinkingOnly(Message message) {
            if (viewType != Message.TYPE_AI) return;
            updateEmptyBubbleVisibility(message);

            // If we have ordered workflow segments, thinking is rendered as
            // part of updateToolCalls — skip the static thinkingContainer here
            if (message.getWorkflowSegments() != null && !message.getWorkflowSegments().isEmpty()) {
                return;
            }

            // Thinking блок (внутри workflowArea, под summary header)
            if (message.hasThinking() && thinkingContainer != null) {
                String thinkingText = stripThinkingTagTokens(message.getThinkingContent());
                if (isVisuallyBlank(thinkingText)) {
                    thinkingContainer.setVisibility(View.GONE);
                    if (textThinking != null) textThinking.setText("");
                    if (workflowArea != null && !message.hasToolCalls() && !showBoundWorkflowSummary) {
                        workflowArea.setVisibility(View.GONE);
                    }
                    return;
                }
                // Show workflowArea since it hosts the thinking container
                if (workflowArea != null) {
                    workflowArea.setVisibility(View.VISIBLE);
                }
                // While working, thinking is collapsed (timer handles visibility)
                if (message.isWorkingState()) {
                    thinkingContainer.setVisibility(View.GONE);
                    return;
                }
                // If summary header is shown, thinking visibility follows the
                // workflow expanded state. Otherwise thinking is standalone.
                boolean summaryVisible = showBoundWorkflowSummary;
                boolean expanded = !summaryVisible || message.isToolWorkflowExpanded();
                thinkingContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);

                if (!thinkingText.equals(textThinking.getText().toString())) {
                    textThinking.setText(thinkingText);
                }
                updateThinkingPreview(message, thinkingText);

                updateThinkingState(message.isThinkingExpanded());

                // Всегда перевешиваем listener на актуальный объект message
                thinkingHeader.setOnClickListener(v -> {
                    message.toggleThinking();
                    animateThinkingToggle(message.isThinkingExpanded());
                });
            } else if (thinkingContainer != null) {
                thinkingContainer.setVisibility(View.GONE);
            }
        }

        /**
         * Content that cannot live in a single TextView: code blocks, display or
         * inline LaTeX and Markdown tables all need MarkdownRenderer.renderToLayout,
         * which owns the app's own table/code-block widgets.
         */
        private static boolean needsLayoutRender(String content) {
            if (content == null || content.isEmpty()) return false;
            return content.contains("```")
                    || content.contains("$$")
                    || content.contains("\\[")
                    || app.multiai.mobile.utils.MarkdownRenderer.containsInlineLatex(content)
                    || app.multiai.mobile.utils.MarkdownRenderer.containsMarkdownTable(content);
        }

        private static String cleanContentForDisplay(String content) {
            if (content == null || content.isEmpty()) return "";
            return content
                    .replaceAll("(?is)<(?:think|thinking|reasoning)>.*?</(?:think|thinking|reasoning)>", "")
                    .replaceAll("(?is)</?(?:think|thinking|reasoning)>", "");
        }

        private static boolean isVisuallyBlank(String content) {
            return content == null || content.trim().isEmpty();
        }

        private static String stripThinkingTagTokens(String thinking) {
            if (thinking == null || thinking.isEmpty()) return thinking;
            return thinking.replaceAll("(?is)</?(?:think|thinking|reasoning)>", "");
        }

        private void updateEmptyBubbleVisibility(Message message) {
            if (bubbleContainer == null || message == null) return;
            List<app.multiai.mobile.models.WorkflowSegment> segments = message.getWorkflowSegments();
            if (segments != null && !segments.isEmpty()
                    && !message.isStandaloneContentVisible()) {
                bubbleContainer.setVisibility(View.GONE);
                return;
            }
            String content = cleanContentForDisplay(message.getContent());
            bubbleContainer.setVisibility(isVisuallyBlank(content) ? View.GONE : View.VISIBLE);
        }

        // Прямое обновление TextView без notifyItemChanged (для анимации).
        // Markdown не парсим до конца стрима: незакрытые ```/$/таблицы меняют
        // высоту сообщения на каждом токене и заставляют RecyclerView трястись.
        void updateContentTextDirect(Message message, String content) {
            if (viewType != Message.TYPE_AI) return;
            if (textMessage != null) {
                app.multiai.mobile.utils.Telemoji.clearTracking(textMessage);
            }
            List<app.multiai.mobile.models.WorkflowSegment> segments = message.getWorkflowSegments();
            if (segments != null && !segments.isEmpty()
                    && !message.isStandaloneContentVisible()) {
                if (bubbleContainer != null) bubbleContainer.setVisibility(View.GONE);
                renderWorkflowSegments(message);
                return;
            }

            String cleaned = cleanContentForDisplay(content);

            if (!isVisuallyBlank(cleaned)) {
                if (bubbleContainer != null) bubbleContainer.setVisibility(View.VISIBLE);
                if (textMessage != null) {
                    textMessage.setVisibility(View.VISIBLE);
                    // Выравнивание НЕ трогаем: явный setTextDirection(LTR)+
                    // setAlignment+setGravity из прошлой версии конфликтовали
                    // и на некоторых прошивках рендерили текст «вертикально»
                    // (по букве на строку). Layout уже задаёт нужное.
                    textMessage.setAlpha(1f);
                    textMessage.setText(cleaned);
                }

                if (codeBlocksContainer != null) {
                    codeBlocksContainer.setVisibility(View.GONE);
                }
            } else {
                updateEmptyBubbleVisibility(message);
                if (textMessage != null) {
                    textMessage.setText("");
                    textMessage.setVisibility(View.GONE);
                }
                if (codeBlocksContainer != null) {
                    codeBlocksContainer.removeAllViews();
                    codeBlocksContainer.setVisibility(View.GONE);
                }
            }
            updateActionBarVisibility(message);
        }
        
        /**
         * 🔥 УЛУЧШЕННЫЙ ИНКРЕМЕНТАЛЬНЫЙ MARKDOWN
         * Парсит markdown элементы по мере появления закрывающих тегов.
         * Таблицы и заголовки рендерятся сразу.
         */
        private CharSequence applyIncrementalMarkdown(String text) {
            SpannableStringBuilder builder = new SpannableStringBuilder(text);
            
            // 1. Блоки кода: ```язык\nкод\n``` (приоритет!)
            applyCodeBlocks(builder);
            
            // 2. Таблицы: | col1 | col2 | (рендерим сразу!)
            applyTables(builder);
            
            // 3. Заголовки: # ## ### (рендерим сразу!)
            applyHeaders(builder);
            
            // 4. Ссылки: [текст](url) (рендерим сразу!)
            applyLinks(builder);
            
            // 5. Жирный курсив: **_текст_** или __*текст*__
            applyIncrementalStyle(builder, "\\*\\*_(.+?)_\\*\\*", 
                () -> new StyleSpan(android.graphics.Typeface.BOLD_ITALIC));
            applyIncrementalStyle(builder, "__\\*(.+?)\\*__", 
                () -> new StyleSpan(android.graphics.Typeface.BOLD_ITALIC));
            
            // 6. Жирный текст: **текст** или __текст__
            applyIncrementalStyle(builder, "\\*\\*(.+?)\\*\\*", 
                () -> new StyleSpan(android.graphics.Typeface.BOLD));
            applyIncrementalStyle(builder, "__(.+?)__", 
                () -> new StyleSpan(android.graphics.Typeface.BOLD));
            
            // 7. Курсив: _текст_ или *текст*
            applyIncrementalStyle(builder, "_(.+?)_", 
                () -> new StyleSpan(android.graphics.Typeface.ITALIC));
            applyIncrementalStyle(builder, "(?<!^)(?<!\\n)\\*(.+?)\\*", 
                () -> new StyleSpan(android.graphics.Typeface.ITALIC));
            
            // 8. Зачеркнутый: ~~текст~~
            applyIncrementalStyle(builder, "~~(.+?)~~", 
                () -> new android.text.style.StrikethroughSpan());
            
            // 9. Строчный код: `код` (monospace + background)
            applyInlineCode(builder);

            // 10. Списки: - item или * item → • item
            applyBulletLists(builder);

            // 11. Blockquote: > text
            applyBlockquotes(builder);
            
            return builder;
        }
        
        /**
         * Применяет стиль к таблицам | col1 | col2 |
         */
        private void applyTables(SpannableStringBuilder builder) {
            // Простая таблица: строки с |
            Pattern pattern = Pattern.compile("^\\|(.+)\\|$", Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(builder.toString());
            
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                
                // Делаем моноширинным шрифтом + рамка
                builder.setSpan(new TypefaceSpan("monospace"), start, end,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new BackgroundColorSpan(color(codeBg)), start, end,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        
        /**
         * Применяет стиль к заголовкам # ## ###
         */
        private void applyHeaders(SpannableStringBuilder builder) {
            // # Заголовок 1
            Pattern h1 = Pattern.compile("^# (.+)$", Pattern.MULTILINE);
            applyHeaderStyle(builder, h1, 1.5f, android.graphics.Typeface.BOLD);
            
            // ## Заголовок 2
            Pattern h2 = Pattern.compile("^## (.+)$", Pattern.MULTILINE);
            applyHeaderStyle(builder, h2, 1.3f, android.graphics.Typeface.BOLD);
            
            // ### Заголовок 3
            Pattern h3 = Pattern.compile("^### (.+)$", Pattern.MULTILINE);
            applyHeaderStyle(builder, h3, 1.1f, android.graphics.Typeface.BOLD);
        }
        
        private void applyHeaderStyle(SpannableStringBuilder builder, Pattern pattern, float scale, int typeface) {
            Matcher matcher = pattern.matcher(builder.toString());
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                
                builder.setSpan(new RelativeSizeSpan(scale), start, end, 
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new StyleSpan(typeface), start, end, 
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        
        /**
         * Применяет стиль к ссылкам [текст](url)
         */
        private void applyLinks(SpannableStringBuilder builder) {
            Pattern pattern = Pattern.compile("\\[(.+?)\\]\\((.+?)\\)");
            Matcher matcher = pattern.matcher(builder.toString());
            
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                String text = matcher.group(1);
                String url = matcher.group(2);
                
                // Заменяем [текст](url) на просто текст
                builder.replace(start, end, text);
                
                // Добавляем цвет ссылки + подчеркивание
                builder.setSpan(new ForegroundColorSpan(color(accentColor)), start, start + text.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new UnderlineSpan(), start, start + text.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        
        /**
         * Применяет стиль к блокам кода ```...```
         */
        private void applyCodeBlocks(SpannableStringBuilder builder) {
            // 🔥 Regex для блоков кода: ```язык (опционально) + перенос + код + ```
            // Используем [\s\S]*? для захвата ЛЮБЫХ символов (включая переносы)
            Pattern pattern = Pattern.compile(
                "```(\\w*)([\\s\\S]*?)```", 
                Pattern.DOTALL
            );
            Matcher matcher = pattern.matcher(builder.toString());
            
            List<CodeBlockReplacement> replacements = new ArrayList<>();
            
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                String lang = matcher.group(1); // язык (может быть пустым)
                String code = matcher.group(2); // код (с переносами)
                
                // Убираем начальный и конечный перенос если есть
                code = code.trim();
                
                // Форматируем: [язык]\nкод
                String formatted = (lang != null && !lang.isEmpty() ? "[" + lang + "]\n" : "") + code;
                
                // Создаем span для моноширинного шрифта + фон кода
                TypefaceSpan monoSpan = new TypefaceSpan("monospace");
                BackgroundColorSpan bgSpan = new BackgroundColorSpan(color(codeBg));
                
                replacements.add(new CodeBlockReplacement(start, end, formatted, monoSpan, bgSpan));
            }
            
            // Применяем замены в обратном порядке
            for (int i = replacements.size() - 1; i >= 0; i--) {
                CodeBlockReplacement r = replacements.get(i);
                builder.replace(r.start, r.end, r.text);
                int spanEnd = r.start + r.text.length();
                builder.setSpan(r.monoSpan, r.start, spanEnd, 
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(r.bgSpan, r.start, spanEnd, 
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        
        /**
         * Inline code: `code` → monospace + dark background
         */
        private void applyInlineCode(SpannableStringBuilder builder) {
            Pattern pattern = Pattern.compile("`([^`]+?)`");
            Matcher matcher = pattern.matcher(builder.toString());
            List<MatchReplacement> replacements = new ArrayList<>();
            while (matcher.find()) {
                replacements.add(new MatchReplacement(matcher.start(), matcher.end(), matcher.group(1), null));
            }
            for (int i = replacements.size() - 1; i >= 0; i--) {
                MatchReplacement r = replacements.get(i);
                builder.replace(r.start, r.end, r.text);
                int spanEnd = r.start + r.text.length();
                builder.setSpan(new TypefaceSpan("monospace"), r.start, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new BackgroundColorSpan(color(codeBg)), r.start, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new ForegroundColorSpan(color(syntaxString)), r.start, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        /**
         * Bullet lists: "- item" or "* item" → "• item"
         */
        private void applyBulletLists(SpannableStringBuilder builder) {
            Pattern pattern = Pattern.compile("^[\\-\\*] (.+)$", Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(builder.toString());
            List<int[]> ranges = new ArrayList<>();
            while (matcher.find()) {
                ranges.add(new int[]{matcher.start(), matcher.end(), matcher.start() + 2});
            }
            for (int i = ranges.size() - 1; i >= 0; i--) {
                int[] r = ranges.get(i);
                builder.replace(r[0], r[0] + 2, "  •  ");
            }
        }

        /**
         * Blockquotes: "> text" → styled quote
         */
        private void applyBlockquotes(SpannableStringBuilder builder) {
            Pattern pattern = Pattern.compile("^> (.+)$", Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(builder.toString());
            List<int[]> ranges = new ArrayList<>();
            while (matcher.find()) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
            }
            for (int i = ranges.size() - 1; i >= 0; i--) {
                int[] r = ranges.get(i);
                // Replace "> " with "┃ "
                builder.replace(r[0], r[0] + 2, "┃ ");
                int end = r[0] + (r[1] - r[0]);
                if (end > builder.length()) end = builder.length();
                builder.setSpan(new ForegroundColorSpan(color(textSecondary)), r[0], end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new StyleSpan(Typeface.ITALIC), r[0], end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        /**
         * Применяет стиль к ЗАКРЫТЫМ парам тегов
         */
        private void applyIncrementalStyle(SpannableStringBuilder builder, String regex, 
                                          StyleSpanFactory factory) {
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(builder.toString());
            
            // Список замен (чтобы не ломать индексы во время итерации)
            List<MatchReplacement> replacements = new ArrayList<>();
            
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                String innerText = matcher.group(1);
                
                replacements.add(new MatchReplacement(start, end, innerText, factory.create()));
            }
            
            // Применяем замены в обратном порядке (чтобы не сбивать индексы)
            for (int i = replacements.size() - 1; i >= 0; i--) {
                MatchReplacement r = replacements.get(i);
                builder.replace(r.start, r.end, r.text);
                builder.setSpan(r.span, r.start, r.start + r.text.length(), 
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        
        // Вспомогательные классы
        private interface StyleSpanFactory {
            Object create();
        }
        
        private static class MatchReplacement {
            int start, end;
            String text;
            Object span;
            
            MatchReplacement(int start, int end, String text, Object span) {
                this.start = start;
                this.end = end;
                this.text = text;
                this.span = span;
            }
        }
        
        private static class CodeBlockReplacement {
            int start, end;
            String text;
            TypefaceSpan monoSpan;
            BackgroundColorSpan bgSpan;
            
            CodeBlockReplacement(int start, int end, String text, 
                               TypefaceSpan monoSpan,
                               BackgroundColorSpan bgSpan) {
                this.start = start;
                this.end = end;
                this.text = text;
                this.monoSpan = monoSpan;
                this.bgSpan = bgSpan;
            }
        }

        // Прямое обновление thinking без повторного bind. Пока шторка свёрнута,
        // не трогаем TextView на каждом токене: это лишние layout-проходы и
        // главный источник дёрганья длинного ответа.
        void updateThinkingTextDirect(Message message) {
            if (viewType != Message.TYPE_AI || textThinking == null) return;
            if (message.getWorkflowSegments() != null && !message.getWorkflowSegments().isEmpty()) {
                if (thinkingContainer != null) thinkingContainer.setVisibility(View.GONE);
                renderWorkflowSegments(message);
                return;
            }
            updateEmptyBubbleVisibility(message);
            String thinking = message.getThinkingContent();
            if (thinking == null) thinking = "";

            if (thinkingContainer != null && !thinking.isEmpty()) {
                thinkingContainer.setVisibility(View.VISIBLE);
            }

            thinking = stripThinkingTagTokens(thinking).trim();
            if (thinking.isEmpty()) return;
            updateThinkingPreview(message, thinking);

            if (thinkingHeader != null) {
                thinkingHeader.setOnClickListener(v -> {
                    message.toggleThinking();
                    if (message.isThinkingExpanded()) {
                        String full = message.getThinkingContent();
                        textThinking.setText(full == null ? "" : full
                                .replace("<think>", "").replace("</think>", "").trim());
                    }
                    animateThinkingToggle(message.isThinkingExpanded());
                });
            }

            if (message.isThinkingExpanded()) {
                if (!thinking.contentEquals(textThinking.getText())) {
                    textThinking.setText(thinking);
                }
                textThinking.setVisibility(View.VISIBLE);
            } else {
                textThinking.setVisibility(View.GONE);
            }
            updateThinkingState(message.isThinkingExpanded());
        }

        private android.text.Spanned formatMarkdown(String text) {
            if (text == null) return new android.text.SpannableString("");
            
            android.text.SpannableStringBuilder builder = new android.text.SpannableStringBuilder(text);
            
            // 1. **жирный текст**
            applyPattern(builder, "\\*\\*(.+?)\\*\\*", new SpanApplier() {
                @Override
                public void apply(android.text.SpannableStringBuilder b, int start, int end) {
                    b.setSpan(
                        new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            });
            
            // 2. *курсив*
            applyPattern(builder, "\\*(.+?)\\*", new SpanApplier() {
                @Override
                public void apply(android.text.SpannableStringBuilder b, int start, int end) {
                    b.setSpan(
                        new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            });
            
            // 3. __подчёркнутый__
            applyPattern(builder, "__(.+?)__", new SpanApplier() {
                @Override
                public void apply(android.text.SpannableStringBuilder b, int start, int end) {
                    b.setSpan(
                        new android.text.style.UnderlineSpan(),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            });
            
            // 4. ~~зачёркнутый~~
            applyPattern(builder, "~~(.+?)~~", new SpanApplier() {
                @Override
                public void apply(android.text.SpannableStringBuilder b, int start, int end) {
                    b.setSpan(
                        new android.text.style.StrikethroughSpan(),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            });
            
            // 5. `код`
            applyPattern(builder, "`(.+?)`", new SpanApplier() {
                @Override
                public void apply(android.text.SpannableStringBuilder b, int start, int end) {
                    b.setSpan(
                        new android.text.style.TypefaceSpan("monospace"),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    b.setSpan(
                        new android.text.style.BackgroundColorSpan(color(codeBg)),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    b.setSpan(
                        new android.text.style.ForegroundColorSpan(color(syntaxString)),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            });
            
            // 6. Дроби с кастомным символом ⁄ (U+2044)
            // Находим паттерн: цифра⁄цифра и делаем красивым
            String currentText = builder.toString();
            java.util.regex.Pattern fractionPattern = java.util.regex.Pattern.compile("(\\d+)⁄(\\d+)");
            java.util.regex.Matcher fractionMatcher = fractionPattern.matcher(currentText);
            
            while (fractionMatcher.find()) {
                int start = fractionMatcher.start();
                int end = fractionMatcher.end();
                
                // Делаем дробь цветной и чуть больше
                builder.setSpan(
                    new android.text.style.ForegroundColorSpan(color(textPrimary)),
                    start, end,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                builder.setSpan(
                    new android.text.style.RelativeSizeSpan(1.1f),
                    start, end,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
            
            return builder;
        }
        
        // Интерфейс для применения span
        private interface SpanApplier {
            void apply(android.text.SpannableStringBuilder builder, int start, int end);
        }
        
        // Применяет regex pattern и заменяет на span
        private void applyPattern(android.text.SpannableStringBuilder builder, String regex, SpanApplier applier) {
            String text = builder.toString();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
            java.util.regex.Matcher matcher = pattern.matcher(text);
            
            int offset = 0;
            while (matcher.find()) {
                int start = matcher.start() - offset;
                int end = matcher.end() - offset;
                String content = matcher.group(1);
                
                // Вычисляем длину маркеров (**, *, __, ~~, `)
                int markerLen = (matcher.group(0).length() - content.length()) / 2;
                
                // Заменяем маркированный текст на чистый
                builder.replace(start, end, content);
                
                // Применяем span
                applier.apply(builder, start, start + content.length());
                
                offset += markerLen * 2; // Убрали маркеры
            }
        }

        private void updateThinkingState(boolean expanded) {
            if (textThinking == null || thinkingToggle == null) return;

            if (expanded) {
                textThinking.setVisibility(View.VISIBLE);
                thinkingToggle.setImageResource(R.drawable.ic_chevron_down);
                thinkingToggle.setRotation(0f);
            } else {
                textThinking.setVisibility(View.GONE);
                thinkingToggle.setImageResource(R.drawable.ic_chevron_right);
                thinkingToggle.setRotation(0f);
            }
        }

        private void animateThinkingToggle(boolean expand) {
            if (textThinking == null || thinkingToggle == null) return;

            // Swap icon with crossfade
            thinkingToggle.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> {
                    thinkingToggle.setImageResource(expand ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right);
                    thinkingToggle.setRotation(0f);
                    thinkingToggle.animate().alpha(1f).setDuration(150).start();
                })
                .start();

            // Плавное появление/скрытие контента
            if (expand) {
                textThinking.setVisibility(View.VISIBLE);
                textThinking.setAlpha(0f);
                textThinking.setTranslationY(-20f);
                textThinking.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
            } else {
                textThinking.animate()
                    .alpha(0f)
                    .translationY(-20f)
                    .setDuration(300)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> textThinking.setVisibility(View.GONE))
                    .start();
            }
        }

        private void updateActionBarVisibility(Message message) {
            if (actionBar == null || message == null) return;
            String content = cleanContentForDisplay(message.getContent());
            RecyclerView.Adapter<?> adapter = getBindingAdapter();
            MessagesAdapter messagesAdapter = adapter instanceof MessagesAdapter
                    ? (MessagesAdapter) adapter : null;
            int index = messagesAdapter != null
                    ? messagesAdapter.messages.indexOf(message) : -1;
            Message next = index >= 0 && index + 1 < messagesAdapter.messages.size()
                    ? messagesAdapter.messages.get(index + 1) : null;
            boolean nextIsToolCard = next != null
                    && next.getType() == Message.TYPE_TOOL_EXECUTION;
            boolean nextIsAssistantSegment = next != null
                    && next.getType() == Message.TYPE_AI;
            List<app.multiai.mobile.models.WorkflowSegment> workflowSegments =
                    message.getWorkflowSegments();
            boolean unifiedWorkflow = workflowSegments != null && !workflowSegments.isEmpty();
            boolean visible = message.getType() == Message.TYPE_AI
                    && !message.isStreaming()
                    && !isVisuallyBlank(content)
                    && (!message.hasToolCalls() || unifiedWorkflow)
                    && !nextIsToolCard
                    && !nextIsAssistantSegment;
            actionBar.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) bindActionBar(message);
        }

        private void bindActionBar(Message message) {
            RecyclerView.Adapter<?> adapter = getBindingAdapter();
            MessagesAdapter messagesAdapter = adapter instanceof MessagesAdapter ? (MessagesAdapter) adapter : null;
            MessageActionListener listener = messagesAdapter != null ? messagesAdapter.actionListener : null;

            // Stable like/disliked state keyed by message identity (survives recycle).
            liked = messagesAdapter != null && messagesAdapter.isLiked(message);
            disliked = messagesAdapter != null && messagesAdapter.isDisliked(message);

            int inactive = color(actionInactive);
            if (btnCopy != null) btnCopy.setColorFilter(inactive);
            if (btnTts != null) btnTts.setColorFilter(inactive);
            if (btnMore != null) btnMore.setColorFilter(inactive);
            if (btnLike != null) btnLike.setColorFilter(liked ? color(accentColor) : inactive);
            if (btnDislike != null) btnDislike.setColorFilter(disliked ? color(errorColor) : inactive);

            if (btnCopy != null) btnCopy.setOnClickListener(v -> {
                tapFeedback(btnCopy);
                if (listener != null) listener.onCopy(message);
                // Фидбек «скопировано»: галочка акцентом на ~1.2с, затем откат.
                btnCopy.setImageResource(R.drawable.ic_check);
                btnCopy.setColorFilter(color(accentColor));
                btnCopy.postDelayed(() -> {
                    btnCopy.setImageResource(R.drawable.ic_copy);
                    btnCopy.setColorFilter(inactive);
                }, 1200);
            });
            if (btnLike != null) btnLike.setOnClickListener(v -> {
                boolean nowLiked = messagesAdapter != null && messagesAdapter.toggleLike(message);
                if (btnLike != null) btnLike.setColorFilter(nowLiked ? color(accentColor) : inactive);
                if (btnDislike != null) btnDislike.setColorFilter(inactive);
                tapFeedback(btnLike);
                if (listener != null) listener.onLike(message);
            });
            if (btnDislike != null) btnDislike.setOnClickListener(v -> {
                boolean nowDisliked = messagesAdapter != null && messagesAdapter.toggleDislike(message);
                if (btnDislike != null) btnDislike.setColorFilter(nowDisliked ? color(errorColor) : inactive);
                if (btnLike != null) btnLike.setColorFilter(inactive);
                tapFeedback(btnDislike);
                if (listener != null) listener.onDislike(message);
            });
            if (btnTts != null) btnTts.setOnClickListener(v -> {
                tapFeedback(btnTts);
                if (listener != null) listener.onTts(message);
            });
            if (btnMore != null) btnMore.setOnClickListener(v -> {
                tapFeedback(btnMore);
                if (listener != null) listener.onMore(message, v);
            });
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

        private void showUserContextMenu(Message message) {
            android.content.Context ctx = itemView.getContext();

            // Bottom sheet style popup. Работа с проектом идёт от уже
            // отправленного запроса, поэтому «Редактировать текст» здесь убрано:
            // правка сообщения после выполненных тулов рассинхронизирует ход.
            java.util.List<ModernSheet.MenuItem> items = java.util.Arrays.asList(
                new ModernSheet.MenuItem(ctx.getString(R.string.kit_msg_action_copy), R.drawable.ic_copy, false, null),
                new ModernSheet.MenuItem(ctx.getString(R.string.kit_msg_action_select_text), R.drawable.ic_select_text, false, null)
            );

            ModernSheet.menu(ctx, null, items, (idx, item) -> {
                switch (idx) {
                    case 0:
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        if (cm != null && message.getContent() != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", message.getContent()));
                            ModernToast.show((Activity) ctx, ctx.getString(R.string.kit_msg_copied));
                        }
                        break;
                    case 1:
                        if (textMessage != null) {
                            textMessage.setTextIsSelectable(true);
                            textMessage.requestFocus();
                        }
                        break;
                }
            });
        }

        private int dp(int value) {
            float density = itemView.getResources().getDisplayMetrics().density;
            return Math.round(value * density);
        }
    }

    /** Decode a downsized thumbnail from the message's base64 or file path.
     *  Must be called off the UI thread. */
    private static android.graphics.Bitmap decodeImageThumbnail(Message message, int maxDim) {
        try {
            String base64 = message.getImageBase64();
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
