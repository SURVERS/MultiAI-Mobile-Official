package app.multiai.mobile.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.multiai.mobile.models.Message;
import app.multiai.mobile.models.ToolCall;
import app.multiai.mobile.models.WorkflowSegment;

/**
 * Делает цитаты вида [1], [2] кликабельными: подставляет markdown-ссылки на
 * источники из результатов web_search этого сообщения. Уже готовые ссылки
 * [1](url) не трогает.
 */
public final class CitationLinker {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,2})\\](?!\\()");

    private CitationLinker() {}

    public static String link(Message message, String content) {
        if (message == null || content == null || content.isEmpty()
                || !content.contains("[")) return content;
        Map<Integer, String> urls = new HashMap<>();
        List<List<ToolCall>> buckets = new ArrayList<>();
        buckets.add(message.getToolCalls());
        List<WorkflowSegment> segments = message.getWorkflowSegments();
        if (segments != null) {
            for (WorkflowSegment seg : segments) {
                if (seg != null) buckets.add(seg.getToolCalls());
            }
        }
        for (List<ToolCall> bucket : buckets) {
            if (bucket == null) continue;
            for (ToolCall call : bucket) {
                if (call == null || !"web_search".equals(call.getName())) continue;
                JSONObject result = call.getResult();
                if (result == null) continue;
                JSONArray sources = result.optJSONArray("sources");
                if (sources == null) continue;
                for (int i = 0; i < sources.length(); i++) {
                    JSONObject s = sources.optJSONObject(i);
                    if (s == null) continue;
                    int id = s.optInt("id", i + 1);
                    String url = s.optString("url", "");
                    if (!url.isEmpty()) urls.put(id, url);
                }
            }
        }
        if (urls.isEmpty()) return content;
        Matcher m = CITATION_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            String url = urls.get(id);
            if (url == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement("[" + id + "](" + url + ")"));
                changed = true;
            }
        }
        if (!changed) return content;
        m.appendTail(sb);
        return sb.toString();
    }
}
