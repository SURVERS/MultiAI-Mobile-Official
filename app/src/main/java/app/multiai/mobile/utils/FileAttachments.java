package app.multiai.mobile.utils;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class FileAttachments {
    private static final String PREFIX = "multiai-files-v1:";
    public static final class Entry {
        public final String name;
        public final String content;
        public Entry(String name, String content) {
            this.name = name;
            this.content = content;
        }
    }
    private FileAttachments() {}

    public static List<Entry> unpack(String name, String content) {
        List<Entry> entries = new ArrayList<>();
        if (content != null && content.startsWith(PREFIX)) {
            try {
                JSONArray array = new JSONArray(content.substring(PREFIX.length()));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    entries.add(new Entry(item.getString("name"), item.getString("content")));
                }
                return entries;
            } catch (Exception ignored) { }
        }
        if (name != null || content != null) entries.add(new Entry(name == null ? "file" : name, content == null ? "" : content));
        return entries;
    }

    public static String pack(List<Entry> entries) {
        if (entries.isEmpty()) return null;
        if (entries.size() == 1 && !entries.get(0).content.startsWith(PREFIX)) return entries.get(0).content;
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                JSONObject item = new JSONObject();
                item.put("name", entry.name);
                item.put("content", entry.content);
                array.put(item);
            }
        } catch (Exception error) { throw new IllegalArgumentException(error); }
        return PREFIX + array;
    }

    public static String names(List<Entry> entries) {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) names.add(entry.name);
        return names.isEmpty() ? null : String.join(", ", names);
    }

    public static String prompt(String name, String content) {
        StringBuilder result = new StringBuilder();
        for (Entry entry : unpack(name, content)) {
            result.append("\n\n```file: ").append(entry.name).append('\n').append(entry.content).append("\n```");
        }
        return result.toString();
    }
}
