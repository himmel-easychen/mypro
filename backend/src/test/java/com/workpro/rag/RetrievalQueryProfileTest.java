package com.workpro.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalQueryProfileTest {
    @Test
    void answerContextUsesWiderFusionPoolForPromptEvidence() {
        var search = RetrievalQueryProfile.from("项目名称是什么", Map.of());
        var answer = RetrievalQueryProfile.from("项目名称是什么", Map.of("answer_context", true));
        assertEquals(search.providerTopK(), answer.providerTopK());
        assertEquals(12, search.fusionTopK());
        assertEquals(30, answer.fusionTopK());
        assertEquals(5, search.contextTopK());
        assertEquals(10, answer.contextTopK());
    }
    @Test
    void multiHopUsesExpandedCandidatesAndStructuredIsOptional() {
        var profile = RetrievalQueryProfile.from("结合项目名称和采购编号回答", Map.of("query_type", "multi_hop"));
        assertEquals(RetrievalQueryProfile.Intent.MULTI_HOP, profile.intent());
        assertEquals(50, profile.providerTopK());
        assertEquals(50, profile.fusionTopK());
        assertEquals(5, profile.contextTopK());
        assertTrue(profile.structuredEnabled());
    }

    @Test
    void basicFactUsesTwelveAndDoesNotCallStructured() {
        var profile = RetrievalQueryProfile.from("项目名称是什么", Map.of());
        assertEquals(RetrievalQueryProfile.Intent.BASIC, profile.intent());
        assertEquals(12, profile.providerTopK());
        assertFalse(profile.structuredEnabled());
    }

    @Test
    void compareUsesExpandedCandidatesAndStructuredTwenty() {
        var profile = RetrievalQueryProfile.from("比较两个区域的预计采购金额谁更高", Map.of());
        assertEquals(RetrievalQueryProfile.Intent.COMPARE, profile.intent());
        assertEquals(50, profile.providerTopK());
        assertEquals(20, profile.structuredTopK());
        assertTrue(profile.structuredEnabled());
    }

    @Test
    void identifiesNaturalMultiQuestionWithoutHarnessLabels() {
        var profile = RetrievalQueryProfile.from("金额是多少？服务期限又是多久？", Map.of());

        assertEquals(RetrievalQueryProfile.Intent.MULTI_HOP, profile.intent());
    }

    @Test
    void identifiesMultipleEntitiesSharingOneInterrogative() {
        var profile = RetrievalQueryProfile.from(
                "人民医院和花东镇政府的合同金额分别是多少？", Map.of());

        assertEquals(RetrievalQueryProfile.Intent.MULTI_HOP, profile.intent());
        assertEquals(50, profile.providerTopK());
    }

    @Test
    void routesMultipleAmountsWithArithmeticRequestAsMultiHop() {
        var profile = RetrievalQueryProfile.from(
                "合同金额为60.984532万元，另一笔为10.298780万元，两笔加起来是多少？", Map.of());
        assertEquals(RetrievalQueryProfile.Intent.MULTI_HOP, profile.intent());
        assertEquals(50, profile.providerTopK());
    }
}
