package com.workpro.api;

import com.workpro.tender.domain.*;
import com.workpro.tender.service.TenderAnalysisService;
import com.workpro.tender.service.TenderCollaborationService;
import com.workpro.tender.service.TenderWorkflowService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenderWorkflowControllerTest {
    TenderTask task() {
        return new TenderTask("task-1", "投标任务", "u", TenderTaskStatus.CREATED, TenderMode.NONE,
                null, List.of(), Map.of(), "tester", Instant.now(), Instant.now(), null);
    }
    TenderArtifactVersion version() {
        return new TenderArtifactVersion("art-1", "task-1", 1, TenderArtifactStatus.READY,
                Map.of("project", "x"), List.of(), List.of(), List.of(), List.of(), Map.of(), "tester", Instant.now());
    }

    @Test void delegatesTaskAndTransitionApis() {
        var service = mock(TenderWorkflowService.class);
        var analysis = mock(TenderAnalysisService.class);
        var controller = new TenderWorkflowController(service, analysis, mock(TenderCollaborationService.class));
        var create = new CreateTaskRequest("投标任务", "u", List.of(), Map.of(), "tester");
        when(service.createTask(create)).thenReturn(task());
        assertEquals("task-1", controller.createTask(create).taskId());
        when(service.getTask("task-1")).thenReturn(task());
        assertEquals(TenderTaskStatus.CREATED, controller.getTask("task-1").status());
        var transition = new TransitionRequest(TenderTaskStatus.ANALYZING, "tester", TenderTransitionTrigger.USER, "start", List.of(), List.of(), Map.of());
        when(service.transition("task-1", transition)).thenReturn(new TenderTask("task-1", "投标任务", "u", TenderTaskStatus.ANALYZING, TenderMode.ANALYSIS, null, List.of(), Map.of(), "tester", Instant.now(), Instant.now(), null));
        assertEquals(TenderTaskStatus.ANALYZING, controller.transition("task-1", transition).status());
        when(service.listTransitions("task-1")).thenReturn(List.of(Map.of("toStatus", "ANALYZING")));
        assertEquals(1, controller.listTransitions("task-1").size());
    }

    @Test void delegatesArtifactConfirmationAndIssueApis() {
        var service = mock(TenderWorkflowService.class);
        var analysis = mock(TenderAnalysisService.class);
        var controller = new TenderWorkflowController(service, analysis, mock(TenderCollaborationService.class));
        var createArtifact = new CreateArtifactRequest(TenderArtifactType.TENDER_BRIEF, TenderArtifactStatus.READY,
                TenderMode.ANALYSIS, Map.of("project", "x"), List.of(), List.of(), List.of(), List.of(), Map.of(), "tester");
        when(service.createArtifact("task-1", createArtifact)).thenReturn(version());
        assertEquals(1, controller.createArtifact("task-1", createArtifact).version());
        var append = new AppendArtifactVersionRequest(TenderArtifactStatus.CONFIRMED, Map.of("project", "y"), List.of(), List.of(), List.of(), List.of(), Map.of(), "tester");
        when(service.appendArtifactVersion("art-1", append)).thenReturn(new TenderArtifactVersion("art-1", "task-1", 2, TenderArtifactStatus.CONFIRMED, Map.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), "tester", Instant.now()));
        assertEquals(2, controller.appendArtifactVersion("art-1", append).version());
        when(service.latestVersion("art-1")).thenReturn(version());
        assertEquals("art-1", controller.latestVersion("art-1").artifactId());
        when(service.listVersions("art-1")).thenReturn(List.of(version()));
        assertEquals(1, controller.listVersions("art-1").size());
        var confirmation = new CreateConfirmationRequest("是否承诺?", "高风险", List.of(), List.of("art-1"), List.of(), "tester");
        when(service.createConfirmation("task-1", confirmation)).thenReturn(Map.of("confirmationId", "c1"));
        assertEquals("c1", controller.createConfirmation("task-1", confirmation).get("confirmationId"));
        when(service.listConfirmations("task-1")).thenReturn(List.of(Map.of("confirmationId", "c1")));
        assertEquals(1, controller.listConfirmations("task-1").size());
        var answer = new AnswerConfirmationRequest(Map.of("id", "A"), null, "u");
        when(service.answerConfirmation("c1", answer)).thenReturn(Map.of("status", "ANSWERED"));
        assertEquals("ANSWERED", controller.answerConfirmation("c1", answer).get("status"));
        var issue = new CreateReviewIssueRequest("art-1", 1, "unsupported_claim", TenderIssueSeverity.HIGH, "SEC-1", "无证据", "补证据", true, TenderMode.COLLABORATION, "reviewer");
        when(service.createReviewIssue("task-1", issue)).thenReturn(Map.of("issueId", "i1"));
        assertEquals("i1", controller.createIssue("task-1", issue).get("issueId"));
        when(service.listIssues("task-1")).thenReturn(List.of(Map.of("issueId", "i1")));
        assertEquals(1, controller.listIssues("task-1").size());
    }

    @Test void delegatesAnalysisRunApi() {
        var service = mock(TenderWorkflowService.class);
        var analysis = mock(TenderAnalysisService.class);
        var controller = new TenderWorkflowController(service, analysis, mock(TenderCollaborationService.class));
        var request = new RunAnalysisRequest(List.of("doc-1"), List.of("corp-1"), List.of(), Map.of(), "tester");
        when(analysis.runAnalysis("task-1", request)).thenReturn(new RunAnalysisResult(
                "task-1", TenderTaskStatus.AWAITING_CONFIRMATION, List.of("ParseTenderDocuments"),
                Map.of("TENDER_BRIEF", "art-1"), List.of("c1"), List.of()));
        assertEquals(TenderTaskStatus.AWAITING_CONFIRMATION, controller.runAnalysis("task-1", request).status());
    }

    @Test void delegatesCollaborationApis() {
        var service = mock(TenderWorkflowService.class);
        var analysis = mock(TenderAnalysisService.class);
        var collaboration = mock(TenderCollaborationService.class);
        var controller = new TenderWorkflowController(service, analysis, collaboration);
        var request = new ApplyCollaborationEventRequest("evt-1", CollaborationEventType.CONFIRM_SCOPE,
                null, null, Map.of("target_lots", List.of("A")), "owner", "project_owner",
                List.of(), List.of(), null);
        when(collaboration.applyEvent("task-1", request)).thenReturn(
                new CollaborationEventResult("evt-1", "conf-1", "applied", Map.of("generation_readiness", "not_ready")));
        assertEquals("conf-1", controller.applyCollaborationEvent("task-1", request).confirmationId());
        when(collaboration.currentState("task-1")).thenReturn(Map.of("task_id", "task-1"));
        assertEquals("task-1", controller.collaborationState("task-1").get("task_id"));
        when(collaboration.evaluateReadiness("task-1", "owner")).thenReturn(
                new GenerationReadinessResult("needs_more_materials", List.of("evidence"), Map.of(), "art-1", 1));
        assertEquals("needs_more_materials", controller.evaluateReadiness("task-1", "owner").readiness());
    }
}
