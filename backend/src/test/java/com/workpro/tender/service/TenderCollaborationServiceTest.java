package com.workpro.tender.service;

import com.workpro.tender.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TenderCollaborationServiceTest {

    @Test
    void buildsGenerationReadyStateFromConfirmedDecisionsAndKeepsEventsIdempotent() {
        InMemoryWorkflow workflow = new InMemoryWorkflow();
        workflow.createArtifact("task-1", artifact(TenderArtifactType.STRATEGY_EVALUATION_PACK,
                TenderArtifactStatus.READY, Map.of("strategies", List.of(Map.of("strategy_id", "balanced")))));
        addGenerationContracts(workflow);
        TenderCollaborationService service = new TenderCollaborationService(workflow);

        service.applyEvent("task-1", event("evidence-1", CollaborationEventType.CONFIRM_EVIDENCE,
                Map.of("support_level", "fully_supported", "generation_allowed", true,
                        "evidence_refs", List.of("evidence:1"))));
        service.applyEvent("task-1", event("strategy-1", CollaborationEventType.SELECT_STRATEGY,
                Map.of("strategy_id", "balanced", "source_strategy_evaluation_id", "evaluation-1")));
        service.applyEvent("task-1", event("approval-1", CollaborationEventType.APPROVE_GENERATION,
                Map.of("approved", true)));

        CollaborationEventResult duplicate = service.applyEvent("task-1", event("approval-1",
                CollaborationEventType.APPROVE_GENERATION, Map.of("approved", true)));
        GenerationReadinessResult readiness = service.evaluateReadiness("task-1", "owner-1");

        assertThat(duplicate.status()).isEqualTo("duplicate");
        assertThat(readiness.readiness()).isEqualTo("ready_for_generation");
        assertThat(readiness.blockingItems()).isEmpty();
        assertThat(service.currentState("task-1").get("generation_readiness")).isEqualTo("ready_for_generation");
        assertThat(workflow.artifactsOf(TenderArtifactType.CONFIRMATION_RECORD)).hasSize(3);
    }

    @Test
    void falseGenerationApprovalDoesNotUnlockGeneration() {
        InMemoryWorkflow workflow = new InMemoryWorkflow();
        workflow.createArtifact("task-1", artifact(TenderArtifactType.CONFIRMED_EVIDENCE_BINDING_PACK,
                TenderArtifactStatus.CONFIRMED, Map.of("generation_allowed", true)));
        workflow.createArtifact("task-1", artifact(TenderArtifactType.SELECTED_STRATEGY_PACK,
                TenderArtifactStatus.CONFIRMED, Map.of("strategy_id", "balanced")));
        TenderCollaborationService service = new TenderCollaborationService(workflow);

        service.applyEvent("task-1", event("approval-false", CollaborationEventType.APPROVE_GENERATION,
                Map.of("approved", false)));

        assertThat(service.evaluateReadiness("task-1", "owner-1").blockingItems())
                .contains("generation_approval:missing");
    }

    @Test
    void referenceHistoryEventsAreIdempotent() {
        InMemoryWorkflow workflow = new InMemoryWorkflow();
        TenderCollaborationService service = new TenderCollaborationService(workflow);
        ApplyCollaborationEventRequest request = event("history-1", CollaborationEventType.ADD_REFERENCE_HISTORY,
                Map.of("summary", "Earlier discussion"));

        service.applyEvent("task-1", request);
        CollaborationEventResult duplicate = service.applyEvent("task-1", request);

        assertThat(duplicate.status()).isEqualTo("duplicate");
        TenderArtifactVersion memory = workflow.latestVersion(
                workflow.artifactsOf(TenderArtifactType.COLLABORATION_HISTORY_MEMORY).get(0).artifactId());
        assertThat((List<?>) memory.content().get("entries")).hasSize(1);
    }

    private static ApplyCollaborationEventRequest event(String id, CollaborationEventType type, Map<String, Object> value) {
        return new ApplyCollaborationEventRequest(id, type, "request-1", "decision-1", value,
                "owner-1", "project_owner", List.of(), List.of(), null);
    }

    private static CreateArtifactRequest artifact(TenderArtifactType type, TenderArtifactStatus status,
                                                   Map<String, Object> content) {
        return new CreateArtifactRequest(type, status, TenderMode.COLLABORATION, content,
                List.of(), List.of(), List.of(), List.of(), Map.of(), "test");
    }

    private static void addGenerationContracts(InMemoryWorkflow workflow) {
        workflow.createArtifact("task-1", artifact(TenderArtifactType.TENDER_TEMPLATE_PACK,
                TenderArtifactStatus.NEEDS_CONFIRMATION, Map.of("document_parts", List.of())));
        workflow.createArtifact("task-1", artifact(TenderArtifactType.CONFIRMED_CONTENT_PACK,
                TenderArtifactStatus.NEEDS_CONFIRMATION, Map.of("sections", List.of())));
        workflow.createArtifact("task-1", artifact(TenderArtifactType.GENERATION_INPUT_PACK,
                TenderArtifactStatus.NEEDS_CONFIRMATION, Map.of("artifact", "GenerationInputPack", "contract_version", "1.0")));
    }

    private static final class InMemoryWorkflow extends TenderWorkflowService {
        private final Map<String, TenderArtifact> artifacts = new LinkedHashMap<>();
        private final Map<String, TenderArtifactVersion> versions = new LinkedHashMap<>();
        private int sequence;

        private InMemoryWorkflow() {
            super(null, new ObjectMapper());
        }

        @Override
        public TenderTask getTask(String taskId) {
            Instant now = Instant.now();
            return new TenderTask(taskId, "Tender", "owner-1", TenderTaskStatus.COLLABORATING,
                    TenderMode.COLLABORATION, "collaboration", List.of(), Map.of(), "owner-1", now, now, null);
        }

        @Override
        public TenderArtifactVersion createArtifact(String taskId, CreateArtifactRequest request) {
            String id = "artifact-" + ++sequence;
            Instant now = Instant.now();
            TenderArtifact artifact = new TenderArtifact(id, taskId, request.type(), 1, request.status(),
                    request.mode(), now, now);
            TenderArtifactVersion version = new TenderArtifactVersion(id, taskId, 1, request.status(),
                    request.content(), request.sourceRefs(), request.evidenceRefs(), request.confirmationRefs(),
                    request.issues(), request.metadata(), request.createdBy(), now);
            artifacts.put(id, artifact);
            versions.put(id, version);
            return version;
        }

        @Override
        public TenderArtifactVersion appendArtifactVersion(String artifactId, AppendArtifactVersionRequest request) {
            TenderArtifact current = artifacts.get(artifactId);
            int next = current.latestVersion() + 1;
            Instant now = Instant.now();
            TenderArtifact updated = new TenderArtifact(artifactId, current.taskId(), current.type(), next,
                    request.status(), current.mode(), current.createdAt(), now);
            TenderArtifactVersion version = new TenderArtifactVersion(artifactId, current.taskId(), next,
                    request.status(), request.content(), request.sourceRefs(), request.evidenceRefs(),
                    request.confirmationRefs(), request.issues(), request.metadata(), request.createdBy(), now);
            artifacts.put(artifactId, updated);
            versions.put(artifactId, version);
            return version;
        }

        @Override
        public List<TenderArtifact> listArtifacts(String taskId) {
            return artifacts.values().stream().filter(a -> taskId.equals(a.taskId())).toList();
        }

        @Override
        public TenderArtifactVersion latestVersion(String artifactId) {
            return versions.get(artifactId);
        }

        @Override
        public boolean hasBlockingIssuesForTask(String taskId) {
            return false;
        }

        @Override
        public boolean hasPendingConfirmationsForTask(String taskId) {
            return false;
        }

        private List<TenderArtifact> artifactsOf(TenderArtifactType type) {
            return artifacts.values().stream().filter(a -> a.type() == type).toList();
        }
    }
}
