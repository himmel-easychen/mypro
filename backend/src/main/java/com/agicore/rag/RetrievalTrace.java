package com.agicore.rag;

import java.util.List;
import java.util.Map;

public record RetrievalTrace(
        String traceId,
        String queryType,
        List<String> enabledProviders,
        Map<String, String> providerFailures,
        String intent,
        Map<String, Integer> providerTopK,
        int fusionTopK,
        int contextTopK,
        int candidateCount,
        int rrfK,
        Map<String, Double> providerWeights,
        List<String> finalChunkIds,
        long totalLatencyMs) {
}
