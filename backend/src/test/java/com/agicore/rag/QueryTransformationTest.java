package com.agicore.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryTransformationTest {
    @Test
    void participationVerbIsNotSplitAtConjunctionCharacter() {
        String query = "参与设备采购时，交付与售后需要同时满足哪些条件？";
        var result = QueryTransformation.transform(query, RetrievalQueryProfile.from(query, Map.of()), Map.of());
        assertTrue(result.retrievalQueries().contains("参与设备采购时"));
        assertFalse(result.retrievalQueries().contains("设备采购时"));
    }
    @Test
    void laterQuestionsSurviveLongFirstQuestion() {
        String query = "甲类、乙类和丙类服务完成后，保修期限多久？现场发现故障时如何处理？";
        var result = QueryTransformation.transform(query, RetrievalQueryProfile.from(query, Map.of()), Map.of());
        assertTrue(result.retrievalQueries().stream().anyMatch(q -> q.contains("现场发现故障时如何处理")));
        assertTrue(result.retrievalQueries().get(0).contains("保修期限"));
    }
    @Test
    void alreadyCoveredSentencesDoNotReceiveDuplicateExpansionWeight() {
        String query = "设备发生重大损坏时，报修需要哪些资料？主管需要做哪些工作？";
        var result = QueryTransformation.transform(query, RetrievalQueryProfile.from(query, Map.of()), Map.of());
        assertEquals(List.of(result.normalizedQuery(), "设备发生重大损坏时", "报修需要哪些资料", "主管需要做哪些工作"), result.retrievalQueries());
    }
    @Test
    void primaryQueryPreservesAllConstraintsAndIgnoresScoringData() {
        String query = "根据附件2，2026年广州二类企业不少于5个工位且不允许外协时，费用是否高于深圳？另外服务期限是多少？";
        var profile = RetrievalQueryProfile.from(query, Map.of());
        var clean = QueryTransformation.transform(query, profile, Map.of());
        var polluted = QueryTransformation.transform(query, profile, Map.of(
                "expected", Map.of("answer", "伪造答案"), "gold_chunk", "禁止读取"));
        assertEquals(clean, polluted);
        String primary = clean.retrievalQueries().get(0);
        for (String constraint : List.of("附件2", "2026年", "广州", "二类", "不少于5个", "不允许外协", "高于深圳"))
            assertTrue(primary.contains(constraint));
        assertEquals(1.0, clean.retrievalQueryWeights().get(0));
    }

    @Test
    void directQuestionsAndExplicitFallbackKeepOneQuery() {
        String query = "广州合同金额是多少";
        assertEquals(List.of(query), QueryTransformation.transform(query,
                RetrievalQueryProfile.from(query, Map.of()), Map.of()).retrievalQueries());
        String multi = "广州金额是多少？深圳金额是多少？";
        var transformed = QueryTransformation.transform(multi, RetrievalQueryProfile.from(multi, Map.of()),
                Map.of("disable_query_transform", true));
        assertEquals(List.of(transformed.normalizedQuery()), transformed.retrievalQueries());
    }
    @Test
    void keepsFullQueryRankAheadOfDecomposedEvidenceRank() {
        var candidate = new RetrievalContracts.RetrievalCandidate(
                "chunk-1", null, null, "doc.pdf", "content", "p1",
                Map.of("VECTOR", new RetrievalContracts.ProviderScore(1, 0.99)),
                0.0, RetrievalContracts.CandidateStatus.RETRIEVED);

        assertEquals(1, RetrievalServiceAdapter.effectiveRank(candidate, 0, 1.0, 20));
        assertTrue(RetrievalServiceAdapter.effectiveRank(candidate, 1, 0.35, 20) > 1);
    }

    @Test
    void normalizesFormattingWithoutChangingMeaning() {
        var profile = RetrievalQueryProfile.from("", Map.of("query_type", "basic"));

        var transformed = QueryTransformation.transform("  项目名称：  是什么？ ", profile, Map.of());

        assertEquals("项目名称: 是什么?", transformed.normalizedQuery());
        assertEquals(1, transformed.retrievalQueries().size());
        assertEquals(List.of(1.0), transformed.retrievalQueryWeights());
        assertEquals("rewrite", transformed.strategy());
    }

    @Test
    void decomposesMultiHopQueryIntoIndependentEvidenceQueries() {
        var profile = RetrievalQueryProfile.from("", Map.of("query_type", "multi_hop"));

        var transformed = QueryTransformation.transform(
                "这个项目的全称是什么？采购编号是多少？服务期限是多久？", profile, Map.of());

        assertEquals("decompose", transformed.strategy());
        assertEquals(4, transformed.retrievalQueries().size());
        assertEquals(List.of(1.0, 0.35, 0.35, 0.35), transformed.retrievalQueryWeights());
        assertEquals(transformed.normalizedQuery(), transformed.retrievalQueries().get(0));
        assertTrue(transformed.retrievalQueries().stream().anyMatch(q -> q.contains("全称")));
        assertTrue(transformed.retrievalQueries().stream().anyMatch(q -> q.contains("采购编号")));
        assertTrue(transformed.retrievalQueries().stream().anyMatch(q -> q.contains("服务期限")));
    }

    @Test
    void doesNotDecomposeShortOrUnanswerableQueries() {
        var profile = RetrievalQueryProfile.from("", Map.of("query_type", "unanswerable"));

        var transformed = QueryTransformation.transform("是否有其他未提及的条款？", profile, Map.of());

        assertEquals("rewrite", transformed.strategy());
        assertEquals(1, transformed.retrievalQueries().size());
    }
}
