package app.multiai.mobile.network;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ModelsDevCatalogTest {
    @Test
    public void enrichesOpenCodeGoMissingMetadata() throws Exception {
        CustomProviderClient.FetchedModel sparse = new CustomProviderClient.FetchedModel(
                "qwen3.7-max", "Qwen3.7 Max");
        JSONObject meta = new JSONObject("{"
                + "\"id\":\"qwen3.7-max\",\"name\":\"Qwen3.7 Max\",\"reasoning\":true,"
                + "\"reasoning_options\":[{\"type\":\"toggle\"}],"
                + "\"modalities\":{\"input\":[\"text\"],\"output\":[\"text\"]},"
                + "\"limit\":{\"context\":1000000,\"output\":65536},"
                + "\"cost\":{\"input\":2.5,\"output\":7.5,\"cache_read\":0.5}}"
        );

        CustomProviderClient.FetchedModel model = ModelsDevCatalog.merge(sparse, meta);
        assertEquals(1000000, model.contextWindow);
        assertEquals(65536, model.maxOutputTokens);
        assertEquals("text", model.inputModalities);
        assertEquals("none,high", model.reasoningLevels);
        assertTrue(model.hasPricing);
        assertEquals(2.5, model.priceInput, 0);
        assertEquals(7.5, model.priceOutput, 0);
        assertFalse(model.free);
    }

    @Test
    public void serverMetadataWinsOverModelsDev() throws Exception {
        CustomProviderClient.FetchedModel server = new CustomProviderClient.FetchedModel(
                "model", "Server Name", 32000, 8000, "text,image", "low,high", "high",
                1.25, 2.5, 0.25, true, false);
        JSONObject meta = new JSONObject("{\"name\":\"Other Name\","
                + "\"limit\":{\"context\":1000000,\"output\":65536},"
                + "\"cost\":{\"input\":9,\"output\":10}}"
        );

        CustomProviderClient.FetchedModel model = ModelsDevCatalog.merge(server, meta);
        assertEquals("Server Name", model.displayName);
        assertEquals(32000, model.contextWindow);
        assertEquals(8000, model.maxOutputTokens);
        assertEquals(1.25, model.priceInput, 0);
        assertEquals(2.5, model.priceOutput, 0);
    }
}
