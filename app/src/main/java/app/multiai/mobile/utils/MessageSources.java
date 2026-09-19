package app.multiai.mobile.utils;

import app.multiai.mobile.models.Message;
import app.multiai.mobile.models.ToolCall;
import app.multiai.mobile.models.WorkflowSegment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MessageSources {
    private MessageSources() {}

    public static final class Source {
        public final String url, title, excerpt, domain;
        Source(String url, String title, String excerpt, String domain) {
            this.url = url;
            this.title = title.isEmpty() ? domain : title;
            this.excerpt = excerpt;
            this.domain = domain;
        }
    }

    public static List<Source> collect(List<Message> messages, Message target) {
        Map<String, Source> sources = new LinkedHashMap<>();
        int end = messages == null ? -1 : messages.indexOf(target);
        int start = end;
        // Tool execution and the final answer can be separate rows in one turn.
        while (start > 0 && messages.get(start - 1).getType() != Message.TYPE_USER) start--;
        if (end >= 0) {
            for (int i = start; i <= end; i++) addMessage(sources, messages.get(i));
        } else {
            addMessage(sources, target);
        }
        return new ArrayList<>(sources.values());
    }

    private static void addMessage(Map<String, Source> sources, Message message) {
        if (message == null) return;
        addCalls(sources, message.getToolCalls());
        List<WorkflowSegment> segments = message.getWorkflowSegments();
        if (segments != null) for (WorkflowSegment segment : segments) {
            if (segment != null) addCalls(sources, segment.getToolCalls());
        }
    }

    private static void addCalls(Map<String, Source> sources, List<ToolCall> calls) {
        if (calls == null) return;
        for (ToolCall call : calls) {
            if (call == null || !"web_search".equals(call.getName())) continue;
            JSONObject result = call.getResult();
            if (result == null) continue;
            JSONArray items = result.optJSONArray("sources");
            if (items == null || items.length() == 0) items = result.optJSONArray("results");
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "").trim();
                try {
                    URI uri = new URI(url);
                    String scheme = uri.getScheme();
                    String host = uri.getHost();
                    if (host == null || !("https".equalsIgnoreCase(scheme)
                            || "http".equalsIgnoreCase(scheme))) continue;
                    String domain = host.startsWith("www.") ? host.substring(4) : host;
                    sources.putIfAbsent(url, new Source(url, item.optString("title", ""),
                            item.optString("excerpt", item.optString("snippet", "")), domain));
                } catch (Exception ignored) { }
            }
        }
    }
}
