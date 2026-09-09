package app.multiai.mobile.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM-тесты чистой логики read_skill: строгая валидация slug (белый список,
 * без путей и "..") и извлечение тела SKILL.md с клипом.
 */
public class SkillReaderTest {

    // ─── normalizeSlug: валидные slug-и ───

    @Test
    public void acceptsPlainSlug() {
        assertEquals("banner-design", SkillReader.normalizeSlug("banner-design"));
    }

    @Test
    public void normalizesCaseAndSurroundingWhitespace() {
        assertEquals("code-review", SkillReader.normalizeSlug("  Code-Review\t"));
    }

    @Test
    public void acceptsDotsDigitsUnderscores() {
        assertEquals("vue3.v2.1_beta-2", SkillReader.normalizeSlug("vue3.v2.1_beta-2"));
    }

    // ─── normalizeSlug: arbitrary path access должен быть исключён ───

    @Test
    public void rejectsNullAndEmpty() {
        assertNull(SkillReader.normalizeSlug(null));
        assertNull(SkillReader.normalizeSlug(""));
        assertNull(SkillReader.normalizeSlug("   "));
    }

    @Test
    public void rejectsSlashSeparatedPaths() {
        assertNull(SkillReader.normalizeSlug(".claude/skills/x/SKILL.md"));
        assertNull(SkillReader.normalizeSlug("a/b"));
        assertNull(SkillReader.normalizeSlug("a\\b"));
    }

    @Test
    public void rejectsDotDotTraversal() {
        assertNull(SkillReader.normalizeSlug(".."));
        assertNull(SkillReader.normalizeSlug("../.."));
        assertNull(SkillReader.normalizeSlug("a..b"));
    }

    @Test
    public void rejectsLeadingDotAndDash() {
        // Скрытые имена и "./"-подобные префиксы не проходят белый список.
        assertNull(SkillReader.normalizeSlug(".hidden"));
        assertNull(SkillReader.normalizeSlug("-x"));
        assertNull(SkillReader.normalizeSlug("./slug"));
    }

    @Test
    public void rejectsShellAndControlCharacters() {
        assertNull(SkillReader.normalizeSlug("a;rm -rf /"));
        assertNull(SkillReader.normalizeSlug("a$(id)"));
        assertNull(SkillReader.normalizeSlug("a\nb"));
        assertNull(SkillReader.normalizeSlug("a b"));
        assertNull(SkillReader.normalizeSlug("скилл"));
    }

    @Test
    public void rejectsOverlyLongSlug() {
        StringBuilder longSlug = new StringBuilder();
        for (int i = 0; i < 65; i++) longSlug.append('a');
        assertNull(SkillReader.normalizeSlug(longSlug.toString()));
        StringBuilder maxSlug = new StringBuilder();
        for (int i = 0; i < 64; i++) maxSlug.append('a');
        assertEquals(maxSlug.toString(), SkillReader.normalizeSlug(maxSlug.toString()));
    }

    // ─── extractBody ───

    @Test
    public void stripsFrontmatterFromBody() {
        String raw = "---\nname: Test\ndescription: d\n---\n\nStep 1. Do things.\n";
        SkillReader.Body body = SkillReader.extractBody(raw, 10_000);
        assertEquals("Step 1. Do things.", body.text);
        assertFalse(body.truncated);
    }

    @Test
    public void keepsBodyWithoutFrontmatter() {
        SkillReader.Body body = SkillReader.extractBody("Just instructions", 10_000);
        assertEquals("Just instructions", body.text);
    }

    @Test
    public void clipsToLimitWithMarker() {
        String raw = "---\nname: T\n---\n" + repeat('x', 500);
        SkillReader.Body body = SkillReader.extractBody(raw, 100);
        assertTrue(body.truncated);
        assertEquals(100 + "\n...[truncated]".length(), body.text.length());
        assertTrue(body.text.endsWith("\n...[truncated]"));
    }

    @Test
    public void nullRawYieldsEmptyBody() {
        SkillReader.Body body = SkillReader.extractBody(null, 10);
        assertEquals("", body.text);
        assertFalse(body.truncated);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }
}
