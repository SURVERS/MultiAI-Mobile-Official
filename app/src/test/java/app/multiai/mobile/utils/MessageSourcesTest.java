package app.multiai.mobile.utils;

import app.multiai.mobile.models.Message;
import app.multiai.mobile.models.ToolCall;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class MessageSourcesTest {
    private Message search(String json) throws Exception {
        ToolCall call = new ToolCall("search", "web_search", null);
        call.setResult(new JSONObject(json));
        Message message = new Message("", Message.TYPE_AI);
        message.setToolCalls(Collections.singletonList(call));
        return message;
    }

    @Test public void collectsEarlierToolRowsButNotPreviousTurns() throws Exception {
        Message old = search("{sources:[{url:'https://old.example',title:'Old'}]}");
        Message user = new Message("search", Message.TYPE_USER);
        Message tool = search("{sources:[{url:'https://example.com',title:'Example'}]}");
        Message answer = new Message("Answer", Message.TYPE_AI);
        java.util.List<MessageSources.Source> sources = MessageSources.collect(
                Arrays.asList(old, user, tool, answer), answer);
        assertEquals(1, sources.size());
        assertEquals("Example", sources.get(0).title);
    }

    @Test public void deduplicatesAndRejectsUnsafeUrls() throws Exception {
        Message message = search("{sources:[{url:'https://example.com'},"
                + "{url:'https://example.com'},{url:'javascript:alert(1)'},{url:'file:///tmp/a'}]}");
        assertEquals(1, MessageSources.collect(null, message).size());
        assertEquals("example.com", MessageSources.collect(null, message).get(0).title);
    }

    @Test public void supportsResultsFallbackAndEmptyAnswer() throws Exception {
        Message message = search("{results:[{url:'https://www.example.com/a',snippet:'Summary'}]}");
        assertEquals("Summary", MessageSources.collect(null, message).get(0).excerpt);
        assertEquals("example.com", MessageSources.collect(null, message).get(0).domain);
        assertTrue(MessageSources.collect(null, new Message("Hello", Message.TYPE_AI)).isEmpty());
    }
}
