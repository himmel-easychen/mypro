package com.workpro.rag;

import com.workpro.rag.RetrievalContracts.RetrievalCandidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Promotes at most two high-confidence aspect representatives into Top-5. */
public class MultiHopCoverageSelector {
    private static final double MIN_ASPECT_COVERAGE = 0.20;
    private static final Set<String> WEAK = Set.of(
            "多少", "什么", "是否", "如何", "如果", "需要", "哪些", "同时", "项目", "分别", "对应");
    private static final List<String> ASPECT_FILLERS = List.of(
            "多长时间", "通常", "如果", "遇到", "可以", "需要", "多久", "多少", "什么", "是否", "哪些", "哪个", "如何");

    public List<RetrievalCandidate> select(List<String> retrievalQueries,
                                           List<RetrievalCandidate> ranked,
                                           int protectedTopK) {
        if (ranked == null || ranked.isEmpty() || protectedTopK < 2) return ranked == null ? List.of() : ranked;
        List<String> aspects = retrievalQueries == null ? List.of() : retrievalQueries.stream()
                .skip(1).map(MultiHopCoverageSelector::normalize)
                .filter(q -> q.length() >= 4).distinct().toList();
        if (aspects.size() < 2) return ranked;
        int representativeLimit = Math.min(Math.min(protectedTopK, 3), aspects.size());

        List<RetrievalCandidate> representatives = new ArrayList<>();
        Set<String> representedParents = new HashSet<>();
        for (String aspect : aspects) {
            RetrievalCandidate best = null;
            double bestScore = 0.0;
            for (int rank = 0; rank < ranked.size(); rank++) {
                RetrievalCandidate candidate = ranked.get(rank);
                String parent = parentKey(candidate);
                if (representedParents.contains(parent)) continue;
                double match = aspectMatch(aspect, normalize(candidate.content()));
                // Short evidence chunks often state a sub-answer in fewer words
                // than the natural-language query aspect. Require meaningful
                // overlap, but do not reject those concise evidence rows.
                if (match < MIN_ASPECT_COVERAGE) continue;
                double score = match + 0.08 / (rank + 1);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best != null) {
                representatives.add(best);
                representedParents.add(parentKey(best));
            }
            if (representatives.size() == representativeLimit) break;
        }
        if (representatives.size() < 2) return ranked;

        int window = Math.min(protectedTopK, ranked.size());
        LinkedHashSet<RetrievalCandidate> top = new LinkedHashSet<>(ranked.subList(0, window));
        for (RetrievalCandidate representative : representatives) {
            if (top.contains(representative)) continue;
            RetrievalCandidate evicted = null;
            for (int i = window - 1; i >= 1; i--) {
                RetrievalCandidate candidate = ranked.get(i);
                if (!representatives.contains(candidate)) { evicted = candidate; break; }
            }
            if (evicted != null) {
                top.remove(evicted);
                top.add(representative);
            }
        }
        List<RetrievalCandidate> result = new ArrayList<>(top);
        for (RetrievalCandidate candidate : ranked) if (!result.contains(candidate)) result.add(candidate);
        return result;
    }

    private static double aspectMatch(String aspect, String content) {
        List<String> grams = grams(aspect);
        if (grams.size() < 2 || content.isBlank()) return 0.0;
        long matches = grams.stream().filter(content::contains).count();
        if (matches < 2) return 0.0;
        return matches / (double) grams.size();
    }

    private static List<String> grams(String value) {
        String focused = value;
        for (String filler : ASPECT_FILLERS) focused = focused.replace(filler, "");
        String han = focused.replaceAll("[^\\p{IsHan}]", "");
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= han.length(); i++) {
            String gram = han.substring(i, i + 2);
            if (!WEAK.contains(gram)) grams.add(gram);
        }
        return List.copyOf(grams);
    }

    private static String parentKey(RetrievalCandidate candidate) {
        return candidate.parentChunkId() == null || candidate.parentChunkId().isBlank()
                ? candidate.chunkId() : candidate.parentChunkId();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
