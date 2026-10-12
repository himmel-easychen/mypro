package com.workpro.tender.service;

import com.workpro.tender.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenderAnalysisServiceTest {
    @Test void runAnalysisCreatesContractArtifactsAndMovesToAwaitingConfirmation() {
        TenderWorkflowService workflow = mock(TenderWorkflowService.class);
        TenderAnalysisService service = new TenderAnalysisService(workflow);
        TenderTask created = task(TenderTaskStatus.CREATED);
        TenderTask analyzing = task(TenderTaskStatus.ANALYZING);
        TenderTask ready = task(TenderTaskStatus.ANALYSIS_READY);
        TenderTask awaiting = task(TenderTaskStatus.AWAITING_CONFIRMATION);

        when(workflow.getTask("task-1")).thenReturn(created);
        when(workflow.transition(eq("task-1"), argThat(req -> req.toStatus() == TenderTaskStatus.ANALYZING))).thenReturn(analyzing);
        when(workflow.transition(eq("task-1"), argThat(req -> req.toStatus() == TenderTaskStatus.ANALYSIS_READY))).thenReturn(ready);
        when(workflow.transition(eq("task-1"), argThat(req -> req.toStatus() == TenderTaskStatus.AWAITING_CONFIRMATION))).thenReturn(awaiting);
        when(workflow.listArtifacts("task-1")).thenReturn(List.of());
        when(workflow.createArtifact(eq("task-1"), any())).thenAnswer(invocation -> {
            CreateArtifactRequest request = invocation.getArgument(1);
            return version("art-" + request.type().name(), request.status());
        });
        when(workflow.createConfirmation(eq("task-1"), any())).thenReturn(Map.of("confirmationId", "c1"));

        RunAnalysisResult result = service.runAnalysis("task-1",
                new RunAnalysisRequest(List.of("doc-1"), List.of("corp-1"), List.of(), Map.of(), "tester"));

        assertEquals(TenderTaskStatus.AWAITING_CONFIRMATION, result.status());
        assertTrue(result.executedNodes().contains("EvaluateStrategyOptions"));
        assertTrue(result.artifactIdsByType().containsKey("SCORE_PROJECTION_PACK"));
        assertTrue(result.artifactIdsByType().containsKey("STRATEGY_EVALUATION_PACK"));
        assertTrue(result.artifactIdsByType().containsKey("COLLABORATION_MEMORY_SEED_PACK"));
        assertTrue(result.artifactIdsByType().containsKey("TENDER_ANALYSIS_SUMMARY"));
        assertEquals(List.of("c1"), result.confirmationIds());
        verify(workflow, atLeastOnce()).createArtifact(eq("task-1"), argThat(req ->
                req.type() == TenderArtifactType.STRATEGY_CANDIDATE_PACK
                        && "internal_only".equals(req.content().get("visibility"))));
        verify(workflow).transition(eq("task-1"), argThat(req -> req.toStatus() == TenderTaskStatus.AWAITING_CONFIRMATION));
    }

    private TenderTask task(TenderTaskStatus status) {
        return new TenderTask("task-1", "测试投标任务", "u", status,
                status == TenderTaskStatus.CREATED ? TenderMode.NONE : TenderMode.ANALYSIS,
                null, List.of("source-doc-1"), Map.of(), "tester", Instant.now(), Instant.now(), null);
    }

    private TenderArtifactVersion version(String artifactId, TenderArtifactStatus status) {
        return new TenderArtifactVersion(artifactId, "task-1", 1, status,
                Map.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), "tester", Instant.now());
    }
}
