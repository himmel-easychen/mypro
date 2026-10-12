package com.workpro.rag;

import com.workpro.model.ModelServices;
import com.workpro.persistence.RagPersistence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalProviderTest {
    @Test
    void vectorProviderUsesTheFixedFirstVersionWindowAndPreservesRank() {
        var persistence = new RagPersistence(null) {
            @Override
            public List<VectorHit> searchByVector(double[] queryVector, int limit, String documentId, String model, int dimension) {
                assertEquals(20, limit);
                return List.of(new VectorHit(
                        "chunk-1", "parent-1", "doc-1", "source.pdf", "pdf", "content", "page:1",
                        "page:1", 0.99, "text", 0.88));
            }
        };
        var properties = new ModelServices.Properties();
        properties.getEmbedding().setProvider("mock");
        properties.getEmbedding().setDimension(3);
        var embedding = new ModelServices.EmbeddingClient(properties);
        var provider = new VectorRetrievalProvider(persistence, embedding);

        var result = provider.search("query", null, Map.of());

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).providerScores().get("VECTOR").rank());
        assertEquals(0.88, result.get(0).providerScores().get("VECTOR").score());
    }

    @Test
    void keywordAndExactProvidersUseIndependentProviderScores() {
        var persistence = new RagPersistence(null) {
            @Override
            public List<VectorHit> searchByKeyword(String query, int limit, String documentId) {
                assertEquals(20, limit);
                return List.of(new VectorHit("keyword", null, "doc", "a", "pdf", "x", "p1", "p1", 1, "text", 0.7));
            }

            @Override
            public List<VectorHit> searchByExact(String query, int limit, String documentId, Map<String, Object> filters) {
                assertEquals(20, limit);
                return List.of(new VectorHit("exact", null, "doc", "a", "pdf", "x", "p1", "p1", 1, "text", 1.0));
            }
        };

        var keyword = new KeywordRetrievalProvider(persistence).search("采购编号", null, Map.of());
        var exact = new ExactRetrievalProvider(persistence).search("CG2700", null, Map.of());

        assertEquals(1, keyword.size());
        assertEquals(1, keyword.get(0).providerScores().get("KEYWORD").rank());
        assertEquals(1, exact.size());
        assertEquals(1, exact.get(0).providerScores().get("EXACT").rank());
    }

    @Test
    void structuredProviderExtractsChineseAndNumericTermsForSqlRetrieval() {
        var terms = StructuredRetrievalProvider.extractTerms(
                "广州供电局2023-2024年综合类车辆维保项目合同对应发票合计金额为60.984532万元，另有一组发票累计金额为10.298780万元");

        assertTrue(terms.contains("2023-2024"));
        assertTrue(terms.contains("60.984532"));
        assertTrue(terms.contains("10.298780"));
        assertTrue(terms.contains("广州"));
        assertTrue(terms.contains("供电"));
    }

}
