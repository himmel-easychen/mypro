package com.agicore.rag;

import com.agicore.persistence.RagPersistence;
import com.agicore.rag.RetrievalContracts.CandidateStatus;
import com.agicore.rag.RetrievalContracts.ProviderScore;
import com.agicore.rag.RetrievalContracts.RetrievalCandidate;
import com.agicore.rag.RetrievalContracts.RetrievalProvider;

import java.util.List;
import java.util.Map;

/**
 * Legacy PostgreSQL FTS adapter retained only for compatibility tests.
 * Production retrieval uses ElasticsearchBm25RetrievalProvider.
 */
public class KeywordRetrievalProvider implements RetrievalProvider {
    public static final int PROVIDER_TOP_K = 20;

    private final RagPersistence persistence;

    public KeywordRetrievalProvider(RagPersistence persistence) {
        this.persistence = persistence;
    }

    @Override
    public List<RetrievalCandidate> search(String query, String documentId, Map<String, Object> filters) {
        List<RagPersistence.VectorHit> hits = persistence.searchByKeyword(query, PROVIDER_TOP_K, documentId);
        return java.util.stream.IntStream.range(0, hits.size())
                .mapToObj(index -> toCandidate(hits.get(index), index + 1))
                .toList();
    }

    private RetrievalCandidate toCandidate(RagPersistence.VectorHit hit, int rank) {
        return new RetrievalCandidate(
                hit.chunkId(), hit.parentChunkId(), hit.documentId(), hit.sourceFile(), hit.content(), hit.location(),
                Map.of("KEYWORD", new ProviderScore(rank, hit.similarity())), hit.similarity(), CandidateStatus.RETRIEVED);
    }
}
