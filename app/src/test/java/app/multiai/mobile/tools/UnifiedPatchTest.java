package app.multiai.mobile.tools;
import org.junit.Test;
import static org.junit.Assert.*;
public class UnifiedPatchTest {
    @Test public void rejectsBlankContextAgainstNonblankSource() {
        assertNull(WorkModeTools.applyUnifiedPatch("first\nsecond", "@@ -1,2 +1,2 @@\n\n-second\n+changed"));
    }
    @Test public void appliesExactContext() {
        assertEquals("first\nchanged", WorkModeTools.applyUnifiedPatch("first\nsecond", "@@ -1,2 +1,2 @@\n first\n-second\n+changed"));
    }
}
