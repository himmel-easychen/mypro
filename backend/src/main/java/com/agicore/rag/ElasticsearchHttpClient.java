package com.agicore.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Small REST wrapper used to keep the ES integration dependency-light. */
final class ElasticsearchHttpClient {
    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    ElasticsearchHttpClient(ElasticsearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(100, properties.getConnectTimeoutMs())))
                .build();
    }

    Response request(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.normalizedUrl() + path))
                    .timeout(Duration.ofMillis(Math.max(100, properties.getRequestTimeoutMs())))
                    .header("Accept", "application/json");
            if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
                String raw = properties.getUsername() + ":" + (properties.getPassword() == null ? "" : properties.getPassword());
                builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", contentType == null ? "application/json" : contentType);
                builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RAG_ES_UNAVAILABLE " + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("RAG_ES_UNAVAILABLE " + exception.getMessage(), exception);
        }
    }

    JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body() == null ? "{}" : response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("RAG_ES_INVALID_JSON", exception);
        }
    }

    record Response(int status, String body) {
        boolean successful() {
            return status >= 200 && status < 300;
        }
    }
}
