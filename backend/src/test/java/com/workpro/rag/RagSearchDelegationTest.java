package com.workpro.rag;

import com.workpro.model.ModelServices;
import com.workpro.persistence.RagPersistence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
class RagSearchDelegationTest {
    @Test
    void searchUsesPgvectorRowsAsTheSemanticSource() {
        var properties = new ModelServices.Properties();
        properties.getEmbedding().setProvider("mock");
        properties.getEmbedding().setDimension(2);
        var embedding = new ModelServices.EmbeddingClient(properties) {
            @Override public double[] embed(String text) { return new double[]{0.1, 0.2}; }
        };
        var persistence = new RagPersistence(null) {
            @Override public java.util.List<VectorHit> searchByVector(double[] vector, int limit, String documentId, String model, int dimension) {
                return java.util.List.of(
                new RagPersistence.VectorHit("c1", "p1", "d1", "招标.pdf", "pdf", "车辆维修 | 2年", "page:52", "page:52", .9, "table", .87));
            }
        };
        var ocr = new OcrClient("http://127.0.0.1:8090", false, 100, 100, 1000);
        var service = new RagServices(embedding, persistence, ocr, .95);

        var result = service.search("车辆维修");

        assertEquals(1, result.size());
        assertEquals("page:52", result.get(0).location());
    }

    @org.junit.jupiter.api.Test
    void retrievalRequestDocumentFilterReachesPgvectorQuery() {
        var properties = new ModelServices.Properties();
        properties.getEmbedding().setProvider("mock");
        properties.getEmbedding().setDimension(2);
        var embedding = new ModelServices.EmbeddingClient(properties) {
            @Override public double[] embed(String text) { return new double[]{0.1, 0.2}; }
        };
        var seenDocument = new AtomicReference<String>();
        var persistence = new RagPersistence(null) {
            @Override public java.util.List<VectorHit> searchByVector(double[] vector, int limit, String documentId, String model, int dimension) {
                seenDocument.set(documentId);
                return java.util.List.of();
            }
        };
        var service = new RagServices(embedding, persistence,
                new OcrClient("http://127.0.0.1:8090", false, 100, 100, 1000), .95);
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public int update(String sql, Object... args) { return 1; }
            @Override public java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args) {
                return java.util.List.of();
            }
        };
        var adapter = new RetrievalServiceAdapter(service, jdbc, new ObjectMapper());

        adapter.retrieve(new RetrievalContracts.RetrievalRequest("车辆", "doc-42", null, 3, java.util.Map.of()));

        assertEquals("doc-42", seenDocument.get());
    }
}
