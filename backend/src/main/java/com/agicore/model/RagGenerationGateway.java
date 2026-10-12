package com.agicore.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

/** Independent endpoint and circuit breaker for evidence-grounded RAG generation. */
@Service
public class RagGenerationGateway {
    @Configuration
    @ConfigurationProperties(prefix = "agicore.model.rag-generation")
    public static class Settings extends ModelServices.Properties.Endpoint {}

    private final ModelGateway gateway;
    private final Settings settings;

    public RagGenerationGateway(Settings settings) {
        this.settings = settings;
        var properties = new ModelServices.Properties();
        properties.setLlm(settings);
        gateway = new ModelGateway(new ModelServices.LlmClient(properties));
    }

    public synchronized void configure(String provider, String baseUrl, String apiKey,
                                       String model, Double temperature, Integer maxTokens) {
        settings.setProvider(provider);
        settings.setBaseUrl(baseUrl);
        if (apiKey != null) settings.setApiKey(apiKey);
        settings.setModel(model);
        if (temperature != null) settings.setTemperature(temperature);
        if (maxTokens != null) settings.setMaxTokens(maxTokens);
    }

    public ModelGateway.ModelResponse complete(String prompt) {
        return gateway.completeRag(prompt);
    }
}
