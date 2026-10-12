package com.workpro.rag;

import com.workpro.rag.RetrievalContracts.CandidateStatus;
import com.workpro.rag.RetrievalContracts.ProviderScore;
import com.workpro.rag.RetrievalContracts.RetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentCandidateSelectorTest {
    @Test
    void selectsRelevantDocumentBeforeChunkRanking() {
        var selector = new DocumentCandidateSelector();
        var generic = candidate("a", "generic", "其他区域材料.docx", 0.05, 1);
        var target = candidate("b", "target", "花都区域一般车辆维保服务承诺.docx", 0.04, 2);

        var selected = selector.select("花都区域一般车辆维保服务承诺期限", List.of(generic, target), 1);

        assertEquals(List.of("b"), selected.stream().map(RetrievalCandidate::chunkId).toList());
    }

    private static RetrievalCandidate candidate(String id, String doc, String source, double score, int rank) {
        return new RetrievalCandidate(id, null, doc, source, "期限条款", "p1",
                Map.of("VECTOR", new ProviderScore(rank, score)), score, CandidateStatus.FUSED);
    }
}
