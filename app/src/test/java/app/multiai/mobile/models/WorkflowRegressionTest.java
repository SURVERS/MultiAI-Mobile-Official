package app.multiai.mobile.models;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkflowRegressionTest {
    @Test public void readSkillIsSupported() {
        assertTrue(ToolCall.isSupportedToolName("read_skill"));
        assertFalse(ToolCall.isSupportedToolName("not_a_tool"));
    }

    @Test public void stoppingEndsWorkingAndStreaming() {
        Message message = new Message("", Message.TYPE_AI);
        message.setWorkingState(true);
        message.setStreaming(true);
        message.setWorkflowStopped(true);
        assertFalse(message.isWorkingState());
        assertFalse(message.isStreaming());
        assertTrue(message.isWorkflowStopped());
        assertTrue(message.isToolWorkflowSummary());
        message.setWorkingState(true);
        assertFalse(message.isWorkflowStopped());
    }

    @Test public void modelNamesKeepAReadableVersionSeparator() {
        assertEquals("GLM 5.2", ModelInfo.cleanDisplayName("GLM-5.2"));
        assertEquals("GLM 5.2", ModelInfo.cleanDisplayName("GLM5.2"));
        assertEquals("GPT 6 Astra", ModelInfo.cleanDisplayName("OpenAI: GPT-6 Astra"));
        assertEquals("Qwen 3.6 Max Preview", ModelInfo.cleanDisplayName("Qwen: Qwen3.6 Max Preview"));
    }
}
