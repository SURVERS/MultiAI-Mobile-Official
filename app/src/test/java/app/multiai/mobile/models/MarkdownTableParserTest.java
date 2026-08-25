package app.multiai.mobile.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MarkdownTableParserTest {
    @Test
    public void parsesTableBetweenTextBlocks() {
        String markdown = "Перед таблицей\n\n| Функция | Статус |\n|---|:---:|\n| URL → PDF | ✅ |\n| DOCX | ✅ |\n\nПосле таблицы";
        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parse(markdown);

        assertEquals(3, segments.size());
        assertEquals("Перед таблицей", segments.get(0).text);
        assertTrue(segments.get(1).isTable());
        assertEquals(2, segments.get(1).table.header.size());
        assertEquals(2, segments.get(1).table.rows.size());
        assertEquals(MarkdownTableParser.Alignment.CENTER,
                segments.get(1).table.alignments.get(1));
        assertEquals("После таблицы", segments.get(2).text);
    }

    @Test
    public void normalizesShortRowsWithoutDroppingTable() {
        String markdown = "A | B | C\n---|---:|:---:\n1 | 2";
        MarkdownTableParser.Table table = MarkdownTableParser.parse(markdown).get(0).table;

        assertEquals(3, table.rows.get(0).size());
        assertEquals("", table.rows.get(0).get(2));
        assertEquals(MarkdownTableParser.Alignment.END, table.alignments.get(1));
    }

    @Test
    public void keepsEscapedAndCodePipesInsideCell() {
        String markdown = "| Pattern | Code |\n|---|---|\n| a\\|b | `x|y` |";
        MarkdownTableParser.Table table = MarkdownTableParser.parse(markdown).get(0).table;

        assertEquals("a|b", table.rows.get(0).get(0));
        assertEquals("`x|y`", table.rows.get(0).get(1));
    }

    @Test
    public void acceptsSeparatorWithMismatchedColumnCount() {
        String markdown = "| A | B |\n|---|---|---|\n| 1 | 2 |";
        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parse(markdown);

        assertTrue(segments.get(0).isTable());
        MarkdownTableParser.Table table = segments.get(0).table;
        assertEquals(2, table.header.size());
        assertEquals(2, table.alignments.size());
        assertEquals(1, table.rows.size());
    }

    @Test
    public void breakTagsBecomeNewlinesOutsideCodeSpans() {
        String markdown = "| Step | Note |\n|---|---|\n| one<br>two | `a<br>b` |";
        MarkdownTableParser.Table table = MarkdownTableParser.parse(markdown).get(0).table;

        assertEquals("one\ntwo", table.rows.get(0).get(0));
        assertEquals("`a<br>b`", table.rows.get(0).get(1));
    }

    @Test
    public void selfClosingBreakTagIsAlsoConverted() {
        String markdown = "| A | B |\n|---|---|\n| x<br /> y | z |";
        MarkdownTableParser.Table table = MarkdownTableParser.parse(markdown).get(0).table;

        assertEquals("x\n y", table.rows.get(0).get(0));
    }

    @Test
    public void headerWithoutSeparatorPipesIsNotATable() {
        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parse("a | b\n-----\nтекст");

        assertEquals(1, segments.size());
        assertFalse(segments.get(0).isTable());
    }

    @Test
    public void plainPipesAreNotMistakenForTable() {
        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parse("Используй a | b в тексте");

        assertEquals(1, segments.size());
        assertFalse(segments.get(0).isTable());
        assertFalse(MarkdownTableParser.containsTable("Используй a | b в тексте"));
    }
}
