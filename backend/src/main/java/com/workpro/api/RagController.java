package com.workpro.api;

import com.workpro.domain.Contracts.RetrievalResult;
import com.workpro.rag.RagServices;
import com.workpro.rag.RetrievalContracts;
import com.workpro.rag.RetrievalServiceAdapter;
import com.workpro.rag.ElasticsearchIndexService;
import com.workpro.dialogue.RagPromptBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final RagServices ragServices;
    private final RetrievalServiceAdapter retrievalService;
    private final ElasticsearchIndexService elasticsearchIndexService;
    private final RagPromptBuilder promptBuilder;

    public RagController(RagServices ragServices, RetrievalServiceAdapter retrievalService,
                         ElasticsearchIndexService elasticsearchIndexService,
                         RagPromptBuilder promptBuilder) {
        this.ragServices = ragServices;
        this.retrievalService = retrievalService;
        this.elasticsearchIndexService = elasticsearchIndexService;
        this.promptBuilder = promptBuilder;
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam MultipartFile file) throws IOException {
        Map<String, Object> result = ragServices.ingest(file);
        if (!Boolean.TRUE.equals(result.get("deduplicated"))) {
            ragServices.persist((String) result.get("doc_id"));
        }
        return result;
    }

    @GetMapping("/search")
    public Object search(@RequestParam String query,
                                        @RequestParam(defaultValue = "8") int topK,
                                        @RequestParam(required = false) String documentId,
                                        @RequestParam(defaultValue = "auto") String mode,
                                        @RequestParam(defaultValue = "false") boolean disableQueryTransform,
                                        @RequestParam(defaultValue = "false") boolean diagnostics,
                                        @RequestParam(defaultValue = "false") boolean answerContext) {
        Map<String, Object> filters = new java.util.LinkedHashMap<>();
        filters.put("mode", mode);
        if (disableQueryTransform) {
            filters.put("disable_query_transform", true);
        }
        if (answerContext) {
            filters.put("answer_context", true);
        }
        var pack = retrievalService.retrieve(new RetrievalContracts.RetrievalRequest(
                query, documentId, null, topK, filters));
        if (diagnostics) {
            var prompt = answerContext ? promptBuilder.build(query, List.of(), pack) : null;
            return Map.of("results", pack.results(), "diagnostics", pack.metadata().get("stage_candidates"),
                    "trace", pack.metadata().get("trace"),
                    "query_coverage", pack.metadata().get("query_coverage"),
                    "targeted_query_indices", pack.metadata().get("targeted_query_indices"),
                    "answer_candidates", pack.metadata().getOrDefault("answer_candidates", List.of()),
                    "parent_context", pack.metadata().getOrDefault("parent_context", List.of()),
                    "prompt_evidence", prompt == null ? List.of() : prompt.evidence(),
                    "prompt_chars", prompt == null ? 0 : prompt.text().length());
        }
        return pack.results();
    }

    @GetMapping("/elasticsearch/health")
    public Map<String, Object> elasticsearchHealth() {
        return elasticsearchIndexService.health();
    }

    @PostMapping("/elasticsearch/reindex")
    public Map<String, Object> reindexElasticsearch() {
        var result = elasticsearchIndexService.rebuildFromDatabase();
        return Map.of("requested", result.requested(), "indexed", result.indexed(),
                "failed", result.failed(), "errors", result.errors());
    }

    @GetMapping("/embedding-audit")
    public Map<String, Object> embeddingAudit() {
        var audit = ragServices.embeddingAudit();
        return Map.of(
                "total", audit.total(),
                "native_vectors", audit.nativeVectors(),
                "production_vectors", audit.productionVectors(),
                "legacy_vectors", audit.legacyVectors(),
                "reindex_required", audit.reindexRequired());
    }

    @PostMapping("/embedding-reindex")
    public Map<String, Object> reindexEmbeddings(
            @RequestParam(defaultValue = "0") int limit) {
        var result = ragServices.reindexEmbeddings(limit);
        return Map.of(
                "requested", result.requested(),
                "succeeded", result.succeeded(),
                "failed", result.failed(),
                "errors", result.errors());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "FILE_TOO_LARGE",
                "message", "文件超过 100 MB 上传限制"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> serviceUnavailable(IllegalStateException exception) {
        boolean busy = exception.getMessage() != null && exception.getMessage().startsWith("R1005");
        return ResponseEntity.status(busy ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", busy ? "OCR_BUSY" : "OCR_UNAVAILABLE",
                        "message", exception.getMessage() == null ? "OCR 服务不可用" : exception.getMessage()));
    }
}
