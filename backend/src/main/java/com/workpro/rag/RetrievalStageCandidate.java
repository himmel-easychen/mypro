package com.workpro.rag;

import java.util.Map;
import java.util.Set;

public record RetrievalStageCandidate(
        String chunkId,
        String parentChunkId,
        int rank,
        double finalScore,
        Map<String, Integer> providerRanks,
        Map<String, Double> providerScores,
        Set<Integer> matchedQueryIndices) {
}
