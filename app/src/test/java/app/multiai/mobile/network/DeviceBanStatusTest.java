package app.multiai.mobile.network;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class DeviceBanStatusTest {
    @Test public void explicitBanAndUnbanAreAccepted() throws Exception {
        assertTrue(DeviceBanStatus.parse(new JSONObject("{\"ok\":true,\"banned\":true}")));
        assertFalse(DeviceBanStatus.parse(new JSONObject("{\"ok\":true,\"banned\":false}")));
    }

    @Test public void errorsAndMalformedResponsesCannotUnban() throws Exception {
        String[] invalid = {"{}", "{\"error\":\"unauthorized\"}",
                "{\"ok\":true}", "{\"ok\":false,\"banned\":false}",
                "{\"ok\":true,\"banned\":null}",
                "{\"ok\":true,\"banned\":\"false\"}",
                "{\"ok\":true,\"banned\":0}"};
        for (String response : invalid) {
            try {
                DeviceBanStatus.parse(new JSONObject(response));
                fail("Accepted invalid response: " + response);
            } catch (JSONException expected) {
                // Keep the last known status when the response is not authoritative.
            }
        }
    }
}
