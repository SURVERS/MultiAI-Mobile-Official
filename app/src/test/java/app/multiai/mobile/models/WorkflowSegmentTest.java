package app.multiai.mobile.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.junit.Test;

/**
 * Unit-тесты {@link WorkflowSegment}: transient-состояние раскрытия thinking
 * (переживает ререндеры), видимый текст без тегов, признак контента.
 */
public class WorkflowSegmentTest {

    private static WorkflowSegment thinking(String text) {
        WorkflowSegment seg = new WorkflowSegment(WorkflowSegment.TYPE_THINKING);
        seg.setThinkingText(text);
        return seg;
    }

    @Test
    public void thinkingExpandedDefaultsToFalse() {
        assertFalse(new WorkflowSegment(WorkflowSegment.TYPE_THINKING).isThinkingExpanded());
    }

    @Test
    public void thinkingExpandedToggleSurvivesSimulatedRerenders() {
        WorkflowSegment seg = thinking("рассуждаю...");
        seg.toggleThinkingExpanded();
        assertTrue(seg.isThinkingExpanded());
        // "Ререндер" — состояние читается повторно и не сбрасывается
        assertTrue(seg.isThinkingExpanded());
        assertTrue(seg.isThinkingExpanded());
        seg.toggleThinkingExpanded();
        assertFalse(seg.isThinkingExpanded());
    }

    @Test
    public void setThinkingExpandedExplicit() {
        WorkflowSegment seg = thinking("x");
        seg.setThinkingExpanded(false);
        assertFalse(seg.isThinkingExpanded());
        seg.setThinkingExpanded(true);
        assertTrue(seg.isThinkingExpanded());
    }

    @Test
    public void visibleThinkingStripsTagTokens() {
        WorkflowSegment seg = thinking("<think>раз</think>два</think>");
        assertEquals("раздва", seg.getVisibleThinkingText());
    }

    @Test
    public void visibleThinkingEmptyForBlankOrTagOnlyText() {
        assertEquals("", thinking(null).getVisibleThinkingText());
        assertEquals("", thinking("   ").getVisibleThinkingText());
        assertEquals("", thinking("<think></think>").getVisibleThinkingText());
    }

    @Test
    public void appendThinkingAccumulates() {
        WorkflowSegment seg = new WorkflowSegment(WorkflowSegment.TYPE_THINKING);
        seg.appendThinking("abc");
        seg.appendThinking("def");
        assertEquals("abcdef", seg.getThinkingText());
        seg.appendThinking(null); // no-op
        assertEquals("abcdef", seg.getThinkingText());
    }

    @Test
    public void hasVisibleContentForThinkingAndToolSegments() {
        assertTrue(thinking("text").hasVisibleContent());
        assertFalse(thinking("  ").hasVisibleContent());

        WorkflowSegment tool = new WorkflowSegment(WorkflowSegment.TYPE_TOOL_CALLS);
        assertFalse(tool.hasVisibleContent());
        tool.addToolCall(new ToolCall("1", "web_search", new HashMap<>()));
        assertTrue(tool.hasVisibleContent());
        tool.addToolCall(null); // no-op
        assertEquals(1, tool.getToolCalls().size());
    }

    @Test
    public void contentSegmentAccumulatesAndReportsVisibility() {
        WorkflowSegment content = new WorkflowSegment(WorkflowSegment.TYPE_CONTENT);
        assertFalse(content.hasVisibleContent());
        content.appendContent("Привет");
        content.appendContent(" мир");
        assertEquals("Привет мир", content.getContentText());
        assertTrue(content.hasVisibleContent());
    }

    @Test
    public void toolSegmentConstructorInitsCallList() {
        WorkflowSegment tool = new WorkflowSegment(WorkflowSegment.TYPE_TOOL_CALLS);
        assertEquals(0, tool.getToolCalls().size());
    }
}
