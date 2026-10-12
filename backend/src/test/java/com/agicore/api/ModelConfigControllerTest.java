package com.agicore.api;

import com.agicore.model.ModelServices;
import com.agicore.model.RagGenerationGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelConfigControllerTest {
    @Test
    void appliesSettingsToChatAndRagGenerationWithoutReturningSecret() {
        var properties = new ModelServices.Properties();
        var ragSettings = new RagGenerationGateway.Settings();
        var controller = new ModelConfigController(properties, new RagGenerationGateway(ragSettings));

        var result = controller.update(new ModelConfigController.UpdateRequest(
                "openai-compatible", "https://api.example.com", "test-secret", "model-1", 0.4, 2048));

        assertEquals("openai-compatible", properties.getLlm().getProvider());
        assertEquals("model-1", properties.getLlm().getModel());
        assertEquals("https://api.example.com", ragSettings.getBaseUrl());
        assertEquals("model-1", ragSettings.getModel());
        assertEquals(0.4, ragSettings.getTemperature());
        assertTrue((boolean) result.get("apiKeySet"));
        assertFalse(result.containsKey("apiKey"));
    }

    @Test
    void blankKeyKeepsExistingSecretAndMockProviderCanOmitEndpoint() {
        var properties = new ModelServices.Properties();
        properties.getLlm().setApiKey("existing-secret");
        var settings = new RagGenerationGateway.Settings();
        settings.setApiKey("existing-secret");
        var controller = new ModelConfigController(properties, new RagGenerationGateway(settings));

        controller.update(new ModelConfigController.UpdateRequest("mock", "", null, "local", null, null));

        assertEquals("existing-secret", properties.getLlm().getApiKey());
        assertEquals("existing-secret", settings.getApiKey());
        assertEquals("mock", settings.getProvider());
    }
}
