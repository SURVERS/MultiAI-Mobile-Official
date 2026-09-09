package app.multiai.mobile.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImageAttachmentsTest {
    @Test public void packsAndUnpacksMultipleImages() {
        String packed = ImageAttachments.pack(java.util.Arrays.asList("a", "b", "c"));
        assertEquals(java.util.Arrays.asList("a", "b", "c"), ImageAttachments.unpack(packed));
    }
    @Test public void appendAndRemoveKeepLegacySingleValue() {
        assertEquals("a", ImageAttachments.append(null, "a"));
        String packed = ImageAttachments.append("a", "b");
        assertEquals("b", ImageAttachments.remove(packed, 0));
    }
}
