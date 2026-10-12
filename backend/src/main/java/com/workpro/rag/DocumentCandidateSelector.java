package com.workpro.rag;

import com.workpro.rag.RetrievalContracts.CandidateStatus;
import com.workpro.rag.RetrievalContracts.RetrievalCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative document-first stage built from the enlarged provider pool. */
public class DocumentCandidateSelector {
    public List<RetrievalCandidate> select(String query, List<RetrievalCandidate> candidates, int maxDocuments) {
        if (candidates == null || candidates.isEmpty() || maxDocuments < 1) return List.of();
        Set<String> grams = grams(normalize(query));
        Map<String, DocScore> docs = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate candidate = candidates.get(i);
            String doc = documentKey(candidate);
            DocScore score = docs.computeIfAbsent(doc, ignored -> new DocScore());
            score.bestRankSignal = Math.max(score.bestRankSignal, 1.0 / (i + 1));
            score.providerAgreement = Math.max(score.providerAgreement,
                    Math.min(3, candidate.providerScores().size()) / 3.0);
            String source = normalize(candidate.sourceFile());
            long sourceMatches = grams.stream().filter(source::contains).count();
            score.sourceAgreement = Math.max(score.sourceAgreement,
                    grams.isEmpty() ? 0.0 : sourceMatches / (double) grams.size());
        }
        List<String> selectedDocs = docs.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, DocScore>>comparingDouble(e -> e.getValue().total()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(maxDocuments).map(Map.Entry::getKey).toList();
        Set<String> allowed = new LinkedHashSet<>(selectedDocs);
        List<RetrievalCandidate> selected = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            if (!allowed.contains(documentKey(candidate))) continue;
            double documentBonus = docs.get(documentKey(candidate)).bonus();
            selected.add(candidate.withFinalScore(candidate.finalScore() + documentBonus, CandidateStatus.FUSED));
        }
        return selected;
    }

    private static final class DocScore {
        double bestRankSignal;
        double providerAgreement;
        double sourceAgreement;

        double total() { return bestRankSignal + providerAgreement * 0.20 + sourceAgreement * 0.80; }
        double bonus() { return Math.min(0.06, providerAgreement * 0.01 + sourceAgreement * 0.08); }
    }

    private static Set<String> grams(String value) {
        String han = value.replaceAll("[^\\p{IsHan}]", "");
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i + 4 <= han.length(); i++) {
            String gram = han.substring(i, i + 4);
            if (!gram.contains("什么") && !gram.contains("多少") && !gram.contains("项目")) result.add(gram);
        }
        return result;
    }

    private static String documentKey(RetrievalCandidate candidate) {
        return candidate.documentId() == null || candidate.documentId().isBlank()
                ? "chunk:" + candidate.chunkId() : candidate.documentId();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
