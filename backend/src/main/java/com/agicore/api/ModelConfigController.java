package com.agicore.api;

import com.agicore.model.ModelServices;
import com.agicore.model.RagGenerationGateway;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime model settings for the single-user local workspace. Secrets are never returned. */
@RestController
@RequestMapping("/api/model/config")
public class ModelConfigController {
    private final ModelServices.Properties properties;
    private final RagGenerationGateway ragGeneration;

    public ModelConfigController(ModelServices.Properties properties, RagGenerationGateway ragGeneration) {
        this.properties = properties;
        this.ragGeneration = ragGeneration;
    }

    @GetMapping
    public Map<String, Object> get() {
        var llm = properties.getLlm();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", llm.getProvider());
        result.put("baseUrl", llm.getBaseUrl());
        result.put("model", llm.getModel());
        result.put("temperature", llm.getTemperature());
        result.put("maxTokens", llm.getMaxTokens());
        result.put("configured", "mock".equalsIgnoreCase(llm.getProvider()) ||
                (llm.getBaseUrl() != null && !llm.getBaseUrl().isBlank() && llm.getApiKey() != null && !llm.getApiKey().isBlank()));
        result.put("apiKeySet", llm.getApiKey() != null && !llm.getApiKey().isBlank());
        return result;
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody UpdateRequest request) {
        if (request.provider() == null || request.provider().isBlank()) throw new IllegalArgumentException("MODEL_PROVIDER_REQUIRED");
        if (!"mock".equalsIgnoreCase(request.provider()) &&
                (request.baseUrl() == null || request.baseUrl().isBlank() || request.model() == null || request.model().isBlank()))
            throw new IllegalArgumentException("MODEL_BASE_URL_AND_NAME_REQUIRED");
        if (request.temperature() != null && (request.temperature() < 0 || request.temperature() > 2))
            throw new IllegalArgumentException("MODEL_TEMPERATURE_OUT_OF_RANGE");
        if (request.maxTokens() != null && (request.maxTokens() < 1 || request.maxTokens() > 131072))
            throw new IllegalArgumentException("MODEL_MAX_TOKENS_OUT_OF_RANGE");
        var llm = properties.getLlm();
        llm.setProvider(request.provider().trim());
        llm.setBaseUrl(request.baseUrl() == null ? "" : request.baseUrl().trim());
        llm.setModel(request.model() == null ? "" : request.model().trim());
        if (request.apiKey() != null) llm.setApiKey(request.apiKey());
        if (request.temperature() != null) llm.setTemperature(request.temperature());
        if (request.maxTokens() != null) llm.setMaxTokens(request.maxTokens());
        ragGeneration.configure(request.provider().trim(), request.baseUrl() == null ? "" : request.baseUrl().trim(),
                request.apiKey(), request.model() == null ? "" : request.model().trim(),
                request.temperature(), request.maxTokens());
        return get();
    }

    public record UpdateRequest(String provider, String baseUrl, String apiKey, String model,
                                Double temperature, Integer maxTokens) {}
}
