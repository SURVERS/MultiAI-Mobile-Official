package app.multiai.mobile.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM unit tests for the pure cancellation surface of {@link ToolsManager}:
 * the {@link ToolsManager.CancelSignal} contract and marker constants. The
 * process-spawning paths need Android; only the decision logic is tested here.
 */
public class ToolsManagerCancelTest {

    @Test
    public void cancelledMarkerIsStablePrefix() {
        // WorkModeTools parses execute() output with startsWith(CANCELLED_PREFIX)
        // and rewrites it into a {"cancelled":true} tool result.
        assertTrue(ToolsManager.CANCELLED_PREFIX.length() > 0);
        assertTrue(("CANCELLED (by user)\n").startsWith(ToolsManager.CANCELLED_PREFIX));
        assertFalse("TIMEOUT (60s)".startsWith(ToolsManager.CANCELLED_PREFIX));
    }

    @Test
    public void cancelSignalMethodReferenceTracksToken() {
        app.multiai.mobile.tools.OperationCancelToken token =
                new app.multiai.mobile.tools.OperationCancelToken();
        ToolsManager.CancelSignal signal = token::check;
        assertFalse(signal.isCancelled());
        token.cancel("stop");
        assertTrue(signal.isCancelled());
    }

    @Test
    public void timeoutOutputIsNotMisreadAsCancelled() {
        // execute() distinguishes kill-by-cancel from kill-by-timeout purely by
        // these prefixes; make sure they cannot collide.
        assertFalse("TIMEOUT (60s)".startsWith(ToolsManager.CANCELLED_PREFIX));
        assertFalse((ToolsManager.CANCELLED_PREFIX).startsWith("TIMEOUT"));
    }
}
