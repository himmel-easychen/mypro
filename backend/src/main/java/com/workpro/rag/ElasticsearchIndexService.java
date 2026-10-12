package com.workpro.rag;

import com.workpro.persistence.RagPersistence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchIndexService {
    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final ElasticsearchHttpClient http;
    private final RagPersistence persistence;

    public ElasticsearchIndexService(ElasticsearchProperties properties, ObjectMapper objectMapper,
                                    RagPersistence persistence) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = new ElasticsearchHttpClient(properties, objectMapper);
        this.persistence = persistence;
    }

    public Map<String, Object> health() {
        var response = http.request("GET", "/", null, null);
        if (!response.successful()) throw failure("RAG_ES_HEALTH_FAILED", response);
        JsonNode json = http.json(response);
        return Map.of("status", "UP", "cluster", json.path("cluster_name").asText("unknown"),
                "version", json.path("version").path("number").asText("unknown"),
                "index", properties.getIndex());
    }

    public SyncResult rebuildFromDatabase() {
        deleteIndexIfExists();
        ensureIndex();
        List<RagPersistence.IndexChunk> chunks = persistence.loadChunksForSearchIndex();
        int indexed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        StringBuilder bulk = new StringBuilder();
        for (RagPersistence.IndexChunk chunk : chunks) {
            try {
                bulk.append(objectMapper.writeValueAsString(Map.of("index", Map.of("_id", chunk.chunkId())))).append('\n');
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("chunk_id", chunk.chunkId());
                document.put("parent_chunk_id", chunk.parentChunkId());
                document.put("document_id", chunk.documentId());
                document.put("source_file", chunk.sourceFile());
                document.put("source_type", chunk.sourceType());
                document.put("checksum", chunk.checksum());
                document.put("chunk_role", chunk.chunkRole());
                document.put("block_type", chunk.blockType());
                document.put("content", chunk.content());
                document.put("normalized_content", chunk.content());
                document.put("location", chunk.location());
                document.put("source_anchor", chunk.sourceAnchor());
                document.put("confidence", chunk.confidence());
                document.put("evidence_type", chunk.evidenceType());
                bulk.append(objectMapper.writeValueAsString(document)).append('\n');
                if (bulk.length() > 2_000_000) {
                    BatchResult result = sendBulk(bulk.toString());
                    indexed += result.indexed();
                    failed += result.failed();
                    errors.addAll(result.errors());
                    bulk.setLength(0);
                }
            } catch (JsonProcessingException exception) {
                failed++;
                errors.add(chunk.chunkId() + ":" + exception.getMessage());
            }
        }
        if (bulk.length() > 0) {
            BatchResult result = sendBulk(bulk.toString());
            indexed += result.indexed();
            failed += result.failed();
            errors.addAll(result.errors());
        }
        return new SyncResult(chunks.size(), indexed, failed, errors);
    }

    void ensureIndex() {
        var exists = http.request("HEAD", "/" + properties.getIndex(), null, null);
        if (exists.status() == 200) return;
        if (exists.status() != 404) throw failure("RAG_ES_INDEX_CHECK_FAILED", exists);
        try {
            var response = http.request("PUT", "/" + properties.getIndex(), objectMapper.writeValueAsString(indexDefinition()), "application/json");
            if (!response.successful()) throw failure("RAG_ES_INDEX_CREATE_FAILED", response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG_ES_INDEX_DEFINITION_FAILED", exception);
        }
    }

    Map<String, Object> indexDefinition() {
        Map<String, Object> analyzer = Map.of(
                "type", "custom",
                "tokenizer", "standard",
                "filter", List.of("lowercase", "cjk_width", "cjk_bigram"));
        Map<String, Object> propertiesMap = new LinkedHashMap<>();
        for (String field : List.of("chunk_id", "parent_chunk_id", "document_id", "source_file", "source_type",
                "checksum", "chunk_role", "block_type", "location", "source_anchor", "evidence_type")) {
            propertiesMap.put(field, Map.of("type", "keyword"));
        }
        propertiesMap.put("content", Map.of("type", "text", "analyzer", "cjk_bigram_analyzer"));
        propertiesMap.put("normalized_content", Map.of("type", "text", "analyzer", "cjk_bigram_analyzer"));
        propertiesMap.put("confidence", Map.of("type", "float"));
        return Map.of("settings", Map.of(
                        "number_of_shards", 1,
                        "number_of_replicas", 0,
                        "analysis", Map.of("analyzer", Map.of("cjk_bigram_analyzer", analyzer)),
                        "similarity", Map.of("default", Map.of("type", "BM25", "k1", 1.2, "b", 0.75))),
                "mappings", Map.of("dynamic", false, "properties", propertiesMap));
    }

    private void deleteIndexIfExists() {
        var exists = http.request("HEAD", "/" + properties.getIndex(), null, null);
        if (exists.status() == 404) return;
        if (exists.status() != 200) throw failure("RAG_ES_INDEX_CHECK_FAILED", exists);
        var deleted = http.request("DELETE", "/" + properties.getIndex(), null, null);
        if (!deleted.successful()) throw failure("RAG_ES_INDEX_DELETE_FAILED", deleted);
    }

    private BatchResult sendBulk(String body) {
        var response = http.request("POST", "/" + properties.getIndex() + "/_bulk?refresh=true", body, "application/x-ndjson");
        if (!response.successful()) throw failure("RAG_ES_BULK_FAILED", response);
        JsonNode json = http.json(response);
        int indexed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (JsonNode item : json.path("items")) {
            JsonNode op = item.path("index");
            if (op.path("status").asInt(500) >= 300) {
                failed++;
                errors.add(op.path("_id").asText("unknown") + ":" + op.path("error").toString());
            } else {
                indexed++;
            }
        }
        return new BatchResult(indexed, failed, errors);
    }

    private IllegalStateException failure(String prefix, ElasticsearchHttpClient.Response response) {
        return new IllegalStateException(prefix + " status=" + response.status() + " body=" + response.body());
    }

    private record BatchResult(int indexed, int failed, List<String> errors) {}

    public record SyncResult(int requested, int indexed, int failed, List<String> errors) {}
}
