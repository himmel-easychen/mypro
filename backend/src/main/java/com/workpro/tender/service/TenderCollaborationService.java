package com.workpro.tender.service;

import com.workpro.tender.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class TenderCollaborationService {
    private final TenderWorkflowService workflow;

    public TenderCollaborationService(TenderWorkflowService workflow) {
        this.workflow = workflow;
    }

    @Transactional
    public CollaborationEventResult applyEvent(String taskId, ApplyCollaborationEventRequest request) {
        TenderTask task = workflow.getTask(taskId);
        requireCollaborationState(task);
        if (request == null || request.type() == null) throw new IllegalArgumentException("EMPTY_COLLABORATION_EVENT_TYPE");
        String eventId = required(request.eventId(), "EMPTY_COLLABORATION_EVENT_ID");
        Optional<TenderArtifactVersion> duplicate = findEventById(taskId, eventId);
        if (duplicate.isPresent()) {
            Map<String, Object> record = duplicate.get().content();
            return new CollaborationEventResult(eventId, string(record.get("confirmation_id")), "duplicate", currentState(taskId));
        }

        if (request.type() == CollaborationEventType.ADD_REFERENCE_HISTORY) {
            appendMemory(taskId, TenderArtifactType.COLLABORATION_HISTORY_MEMORY, request, "reference_only");
            return new CollaborationEventResult(eventId, null, "recorded", currentState(taskId));
        }
        if (request.type() == CollaborationEventType.ADD_TEMPORARY_NOTE) {
            appendMemory(taskId, TenderArtifactType.TEMPORARY_COLLABORATION_SESSION, request, "temporary_only");
            return new CollaborationEventResult(eventId, null, "recorded", currentState(taskId));
        }

        String confirmationId = UUID.randomUUID().toString();
        validateAuthoritativeEvent(taskId, request);
        Map<String, Object> record = confirmationRecord(confirmationId, eventId, request);
        create(TenderArtifactType.CONFIRMATION_RECORD, TenderArtifactStatus.CONFIRMED, taskId, record,
                request.sourceRefs(), List.of(confirmationId), actor(request));
        writeDomainArtifact(taskId, confirmationId, request);
        Map<String, Object> state = rebuildState(taskId, actor(request));
        return new CollaborationEventResult(eventId, confirmationId, "applied", state);
    }

    public Map<String, Object> currentState(String taskId) {
        workflow.getTask(taskId);
        return latest(taskId, TenderArtifactType.TENDER_COLLABORATION_STATE)
                .map(TenderArtifactVersion::content)
                .orElseGet(() -> emptyState(taskId));
    }

    @Transactional
    public GenerationReadinessResult evaluateReadiness(String taskId, String actorId) {
        workflow.getTask(taskId);
        List<String> blocking = new ArrayList<>();
        Map<String, String> required = new LinkedHashMap<>();
        required.put("confirmed_evidence_binding_pack", status(taskId, TenderArtifactType.CONFIRMED_EVIDENCE_BINDING_PACK, true));
        required.put("confirmed_pricing_strategy", status(taskId, TenderArtifactType.CONFIRMED_PRICING_STRATEGY, false));
        required.put("commitment_confirmation_pack", status(taskId, TenderArtifactType.COMMITMENT_CONFIRMATION_PACK, false));
        required.put("selected_strategy_pack", status(taskId, TenderArtifactType.SELECTED_STRATEGY_PACK, true));
        required.put("tender_template_pack", confirmedStatus(taskId, TenderArtifactType.TENDER_TEMPLATE_PACK));
        required.put("confirmed_content_pack", confirmedStatus(taskId, TenderArtifactType.CONFIRMED_CONTENT_PACK));
        required.put("generation_input_pack", confirmedStatus(taskId, TenderArtifactType.GENERATION_INPUT_PACK));
        required.forEach((key, value) -> { if ("missing".equals(value) || "blocked".equals(value)) blocking.add(key + ":" + value); });
        if (workflow.hasPendingConfirmationsForTask(taskId)) blocking.add("pending_confirmations");
        if (workflow.hasBlockingIssuesForTask(taskId)) blocking.add("blocking_review_issues");
        boolean approved = activeConfirmations(taskId).stream()
                .filter(v -> "generation_approval".equals(v.content().get("confirmation_type")))
                .anyMatch(v -> Boolean.TRUE.equals(map(v.content().get("confirmed_value")).get("approved")));
        if (!approved) blocking.add("generation_approval:missing");
        String readiness = blocking.isEmpty() ? "ready_for_generation" : "needs_more_materials";
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("readiness", readiness);
        content.put("blocking_items", blocking);
        content.put("required_artifacts", required);
        content.put("approved_by", approved ? actorId : null);
        content.put("confirmation_ids", activeConfirmations(taskId).stream().map(v -> v.content().get("confirmation_id")).toList());
        content.put("generated_at", Instant.now().toString());
        TenderArtifactVersion artifact = upsert(taskId, TenderArtifactType.GENERATION_READINESS_REPORT,
                blocking.isEmpty() ? TenderArtifactStatus.READY : TenderArtifactStatus.BLOCKED, content, actorId);
        rebuildState(taskId, actorId);
        return new GenerationReadinessResult(readiness, List.copyOf(blocking), required, artifact.artifactId(), artifact.version());
    }

    private void validateAuthoritativeEvent(String taskId, ApplyCollaborationEventRequest request) {
        Map<String, Object> value = value(request);
        if (request.type() == CollaborationEventType.CONFIRM_MEMORY_SEED
                && (string(value.get("memory_seed_id")) == null || string(value.get("target_memory_layer")) == null)) {
            throw new IllegalArgumentException("MEMORY_SEED_ID_AND_LAYER_REQUIRED");
        }
        if (request.type() == CollaborationEventType.CONFIRM_EVIDENCE) {
            String support = string(value.get("support_level"));
            boolean allowed = Boolean.TRUE.equals(value.get("generation_allowed"));
            if (allowed && list(value.get("evidence_refs")).isEmpty()) throw new IllegalArgumentException("EVIDENCE_REFS_REQUIRED");
            if (allowed && Set.of("partial", "conflicts", "unknown").contains(support)
                    && activeConfirmations(taskId).stream().noneMatch(v -> "risk_acceptance".equals(v.content().get("confirmation_type")))) {
                throw new IllegalStateException("RISK_ACCEPTANCE_REQUIRED");
            }
        }
        if (request.type() == CollaborationEventType.CONFIRM_PRICING
                && !Set.of("pricing_owner", "project_owner").contains(request.actorRole())) {
            throw new IllegalArgumentException("PRICING_OWNER_REQUIRED");
        }
        if (request.type() == CollaborationEventType.SELECT_STRATEGY) {
            String evaluationId = string(value.get("source_strategy_evaluation_id"));
            if (evaluationId == null || latest(taskId, TenderArtifactType.STRATEGY_EVALUATION_PACK).isEmpty())
                throw new IllegalStateException("STRATEGY_EVALUATION_REQUIRED");
        }
        if (request.type() == CollaborationEventType.REVOKE_CONFIRMATION) {
            String target = request.supersedesConfirmationId();
            if (target == null || activeConfirmations(taskId).stream().noneMatch(v -> target.equals(v.content().get("confirmation_id"))))
                throw new IllegalArgumentException("ACTIVE_CONFIRMATION_REQUIRED");
        }
    }

    private void writeDomainArtifact(String taskId, String confirmationId, ApplyCollaborationEventRequest request) {
        TenderArtifactType type = switch (request.type()) {
            case CONFIRM_EVIDENCE -> TenderArtifactType.CONFIRMED_EVIDENCE_BINDING_PACK;
            case CONFIRM_PRICING -> TenderArtifactType.CONFIRMED_PRICING_STRATEGY;
            case CONFIRM_COMMITMENT -> TenderArtifactType.COMMITMENT_CONFIRMATION_PACK;
            case SELECT_STRATEGY -> TenderArtifactType.SELECTED_STRATEGY_PACK;
            default -> null;
        };
        Map<String, Object> content = new LinkedHashMap<>(value(request));
        if (type != null) {
            content.put("confirmation_ids", List.of(confirmationId));
            upsert(taskId, type, TenderArtifactStatus.CONFIRMED, content, actor(request));
        }
        if (request.type() == CollaborationEventType.CONFIRM_MEMORY_SEED) {
            Map<String, Object> decision = new LinkedHashMap<>(value(request));
            decision.put("confirmation_ids", List.of(confirmationId));
            decision.put("decision_type", "memory_seed_confirmation");
            upsert(taskId, TenderArtifactType.COLLABORATION_DECISION, TenderArtifactStatus.CONFIRMED, decision, actor(request));
            Map<String, Object> memory = new LinkedHashMap<>(decision);
            memory.put("status", "active");
            memory.put("confirmed_at", Instant.now().toString());
            upsert(taskId, TenderArtifactType.AUTHORITATIVE_COLLABORATION_MEMORY,
                    TenderArtifactStatus.CONFIRMED, memory, actor(request));
        }
        if (request.type() == CollaborationEventType.APPROVE_GENERATION
                && Boolean.TRUE.equals(value(request).get("approved"))) {
            confirmGenerationContracts(taskId, confirmationId, actor(request));
        }
    }

    private void confirmGenerationContracts(String taskId, String confirmationId, String actor) {
        Optional<TenderArtifactVersion> structureCandidate = latest(taskId, TenderArtifactType.SUBMISSION_STRUCTURE_PACK);
        if (structureCandidate.isEmpty()) {
            for (TenderArtifactType type : List.of(TenderArtifactType.TENDER_TEMPLATE_PACK,
                    TenderArtifactType.CONFIRMED_CONTENT_PACK, TenderArtifactType.GENERATION_INPUT_PACK)) {
                latest(taskId, type).ifPresent(current -> {
                    Map<String, Object> content = new LinkedHashMap<>(current.content());
                    content.put("status", "confirmed"); content.put("confirmation_ids", List.of(confirmationId));
                    upsert(taskId, type, TenderArtifactStatus.CONFIRMED, content, actor);
                });
            }
            return;
        }
        TenderArtifactVersion structure = structureCandidate.get();
        List<Map<String, Object>> sections = maps(structure.content().get("submission_sections"));
        if (sections.isEmpty()) sections = maps(structure.content().get("sections"));
        List<Map<String, Object>> normalizedSections = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> section : sections) {
            Map<String, Object> normalized = new LinkedHashMap<>(section);
            normalized.putIfAbsent("section_id", Objects.requireNonNullElse(string(section.get("id")), "SECTION-" + index));
            normalized.putIfAbsent("title", Objects.requireNonNullElse(string(section.get("name")), "第" + index + "章"));
            normalized.putIfAbsent("mode", "evidence_grounded_generate");
            normalizedSections.add(normalized);
            index++;
        }
        if (normalizedSections.isEmpty()) throw new IllegalStateException("SUBMISSION_STRUCTURE_HAS_NO_SECTIONS");

        TenderArtifactVersion existingTemplate = latest(taskId, TenderArtifactType.TENDER_TEMPLATE_PACK)
                .orElseThrow(() -> new IllegalStateException("TENDER_TEMPLATE_PACK_MISSING"));
        Map<String, Object> template = new LinkedHashMap<>(existingTemplate.content());
        template.put("status", "confirmed");
        template.put("document_parts", List.of(Map.of("part_id", "BID", "title", "投标文件",
                "sections", normalizedSections)));
        template.put("confirmation_ids", List.of(confirmationId));
        TenderArtifactVersion templateVersion = upsert(taskId, TenderArtifactType.TENDER_TEMPLATE_PACK,
                TenderArtifactStatus.CONFIRMED, template, actor);

        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("artifact", "ConfirmedContentPack");
        confirmed.put("status", "confirmed");
        confirmed.put("content_by_section", normalizedSections.stream().map(section -> Map.of(
                "section_id", section.get("section_id"),
                "instructions", List.of("严格依据已确认策略、证据和招标原文响应本节，不得虚构。"))).toList());
        putSnapshot(confirmed, "evidence", taskId, TenderArtifactType.CONFIRMED_EVIDENCE_BINDING_PACK);
        putSnapshot(confirmed, "strategy", taskId, TenderArtifactType.SELECTED_STRATEGY_PACK);
        putSnapshot(confirmed, "pricing", taskId, TenderArtifactType.CONFIRMED_PRICING_STRATEGY);
        putSnapshot(confirmed, "commitments", taskId, TenderArtifactType.COMMITMENT_CONFIRMATION_PACK);
        putSnapshot(confirmed, "memory", taskId, TenderArtifactType.AUTHORITATIVE_COLLABORATION_MEMORY);
        confirmed.put("confirmation_ids", List.of(confirmationId));
        TenderArtifactVersion confirmedVersion = upsert(taskId, TenderArtifactType.CONFIRMED_CONTENT_PACK,
                TenderArtifactStatus.CONFIRMED, confirmed, actor);

        Map<String, Object> analysis = new LinkedHashMap<>();
        for (TenderArtifactType type : List.of(TenderArtifactType.TENDER_BRIEF, TenderArtifactType.REQUIREMENT_MATRIX,
                TenderArtifactType.SCORING_MATRIX, TenderArtifactType.PRICING_RULE_PACK,
                TenderArtifactType.FATAL_RISK_RULE_PACK, TenderArtifactType.SUBMISSION_STRUCTURE_PACK,
                TenderArtifactType.EVIDENCE_AVAILABILITY_PACK, TenderArtifactType.SCORE_PROJECTION_PACK)) {
            latest(taskId, type).ifPresent(value -> analysis.put(type.name().toLowerCase(Locale.ROOT), value.content()));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("artifact", "GenerationInputPack"); input.put("contract_version", "1.0");
        TenderTask task = workflow.getTask(taskId);
        input.put("task", Map.of("task_id", taskId, "project_name", task.name()));
        input.put("analysis_artifacts", analysis);
        input.put("template_artifact_id", templateVersion.artifactId()); input.put("template_pack", template);
        input.put("confirmed_content_artifact_id", confirmedVersion.artifactId()); input.put("confirmed_content_pack", confirmed);
        input.put("pricing", confirmed.getOrDefault("pricing", Map.of()));
        input.put("scoring_targets", maps(analysis.getOrDefault("scoring_matrix", Map.of()) instanceof Map<?, ?> m ? m.get("scoring_items") : null));
        input.put("blocking_placeholders", List.of());
        input.put("readiness_artifact_type", "GENERATION_READINESS_REPORT");
        input.put("generation_policy", Map.of("allow_unconfirmed_artifacts", false, "require_citations", true));
        input.put("confirmation_ids", List.of(confirmationId));
        upsert(taskId, TenderArtifactType.GENERATION_INPUT_PACK, TenderArtifactStatus.CONFIRMED, input, actor);
    }

    private void putSnapshot(Map<String, Object> target, String key, String taskId, TenderArtifactType type) {
        latest(taskId, type).ifPresent(value -> target.put(key, value.content()));
    }

    private Map<String, Object> rebuildState(String taskId, String actorId) {
        List<TenderArtifactVersion> records = activeConfirmations(taskId);
        Map<String, Object> state = emptyState(taskId);
        state.put("selected_scope", latestValue(records, "scope"));
        state.put("pricing_decision_status", hasType(records, "pricing") ? "confirmed" : "missing");
        state.put("strategy_decision_status", hasType(records, "strategy") ? "confirmed" : "missing");
        state.put("commitment_status", hasType(records, "commitment") ? Map.of("status", "confirmed") : Map.of());
        state.put("risk_acceptance_status", hasType(records, "risk_acceptance") ? Map.of("status", "confirmed") : Map.of());
        state.put("evidence_binding_status", hasType(records, "evidence") ? Map.of("status", "confirmed") : Map.of());
        state.put("confirmation_ids", records.stream().map(v -> v.content().get("confirmation_id")).toList());
        state.put("generation_readiness", latest(taskId, TenderArtifactType.GENERATION_READINESS_REPORT)
                .map(v -> string(v.content().get("readiness"))).orElse("not_ready"));
        state.put("updated_by", actorId == null ? "system" : actorId);
        state.put("updated_at", Instant.now().toString());
        upsert(taskId, TenderArtifactType.TENDER_COLLABORATION_STATE, TenderArtifactStatus.CONFIRMED, state, actorId);
        return state;
    }

    private Map<String, Object> emptyState(String taskId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("task_id", taskId); state.put("selected_scope", Map.of()); state.put("requirement_status", Map.of());
        state.put("evidence_binding_status", Map.of()); state.put("gap_resolution_status", Map.of());
        state.put("pricing_decision_status", "missing"); state.put("strategy_decision_status", "missing");
        state.put("commitment_status", Map.of()); state.put("risk_acceptance_status", Map.of());
        state.put("generation_readiness", "not_ready"); state.put("confirmation_ids", List.of());
        return state;
    }

    private Map<String, Object> confirmationRecord(String id, String eventId, ApplyCollaborationEventRequest request) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("confirmation_id", id); record.put("event_id", eventId); record.put("request_id", request.requestId());
        record.put("decision_id", request.decisionId()); record.put("confirmation_type", confirmationType(request.type()));
        record.put("confirmed_value", value(request));
        record.put("confirmed_by", Map.of("actor_id", actor(request), "role", Objects.requireNonNullElse(request.actorRole(), "project_owner")));
        record.put("source_refs", safe(request.sourceRefs())); record.put("affected_artifact_ids", safe(request.affectedArtifactIds()));
        record.put("status", request.type() == CollaborationEventType.REVOKE_CONFIRMATION ? "revoked" : "applied");
        record.put("confirmed_at", Instant.now().toString()); record.put("supersedes_confirmation_id", request.supersedesConfirmationId());
        return record;
    }

    private List<TenderArtifactVersion> activeConfirmations(String taskId) {
        List<TenderArtifactVersion> all = workflow.listArtifacts(taskId).stream()
                .filter(a -> a.type() == TenderArtifactType.CONFIRMATION_RECORD)
                .map(a -> workflow.latestVersion(a.artifactId())).toList();
        Set<String> revoked = all.stream().filter(v -> "revoked".equals(v.content().get("status")))
                .map(v -> string(v.content().get("supersedes_confirmation_id"))).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return all.stream().filter(v -> Set.of("confirmed", "applied").contains(string(v.content().get("status"))))
                .filter(v -> !revoked.contains(string(v.content().get("confirmation_id")))).toList();
    }

    private Optional<TenderArtifactVersion> findEventById(String taskId, String eventId) {
        return workflow.listArtifacts(taskId).stream()
                .filter(a -> Set.of(TenderArtifactType.CONFIRMATION_RECORD,
                        TenderArtifactType.COLLABORATION_HISTORY_MEMORY,
                        TenderArtifactType.TEMPORARY_COLLABORATION_SESSION).contains(a.type()))
                .map(a -> workflow.latestVersion(a.artifactId()))
                .filter(v -> eventId.equals(v.content().get("event_id"))
                        || list(v.content().get("entries")).stream()
                        .map(this::map).anyMatch(entry -> eventId.equals(entry.get("event_id"))))
                .findFirst();
    }

    private void appendMemory(String taskId, TenderArtifactType type, ApplyCollaborationEventRequest request, String policy) {
        Map<String, Object> entry = Map.of("event_id", request.eventId(), "content", value(request), "write_policy", policy,
                "created_by", actor(request), "created_at", Instant.now().toString());
        Map<String, Object> content = new LinkedHashMap<>();
        List<Object> entries = new ArrayList<>();
        latest(taskId, type).ifPresent(v -> entries.addAll(list(v.content().get("entries"))));
        entries.add(entry); content.put("entries", entries);
        upsert(taskId, type, TenderArtifactStatus.READY, content, actor(request));
    }

    private TenderArtifactVersion upsert(String taskId, TenderArtifactType type, TenderArtifactStatus status, Map<String, Object> content, String actor) {
        Optional<TenderArtifact> existing = workflow.listArtifacts(taskId).stream().filter(a -> a.type() == type).findFirst();
        if (existing.isEmpty()) return create(type, status, taskId, content, List.of(), List.of(), actor);
        return workflow.appendArtifactVersion(existing.get().artifactId(), new AppendArtifactVersionRequest(status, content,
                List.of(), List.of(), List.of(), List.of(), Map.of("contractVersion", "2026-09-19"), actor));
    }

    private TenderArtifactVersion create(TenderArtifactType type, TenderArtifactStatus status, String taskId,
                                         Map<String, Object> content, List<?> sourceRefs, List<?> confirmations, String actor) {
        return workflow.createArtifact(taskId, new CreateArtifactRequest(type, status, TenderMode.COLLABORATION, content,
                new ArrayList<>(sourceRefs), List.of(), new ArrayList<>(confirmations), List.of(),
                Map.of("contractVersion", "2026-09-19"), actor));
    }

    private Optional<TenderArtifactVersion> latest(String taskId, TenderArtifactType type) {
        return workflow.listArtifacts(taskId).stream().filter(a -> a.type() == type).findFirst().map(a -> workflow.latestVersion(a.artifactId()));
    }

    private String status(String taskId, TenderArtifactType type, boolean required) {
        Optional<TenderArtifactVersion> artifact = latest(taskId, type);
        if (artifact.isEmpty()) return required ? "missing" : "not_applicable";
        Object allowed = artifact.get().content().get("generation_allowed");
        return Boolean.FALSE.equals(allowed) || artifact.get().status() == TenderArtifactStatus.BLOCKED ? "blocked" : "ready";
    }

    private String confirmedStatus(String taskId, TenderArtifactType type) {
        Optional<TenderArtifactVersion> artifact = latest(taskId, type);
        if (artifact.isEmpty()) return "missing";
        return artifact.get().status() == TenderArtifactStatus.CONFIRMED ? "ready" : "blocked";
    }

    private void requireCollaborationState(TenderTask task) {
        if (!Set.of(TenderTaskStatus.AWAITING_CONFIRMATION, TenderTaskStatus.COLLABORATING, TenderTaskStatus.COLLABORATION_READY).contains(task.status()))
            throw new IllegalStateException("COLLABORATION_MODE_REQUIRED");
    }

    private String confirmationType(CollaborationEventType type) {
        return switch (type) {
            case CONFIRM_SCOPE -> "scope"; case CONFIRM_EVIDENCE -> "evidence"; case CONFIRM_PRICING -> "pricing";
            case CONFIRM_COMMITMENT -> "commitment"; case SELECT_STRATEGY -> "strategy"; case ACCEPT_RISK -> "risk_acceptance";
            case APPROVE_GENERATION -> "generation_approval"; case CONFIRM_MEMORY_SEED -> "memory_seed"; case REVOKE_CONFIRMATION -> "revocation";
            default -> throw new IllegalArgumentException("NON_AUTHORITATIVE_EVENT");
        };
    }

    private boolean hasType(List<TenderArtifactVersion> records, String type) { return records.stream().anyMatch(v -> type.equals(v.content().get("confirmation_type"))); }
    private Object latestValue(List<TenderArtifactVersion> records, String type) { return records.stream().filter(v -> type.equals(v.content().get("confirmation_type"))).reduce((a,b) -> b).map(v -> v.content().get("confirmed_value")).orElse(Map.of()); }
    @SuppressWarnings("unchecked") private List<Object> list(Object value) { return value instanceof List<?> l ? new ArrayList<>((List<Object>) l) : List.of(); }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> maps(Object value) { return value instanceof List<?> l ? l.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList() : List.of(); }
    private Map<String, Object> value(ApplyCollaborationEventRequest r) { return r.value() == null ? Map.of() : r.value(); }
    private List<?> safe(List<?> value) { return value == null ? List.of() : value; }
    private String actor(ApplyCollaborationEventRequest r) { return r.actorId() == null || r.actorId().isBlank() ? "system" : r.actorId(); }
    private String required(String value, String error) { if (value == null || value.isBlank()) throw new IllegalArgumentException(error); return value; }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
}
