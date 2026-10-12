package com.agicore.rag;

import com.agicore.persistence.RagPersistence;
import com.agicore.model.ModelServices;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchPackTest {
    @Test
    void retrievalPackCarriesStableParentAndEvidenceMetadata() {
        var properties = new ModelServices.Properties();
        properties.getEmbedding().setProvider("mock");
        properties.getEmbedding().setDimension(2);
        var embedding = new ModelServices.EmbeddingClient(properties) {
            @Override public double[] embed(String text) { return new double[]{1, 0}; }
        };
        var persistence = new RagPersistence(null) {
            @Override public java.util.List<VectorHit> searchByVector(double[] vector, int limit, String documentId, String model, int dimension) {
                return java.util.List.of(new VectorHit("child-1", "parent-1", "doc-1", "source.pdf", "pdf",
                        "维修车辆 | 2年", "page:52", "page:52", .95, "table", .9));
            }
        };
        var service = new RagServices(embedding, persistence,
                new OcrClient("http://127.0.0.1:8090", false, 100, 100, 1000), .95);
        var result = service.search("维修车辆");
        assertEquals("parent-1", result.get(0).parent_chunk_id());
        assertEquals("page:52", result.get(0).location());
    }

    @Test
    void retrievalAdapterExpandsParentContextFromDatabase() {
        var properties = new ModelServices.Properties();
        properties.getEmbedding().setProvider("mock");
        properties.getEmbedding().setDimension(2);
        var embedding = new ModelServices.EmbeddingClient(properties) {
            @Override public double[] embed(String text) { return new double[]{1, 0}; }
        };
        var persistence = new RagPersistence(null) {
            @Override public java.util.List<VectorHit> searchByVector(double[] vector, int limit, String documentId, String model, int dimension) {
                return java.util.List.of(new VectorHit("child-1", "parent-1", "doc-1", "source.pdf", "pdf",
                        "维修车辆 | 2年", "page:52", "page:52", .95, "table", .9));
            }
        };
        var service = new RagServices(embedding, persistence,
                new OcrClient("http://127.0.0.1:8090", false, 100, 100, 1000), .95);
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public int update(String sql, Object... args) { return 1; }
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return List.of(Map.of("chunk_id", "parent-1", "document_id", "doc-1",
                        "source_file", "source.pdf", "source_type", "pdf",
                        "content", "完整父块上下文", "location", "document"));
            }
        };
        var adapter = new RetrievalServiceAdapter(service, jdbc, new ObjectMapper());

        var pack = adapter.retrieve(new RetrievalContracts.RetrievalRequest(
                "维修车辆", null, null, 5, Map.of()));

        @SuppressWarnings("unchecked")
        var parents = (Map<String, Map<String, Object>>) pack.metadata().get("parent_context");
        assertEquals("完整父块上下文", parents.get("parent-1").get("content"));
    }
}
