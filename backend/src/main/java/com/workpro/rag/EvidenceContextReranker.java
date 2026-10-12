package com.workpro.rag;

import com.workpro.rag.RetrievalContracts.RetrievalCandidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selects final context while preventing same-parent duplicate domination. */
public class EvidenceContextReranker {
    public List<RetrievalCandidate> selectContext(String query, List<RetrievalCandidate> reranked, int contextTopK) {
        return selectContext(query, reranked, contextTopK, false);
    }

    public List<RetrievalCandidate> selectContext(String query, List<RetrievalCandidate> reranked,
                                                   int contextTopK, boolean preserveMultiHopWindow) {
        return selectContext(query, reranked, contextTopK, preserveMultiHopWindow, List.of());
    }

    public List<RetrievalCandidate> selectContext(String query, List<RetrievalCandidate> reranked,
                                                   int contextTopK, boolean preserveMultiHopWindow,
                                                   List<String> aspectQueries) {
        if (reranked == null || reranked.isEmpty() || contextTopK < 1) return List.of();
        List<RetrievalCandidate> selected = new ArrayList<>();
        Set<String> parents = new HashSet<>();
        if (preserveMultiHopWindow) {
            // First reserve one already-reranked candidate for each decomposed aspect.
            // Query provenance is carried from provider retrieval through fusion and
            // reranking, so this does not require a larger global candidate pool.
            int aspectCount = Math.min(aspectQueries == null ? 0 : aspectQueries.size(), contextTopK);
            for (int aspect = 0; aspect < aspectCount && selected.size() < contextTopK; aspect++) {
                int queryIndex = aspect + 1;
                RetrievalCandidate best = null;
                double bestScore = -1.0;
                for (int rank = 0; rank < reranked.size(); rank++) {
                    RetrievalCandidate candidate = reranked.get(rank);
                    if (!candidate.matchedQueryIndices().contains(queryIndex)) continue;
                    String parent = parentKey(candidate);
                    if (parents.contains(parent)) continue;
                    double score = aspectOverlap(aspectQueries.get(aspect), candidate.content()) * 3.0
                            + candidate.finalScore() + 1.0 / (rank + 1);
                    if (score > bestScore) {
                        best = candidate;
                        bestScore = score;
                    }
                }
                if (best != null && parents.add(parentKey(best))) {
                    selected.add(best);
                }
            }
            // Keep the original protected retrieval window after aspect coverage.
            int protectedWindow = Math.min(Math.min(5, contextTopK), reranked.size());
            for (int i = 0; i < protectedWindow && selected.size() < contextTopK; i++) {
                RetrievalCandidate candidate = reranked.get(i);
                // This window preserves the original top-ranked evidence, including
                // sibling chunks from the same parent. Parent deduplication applies
                // only to the later answer-expansion stage.
                parents.add(parentKey(candidate));
                if (!selected.contains(candidate)) selected.add(candidate);
            }
        }
        for (RetrievalCandidate candidate : reranked) {
            if (selected.contains(candidate)) continue;
            String key = parentKey(candidate);
            if (parents.add(key)) selected.add(candidate);
            if (selected.size() >= Math.min(5, contextTopK)) break;
        }
        // Preserve the original five, then spend the answer-only expansion budget on
        // uncovered query terms rather than another near-duplicate of the leading topic.
        Set<String> terms = queryTerms(query);
        Set<String> covered = new HashSet<>();
        selected.forEach(c -> terms.stream().filter(c.content()::contains).forEach(covered::add));
        while (selected.size() < contextTopK) {
            RetrievalCandidate best = null;
            double bestGain = -1;
            for (int i = 0; i < reranked.size(); i++) {
                var c = reranked.get(i);
                String parent = c.parentChunkId() == null || c.parentChunkId().isBlank() ? c.chunkId() : c.parentChunkId();
                if (parents.contains(parent)) continue;
                long gain = terms.stream().filter(t -> !covered.contains(t) && c.content().contains(t)).count();
                double score = gain + 1.0 / (i + 1);
                if (score > bestGain) { best = c; bestGain = score; }
            }
            if (best == null) break;
            selected.add(best);
            parents.add(best.parentChunkId() == null || best.parentChunkId().isBlank() ? best.chunkId() : best.parentChunkId());
            String content = best.content();
            terms.stream().filter(content::contains).forEach(covered::add);
        }
        // Answer expansion carries the full parent text: duplicate children add no
        // evidence and can bury a short, decisive table row. Keep search compatibility.
        if (contextTopK <= 5 && selected.size() < contextTopK) {
            for (RetrievalCandidate candidate : reranked) {
                if (!selected.contains(candidate)) selected.add(candidate);
                if (selected.size() >= contextTopK) break;
            }
        }
        return selected;
    }

    private String parentKey(RetrievalCandidate candidate) {
        return candidate.parentChunkId() == null || candidate.parentChunkId().isBlank()
                ? candidate.chunkId() : candidate.parentChunkId();
    }

    private Set<String> queryTerms(String query) {
        Set<String> terms = new HashSet<>();
        if (query == null) return terms;
        for (String run : query.split("[^\\p{IsHan}A-Za-z0-9]+"))
            for (int i = 0; i + 2 <= run.length(); i++) {
                String term = run.substring(i, i + 2);
                if (!Set.of("多少", "什么", "是否", "如何", "如果", "需要", "哪些", "同时", "项目").contains(term)) terms.add(term);
            }
        return terms;
    }

    private double aspectOverlap(String aspect, String content) {
        if (aspect == null || content == null || aspect.isBlank() || content.isBlank()) return 0.0;
        String normalizedAspect = aspect.replaceAll("多长时间|通常|如果|遇到|可以|需要|多久|多少|什么|是否|哪些|哪个|如何", "")
                .replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        String normalizedContent = content.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        if (normalizedAspect.length() < 2) return 0.0;
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 <= normalizedAspect.length(); i++)
            grams.add(normalizedAspect.substring(i, i + 2));
        long matches = grams.stream().filter(normalizedContent::contains).count();
        return matches / (double) grams.size();
    }
}
