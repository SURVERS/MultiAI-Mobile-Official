package app.multiai.mobile.network;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CustomProviderClientTest {

    @Test
    public void embeddingModelsAreNotAddedToChatCatalog() {
        List<CustomProviderClient.FetchedModel> models = CustomProviderClient.parseModelCatalog(
                "{\"data\":[{\"id\":\"bge-m3\",\"output_modalities\":[\"embedding\"]},{\"id\":\"chat-model\"}]}"
        );
        assertEquals(1, models.size());
        assertEquals("chat-model", models.get(0).id);
    }

    @Test
    public void baiMissingPricingIsUnknownAndNotFree() {
        List<CustomProviderClient.FetchedModel> models = CustomProviderClient.parseModelCatalog(
                "{\"data\":[{\"id\":\"gpt-5.6-sol\",\"owned_by\":\"azure\"}]}"
        );
        assertEquals(1, models.size());
        CustomProviderClient.FetchedModel model = models.get(0);
        assertFalse(model.hasPricing);
        assertFalse(model.free);
        assertEquals(0, model.priceInput, 0);
    }

    @Test
    public void amdPerTokenPricingBecomesDollarsPerMillion() {
        List<CustomProviderClient.FetchedModel> models = CustomProviderClient.parseModelCatalog(
                "{\"data\":[{\"id\":\"DeepSeek-V4-Flash\",\"context_length\":1048576,"
                        + "\"architecture\":{\"input_modalities\":[\"text\"]},"
                        + "\"pricing\":{\"prompt\":\"1.4e-7\",\"completion\":\"2.8e-7\"},"
                        + "\"free\":false}]}", true
        );
        CustomProviderClient.FetchedModel model = models.get(0);
        assertTrue(model.hasPricing);
        assertFalse(model.free);
        assertEquals(0.14, model.priceInput, 0.000001);
        assertEquals(0.28, model.priceOutput, 0.000001);
        assertEquals(1048576, model.contextWindow);
    }

    @Test
    public void openRouterParsesReasoningModalitiesAndPricing() {
        List<CustomProviderClient.FetchedModel> models = CustomProviderClient.parseModelCatalog(
                "{\"data\":[{\"id\":\"openai/gpt-6-astra\",\"name\":\"OpenAI: GPT-6 Astra\","
                        + "\"context_length\":1050000,"
                        + "\"pricing\":{\"prompt\":\"0.00001\",\"completion\":\"0.00005\"},"
                        + "\"architecture\":{\"input_modalities\":[\"file\",\"image\",\"text\"]},"
                        + "\"reasoning\":{\"supported_efforts\":[\"high\",\"medium\",\"low\"],"
                        + "\"default_effort\":\"medium\"}}]}", true
        );
        CustomProviderClient.FetchedModel model = models.get(0);
        // Вендорный префикс "OpenAI: " вырезается из pretty-имени.
        assertEquals("GPT-6 Astra", model.displayName);
        assertEquals("file,image,text", model.inputModalities);
        assertEquals("high,medium,low", model.reasoningLevels);
        assertEquals("medium", model.reasoningDefault);
        assertEquals(10, model.priceInput, 0.000001);
        assertEquals(50, model.priceOutput, 0.000001);
        assertFalse(model.free);
    }

    @Test
    public void explicitZeroDollarPricingIsFree() {
        CustomProviderClient.FetchedModel model = CustomProviderClient.parseModelCatalog(
                "{\"data\":[{\"id\":\"demo\",\"pricing\":{\"prompt\":0,\"completion\":0}}]}"
        ).get(0);
        assertTrue(model.hasPricing);
        assertTrue(model.free);
    }

    @Test
    public void baiMarksOnlyOfficialLimitedFreeModels() {
        List<CustomProviderClient.FetchedModel> source = CustomProviderClient.parseModelCatalog(
                "{\"data\":[{\"id\":\"hy3\"},{\"id\":\"mimo-v2.5\"},"
                        + "{\"id\":\"glm-5.3-flash\"},{\"id\":\"qwen3.8-flash\"},"
                        + "{\"id\":\"gpt-5.6-sol\"}]}"
        );
        List<CustomProviderClient.FetchedModel> models = CustomProviderClient.enrichProviderModels(
                "https://api.b.ai/v1", source);
        assertTrue(models.get(0).free);
        assertTrue(models.get(1).free);
        assertTrue(models.get(2).free);
        assertTrue(models.get(3).free);
        assertFalse(models.get(4).free);
    }

    @Test
    public void freeSuffixIsFreeWithoutPricing() {
        CustomProviderClient.FetchedModel model = CustomProviderClient.parseModelCatalog(
                "{\"data\":[{\"id\":\"vendor/model:free\"}]}"
        ).get(0);
        assertFalse(model.hasPricing);
        assertTrue(model.free);
    }
}
