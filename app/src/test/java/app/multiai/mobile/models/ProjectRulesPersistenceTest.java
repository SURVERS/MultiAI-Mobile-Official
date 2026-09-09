package app.multiai.mobile.models;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProjectRulesPersistenceTest {
    @Test public void injectionKeySurvivesChatReload() {
        Chat chat = new Chat();
        chat.setInjectedProjectRulesKey("content://project|fingerprint");
        Gson gson = new Gson();
        Chat restored = gson.fromJson(gson.toJson(chat), Chat.class);
        assertEquals(chat.getInjectedProjectRulesKey(), restored.getInjectedProjectRulesKey());
        assertNull(new Chat().getInjectedProjectRulesKey());
    }

    @Test public void skillHasItsOwnIconAndBelongsToExplore() {
        ToolCall call = new ToolCall("skill-1", "read_skill", new java.util.HashMap<>());
        assertEquals(app.multiai.mobile.R.drawable.ic_skill_flask, call.getIconRes());
        assertTrue(app.multiai.mobile.views.ToolWidgetRenderer.isExploreTool("read_skill"));
    }
}
