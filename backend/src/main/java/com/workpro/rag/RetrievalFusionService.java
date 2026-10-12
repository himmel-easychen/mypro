package com.workpro.rag;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges the three provider lists with the locked first-version RRF baseline. */
@Service
public class RetrievalFusionService {
    public List<RetrievalContracts.RetrievalCandidate> fuse(
            List<RetrievalContracts.RetrievalCandidate> vectorHits,
            List<RetrievalContracts.RetrievalCandidate> keywordHits,
            List<RetrievalContracts.RetrievalCandidate> exactHits,
            Map<String, Double> providerWeights,
            int rrfK,
            int fusionTopK) {

        if (rrfK < 1 || fusionTopK < 1) {
            throw new IllegalArgumentException("rrfK and fusionTopK must be positive");
        }
        Map<String, RetrievalContracts.RetrievalCandidate> merged = new LinkedHashMap<>();
        for (List<RetrievalContracts.RetrievalCandidate> hits : List.of(vectorHits, keywordHits, exactHits)) {
            for (RetrievalContracts.RetrievalCandidate candidate : hits) {
                merged.merge(candidate.chunkId(), candidate,
                        RetrievalContracts.RetrievalCandidate::merge);
            }
        }

        List<RetrievalContracts.RetrievalCandidate> scored = new ArrayList<>(merged.size());
        for (RetrievalContracts.RetrievalCandidate candidate : merged.values()) {
            double rrfScore = candidate.providerScores().entrySet().stream()
                    .mapToDouble(entry -> {
                        double weight = providerWeights.getOrDefault(entry.getKey(), 1.0);
                        return weight / (rrfK + entry.getValue().rank());
                    })
                    .sum();
            scored.add(candidate.withFinalScore(rrfScore,
                    candidate.providerScores().size() > 1
                            ? RetrievalContracts.CandidateStatus.FUSED
                            : RetrievalContracts.CandidateStatus.RETRIEVED));
        }
        scored.sort(Comparator.comparingDouble(RetrievalContracts.RetrievalCandidate::finalScore)
                .reversed()
                .thenComparing(RetrievalContracts.RetrievalCandidate::chunkId));
        return scored.subList(0, Math.min(fusionTopK, scored.size()));
    }
}
