package app.multiai.mobile.utils;
import org.junit.Test;
import static org.junit.Assert.*;
public class StreamingMarkdownTest {
    @Test public void balancesPartialBold() { assertEquals("**hello**", StreamingMarkdown.prepare("**hello")); }
    @Test public void closesCodeFenceWithoutChangingCode() { assertEquals("```js\na*b\n```", StreamingMarkdown.prepare("```js\na*b")); }
    @Test public void preservesEscapedStars() { assertEquals("\\*literal", StreamingMarkdown.prepare("\\*literal")); }
    @Test public void keepsCompleteMarkdown() { assertEquals("**done**", StreamingMarkdown.prepare("**done**")); }
}
