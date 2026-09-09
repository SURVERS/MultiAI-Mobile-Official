package app.multiai.mobile.tools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ProjectFileMimeTest {
    @Test public void preservesKnownWebTypes() {
        assertEquals("text/html", ProjectFileMime.forExtension("html", "text/html"));
        assertEquals("text/css", ProjectFileMime.forExtension("css", "text/css"));
        assertEquals("application/json", ProjectFileMime.forExtension("json", "application/json"));
    }

    @Test public void sourceFilesNeverFallBackToPlainText() {
        for (String extension : new String[]{"js", "ts", "tsx", "xml", "md", "py", "cpp", "java", "", "custom"}) {
            assertEquals(extension, "application/octet-stream", ProjectFileMime.forExtension(extension, "text/plain"));
            assertEquals(extension, "application/octet-stream", ProjectFileMime.forExtension(extension, null));
        }
    }

    @Test public void explicitTextFileKeepsTextMime() {
        assertEquals("text/plain", ProjectFileMime.forExtension("txt", "text/plain"));
    }
}
