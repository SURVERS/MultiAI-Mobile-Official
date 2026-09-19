package app.multiai.mobile.utils;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class FileAttachmentsTest {
    @Test public void appendingAndRemovingPreservesEachFile() {
        List<FileAttachments.Entry> files = FileAttachments.unpack("first.txt", "one");
        files.add(new FileAttachments.Entry("second.txt", "two"));
        String packed = FileAttachments.pack(files);
        List<FileAttachments.Entry> restored = FileAttachments.unpack(FileAttachments.names(files), packed);
        assertEquals(2, restored.size());
        assertEquals("one", restored.get(0).content);
        assertEquals("two", restored.get(1).content);
        String prompt = FileAttachments.prompt(FileAttachments.names(files), packed);
        assertTrue(prompt.contains("```file: first.txt\none"));
        assertTrue(prompt.contains("```file: second.txt\ntwo"));
        assertFalse(prompt.contains("multiai-files-v1:"));
        restored.remove(0);
        assertEquals("second.txt", FileAttachments.names(restored));
        assertEquals("two", FileAttachments.pack(restored));
        restored.clear();
        assertNull(FileAttachments.pack(restored));
    }
}
