package com.agicore.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchBm25RetrievalProviderTest {
    @Test
    void sendsChineseMultiMatchAndDocumentFilterToElasticsearch() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rag_chunks/_search", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"hits\":{\"hits\":[{\"_score\":4.2,\"_source\":{\"chunk_id\":\"c1\",\"document_id\":\"d1\",\"content\":\"采购编号 CG1\",\"location\":\"page:1\"}}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ElasticsearchProperties();
            properties.setUrl("http://localhost:" + server.getAddress().getPort());
            var provider = new ElasticsearchBm25RetrievalProvider(properties, new ObjectMapper());

            var result = provider.search("采购编号", "d1", Map.of());

            assertEquals(1, result.size());
            assertEquals("c1", result.get(0).chunkId());
            assertEquals(1, result.get(0).providerScores().get("ELASTICSEARCH_BM25").rank());
            assertTrue(requestBody.get().contains("multi_match"));
            assertTrue(requestBody.get().contains("document_id"));
            assertTrue(requestBody.get().contains("d1"));
        } finally {
            server.stop(0);
        }
    }
}
