package com.agicore.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalServiceAdapterDiagnosticsTest {
    @Test
    void partialProviderFailureIsDegradedEvenWhenEvidenceSurvives() {
        var vector = org.mockito.Mockito.mock(VectorRetrievalProvider.class);
        var bm25 = org.mockito.Mockito.mock(ElasticsearchBm25RetrievalProvider.class);
        org.mockito.Mockito.when(vector.search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new IllegalStateException("embedding unavailable"));
        org.mockito.Mockito.when(bm25.search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(candidate("survivor", "ELASTICSEARCH_BM25", 1, .8, .03)));
        var adapter = new RetrievalServiceAdapter(null,
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), vector, bm25, null, new RetrievalFusionService());
        var pack = adapter.retrieve(new RetrievalContracts.RetrievalRequest("服务承诺", null, null, 20, Map.of()));
        assertEquals("DEGRADED", pack.metadata().get("retrieval_status"));
        assertEquals("survivor", pack.results().get(0).chunk_id());
    }
    @Test
    void optionalRewriteFailureKeepsOriginalEvidence() {
        var vector = org.mockito.Mockito.mock(VectorRetrievalProvider.class);
        org.mockito.Mockito.when(vector.search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(candidate("first", "VECTOR", 1, .8, .03)))
                .thenThrow(new IllegalStateException("optional query failed"));
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var adapter = new RetrievalServiceAdapter(null, jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                vector, null, null, new RetrievalFusionService());
        var pack = adapter.retrieve(new RetrievalContracts.RetrievalRequest("广州金额多少？深圳期限多久？",
                null, null, 20, Map.of("mode", "vector")));
        assertEquals(List.of("first"), pack.results().stream().map(r -> r.chunk_id()).toList());
        assertEquals("OK", pack.metadata().get("retrieval_status"));
    }
    @Test
    void snapshotsKeepStageOrderRankAndScores() {
        var first = candidate("first", "VECTOR", 3, 0.8, 0.03);
        var second = candidate("second", "VECTOR", 7, 0.6, 0.02);

        var snapshots = RetrievalServiceAdapter.snapshotCandidates(List.of(first, second));

        assertEquals(List.of("first", "second"), snapshots.stream().map(RetrievalStageCandidate::chunkId).toList());
        assertEquals(1, snapshots.get(0).rank());
        assertEquals(3, snapshots.get(0).providerRanks().get("VECTOR"));
        assertEquals(0.03, snapshots.get(0).finalScore());
    }

    private static RetrievalContracts.RetrievalCandidate candidate(
            String id, String provider, int providerRank, double providerScore, double finalScore) {
        return new RetrievalContracts.RetrievalCandidate(id, null, "doc", "source", "content", "page:1",
                Map.of(provider, new RetrievalContracts.ProviderScore(providerRank, providerScore)), finalScore,
                RetrievalContracts.CandidateStatus.FUSED);
    }
}
