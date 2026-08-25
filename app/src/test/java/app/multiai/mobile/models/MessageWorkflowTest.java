package app.multiai.mobile.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Unit-тесты чистой логики ремонта Work Mode UI:
 * политика раскрытия workflow-карточки и presentation fingerprint.
 *
 * <p>Проверяемые инварианты:
 * <ul>
 *   <li>Workflow автоматически открыт, пока идёт работа или ход завершён
 *       с summary — если пользователь явно не свернул карточку.</li>
 *   <li>Ручное состояние (workflowCollapsedManually=true) переживает
 *       ререндеры: авто-раскрытие отключено до нового ручного открытия.</li>
 *   <li>Fingerprint меняется при изменении status/result/expanded tool calls
 *       и сегментов, но стабилен при повторном чтении без изменений.</li>
 *   <li>Старые чаты (все transient-флаги false после Gson) получают
 *       авто-раскрытие как раньше.</li>
 * </ul>
 */
public class MessageWorkflowTest {

    private static ToolCall toolCall(String name, String status) {
        ToolCall call = new ToolCall("id-" + name, name, new HashMap<>());
        call.setStatus(status);
        return call;
    }

    // ===== computeWorkflowExpanded: auto-open policy =====

    @Test
    public void workflowAutoOpensWhileWorking() {
        Message msg = new Message("", Message.TYPE_AI);
        msg.setWorkingState(true);
        assertTrue("working state -> auto-open", msg.computeWorkflowExpanded(false));
        assertTrue(msg.isToolWorkflowExpanded());
    }

    @Test
    public void workflowAutoOpensAfterCompletionWithSummary() {
        Message msg = new Message("done", Message.TYPE_AI);
        msg.setToolWorkflowSummary(true);
        msg.setWorkingState(false);
        assertTrue("completed summary -> auto-open", msg.computeWorkflowExpanded(true));
        assertTrue(msg.isToolWorkflowExpanded());
    }

    @Test
    public void manualCollapseSurvivesWorkingRerenders() {
        Message msg = new Message("", Message.TYPE_AI);
        msg.setWorkingState(true);
        assertTrue(msg.computeWorkflowExpanded(false));

        // Пользователь свернул вручную во время работы
        msg.toggleToolWorkflowExpanded(); // true -> false
        msg.setWorkflowCollapsedManually(true);

        assertFalse("manual collapse sticks during work",
                msg.computeWorkflowExpanded(false));
        // ...и после ряда "ререндеров" (повторные вызовы) остаётся свёрнутым
        assertFalse(msg.computeWorkflowExpanded(false));
        assertFalse(msg.computeWorkflowExpanded(false));
    }

    @Test
    public void manualCollapseSurvivesCompletionRerenders() {
        Message msg = new Message("done", Message.TYPE_AI);
        msg.setToolWorkflowSummary(true);
        msg.setToolWorkflowExpanded(true);

        msg.toggleToolWorkflowExpanded(); // collapse
        msg.setWorkflowCollapsedManually(true);

        assertFalse("completed summary does not force-open a manually collapsed card",
                msg.computeWorkflowExpanded(true));
        assertFalse(msg.computeWorkflowExpanded(true));
    }

    @Test
    public void manualReopenRestoresAutoBehaviour() {
        Message msg = new Message("", Message.TYPE_AI);
        msg.setWorkingState(true);
        msg.computeWorkflowExpanded(false);
        msg.toggleToolWorkflowExpanded(); // collapse
        msg.setWorkflowCollapsedManually(true);
        assertFalse(msg.computeWorkflowExpanded(false));

        // Пользователь передумал и раскрыл снова
        msg.toggleToolWorkflowExpanded(); // open
        msg.setWorkflowCollapsedManually(false);
        assertTrue(msg.computeWorkflowExpanded(false));
    }

    @Test
    public void oldRestoredChatDefaultsToAutoOpen() {
        // Gson-десериализация: все transient-поля false
        Message msg = new Message("old", Message.TYPE_AI);
        msg.setToolWorkflowSummary(true); // persisted non-transient flag
        assertFalse(msg.isToolWorkflowExpanded());
        assertFalse(msg.isWorkflowCollapsedManually());
        assertTrue("restored chat without manual state -> auto-open",
                msg.computeWorkflowExpanded(true));
    }

    @Test
    public void noWorkflowCardBehavesAsBefore() {
        Message msg = new Message("plain text", Message.TYPE_AI);
        msg.setWorkingState(false);
        msg.setToolWorkflowSummary(false);
        // Без карточки expanded=true — старое поведение "всё видно"
        assertTrue(msg.computeWorkflowExpanded(false));
    }

    // ===== presentationFingerprint =====

    @Test
    public void fingerprintStableAcrossRepeatedCalls() {
        Message msg = new Message("hello", Message.TYPE_AI);
        msg.setThinkingContent("hmm");
        msg.addWorkflowToolCall(toolCall("web_search", "loading"));
        assertEquals(msg.presentationFingerprint(), msg.presentationFingerprint());
    }

    @Test
    public void fingerprintChangesWithToolStatus() {
        Message msg = new Message("a", Message.TYPE_AI);
        ToolCall call = toolCall("web_search", "loading");
        msg.addToolCall(call);
        long before = msg.presentationFingerprint();

        call.setStatus("success");
        assertNotEquals("status change must be visible in fingerprint",
                before, msg.presentationFingerprint());
    }

    @Test
    public void fingerprintChangesWithToolResult() throws Exception {
        Message msg = new Message("a", Message.TYPE_AI);
        ToolCall call = toolCall("web_search", "success");
        msg.addToolCall(call);
        long before = msg.presentationFingerprint();

        call.setResult(new JSONObject("{\"success\":true,\"results\":[]}"));
        assertNotEquals("result change must be visible in fingerprint",
                before, msg.presentationFingerprint());
    }

    @Test
    public void fingerprintChangesWithExpandedFlags() {
        Message msg = new Message("a", Message.TYPE_AI);
        ToolCall call = toolCall("web_search", "success");
        msg.addToolCall(call);
        long before = msg.presentationFingerprint();

        call.setExpanded(true);
        assertNotEquals(before, msg.presentationFingerprint());
    }

    @Test
    public void fingerprintChangesWithSegmentThinkingGrowth() {
        Message msg = new Message("a", Message.TYPE_AI);
        msg.appendWorkflowThinking("abc");
        long before = msg.presentationFingerprint();

        msg.appendWorkflowThinking("def");
        assertNotEquals("thinking growth must be visible in fingerprint",
                before, msg.presentationFingerprint());
    }

    @Test
    public void fingerprintChangesWithWorkflowCardState() {
        Message msg = new Message("a", Message.TYPE_AI);
        msg.setWorkingState(true);
        long working = msg.presentationFingerprint();

        msg.setWorkingState(false);
        msg.setToolWorkflowSummary(true);
        assertNotEquals("working -> completed transition must be visible",
                working, msg.presentationFingerprint());
    }

    @Test
    public void fingerprintIgnoresThinkTagsInsideContent() {
        Message msg = new Message("before <think>secret</think> after", Message.TYPE_AI);
        long a = msg.presentationFingerprint();
        msg.setContent("<think>secret</think> before  after");
        assertEquals("thinking tag content is not user-visible",
                a, msg.presentationFingerprint());
    }

    // ===== workflow segment helpers on Message =====

    @Test
    public void segmentsGrowInAlternatingOrder() {
        Message msg = new Message("", Message.TYPE_AI);
        msg.appendWorkflowThinking("step 1");
        msg.addWorkflowToolCall(toolCall("web_search", "loading"));
        msg.appendWorkflowThinking("step 2");

        java.util.List<WorkflowSegment> segments = msg.getWorkflowSegments();
        assertEquals(3, segments.size());
        assertEquals(WorkflowSegment.TYPE_THINKING, segments.get(0).getType());
        assertEquals(WorkflowSegment.TYPE_TOOL_CALLS, segments.get(1).getType());
        assertEquals(WorkflowSegment.TYPE_THINKING, segments.get(2).getType());
        assertTrue(msg.hasWorkflowThinking());
        assertTrue(msg.hasWorkflowToolCalls());
    }

    @Test
    public void movingStandaloneContentUsesSingleSourceOfTruth() {
        Message msg = new Message("before tool", Message.TYPE_AI);
        assertTrue(msg.moveStandaloneContentToWorkflow());
        assertEquals("", msg.getContentSafe());
        assertFalse(msg.isStandaloneContentVisible());
        assertEquals("before tool", msg.getWorkflowSegments().get(0).getContentText());
    }

    @Test
    public void finalContentSurvivesSerializationAfterWorkflow() {
        Message msg = new Message("before tool", Message.TYPE_AI);
        msg.moveStandaloneContentToWorkflow();
        msg.addWorkflowToolCall(toolCall("read_file", "success"));
        msg.setContent("final answer");
        msg.setStandaloneContentVisible(true);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        Message restored = gson.fromJson(gson.toJson(msg), Message.class);

        assertEquals("final answer", restored.getContentSafe());
        assertTrue(restored.isStandaloneContentVisible());
        assertEquals(2, restored.getWorkflowSegments().size());
    }

    @Test
    public void restoredHiddenFinalContentIsRepaired() {
        Message msg = new Message("final answer", Message.TYPE_AI);
        msg.appendWorkflowContent("before tool");
        msg.setStandaloneContentVisible(false);

        assertTrue(msg.normalizeWorkflowContentState());
        assertEquals("final answer", msg.getContentSafe());
        assertTrue(msg.isStandaloneContentVisible());
    }

    @Test
    public void restoredDuplicatedContentKeepsFinalCopyOnly() {
        Message msg = new Message("before tool", Message.TYPE_AI);
        msg.appendWorkflowContent("before tool");

        assertTrue(msg.normalizeWorkflowContentState());
        assertEquals("before tool", msg.getContentSafe());
        assertTrue(msg.isStandaloneContentVisible());
        assertTrue(msg.getWorkflowSegments().isEmpty());
    }
}
