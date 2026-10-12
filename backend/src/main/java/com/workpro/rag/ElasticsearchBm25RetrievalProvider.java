package com.workpro.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workpro.rag.RetrievalContracts.CandidateStatus;
import com.workpro.rag.RetrievalContracts.ProviderScore;
import com.workpro.rag.RetrievalContracts.RetrievalCandidate;
import com.workpro.rag.RetrievalContracts.RetrievalProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElasticsearchBm25RetrievalProvider implements RetrievalProvider {
    public static final int PROVIDER_TOP_K = 20;
    public static final String PROVIDER_NAME = "ELASTICSEARCH_BM25";

    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final ElasticsearchHttpClient http;

    public ElasticsearchBm25RetrievalProvider(ElasticsearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = new ElasticsearchHttpClient(properties, objectMapper);
    }

    @Override
    public List<RetrievalCandidate> search(String query, String documentId, Map<String, Object> filters) {
        if (query == null || query.isBlank()) return List.of();
        try {
            String body = objectMapper.writeValueAsString(searchBody(query, documentId, VectorRetrievalProvider.requestedTopK(filters, PROVIDER_TOP_K)));
            var response = http.request("POST", "/" + properties.getIndex() + "/_search", body, "application/json");
            if (!response.successful()) {
                throw new IllegalStateException("RAG_ES_SEARCH_FAILED status=" + response.status() + " body=" + response.body());
            }
            return parseHits(http.json(response));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG_ES_QUERY_BUILD_FAILED", exception);
        }
    }

    Map<String, Object> searchBody(String query, String documentId, int size) {
        Map<String, Object> multiMatch = new LinkedHashMap<>();
        multiMatch.put("query", query);
        multiMatch.put("fields", List.of("content^4", "normalized_content^3", "source_file^0.5", "location^0.5", "block_type^0.5"));
        multiMatch.put("type", "best_fields");
        multiMatch.put("operator", "or");
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(Map.of("multi_match", multiMatch)));
        if (documentId != null && !documentId.isBlank()) {
            bool.put("filter", List.of(Map.of("term", Map.of("document_id", documentId))));
        }
        return Map.of("size", size, "track_total_hits", false, "query", Map.of("bool", bool),
                "_source", List.of("chunk_id", "parent_chunk_id", "document_id", "source_file", "content", "location"));
    }

    private List<RetrievalCandidate> parseHits(JsonNode root) {
        List<RetrievalCandidate> result = new ArrayList<>();
        int rank = 1;
        for (JsonNode hit : root.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            String chunkId = source.path("chunk_id").asText(hit.path("_id").asText(""));
            if (chunkId.isBlank()) continue;
            result.add(new RetrievalCandidate(
                    chunkId,
                    nullableText(source, "parent_chunk_id"),
                    nullableText(source, "document_id"),
                    nullableText(source, "source_file"),
                    nullableText(source, "content"),
                    nullableText(source, "location"),
                    Map.of(PROVIDER_NAME, new ProviderScore(rank++, hit.path("_score").asDouble(0.0))),
                    hit.path("_score").asDouble(0.0),
                    CandidateStatus.RETRIEVED));
        }
        return result;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
