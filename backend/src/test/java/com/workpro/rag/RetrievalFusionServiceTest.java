package com.workpro.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalFusionServiceTest {
    @Test
    void fusesDuplicateChunksWithEqualWeightRrfAndKeepsTopEight() {
        var vector = candidate("same", "VECTOR", 1);
        var keyword = candidate("same", "KEYWORD", 1);
        var exact = candidate("other", "EXACT", 1);
        var extras = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> candidate("extra-" + index, "VECTOR", index + 2))
                .toList();

        var result = new RetrievalFusionService().fuse(
                List.of(vector), List.of(keyword), java.util.stream.Stream.concat(List.of(exact).stream(), extras.stream()).toList(),
                Map.of("VECTOR", 1.0, "KEYWORD", 1.0, "EXACT", 1.0), 60, 8);

        assertEquals(8, result.size());
        assertEquals("same", result.get(0).chunkId());
        assertEquals(2, result.get(0).providerScores().size());
        assertEquals(RetrievalContracts.CandidateStatus.FUSED, result.get(0).status());
        assertEquals(2.0 / 61.0, result.get(0).finalScore(), 1e-12);
    }

    private static RetrievalContracts.RetrievalCandidate candidate(String id, String provider, int rank) {
        return new RetrievalContracts.RetrievalCandidate(id, null, "doc", "source", "content", "page:1",
                Map.of(provider, new RetrievalContracts.ProviderScore(rank, 1.0)), 0.0,
                RetrievalContracts.CandidateStatus.RETRIEVED);
    }
}
