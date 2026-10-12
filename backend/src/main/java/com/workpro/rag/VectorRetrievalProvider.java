package com.workpro.rag;

import com.workpro.model.ModelServices.EmbeddingClient;
import com.workpro.persistence.RagPersistence;
import com.workpro.rag.RetrievalContracts.CandidateStatus;
import com.workpro.rag.RetrievalContracts.ProviderScore;
import com.workpro.rag.RetrievalContracts.RetrievalCandidate;
import com.workpro.rag.RetrievalContracts.RetrievalProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class VectorRetrievalProvider implements RetrievalProvider {
    public static final int PROVIDER_TOP_K = 20;

    private final RagPersistence persistence;
    private final EmbeddingClient embedding;

    public VectorRetrievalProvider(RagPersistence persistence, EmbeddingClient embedding) {
        this.persistence = persistence;
        this.embedding = embedding;
    }

    @Override
    public List<RetrievalCandidate> search(String query, String documentId, Map<String, Object> filters) {
        double[] queryVector = embedding.embed(query == null ? "" : query);
        List<RagPersistence.VectorHit> hits = persistence.searchByVector(queryVector,
                requestedTopK(filters, PROVIDER_TOP_K), documentId, embedding.model(), embedding.dimension());
        return java.util.stream.IntStream.range(0, hits.size())
                .mapToObj(index -> toCandidate(hits.get(index), index + 1))
                .toList();
    }

    static int requestedTopK(Map<String, Object> filters, int fallback) {
        if (filters == null) return fallback;
        Object value = filters.get("_provider_top_k");
        if (value instanceof Number n) return Math.max(1, Math.min(60, n.intValue()));
        return fallback;
    }

    private RetrievalCandidate toCandidate(RagPersistence.VectorHit hit, int rank) {
        return new RetrievalCandidate(
                hit.chunkId(), hit.parentChunkId(), hit.documentId(), hit.sourceFile(), hit.content(), hit.location(),
                Map.of("VECTOR", new ProviderScore(rank, hit.similarity())), hit.similarity(), CandidateStatus.RETRIEVED);
    }
}
