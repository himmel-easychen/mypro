package com.agicore.tender.service;

import com.agicore.tender.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TenderPipelineService {
    private final TenderWorkflowService workflow;
    private final TenderCollaborationService collaboration;
    private final TenderGenerationService generation;

    public TenderPipelineService(TenderWorkflowService workflow, TenderCollaborationService collaboration,
                                 TenderGenerationService generation) {
        this.workflow = workflow;
        this.collaboration = collaboration;
        this.generation = generation;
    }

    @Transactional
    public FinalizeTenderPipelineResult finalizeAndGenerate(String taskId, FinalizeTenderPipelineRequest request) throws Exception {
        FinalizeTenderPipelineRequest safe = request == null
                ? new FinalizeTenderPipelineRequest(List.of(), false, "pipeline") : request;
        String actor = safe.actorId() == null || safe.actorId().isBlank() ? "pipeline" : safe.actorId();
        for (Map<String, Object> pending : workflow.listConfirmations(taskId)) {
            if ("PENDING".equals(pending.get("status"))) {
                workflow.answerConfirmation(String.valueOf(pending.get("confirmationId")),
                        new AnswerConfirmationRequest(Map.of("id", "ENTER_COLLABORATION"),
                                "进入协作模式并使用本次提交的确认事件", actor));
            }
        }
        TenderTask task = workflow.getTask(taskId);
        if (task.status() == TenderTaskStatus.AWAITING_CONFIRMATION) {
            workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.COLLABORATING, actor,
                    TenderTransitionTrigger.USER, "pipeline collaboration started", List.of(), List.of(), Map.of()));
        }
        List<CollaborationEventResult> applied = new ArrayList<>();
        if (safe.events() != null) for (ApplyCollaborationEventRequest event : safe.events()) {
            applied.add(collaboration.applyEvent(taskId, event));
        }
        GenerationReadinessResult readiness = collaboration.evaluateReadiness(taskId, actor);
        if (!"ready_for_generation".equals(readiness.readiness())) {
            return new FinalizeTenderPipelineResult(taskId, "needs_confirmation", readiness, null, List.copyOf(applied));
        }
        task = workflow.getTask(taskId);
        if (task.status() == TenderTaskStatus.COLLABORATING) {
            workflow.transition(taskId, new TransitionRequest(TenderTaskStatus.COLLABORATION_READY, actor,
                    TenderTransitionTrigger.AGENT, "pipeline contracts ready", List.of(), List.of(), Map.of()));
        }
        RunGenerationResult output = generation.run(taskId, new RunGenerationRequest(actor, safe.llmEnabled(), Map.of()));
        return new FinalizeTenderPipelineResult(taskId, output.status(), readiness, output, List.copyOf(applied));
    }
}
