package com.workpro.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalCandidateTest {
    @Test
    void mergesSameChunkAndKeepsEveryProviderRankAndScore() {
        var vector = new RetrievalContracts.RetrievalCandidate(
                "chunk-1", "parent-1", "doc-1", "source.pdf", "正文", "page:1",
                Map.of("VECTOR", new RetrievalContracts.ProviderScore(1, 0.91)),
                0.0, RetrievalContracts.CandidateStatus.RETRIEVED);
        var keyword = new RetrievalContracts.RetrievalCandidate(
                "chunk-1", "parent-1", "doc-1", "source.pdf", "正文", "page:1",
                Map.of("KEYWORD", new RetrievalContracts.ProviderScore(3, 12.0)),
                0.0, RetrievalContracts.CandidateStatus.RETRIEVED);

        var merged = vector.merge(keyword);

        assertEquals("chunk-1", merged.chunkId());
        assertEquals(1, merged.providerScores().get("VECTOR").rank());
        assertEquals(0.91, merged.providerScores().get("VECTOR").score());
        assertEquals(3, merged.providerScores().get("KEYWORD").rank());
        assertEquals(12.0, merged.providerScores().get("KEYWORD").score());
        assertEquals(RetrievalContracts.CandidateStatus.FUSED, merged.status());
        assertTrue(merged.content().contains("正文"));
    }
}
