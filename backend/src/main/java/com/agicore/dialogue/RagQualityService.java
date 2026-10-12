package com.agicore.dialogue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RagQualityService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RagQualityService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record AnswerRequest(String query, String documentId, String chapterId, List<Object> history,
                                boolean disableQueryTransform) {}
    public record FeedbackRequest(String traceId, String userId, String rating, String category,
                                  String comment, Map<String, Object> metadata) {}
    public record ReviewRequest(String traceId, String reviewer, Boolean requiredFactsCovered,
                                Boolean numericUnitCorrect, Boolean citationSupported, Boolean shouldRefuse,
                                String errorType, String notes, Map<String, Object> metadata) {}
    public record ReplayRequest(String traceId, Boolean disableQueryTransform) {}
    public record ReplaySeed(String traceId, String query, String documentId, String chapterId,
                             List<Object> history, boolean disableQueryTransform) {}

    public String recordAnswer(AnswerRequest request, AnswerService.Answer answer) {
        String traceId = traceId(answer).orElse(UUID.randomUUID().toString());
        jdbc.update("insert into rag_answer_trace(trace_id,query,document_id,chapter_id,history_json,disable_query_transform,status,error_code,answer,refusal,refusal_reason,citations_json,retrieved_chunk_ids_json,evidence_json,retrieval_json,prompt,raw_response,latency_ms,model_latency_ms,validation_json) " +
                        "values(?::uuid,?,?::uuid,?,?::jsonb,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?::jsonb) " +
                        "on conflict(trace_id) do update set status=excluded.status,error_code=excluded.error_code,answer=excluded.answer,refusal=excluded.refusal,refusal_reason=excluded.refusal_reason,citations_json=excluded.citations_json,retrieved_chunk_ids_json=excluded.retrieved_chunk_ids_json,evidence_json=excluded.evidence_json,retrieval_json=excluded.retrieval_json,prompt=excluded.prompt,raw_response=excluded.raw_response,latency_ms=excluded.latency_ms,model_latency_ms=excluded.model_latency_ms,validation_json=excluded.validation_json",
                traceId,
                request.query(),
                blankToNull(request.documentId()),
                request.chapterId(),
                json(request.history() == null ? List.of() : request.history()),
                request.disableQueryTransform(),
                answer.status(),
                answer.error(),
                answer.answer(),
                answer.refusal(),
                answer.refusalReason(),
                json(answer.citations()),
                json(answer.retrievedChunkIds()),
                json(answer.evidence()),
                json(answer.retrieval()),
                answer.prompt(),
                answer.rawResponse(),
                answer.latencyMs(),
                answer.modelLatencyMs(),
                json(validation(answer)));
        return traceId;
    }

    public Map<String, Object> recordFeedback(FeedbackRequest request) {
        requireTraceId(request.traceId());
        String rating = Objects.requireNonNullElse(request.rating(), "").strip();
        if (rating.isBlank()) throw new IllegalArgumentException("EMPTY_RATING");
        UUID feedbackId = UUID.randomUUID();
        jdbc.update("insert into rag_answer_feedback(feedback_id,trace_id,user_id,rating,category,comment,metadata_json) values(?,?::uuid,?,?,?,?,?::jsonb)",
                feedbackId, request.traceId(), request.userId(), rating, request.category(), request.comment(), json(nullToEmpty(request.metadata())));
        return Map.of("feedbackId", feedbackId.toString(), "traceId", request.traceId(), "status", "recorded");
    }

    public Map<String, Object> recordReview(ReviewRequest request) {
        requireTraceId(request.traceId());
        UUID reviewId = UUID.randomUUID();
        jdbc.update("insert into rag_answer_audit_review(review_id,trace_id,reviewer,required_facts_covered,numeric_unit_correct,citation_supported,should_refuse,error_type,notes,metadata_json) values(?,?::uuid,?,?,?,?,?,?,?,?::jsonb)",
                reviewId, request.traceId(), request.reviewer(), request.requiredFactsCovered(), request.numericUnitCorrect(),
                request.citationSupported(), request.shouldRefuse(), request.errorType(), request.notes(), json(nullToEmpty(request.metadata())));
        return Map.of("reviewId", reviewId.toString(), "traceId", request.traceId(), "status", "recorded");
    }

    public Map<String, Object> trace(String traceId) {
        requireTraceId(traceId);
        return jdbc.query("select trace_id::text,query,document_id::text,chapter_id,history_json::text,disable_query_transform,status,error_code,answer,refusal,refusal_reason,citations_json::text,retrieved_chunk_ids_json::text,evidence_json::text,retrieval_json::text,latency_ms,model_latency_ms,created_at::text from rag_answer_trace where trace_id=?::uuid",
                ps -> ps.setString(1, traceId), rs -> {
                    if (!rs.next()) throw new NoSuchElementException("TRACE_NOT_FOUND");
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("traceId", rs.getString(1));
                    out.put("query", rs.getString(2));
                    out.put("documentId", rs.getString(3));
                    out.put("chapterId", rs.getString(4));
                    out.put("history", read(rs.getString(5), List.class));
                    out.put("disableQueryTransform", rs.getBoolean(6));
                    out.put("status", rs.getString(7));
                    out.put("error", rs.getString(8));
                    out.put("answer", rs.getString(9));
                    out.put("refusal", rs.getBoolean(10));
                    out.put("refusalReason", rs.getString(11));
                    out.put("citations", read(rs.getString(12), List.class));
                    out.put("retrievedChunkIds", read(rs.getString(13), List.class));
                    out.put("evidence", read(rs.getString(14), List.class));
                    out.put("retrieval", read(rs.getString(15), Map.class));
                    out.put("latencyMs", rs.getLong(16));
                    out.put("modelLatencyMs", rs.getLong(17));
                    out.put("createdAt", rs.getString(18));
                    out.put("feedback", feedback(traceId));
                    out.put("reviews", reviews(traceId));
                    return out;
                });
    }

    public ReplaySeed replaySeed(ReplayRequest request) {
        requireTraceId(request.traceId());
        return jdbc.query("select trace_id::text,query,document_id::text,chapter_id,history_json::text,disable_query_transform from rag_answer_trace where trace_id=?::uuid",
                ps -> ps.setString(1, request.traceId()), rs -> {
                    if (!rs.next()) throw new NoSuchElementException("TRACE_NOT_FOUND");
                    boolean disable = request.disableQueryTransform() == null ? rs.getBoolean(6) : request.disableQueryTransform();
                    return new ReplaySeed(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), read(rs.getString(5), List.class), disable);
                });
    }

    private List<Map<String, Object>> feedback(String traceId) {
        return jdbc.query("select feedback_id::text,user_id,rating,category,comment,metadata_json::text,created_at::text from rag_answer_feedback where trace_id=?::uuid order by created_at",
                (rs, row) -> Map.of("feedbackId", rs.getString(1), "userId", nullable(rs.getString(2)), "rating", rs.getString(3),
                        "category", nullable(rs.getString(4)), "comment", nullable(rs.getString(5)), "metadata", read(rs.getString(6), Map.class), "createdAt", rs.getString(7)), traceId);
    }

    private List<Map<String, Object>> reviews(String traceId) {
        return jdbc.query("select review_id::text,reviewer,required_facts_covered,numeric_unit_correct,citation_supported,should_refuse,error_type,notes,metadata_json::text,created_at::text from rag_answer_audit_review where trace_id=?::uuid order by created_at",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reviewId", rs.getString(1)); m.put("reviewer", rs.getString(2));
                    m.put("requiredFactsCovered", (Boolean) rs.getObject(3)); m.put("numericUnitCorrect", (Boolean) rs.getObject(4));
                    m.put("citationSupported", (Boolean) rs.getObject(5)); m.put("shouldRefuse", (Boolean) rs.getObject(6));
                    m.put("errorType", rs.getString(7)); m.put("notes", rs.getString(8));
                    m.put("metadata", read(rs.getString(9), Map.class)); m.put("createdAt", rs.getString(10));
                    return m;
                }, traceId);
    }

    private Optional<String> traceId(AnswerService.Answer answer) {
        Object value = answer.retrieval() == null ? null : answer.retrieval().get("trace_id");
        return value == null || value.toString().isBlank() ? Optional.empty() : Optional.of(value.toString());
    }

    private Map<String, Object> validation(AnswerService.Answer answer) {
        return Map.of("answerPresent", answer.answer() != null && !answer.answer().isBlank(),
                "citationIdMapping", answer.error() == null || !"INVALID_CITATIONS".equals(answer.error()),
                "unitValidation", answer.error() == null || !"INVALID_ANSWER_UNITS".equals(answer.error()));
    }

    private void requireTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) throw new IllegalArgumentException("EMPTY_TRACE_ID");
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("JSON_ENCODING_FAILED", e); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value == null || value.isBlank() ? (Map.class.equals(type) ? "{}" : "[]") : value, type); }
        catch (Exception e) { throw new IllegalArgumentException("JSON_DECODING_FAILED", e); }
    }
    private Map<String, Object> nullToEmpty(Map<String, Object> value) { return value == null ? Map.of() : value; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private Object nullable(Object value) { return value == null ? "" : value; }
}
