package com.agicore.rag;

import com.agicore.rag.RetrievalContracts.RetrievalCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Optional remote cross-encoder reranker. It is fail-open and never invents candidates. */
@Service
@EnableConfigurationProperties(CrossEncoderReranker.Properties.class)
public class CrossEncoderReranker {
    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final int MAX_TEXTS_PER_REQUEST = 32;
    private final Properties properties;
    public CrossEncoderReranker(Properties properties) { this.properties = properties; }

    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, int topK) {
        if (!properties.enabled() || candidates == null || candidates.isEmpty()) return candidates == null ? List.of() : candidates;
        try {
            // Hugging Face TEI's native cross-encoder endpoint accepts {query, texts}
            // and returns indexed scores. Keeping the wire format native lets us
            // run a real local model without a cloud-provider key.
            RestClient client = RestClient.builder().baseUrl(properties.baseUrl().replaceAll("/+$", "")).build();
            List<Scored> scored = new ArrayList<>();
            for (int offset = 0; offset < candidates.size(); offset += MAX_TEXTS_PER_REQUEST) {
                int end = Math.min(offset + MAX_TEXTS_PER_REQUEST, candidates.size());
                List<String> texts = candidates.subList(offset, end).stream()
                        .map(RetrievalCandidate::content).toList();
                Map<String,Object> body = Map.of("query", query, "texts", texts, "raw_scores", true);
                JsonNode results = client.post().uri(properties.path()).contentType(MediaType.APPLICATION_JSON)
                        .headers(h -> { if (!properties.apiKey().isBlank()) h.setBearerAuth(properties.apiKey()); })
                        .body(body).retrieve().body(JsonNode.class);
                if (results == null || !results.isArray()) return candidates;
                for (JsonNode item : results) {
                    int localIndex = item.path("index").asInt(-1);
                    if (localIndex >= 0 && localIndex < texts.size()) {
                        int index = offset + localIndex;
                        scored.add(new Scored(candidates.get(index), item.path("score").asDouble(0), index));
                    }
                }
            }
            if (scored.size() != candidates.size()) return candidates;
            scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
            log.info("Cross-encoder reranked {} candidates with {}", candidates.size(), properties.model());
            return scored.stream().limit(Math.max(1, topK)).map(s -> s.candidate().withFinalScore(s.score(), RetrievalContracts.CandidateStatus.RERANKED)).toList();
        } catch (RuntimeException error) {
            log.warn("Cross-encoder rerank failed; keeping lexical ordering: {}", error.getClass().getSimpleName());
            return candidates;
        }
    }

    private record Scored(RetrievalCandidate candidate, double score, int index) {}

    @ConfigurationProperties(prefix = "agicore.retrieval.rerank")
    public record Properties(boolean enabled, String baseUrl, String path, String apiKey, String model) {
        public Properties { baseUrl = Objects.requireNonNullElse(baseUrl, "http://127.0.0.1:8000"); path = Objects.requireNonNullElse(path, "/rerank"); apiKey = Objects.requireNonNullElse(apiKey, ""); model = Objects.requireNonNullElse(model, "BAAI/bge-reranker-base"); }
    }
}
