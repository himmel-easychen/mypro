package com.workpro.tender.service;

import com.workpro.tender.domain.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class TenderWorkflowService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final Map<TenderTaskStatus, Set<TenderTaskStatus>> ALLOWED = Map.ofEntries(
            entry(TenderTaskStatus.CREATED, TenderTaskStatus.ANALYZING),
            entry(TenderTaskStatus.ANALYZING, TenderTaskStatus.ANALYSIS_READY, TenderTaskStatus.BLOCKED, TenderTaskStatus.FAILED),
            entry(TenderTaskStatus.ANALYSIS_READY, TenderTaskStatus.AWAITING_CONFIRMATION, TenderTaskStatus.COLLABORATING, TenderTaskStatus.GENERATING),
            entry(TenderTaskStatus.AWAITING_CONFIRMATION, TenderTaskStatus.COLLABORATING, TenderTaskStatus.BLOCKED, TenderTaskStatus.CANCELLED),
            entry(TenderTaskStatus.COLLABORATING, TenderTaskStatus.COLLABORATION_READY, TenderTaskStatus.AWAITING_CONFIRMATION, TenderTaskStatus.BLOCKED),
            entry(TenderTaskStatus.COLLABORATION_READY, TenderTaskStatus.GENERATING),
            entry(TenderTaskStatus.GENERATING, TenderTaskStatus.REVIEWING, TenderTaskStatus.BLOCKED, TenderTaskStatus.FAILED),
            entry(TenderTaskStatus.REVIEWING, TenderTaskStatus.EXPORTING, TenderTaskStatus.REVISION_REQUIRED, TenderTaskStatus.AWAITING_CONFIRMATION, TenderTaskStatus.BLOCKED),
            entry(TenderTaskStatus.REVISION_REQUIRED, TenderTaskStatus.GENERATING, TenderTaskStatus.COLLABORATING, TenderTaskStatus.ANALYZING),
            entry(TenderTaskStatus.EXPORTING, TenderTaskStatus.EXPORT_READY, TenderTaskStatus.FAILED),
            entry(TenderTaskStatus.EXPORT_READY, TenderTaskStatus.DELIVERED, TenderTaskStatus.REVISION_REQUIRED, TenderTaskStatus.ARCHIVED),
            entry(TenderTaskStatus.DELIVERED, TenderTaskStatus.ARCHIVED),
            entry(TenderTaskStatus.FAILED, TenderTaskStatus.ARCHIVED),
            entry(TenderTaskStatus.CANCELLED, TenderTaskStatus.ARCHIVED),
            entry(TenderTaskStatus.BLOCKED, TenderTaskStatus.ANALYZING, TenderTaskStatus.COLLABORATING, TenderTaskStatus.GENERATING)
    );

    public TenderWorkflowService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public TenderTask createTask(CreateTaskRequest request) {
        if (request.name() == null || request.name().isBlank()) throw new IllegalArgumentException("EMPTY_TASK_NAME");
        String taskId = UUID.randomUUID().toString();
        jdbc.update("insert into tender_task(task_id,name,owner_id,status,current_mode,source_document_ids,metadata,created_by) values(?,?,?,?,?,?::jsonb,?::jsonb,?)",
                taskId, request.name(), blankToNull(request.ownerId()), TenderTaskStatus.CREATED.name(), TenderMode.NONE.name(),
                json(list(request.sourceDocumentIds())), json(map(request.metadata())), user(request.createdBy()));
        return getTask(taskId);
    }

    public TenderTask getTask(String taskId) {
        return jdbc.query("select task_id,name,owner_id,status,current_mode,current_stage,source_document_ids::text,metadata::text,created_by,created_at,updated_at,archived_at from tender_task where task_id=?",
                ps -> ps.setString(1, required(taskId, "EMPTY_TASK_ID")), rs -> {
                    if (!rs.next()) throw new NoSuchElementException("TENDER_TASK_NOT_FOUND");
                    return new TenderTask(rs.getString(1), rs.getString(2), rs.getString(3), TenderTaskStatus.valueOf(rs.getString(4)),
                            TenderMode.valueOf(rs.getString(5)), rs.getString(6), readList(rs.getString(7), String.class), readMap(rs.getString(8)),
                            rs.getString(9), instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)));
                });
    }

    public TenderTask transition(String taskId, TransitionRequest request) {
        TenderTask task = getTask(taskId);
        TenderTaskStatus to = Objects.requireNonNull(request.toStatus(), "toStatus");
        assertTransitionAllowed(task.status(), to);
        if ((to == TenderTaskStatus.COLLABORATING || to == TenderTaskStatus.COLLABORATION_READY || to == TenderTaskStatus.GENERATING)
                && hasPendingConfirmations(taskId)) {
            throw new IllegalStateException("PENDING_CONFIRMATIONS_EXIST");
        }
        if (to == TenderTaskStatus.GENERATING) {
            if (hasBlockingIssues(taskId)) throw new IllegalStateException("BLOCKING_ISSUES_EXIST");
            requireGenerationInputs(taskId);
            requireGenerationReadiness(taskId);
        }
        if (to == TenderTaskStatus.EXPORT_READY && !hasPassedReview(taskId)) throw new IllegalStateException("PASSED_REVIEW_REQUIRED");
        TenderMode toMode = modeFor(to);
        jdbc.update("update tender_task set status=?, current_mode=?, updated_at=now() where task_id=?", to.name(), toMode.name(), taskId);
        jdbc.update("insert into tender_transition_log(transition_id,task_id,from_status,to_status,from_mode,to_mode,triggered_by,trigger_type,reason,related_artifact_ids,related_issue_ids,metadata) values(?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)",
                UUID.randomUUID().toString(), taskId, task.status().name(), to.name(), task.currentMode().name(), toMode.name(),
                user(request.triggeredBy()), trigger(request.triggerType()).name(), request.reason(), json(list(request.relatedArtifactIds())), json(list(request.relatedIssueIds())), json(map(request.metadata())));
        return getTask(taskId);
    }

    public TenderArtifactVersion createArtifact(String taskId, CreateArtifactRequest request) {
        getTask(taskId);
        String artifactId = UUID.randomUUID().toString();
        TenderArtifactStatus status = status(request.status());
        TenderMode mode = request.mode() == null ? TenderMode.NONE : request.mode();
        jdbc.update("insert into tender_artifact(artifact_id,task_id,artifact_type,latest_version,status,mode) values(?,?,?,?,?,?)",
                artifactId, taskId, Objects.requireNonNull(request.type(), "type").name(), 1, status.name(), mode.name());
        insertVersion(artifactId, taskId, 1, status, request.content(), request.sourceRefs(), request.evidenceRefs(), request.confirmationRefs(), request.issues(), request.metadata(), request.createdBy());
        return getArtifactVersion(artifactId, 1);
    }

    public TenderArtifactVersion appendArtifactVersion(String artifactId, AppendArtifactVersionRequest request) {
        TenderArtifact artifact = getArtifact(artifactId);
        int next = artifact.latestVersion() + 1;
        TenderArtifactStatus status = status(request.status());
        insertVersion(artifactId, artifact.taskId(), next, status, request.content(), request.sourceRefs(), request.evidenceRefs(), request.confirmationRefs(), request.issues(), request.metadata(), request.createdBy());
        jdbc.update("update tender_artifact set latest_version=?, status=?, updated_at=now() where artifact_id=?", next, status.name(), artifactId);
        return getArtifactVersion(artifactId, next);
    }

    public TenderArtifact getArtifact(String artifactId) {
        return jdbc.query("select artifact_id,task_id,artifact_type,latest_version,status,mode,created_at,updated_at from tender_artifact where artifact_id=?",
                ps -> ps.setString(1, required(artifactId, "EMPTY_ARTIFACT_ID")), rs -> {
                    if (!rs.next()) throw new NoSuchElementException("TENDER_ARTIFACT_NOT_FOUND");
                    return new TenderArtifact(rs.getString(1), rs.getString(2), TenderArtifactType.valueOf(rs.getString(3)), rs.getInt(4),
                            TenderArtifactStatus.valueOf(rs.getString(5)), TenderMode.valueOf(rs.getString(6)), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)));
                });
    }

    public List<TenderArtifact> listArtifacts(String taskId) {
        return jdbc.query("select artifact_id,task_id,artifact_type,latest_version,status,mode,created_at,updated_at from tender_artifact where task_id=? order by created_at",
                (rs, row) -> new TenderArtifact(rs.getString(1), rs.getString(2), TenderArtifactType.valueOf(rs.getString(3)), rs.getInt(4), TenderArtifactStatus.valueOf(rs.getString(5)), TenderMode.valueOf(rs.getString(6)), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8))), taskId);
    }

    public TenderArtifactVersion latestVersion(String artifactId) {
        TenderArtifact artifact = getArtifact(artifactId);
        return getArtifactVersion(artifactId, artifact.latestVersion());
    }

    public TenderArtifactVersion latestArtifactByType(String taskId, TenderArtifactType type) {
        return jdbc.query("select v.artifact_id,v.task_id,v.version,v.status,v.content::text,v.source_refs::text,v.evidence_refs::text,v.confirmation_refs::text,v.issues::text,v.metadata::text,v.created_by,v.created_at " +
                        "from tender_artifact a join tender_artifact_version v on v.artifact_id=a.artifact_id and v.version=a.latest_version " +
                        "where a.task_id=? and a.artifact_type=? order by a.updated_at desc limit 1",
                ps -> { ps.setString(1, required(taskId, "EMPTY_TASK_ID")); ps.setString(2, Objects.requireNonNull(type).name()); }, rs -> {
                    if (!rs.next()) throw new NoSuchElementException("TENDER_ARTIFACT_TYPE_NOT_FOUND:" + type);
                    return new TenderArtifactVersion(rs.getString(1), rs.getString(2), rs.getInt(3), TenderArtifactStatus.valueOf(rs.getString(4)),
                            readMap(rs.getString(5)), readList(rs.getString(6), Object.class), readList(rs.getString(7), Object.class),
                            readList(rs.getString(8), Object.class), readList(rs.getString(9), Object.class), readMap(rs.getString(10)),
                            rs.getString(11), instant(rs.getTimestamp(12)));
                });
    }

    public TenderArtifactVersion getArtifactVersion(String artifactId, int version) {
        return jdbc.query("select artifact_id,task_id,version,status,content::text,source_refs::text,evidence_refs::text,confirmation_refs::text,issues::text,metadata::text,created_by,created_at from tender_artifact_version where artifact_id=? and version=?",
                ps -> { ps.setString(1, artifactId); ps.setInt(2, version); }, rs -> {
                    if (!rs.next()) throw new NoSuchElementException("TENDER_ARTIFACT_VERSION_NOT_FOUND");
                    return new TenderArtifactVersion(rs.getString(1), rs.getString(2), rs.getInt(3), TenderArtifactStatus.valueOf(rs.getString(4)),
                            readMap(rs.getString(5)), readList(rs.getString(6), Object.class), readList(rs.getString(7), Object.class), readList(rs.getString(8), Object.class),
                            readList(rs.getString(9), Object.class), readMap(rs.getString(10)), rs.getString(11), instant(rs.getTimestamp(12)));
                });
    }

    public Map<String, Object> createConfirmation(String taskId, CreateConfirmationRequest request) {
        getTask(taskId);
        if (request.question() == null || request.question().isBlank()) throw new IllegalArgumentException("EMPTY_CONFIRMATION_QUESTION");
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into tender_confirmation_request(confirmation_id,task_id,status,question,reason,options,affected_artifact_ids,affected_sections,created_by) values(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)",
                id, taskId, "PENDING", request.question(), Objects.requireNonNullElse(request.reason(), ""), json(list(request.options())), json(list(request.affectedArtifactIds())), json(list(request.affectedSections())), user(request.createdBy()));
        return Map.of("confirmationId", id, "taskId", taskId, "status", "PENDING");
    }

    public Map<String, Object> answerConfirmation(String confirmationId, AnswerConfirmationRequest request) {
        required(confirmationId, "EMPTY_CONFIRMATION_ID");
        int updated = jdbc.update("update tender_confirmation_request set status='ANSWERED', selected_option=?::jsonb, custom_answer=?, answered_by=?, answered_at=now() where confirmation_id=? and status='PENDING'",
                json(map(request.selectedOption())), request.customAnswer(), user(request.answeredBy()), confirmationId);
        if (updated == 0) throw new IllegalStateException("CONFIRMATION_NOT_PENDING");
        return Map.of("confirmationId", confirmationId, "status", "ANSWERED");
    }

    public Map<String, Object> createReviewIssue(String taskId, CreateReviewIssueRequest request) {
        getTask(taskId);
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into tender_review_issue(issue_id,task_id,artifact_id,artifact_version,issue_type,severity,status,location,description,suggested_fix,blocked,rollback_target,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, taskId, request.artifactId(), request.artifactVersion(), required(request.issueType(), "EMPTY_ISSUE_TYPE"), Objects.requireNonNull(request.severity(), "severity").name(),
                "OPEN", request.location(), required(request.description(), "EMPTY_ISSUE_DESCRIPTION"), request.suggestedFix(), request.blocked(), request.rollbackTarget() == null ? null : request.rollbackTarget().name(), user(request.createdBy()));
        return Map.of("issueId", id, "taskId", taskId, "status", "OPEN", "blocked", request.blocked());
    }

    public List<Map<String, Object>> listIssues(String taskId) {
        return jdbc.query("select issue_id,artifact_id,artifact_version,issue_type,severity,status,location,description,suggested_fix,blocked,rollback_target,created_by,created_at::text from tender_review_issue where task_id=? order by created_at",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("issueId", rs.getString(1)); m.put("artifactId", rs.getString(2)); m.put("artifactVersion", rs.getObject(3));
                    m.put("issueType", rs.getString(4)); m.put("severity", rs.getString(5)); m.put("status", rs.getString(6));
                    m.put("location", rs.getString(7)); m.put("description", rs.getString(8)); m.put("suggestedFix", rs.getString(9));
                    m.put("blocked", rs.getBoolean(10)); m.put("rollbackTarget", rs.getString(11)); m.put("createdBy", rs.getString(12)); m.put("createdAt", rs.getString(13));
                    return m;
                }, taskId);
    }



    public List<Map<String, Object>> listTransitions(String taskId) {
        return jdbc.query("select transition_id,from_status,to_status,from_mode,to_mode,triggered_by,trigger_type,reason,related_artifact_ids::text,related_issue_ids::text,metadata::text,created_at::text from tender_transition_log where task_id=? order by created_at",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("transitionId", rs.getString(1)); m.put("fromStatus", rs.getString(2)); m.put("toStatus", rs.getString(3));
                    m.put("fromMode", rs.getString(4)); m.put("toMode", rs.getString(5)); m.put("triggeredBy", rs.getString(6));
                    m.put("triggerType", rs.getString(7)); m.put("reason", rs.getString(8));
                    m.put("relatedArtifactIds", readList(rs.getString(9), Object.class));
                    m.put("relatedIssueIds", readList(rs.getString(10), Object.class));
                    m.put("metadata", readMap(rs.getString(11))); m.put("createdAt", rs.getString(12));
                    return m;
                }, required(taskId, "EMPTY_TASK_ID"));
    }

    public List<Map<String, Object>> listConfirmations(String taskId) {
        return jdbc.query("select confirmation_id,status,question,reason,options::text,selected_option::text,custom_answer,affected_artifact_ids::text,affected_sections::text,created_by,answered_by,created_at::text,answered_at::text from tender_confirmation_request where task_id=? order by created_at",
                (rs, row) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("confirmationId", rs.getString(1)); m.put("status", rs.getString(2)); m.put("question", rs.getString(3));
                    m.put("reason", rs.getString(4)); m.put("options", readList(rs.getString(5), Object.class));
                    m.put("selectedOption", rs.getString(6) == null ? Map.of() : readMap(rs.getString(6)));
                    m.put("customAnswer", rs.getString(7)); m.put("affectedArtifactIds", readList(rs.getString(8), Object.class));
                    m.put("affectedSections", readList(rs.getString(9), Object.class)); m.put("createdBy", rs.getString(10));
                    m.put("answeredBy", rs.getString(11)); m.put("createdAt", rs.getString(12)); m.put("answeredAt", rs.getString(13));
                    return m;
                }, required(taskId, "EMPTY_TASK_ID"));
    }

    public List<TenderArtifactVersion> listVersions(String artifactId) {
        return jdbc.query("select artifact_id,task_id,version,status,content::text,source_refs::text,evidence_refs::text,confirmation_refs::text,issues::text,metadata::text,created_by,created_at from tender_artifact_version where artifact_id=? order by version",
                (rs, row) -> new TenderArtifactVersion(rs.getString(1), rs.getString(2), rs.getInt(3), TenderArtifactStatus.valueOf(rs.getString(4)),
                        readMap(rs.getString(5)), readList(rs.getString(6), Object.class), readList(rs.getString(7), Object.class),
                        readList(rs.getString(8), Object.class), readList(rs.getString(9), Object.class), readMap(rs.getString(10)),
                        rs.getString(11), instant(rs.getTimestamp(12))), required(artifactId, "EMPTY_ARTIFACT_ID"));
    }

    public void assertTransitionAllowed(TenderTaskStatus from, TenderTaskStatus to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) throw new IllegalStateException("ILLEGAL_TENDER_TRANSITION:" + from + "->" + to);
    }

    public TenderMode modeFor(TenderTaskStatus status) {
        return switch (status) {
            case ANALYZING, ANALYSIS_READY -> TenderMode.ANALYSIS;
            case AWAITING_CONFIRMATION, COLLABORATING, COLLABORATION_READY -> TenderMode.COLLABORATION;
            case GENERATING, REVIEWING, REVISION_REQUIRED, EXPORTING, EXPORT_READY -> TenderMode.GENERATION;
            default -> TenderMode.NONE;
        };
    }

    private void requireGenerationInputs(String taskId) {
        Set<String> types = new HashSet<>(jdbc.queryForList("select artifact_type from tender_artifact where task_id=? and status in ('READY','CONFIRMED','NEEDS_CONFIRMATION')", String.class, taskId));
        boolean hasEvidence = types.contains("EVIDENCE_BINDING_PACK")
                || types.contains("EVIDENCE_PACK")
                || types.contains("CONFIRMED_EVIDENCE_BINDING_PACK");
        if (!(types.contains("TENDER_BRIEF")
                && types.contains("REQUIREMENT_MATRIX")
                && types.contains("SCORING_MATRIX")
                && hasEvidence
                && types.contains("SCORE_PROJECTION_PACK")
                && (types.contains("STRATEGY_EVALUATION_PACK") || types.contains("SELECTED_STRATEGY_PACK"))
                && types.contains("TENDER_TEMPLATE_PACK")
                && types.contains("CONFIRMED_CONTENT_PACK")
                && types.contains("GENERATION_INPUT_PACK"))) {
            throw new IllegalStateException("GENERATION_INPUTS_NOT_READY");
        }
    }
    private void requireGenerationReadiness(String taskId) {
        Integer count = jdbc.queryForObject("select count(*) from tender_artifact a join tender_artifact_version v on v.artifact_id=a.artifact_id and v.version=a.latest_version where a.task_id=? and a.artifact_type='GENERATION_READINESS_REPORT' and a.status='READY' and v.content->>'readiness'='ready_for_generation'", Integer.class, taskId);
        if (count == null || count == 0) throw new IllegalStateException("GENERATION_READINESS_REQUIRED");
    }
    public boolean hasBlockingIssuesForTask(String taskId) { return hasBlockingIssues(taskId); }
    public boolean hasPendingConfirmationsForTask(String taskId) { return hasPendingConfirmations(taskId); }
    private boolean hasBlockingIssues(String taskId) {
        Integer count = jdbc.queryForObject("select count(*) from tender_review_issue where task_id=? and blocked=true and status='OPEN'", Integer.class, taskId);
        return count != null && count > 0;
    }
    private boolean hasPendingConfirmations(String taskId) {
        Integer count = jdbc.queryForObject("select count(*) from tender_confirmation_request where task_id=? and status='PENDING'", Integer.class, taskId);
        return count != null && count > 0;
    }
    private boolean hasPassedReview(String taskId) {
        Integer count = jdbc.queryForObject("select count(*) from tender_artifact where task_id=? and artifact_type='REVIEW_REPORT' and status in ('READY','CONFIRMED')", Integer.class, taskId);
        return count != null && count > 0 && !hasBlockingIssues(taskId);
    }
    private void insertVersion(String artifactId, String taskId, int version, TenderArtifactStatus status, Map<String, Object> content, List<Object> sourceRefs, List<Object> evidenceRefs, List<Object> confirmationRefs, List<Object> issues, Map<String, Object> metadata, String createdBy) {
        jdbc.update("insert into tender_artifact_version(artifact_id,task_id,version,status,content,source_refs,evidence_refs,confirmation_refs,issues,metadata,created_by) values(?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)",
                artifactId, taskId, version, status.name(), json(map(content)), json(list(sourceRefs)), json(list(evidenceRefs)), json(list(confirmationRefs)), json(list(issues)), json(map(metadata)), user(createdBy));
    }

    @SafeVarargs private static Map.Entry<TenderTaskStatus, Set<TenderTaskStatus>> entry(TenderTaskStatus key, TenderTaskStatus... values) { return Map.entry(key, Set.of(values)); }
    private TenderTransitionTrigger trigger(TenderTransitionTrigger value) { return value == null ? TenderTransitionTrigger.SYSTEM : value; }
    private TenderArtifactStatus status(TenderArtifactStatus value) { return value == null ? TenderArtifactStatus.DRAFT : value; }
    private String user(String value) { return value == null || value.isBlank() ? "system" : value; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String required(String value, String error) { if (value == null || value.isBlank()) throw new IllegalArgumentException(error); return value; }
    private List<?> list(List<?> value) { return value == null ? List.of() : value; }
    private Map<String, Object> map(Map<String, Object> value) { return value == null ? Map.of() : value; }
    private Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("JSON_ENCODING_FAILED", e); } }
    private Map<String, Object> readMap(String value) { try { return mapper.readValue(value == null || value.isBlank() ? "{}" : value, Map.class); } catch (Exception e) { throw new IllegalArgumentException("JSON_DECODING_FAILED", e); } }
    private <T> List<T> readList(String value, Class<T> type) { try { return mapper.readValue(value == null || value.isBlank() ? "[]" : value, List.class); } catch (Exception e) { throw new IllegalArgumentException("JSON_DECODING_FAILED", e); } }
}
