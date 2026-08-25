package app.multiai.mobile.models;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Модель чата с историей сообщений
 */
public class Chat {
    private String id;
    private String title;
    private List<Message> messages;
    private long createdAt;
    private long updatedAt;
    private boolean archived;
    private boolean pinned;
    /** Титул ещё не задавали (ни AI, ни юзером) — можно генерировать. */
    private boolean titleAuto = true;
    /** Модель чата (пер-чат выбор; null → использовать глобальную). */
    private String modelId;
    /** Reasoning effort выбранной модели чата (none/low/medium/high/max; null → дефолт модели). */
    private String modelEffort;
    /** Скрытая AI-сводка старой части диалога для авто-суммаризации контекста. */
    private String contextSummary;
    /** Сколько первых сообщений покрывает {@link #contextSummary}; UI-история не удаляется. */
    private int contextSummaryMessageCount;

    public Chat() {
        this.id = UUID.randomUUID().toString();
        this.title = "Новый чат";
        this.messages = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public Chat(String id, String title, List<Message> messages, long createdAt, long updatedAt) {
        this.id = id;
        this.title = title;
        this.messages = messages != null ? messages : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.updatedAt = System.currentTimeMillis();
        // Явная установка титула (юзером или AI) закрывает автогенерацию.
        this.titleAuto = false;
    }

    public boolean isTitleAuto() {
        return titleAuto;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getModelEffort() {
        return modelEffort;
    }

    public void setModelEffort(String modelEffort) {
        this.modelEffort = modelEffort;
    }

    /**
     * Возвращает скрытую сводку старой части истории. Она не является отдельным
     * UI-сообщением и нужна только при подготовке контекста для модели.
     */
    public String getContextSummary() {
        return contextSummary;
    }

    public void setContextSummary(String contextSummary) {
        this.contextSummary = contextSummary;
        this.updatedAt = System.currentTimeMillis();
    }

    public int getContextSummaryMessageCount() {
        return Math.max(0, contextSummaryMessageCount);
    }

    public void setContextSummaryMessageCount(int contextSummaryMessageCount) {
        this.contextSummaryMessageCount = Math.max(0, contextSummaryMessageCount);
        this.updatedAt = System.currentTimeMillis();
    }

    public void clearContextSummary() {
        this.contextSummary = null;
        this.contextSummaryMessageCount = 0;
        this.updatedAt = System.currentTimeMillis();
    }

    public List<Message> getMessages() {
        if (messages == null) messages = new ArrayList<>();
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
        this.updatedAt = System.currentTimeMillis();
    }

    public void addMessage(Message message) {
        this.messages.add(message);
        this.updatedAt = System.currentTimeMillis();
    }

    public boolean removeMessage(Message message) {
        boolean removed = this.messages.remove(message);
        if (removed) {
            this.updatedAt = System.currentTimeMillis();
        }
        return removed;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
        this.updatedAt = System.currentTimeMillis();
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Получить превью последнего сообщения
     */
    public String getLastMessagePreview() {
        if (messages.isEmpty()) {
            return "Нет сообщений";
        }
        Message lastMsg = messages.get(messages.size() - 1);
        String content = lastMsg.getContent();
        if (content == null || content.trim().isEmpty()) {
            return lastMsg.hasToolCalls() ? "[Инструменты]" : "...";
        }
        if (content.length() > 50) {
            return content.substring(0, 50) + "...";
        }
        return content;
    }

    /**
     * Обновить заголовок из первого сообщения пользователя
     */
    public void updateTitleFromFirstMessage() {
        for (Message msg : messages) {
            if (msg.getType() == Message.TYPE_USER && msg.getContent() != null && !msg.getContent().isEmpty()) {
                String content = msg.getContent();
                if (content.length() > 30) {
                    this.title = content.substring(0, 30) + "...";
                } else {
                    this.title = content;
                }
                this.updatedAt = System.currentTimeMillis();
                break;
            }
        }
    }
}
