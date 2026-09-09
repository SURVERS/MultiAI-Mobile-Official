package app.multiai.mobile.utils;

public final class StreamingMarkdown {
    private StreamingMarkdown() {}

    public static String prepare(String source) {
        if (source == null || source.isEmpty()) return "";
        StringBuilder result = new StringBuilder(source);
        boolean fenced = false;
        boolean inline = false;
        boolean bold = false;
        boolean italic = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (!fenced && c == '\\') { i++; continue; }
            if (c == '`' && source.startsWith("```", i)) {
                fenced = !fenced;
                i += 2;
                continue;
            }
            if (fenced) continue;
            if (c == '`') { inline = !inline; continue; }
            if (inline) continue;
            if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                bold = !bold;
                i++;
            } else if (c == '*' && !(i == 0 || source.charAt(i - 1) == '\n')) {
                italic = !italic;
            }
        }
        if (fenced) return result.append("\n```").toString();
        if (inline) result.append('`');
        if (italic) result.append('*');
        if (bold) result.append("**");
        return result.toString();
    }
}
