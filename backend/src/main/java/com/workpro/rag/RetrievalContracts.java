package com.workpro.rag;

import com.workpro.domain.Contracts.EvidenceRef;
import com.workpro.domain.Contracts.RetrievalResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

public final class RetrievalContracts {
    private RetrievalContracts() {}

    public record RetrievalRequest(String query, String docId, String chapterId, int topK,
                                   Map<String, Object> filters) {}

    public record RetrievalPack(List<RetrievalResult> results, List<EvidenceRef> evidenceRefs,
                                Map<String, Object> metadata) {}

    public interface RetrievalService {
        RetrievalPack retrieve(RetrievalRequest request);
    }

    public interface RetrievalProvider {
        List<RetrievalCandidate> search(String query, String documentId, Map<String, Object> filters);
    }

    public enum CandidateStatus {
          RETRIEVED,
          FUSED,
          RERANKED,
          EXPANDED
    }

    public record ProviderScore(int rank, double score) {
        public ProviderScore {
            if (rank < 1) {
                throw new IllegalArgumentException("provider rank must be positive");
            }
        }
    }

    public record RetrievalCandidate(
            String chunkId,
            String parentChunkId,
            String documentId,
            String sourceFile,
            String content,
            String location,
            Map<String, ProviderScore> providerScores,
            double finalScore,
            CandidateStatus status,
            Set<Integer> matchedQueryIndices) {

        public RetrievalCandidate(String chunkId, String parentChunkId, String documentId,
                                  String sourceFile, String content, String location,
                                  Map<String, ProviderScore> providerScores,
                                  double finalScore, CandidateStatus status) {
            this(chunkId, parentChunkId, documentId, sourceFile, content, location,
                    providerScores, finalScore, status, Set.of());
        }

        public RetrievalCandidate {
            if (chunkId == null || chunkId.isBlank()) {
                throw new IllegalArgumentException("chunkId is required");
            }
            Objects.requireNonNull(providerScores, "providerScores");
            Objects.requireNonNull(status, "status");
            providerScores = Map.copyOf(providerScores);
            matchedQueryIndices = matchedQueryIndices == null ? Set.of() : Set.copyOf(matchedQueryIndices);
        }

        public RetrievalCandidate merge(RetrievalCandidate other) {
            Objects.requireNonNull(other, "other");
            if (!chunkId.equals(other.chunkId)) {
                throw new IllegalArgumentException("cannot merge different chunks");
            }
            Map<String, ProviderScore> mergedScores = new LinkedHashMap<>(providerScores);
            mergedScores.putAll(other.providerScores);
            return new RetrievalCandidate(
                    chunkId,
                    firstNonBlank(parentChunkId, other.parentChunkId),
                    firstNonBlank(documentId, other.documentId),
                    firstNonBlank(sourceFile, other.sourceFile),
                    firstNonBlank(content, other.content),
                    firstNonBlank(location, other.location),
                    mergedScores,
                    Math.max(finalScore, other.finalScore),
                    CandidateStatus.FUSED,
                    union(matchedQueryIndices, other.matchedQueryIndices));
        }

        public RetrievalCandidate withFinalScore(double score, CandidateStatus nextStatus) {
            return new RetrievalCandidate(chunkId, parentChunkId, documentId, sourceFile, content,
                    location, providerScores, score, nextStatus, matchedQueryIndices);
        }

        public RetrievalCandidate withMatchedQueryIndex(int queryIndex) {
            Set<Integer> indices = new LinkedHashSet<>(matchedQueryIndices);
            indices.add(queryIndex);
            return new RetrievalCandidate(chunkId, parentChunkId, documentId, sourceFile, content,
                    location, providerScores, finalScore, status, indices);
        }

        public RetrievalCandidate withMatchedQueryIndices(Set<Integer> queryIndices) {
            return new RetrievalCandidate(chunkId, parentChunkId, documentId, sourceFile, content,
                    location, providerScores, finalScore, status, union(matchedQueryIndices, queryIndices));
        }

        private static Set<Integer> union(Set<Integer> left, Set<Integer> right) {
            Set<Integer> values = new LinkedHashSet<>();
            if (left != null) values.addAll(left);
            if (right != null) values.addAll(right);
            return values;
        }

        private static String firstNonBlank(String left, String right) {
            return left != null && !left.isBlank() ? left : right;
        }
    }
}
