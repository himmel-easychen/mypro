package com.workpro.rag;

import com.workpro.rag.RetrievalContracts.CandidateStatus;
import com.workpro.rag.RetrievalContracts.ProviderScore;
import com.workpro.rag.RetrievalContracts.RetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiHopCoverageSelectorTest {
    @Test
    void promotesTwoDistinctHighConfidenceAspectsWithoutMovingTopOne() {
        List<RetrievalCandidate> ranked = new ArrayList<>();
        ranked.add(candidate("first", "p1", "车辆维修项目服务说明"));
        ranked.add(candidate("noise2", "p2", "通用设备清单"));
        ranked.add(candidate("noise3", "p3", "其他项目说明"));
        ranked.add(candidate("service", "p4", "服务保障达到A级要求并提供应急响应"));
        ranked.add(candidate("noise5", "p5", "普通合同内容"));
        ranked.add(candidate("compliance", "p6", "投标合规要求独立法人不得联合投标"));

        var result = new MultiHopCoverageSelector().select(
                List.of("投标合规和服务保障需要哪些条件", "投标合规", "服务保障需要哪些条件"), ranked, 5);

        assertEquals("first", result.get(0).chunkId());
        assertTrue(result.subList(0, 5).stream().anyMatch(c -> c.chunkId().equals("compliance")));
        assertTrue(result.subList(0, 5).stream().anyMatch(c -> c.chunkId().equals("service")));
        assertEquals(6, result.size());
    }

    @Test
    void doesNotForceWeakAspectMatches() {
        var ranked = List.of(candidate("first", "p1", "车辆维修项目"), candidate("second", "p2", "普通合同"));
        assertEquals(ranked, new MultiHopCoverageSelector().select(
                List.of("甲和乙分别是什么", "甲", "乙分别是什么"), ranked, 5));
    }

    private static RetrievalCandidate candidate(String id, String parent, String content) {
        return new RetrievalCandidate(id, parent, null, "source.pdf", content, "p1",
                Map.of("VECTOR", new ProviderScore(1, 0.1)), 0.1, CandidateStatus.FUSED);
    }
}
