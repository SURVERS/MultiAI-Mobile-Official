package app.multiai.mobile.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MarkdownTableParser {
    public enum Alignment { START, CENTER, END }

    public static final class Segment {
        public final String text;
        public final Table table;

        Segment(String text, Table table) {
            this.text = text == null ? "" : text;
            this.table = table;
        }

        public boolean isTable() {
            return table != null;
        }
    }

    public static final class Table {
        public final List<String> header;
        public final List<List<String>> rows;
        public final List<Alignment> alignments;

        Table(List<String> header, List<List<String>> rows, List<Alignment> alignments) {
            this.header = Collections.unmodifiableList(new ArrayList<>(header));
            List<List<String>> safeRows = new ArrayList<>();
            for (List<String> row : rows) {
                safeRows.add(Collections.unmodifiableList(new ArrayList<>(row)));
            }
            this.rows = Collections.unmodifiableList(safeRows);
            this.alignments = Collections.unmodifiableList(new ArrayList<>(alignments));
        }
    }

    private MarkdownTableParser() { }

    public static boolean containsTable(String markdown) {
        for (Segment segment : parse(markdown)) if (segment.isTable()) return true;
        return false;
    }

    public static List<Segment> parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) return Collections.emptyList();
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Segment> output = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int index = 0;
        while (index < lines.length) {
            ParsedTable parsed = index + 1 < lines.length ? parseTable(lines, index) : null;
            if (parsed == null) {
                if (text.length() > 0) text.append('\n');
                text.append(lines[index]);
                index++;
                continue;
            }
            flushText(output, text);
            output.add(new Segment("", parsed.table));
            index = parsed.nextIndex;
        }
        flushText(output, text);
        return output;
    }

    private static ParsedTable parseTable(String[] lines, int start) {
        if (!hasPipe(lines[start]) || !hasPipe(lines[start + 1])) return null;
        List<String> header = splitRow(lines[start]);
        List<String> separator = splitRow(lines[start + 1]);
        // GFM allows a single dash ("|-|", "|:-:|"). Requiring three made the
        // parser reject valid tables, and the message then fell back to the
        // old Markwon table rendering.
        if (header.size() < 2 || separator.size() < 2 || !isSeparatorRow(separator)) return null;
        // The header defines the column count. Models regularly emit an
        // alignment row with one column too many or too few; rejecting those
        // tables sent the whole message to the legacy Markwon renderer, which
        // is exactly the "broken table" the new widget was built to replace.
        List<Alignment> alignments = new ArrayList<>(header.size());
        for (int i = 0; i < header.size(); i++) {
            alignments.add(alignmentOf(i < separator.size() ? separator.get(i).trim() : ""));
        }

        List<List<String>> rows = new ArrayList<>();
        int index = start + 2;
        while (index < lines.length && hasPipe(lines[index]) && !lines[index].trim().isEmpty()) {
            List<String> row = splitRow(lines[index]);
            if (row.size() < 2) break;
            index++;
            // Models sometimes repeat the alignment row or add a bottom border
            // row. Rendered as data it produced a stray "———" rule inside a cell.
            if (isSeparatorRow(row)) continue;
            rows.add(normalize(row, header.size()));
        }
        return new ParsedTable(new Table(normalize(header, header.size()), rows, alignments), index);
    }

    private static boolean isSeparatorCell(String value) {
        return value != null && value.matches(":?-+:?");
    }

    private static Alignment alignmentOf(String separatorCell) {
        String value = separatorCell == null ? "" : separatorCell.trim();
        if (value.length() > 1 && value.startsWith(":") && value.endsWith(":")) return Alignment.CENTER;
        if (value.endsWith(":")) return Alignment.END;
        return Alignment.START;
    }

    /** True when every non-empty cell of the row is just an alignment/border marker. */
    private static boolean isSeparatorRow(List<String> row) {
        boolean sawMarker = false;
        for (String cell : row) {
            String value = cell == null ? "" : cell.trim();
            if (value.isEmpty()) continue;
            if (!isSeparatorCell(value)) return false;
            sawMarker = true;
        }
        return sawMarker;
    }

    private static List<String> normalize(List<String> cells, int count) {
        List<String> normalized = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            normalized.add(i < cells.size() ? normalizeCell(cells.get(i)) : "");
        }
        return normalized;
    }

    /**
     * A pipe table cell cannot contain a real newline, so models write
     * {@code <br>} for multi-line cells. Markdown rendering here runs without
     * an HTML plugin, so the tag used to show up as literal text inside the
     * cell. Translate it outside inline code spans.
     */
    static String normalizeCell(String cell) {
        String value = cell == null ? "" : cell.trim();
        if (value.isEmpty()) return "";
        if (value.indexOf('<') < 0) return value;
        StringBuilder output = new StringBuilder(value.length());
        boolean code = false;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '`') {
                code = !code;
                output.append(current);
                index++;
                continue;
            }
            if (!code && current == '<') {
                int end = matchBreakTag(value, index);
                if (end > index) {
                    output.append('\n');
                    index = end;
                    continue;
                }
            }
            output.append(current);
            index++;
        }
        return output.toString().trim();
    }

    /** Returns the index right after a {@code <br>}/{@code <br/>} tag, or {@code start}. */
    private static int matchBreakTag(String value, int start) {
        int index = start + 1;
        if (index + 1 >= value.length()) return start;
        char first = value.charAt(index);
        char second = value.charAt(index + 1);
        if ((first != 'b' && first != 'B') || (second != 'r' && second != 'R')) return start;
        index += 2;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        if (index < value.length() && value.charAt(index) == '/') index++;
        if (index < value.length() && value.charAt(index) == '>') return index + 1;
        return start;
    }

    private static boolean hasPipe(String line) {
        if (line == null) return false;
        boolean escaped = false;
        boolean code = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '`') {
                code = !code;
            } else if (value == '|' && !code) {
                return true;
            }
        }
        return false;
    }

    static List<String> splitRow(String line) {
        String value = line == null ? "" : line.trim();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|") && !value.endsWith("\\|")) value = value.substring(0, value.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        boolean code = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                cell.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
                cell.append(current);
            } else if (current == '`') {
                code = !code;
                cell.append(current);
            } else if (current == '|' && !code) {
                cells.add(cell.toString().trim().replace("\\|", "|"));
                cell.setLength(0);
            } else {
                cell.append(current);
            }
        }
        if (escaped) cell.append('\\');
        cells.add(cell.toString().trim().replace("\\|", "|"));
        return cells;
    }

    private static void flushText(List<Segment> output, StringBuilder text) {
        String value = text.toString().trim();
        if (!value.isEmpty()) output.add(new Segment(value, null));
        text.setLength(0);
    }

    private static final class ParsedTable {
        final Table table;
        final int nextIndex;

        ParsedTable(Table table, int nextIndex) {
            this.table = table;
            this.nextIndex = nextIndex;
        }
    }
}
