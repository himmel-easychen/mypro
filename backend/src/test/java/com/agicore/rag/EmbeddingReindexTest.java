package com.agicore.rag;

import com.agicore.model.ModelServices;
import com.agicore.persistence.RagPersistence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingReindexTest {
    @Test
    void reindexEmbeddingsOnlyWritesCandidatesAndReportsFailures() {
        var properties = new ModelServices.Properties();
        properties.getEmbedding().setProvider("dashscope");
        properties.getEmbedding().setModel("qwen3.7-text-embedding");
        properties.getEmbedding().setDimension(2);
        var embedding = new ModelServices.EmbeddingClient(properties) {
            @Override public double[] embed(String text) { return new double[]{1.0, 0.0}; }
            @Override public List<double[]> embedBatch(List<String> texts) {
                return texts.stream().map(text -> new double[]{1.0, 0.0}).toList();
            }
        };
        AtomicInteger writes = new AtomicInteger();
        var persistence = new RagPersistence(null) {
            @Override public List<ReindexChunk> loadChunksForReindex(String model, int dimension, int limit) {
                return List.of(new ReindexChunk("chunk-1", "doc-1", "维修车辆"));
            }

            @Override public void upsertEmbedding(String chunkId, double[] vector, String model,
                                                   int dimension, String provider) {
                writes.incrementAndGet();
            }
        };
        var service = new RagServices(embedding, persistence,
                new OcrClient("http://127.0.0.1:8090", false, 100, 100, 1000), .95);

        var result = service.reindexEmbeddings(10);

        assertEquals(1, result.requested());
        assertEquals(1, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(1, writes.get());
    }
}
