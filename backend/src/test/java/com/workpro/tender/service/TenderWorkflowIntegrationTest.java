package com.workpro.tender.service;

import com.workpro.tender.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.data.redis.host=127.0.0.1",
        "workpro.ocr.enabled=false"
})
class TenderWorkflowIntegrationTest {
    @Autowired TenderWorkflowService workflow;
    @Autowired TenderCollaborationService collaboration;

    @Test void foundationWorkflowBlocksConfirmsRevisesAndReachesExportReady() {
        var task = workflow.createTask(new CreateTaskRequest("运行级验收投标任务", "tester", List.of(), Map.of("scope", "integration"), "tester"));
        String taskId = task.taskId();
        assertEquals(TenderTaskStatus.CREATED, task.status());
        task = workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.ANALYZING, "tester", TenderTransitionTrigger.USER, "start", List.of(), List.of(), Map.of()));
        assertEquals(TenderTaskStatus.ANALYZING, task.status());

        workflow.createArtifact(taskId, artifact(TenderArtifactType.TENDER_BRIEF, TenderArtifactStatus.READY, Map.of("project_name", "测试项目")));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.REQUIREMENT_MATRIX, TenderArtifactStatus.READY, Map.of("requirements", List.of(Map.of("id", "REQ-1")))));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.SCORING_MATRIX, TenderArtifactStatus.READY, Map.of("items", List.of(Map.of("id", "SC-1", "max_score", 20)))));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.EVIDENCE_BINDING_PACK, TenderArtifactStatus.READY, Map.of("bindings", List.of(Map.of("id", "EV-1")))));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.SCORE_PROJECTION_PACK, TenderArtifactStatus.READY, Map.of("projections", List.of(Map.of("scoring_item_id", "SC-1", "projected_score_range", Map.of("min", 15, "max", 20))))));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.STRATEGY_EVALUATION_PACK, TenderArtifactStatus.READY, Map.of("options", List.of(Map.of("option_id", "OPT-1", "expected_final_score_range", Map.of("min", 15, "max", 20))))));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.TENDER_TEMPLATE_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION, Map.of("document_parts", List.of())));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.CONFIRMED_CONTENT_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION, Map.of("sections", List.of())));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.GENERATION_INPUT_PACK, TenderArtifactStatus.NEEDS_CONFIRMATION, Map.of("artifact", "GenerationInputPack", "contract_version", "1.0")));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.GAP_LIST, TenderArtifactStatus.NEEDS_CONFIRMATION, Map.of("gaps", List.of(Map.of("severity", "high")))));
        task = workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.ANALYSIS_READY, "agent", TenderTransitionTrigger.AGENT, "analysis ready", List.of(), List.of(), Map.of()));
        assertEquals(TenderTaskStatus.ANALYSIS_READY, task.status());

        var confirmation = workflow.createConfirmation(taskId, new CreateConfirmationRequest("是否接受高风险承诺？", "存在 high gap", List.of(Map.of("id", "A", "label", "接受")), List.of(), List.of(), "agent"));
        task = workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.AWAITING_CONFIRMATION, "agent", TenderTransitionTrigger.AGENT, "need user", List.of(), List.of(), Map.of()));
        assertThrows(IllegalStateException.class, () -> workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.COLLABORATING, "tester", TenderTransitionTrigger.USER, "premature", List.of(), List.of(), Map.of())));
        workflow.answerConfirmation(confirmation.get("confirmationId").toString(), new AnswerConfirmationRequest(Map.of("id", "A"), null, "tester"));
        task = workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.COLLABORATING, "tester", TenderTransitionTrigger.USER, "answered", List.of(), List.of(), Map.of()));
        assertEquals(TenderTaskStatus.COLLABORATING, task.status());
        var evidence = collaboration.applyEvent(taskId, event("evt-evidence", CollaborationEventType.CONFIRM_EVIDENCE,
                Map.of("bindings", List.of(Map.of("binding_id", "BIND-1")), "evidence_refs", List.of("doc-1:p1"),
                        "support_level", "full", "generation_allowed", true), "project_owner"));
        assertEquals("applied", evidence.status());
        collaboration.applyEvent(taskId, event("evt-strategy", CollaborationEventType.SELECT_STRATEGY,
                Map.of("selected_strategy_id", "STRATEGY-1", "source_strategy_evaluation_id", "EVAL-1",
                        "selected_options", List.of("OPT-1")), "project_owner"));
        collaboration.applyEvent(taskId, event("evt-approval", CollaborationEventType.APPROVE_GENERATION,
                Map.of("approved", true), "project_owner"));
        var readiness = collaboration.evaluateReadiness(taskId, "tester");
        assertEquals("ready_for_generation", readiness.readiness());
        assertEquals("ready_for_generation", collaboration.currentState(taskId).get("generation_readiness"));
        workflow.createArtifact(taskId, artifact(TenderArtifactType.INITIAL_OUTLINE, TenderArtifactStatus.READY, Map.of("sections", List.of("一、响应"))));
        task = workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.COLLABORATION_READY, "agent", TenderTransitionTrigger.AGENT, "collaboration ready", List.of(), List.of(), Map.of()));
        task = workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.GENERATING, "agent", TenderTransitionTrigger.AGENT, "generate", List.of(), List.of(), Map.of()));
        assertEquals(TenderTaskStatus.GENERATING, task.status());

        workflow.createArtifact(taskId, artifact(TenderArtifactType.DRAFT_PACK, TenderArtifactStatus.READY, Map.of("sections", List.of(Map.of("id", "SEC-1")))));
        task = workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.REVIEWING, "agent", TenderTransitionTrigger.AGENT, "review", List.of(), List.of(), Map.of()));
        workflow.createReviewIssue(taskId, new CreateReviewIssueRequest(null, null, "unsupported_claim", TenderIssueSeverity.HIGH, "SEC-1", "缺少证据", "补证据", true, TenderMode.COLLABORATION, "reviewer"));
        task = workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.REVISION_REQUIRED, "reviewer", TenderTransitionTrigger.REVIEW, "blocked", List.of(), List.of(), Map.of()));
        assertEquals(TenderTaskStatus.REVISION_REQUIRED, task.status());

        assertFalse(workflow.listTransitions(taskId).isEmpty());
        assertFalse(workflow.listConfirmations(taskId).isEmpty());
        assertFalse(workflow.listIssues(taskId).isEmpty());
    }

    private CreateArtifactRequest artifact(TenderArtifactType type, TenderArtifactStatus status, Map<String, Object> content) {
        return new CreateArtifactRequest(type, status, TenderMode.ANALYSIS, content, List.of(), List.of(), List.of(), List.of(), Map.of(), "tester");
    }

    private ApplyCollaborationEventRequest event(String eventId, CollaborationEventType type,
                                                  Map<String, Object> value, String role) {
        return new ApplyCollaborationEventRequest(eventId, type, null, null, value, "tester", role,
                List.of(), List.of(), null);
    }
}
