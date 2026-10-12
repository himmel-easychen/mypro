package com.agicore.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingClientTest {
    @Test
    void usesDashscopeNativeRequestAndResponse() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/embeddings/text-embedding/text-embedding", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"output\":{\"embeddings\":[{\"embedding\":[0.1,0.2]}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ModelServices.Properties properties = new ModelServices.Properties();
            ModelServices.Properties.Endpoint endpoint = properties.getEmbedding();
            endpoint.setProvider("dashscope");
            endpoint.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1");
            endpoint.setModel("qwen3.7-text-embedding");
            endpoint.setDimension(2);
            double[] vector = new ModelServices.EmbeddingClient(properties).embed("测试文本");
            assertEquals(2, vector.length);
            assertEquals(2, new ModelServices.EmbeddingClient(properties).dimension());
            assertTrue(request.get().contains("\"texts\":[\"测试文本\"]"));
        } finally {
            server.stop(0);
        }
    }
}
