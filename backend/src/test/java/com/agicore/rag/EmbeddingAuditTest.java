package com.agicore.rag;

import com.agicore.persistence.RagPersistence;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingAuditTest {
    @Test
    void auditReportsProductionAndLegacyEmbeddingCounts() {
        var audit = RagPersistence.EmbeddingAudit.from(Map.of(
                "total", 1688L, "native_vectors", 447L,
                "production_vectors", 447L, "legacy_vectors", 1241L,
                "reindex_required", 1241L));

        assertEquals(1688, audit.total());
        assertEquals(447, audit.productionVectors());
        assertEquals(1241, audit.reindexRequired());
    }

    @Test
    void ragServicesExposesThePersistenceAudit() {
        var properties = new com.agicore.model.ModelServices.Properties();
        properties.getEmbedding().setProvider("mock");
        properties.getEmbedding().setDimension(2);
        var persistence = new RagPersistence(null) {
            @Override public EmbeddingAudit embeddingAudit(String model, int dimension) {
                return new EmbeddingAudit(4, 2, 2, 2, 2);
            }
        };
        var services = new RagServices(
                new com.agicore.model.ModelServices.EmbeddingClient(properties), persistence,
                new OcrClient("http://127.0.0.1:8090", false, 100, 100, 1000), .95);

        assertEquals(2, services.embeddingAudit().reindexRequired());
    }
}
