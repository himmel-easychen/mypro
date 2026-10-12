package com.workpro.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceContextRerankerTest {
    @Test
    void preservesSameParentChildrenInProtectedMultiHopWindow() {
        var ranked = List.of(
                candidate("first", "方面甲", "parent-a", .9),
                candidate("second", "方面乙", "parent-a", .8),
                candidate("third", "方面丙", "parent-b", .7));

        var selected = new EvidenceContextReranker().selectContext("方面甲和方面乙", ranked, 3, true);

        assertEquals(List.of("first", "second", "third"),
                selected.stream().map(RetrievalContracts.RetrievalCandidate::chunkId).toList());
    }
    @Test
    void answerExpansionDoesNotPadWithRepeatedParents() {
        var hits = List.of(candidate("a", "设备", "parent-1", .9),
                candidate("b", "设备另一行", "parent-1", .8),
                candidate("c", "条件", "parent-2", .7));
        assertEquals(List.of("a", "c"), new EvidenceContextReranker()
                .selectContext("设备条件", hits, 10).stream()
                .map(RetrievalContracts.RetrievalCandidate::chunkId).toList());
    }
    @Test
    void answerExpansionIncludesUncoveredTopicInsteadOfDuplicateTopic() {
        var hits = new java.util.ArrayList<RetrievalContracts.RetrievalCandidate>();
        for (int i = 0; i < 9; i++) hits.add(candidate("c" + i, "质量保证期限", "p" + i, 1.0 - i * .01));
        hits.add(candidate("rescue", "现场修复故障三十分钟", "other", .1));
        var selected = new EvidenceContextReranker().selectContext("质量保证期限多久？现场修复故障多久？", hits, 6);
        assertEquals("rescue", selected.get(5).chunkId());
        assertEquals("c0", selected.get(0).chunkId());
    }
    @Test
    void selectsDiverseChunksAndSuppressesSameParentDuplicates() {
        var a = candidate("a", "项目名称是车辆维保", "parent-1", 0.9);
        var duplicate = candidate("b", "项目名称是车辆维保（重复）", "parent-1", 0.8);
        var c = candidate("c", "采购编号为 CG2700-2026", "parent-2", 0.7);
        var result = new EvidenceContextReranker().selectContext("项目名称和采购编号", List.of(a, duplicate, c), 2);
        assertEquals(List.of("a", "c"), result.stream().map(RetrievalContracts.RetrievalCandidate::chunkId).toList());
    }

    private static RetrievalContracts.RetrievalCandidate candidate(String id, String content, String parent, double score) {
        return new RetrievalContracts.RetrievalCandidate(id, parent, "doc", "source.pdf", content, "p1",
                Map.of("VECTOR", new RetrievalContracts.ProviderScore(1, score)), score,
                RetrievalContracts.CandidateStatus.FUSED);
    }
}
