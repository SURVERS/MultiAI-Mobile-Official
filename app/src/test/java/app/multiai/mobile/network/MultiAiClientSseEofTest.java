package app.multiai.mobile.network;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MultiAiClientSseEofTest {

    @Test
    public void eofAfterUsefulPayloadCompletesWithoutDoneMarker() {
        assertTrue(MultiAiClient.shouldCompleteSseAtEof(true, false));
    }

    @Test
    public void eofWithoutPayloadIsTruncated() {
        assertFalse(MultiAiClient.shouldCompleteSseAtEof(false, false));
    }

    @Test
    public void eofAfterMalformedTrailingEventIsTruncated() {
        assertFalse(MultiAiClient.shouldCompleteSseAtEof(true, true));
    }
}
