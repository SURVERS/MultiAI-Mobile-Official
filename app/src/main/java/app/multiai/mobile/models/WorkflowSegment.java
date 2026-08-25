package app.multiai.mobile.models;

import java.util.ArrayList;
import java.util.List;

/**
 * Один сегмент workflow: thinking, группа tool calls или AI content.
 * Сегменты упорядочены по времени прихода и полностью описывают UI хода.
 */
public class WorkflowSegment {
    public static final int TYPE_THINKING = 0;
    public static final int TYPE_TOOL_CALLS = 1;
    public static final int TYPE_CONTENT = 2;

    private final int type;
    /** Для TYPE_THINKING — накопленный текст размышлений. */
    private String thinkingText;
    /** Для TYPE_CONTENT — видимый текст ответа этого model round. */
    private String contentText;
    /** Для TYPE_TOOL_CALLS — вызовы инструментов в этом сегменте. */
    private List<ToolCall> toolCalls;
    /** Сколько секунд занял сегмент (для отображения). */
    private long durationMs;
    /**
     * UI-состояние раскрытия thinking-сегмента. transient: в JSON чата не
     * пишется, но переживает пере-рендер сегментов в рамках сессии —
     * свёрнутый пользователем thinking не раскрывается на каждом токене.
     */
    private transient boolean thinkingExpanded = false;

    public WorkflowSegment(int type) {
        this.type = type;
        if (type == TYPE_TOOL_CALLS) {
            this.toolCalls = new ArrayList<>();
        }
    }

    public int getType() { return type; }
    public String getThinkingText() { return thinkingText; }
    public void setThinkingText(String text) { this.thinkingText = text; }
    public void appendThinking(String text) {
        if (text == null) return;
        if (thinkingText == null) thinkingText = text;
        else thinkingText += text;
    }
    public String getContentText() { return contentText; }
    public void setContentText(String text) { this.contentText = text; }
    public void appendContent(String text) {
        if (text == null) return;
        if (contentText == null) contentText = text;
        else contentText += text;
    }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void addToolCall(ToolCall call) {
        if (call == null) return;
        if (toolCalls == null) toolCalls = new ArrayList<>();
        toolCalls.add(call);
    }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long ms) { this.durationMs = ms; }

    public boolean isThinkingExpanded() { return thinkingExpanded; }
    public void setThinkingExpanded(boolean expanded) { this.thinkingExpanded = expanded; }
    public void toggleThinkingExpanded() { this.thinkingExpanded = !this.thinkingExpanded; }

    /**
     * Видимый thinking-контент без тегов размышлений и пустых фрагментов —
     * чистая логика для дедупликации рендера и unit-тестов.
     */
    public String getVisibleThinkingText() {
        if (thinkingText == null) return "";
        String cleaned = thinkingText.replaceAll("(?is)</?(?:think|thinking|reasoning)>", "");
        return cleaned.trim().isEmpty() ? "" : cleaned;
    }

    /** Есть ли у сегмента отображаемое содержимое. */
    public boolean hasVisibleContent() {
        if (type == TYPE_THINKING) return !getVisibleThinkingText().isEmpty();
        if (type == TYPE_CONTENT) return contentText != null && !contentText.trim().isEmpty();
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
