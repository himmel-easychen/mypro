package com.workpro.api;

import com.workpro.tender.domain.*;
import com.workpro.tender.service.TenderAnalysisService;
import com.workpro.tender.service.TenderCollaborationService;
import com.workpro.tender.service.TenderWorkflowService;
import com.workpro.tender.service.TenderLlmAnalysisService;
import com.workpro.tender.service.TenderGenerationService;
import com.workpro.tender.service.TenderPipelineService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tender")
public class TenderWorkflowController {
    private final TenderWorkflowService workflow;
    private final TenderAnalysisService analysis;
    private final TenderCollaborationService collaboration;
    private final TenderLlmAnalysisService llmAnalysis;
    private final TenderGenerationService generation;
    private final TenderPipelineService pipeline;

    @Autowired
    public TenderWorkflowController(TenderWorkflowService workflow, TenderAnalysisService analysis,
                                    TenderCollaborationService collaboration, TenderLlmAnalysisService llmAnalysis,
                                    TenderGenerationService generation, TenderPipelineService pipeline) {
        this.workflow = workflow;
        this.analysis = analysis;
        this.collaboration = collaboration;
        this.llmAnalysis = llmAnalysis;
        this.generation = generation;
        this.pipeline = pipeline;
    }

    public TenderWorkflowController(TenderWorkflowService workflow, TenderAnalysisService analysis,
                                    TenderCollaborationService collaboration) {
        this(workflow, analysis, collaboration, null, null, null);
    }

    @PostMapping("/tasks")
    public TenderTask createTask(@RequestBody CreateTaskRequest request) {
        return workflow.createTask(request);
    }

    @GetMapping("/tasks/{taskId}")
    public TenderTask getTask(@PathVariable String taskId) {
        return workflow.getTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/transition")
    public TenderTask transition(@PathVariable String taskId, @RequestBody TransitionRequest request) {
        return workflow.transition(taskId, request);
    }

    @GetMapping("/tasks/{taskId}/transitions")
    public List<Map<String, Object>> listTransitions(@PathVariable String taskId) {
        return workflow.listTransitions(taskId);
    }

    @PostMapping("/tasks/{taskId}/artifacts")
    public TenderArtifactVersion createArtifact(@PathVariable String taskId, @RequestBody CreateArtifactRequest request) {
        return workflow.createArtifact(taskId, request);
    }

    @GetMapping("/tasks/{taskId}/artifacts")
    public List<TenderArtifact> listArtifacts(@PathVariable String taskId) {
        return workflow.listArtifacts(taskId);
    }

    @PostMapping("/artifacts/{artifactId}/versions")
    public TenderArtifactVersion appendArtifactVersion(@PathVariable String artifactId, @RequestBody AppendArtifactVersionRequest request) {
        return workflow.appendArtifactVersion(artifactId, request);
    }

    @GetMapping("/artifacts/{artifactId}/versions/latest")
    public TenderArtifactVersion latestVersion(@PathVariable String artifactId) {
        return workflow.latestVersion(artifactId);
    }

    @GetMapping("/artifacts/{artifactId}/versions")
    public List<TenderArtifactVersion> listVersions(@PathVariable String artifactId) {
        return workflow.listVersions(artifactId);
    }

    @PostMapping("/tasks/{taskId}/confirmations")
    public Map<String, Object> createConfirmation(@PathVariable String taskId, @RequestBody CreateConfirmationRequest request) {
        return workflow.createConfirmation(taskId, request);
    }

    @GetMapping("/tasks/{taskId}/confirmations")
    public List<Map<String, Object>> listConfirmations(@PathVariable String taskId) {
        return workflow.listConfirmations(taskId);
    }

    @PostMapping("/confirmations/{confirmationId}/answer")
    public Map<String, Object> answerConfirmation(@PathVariable String confirmationId, @RequestBody AnswerConfirmationRequest request) {
        return workflow.answerConfirmation(confirmationId, request);
    }

    @PostMapping("/tasks/{taskId}/issues")
    public Map<String, Object> createIssue(@PathVariable String taskId, @RequestBody CreateReviewIssueRequest request) {
        return workflow.createReviewIssue(taskId, request);
    }

    @GetMapping("/tasks/{taskId}/issues")
    public List<Map<String, Object>> listIssues(@PathVariable String taskId) {
        return workflow.listIssues(taskId);
    }

    @PostMapping("/tasks/{taskId}/analysis/run")
    public RunAnalysisResult runAnalysis(@PathVariable String taskId, @RequestBody(required = false) RunAnalysisRequest request) {
        return analysis.runAnalysis(taskId, request);
    }

    @PostMapping("/tasks/{taskId}/collaboration/events")
    public CollaborationEventResult applyCollaborationEvent(@PathVariable String taskId,
                                                             @RequestBody ApplyCollaborationEventRequest request) {
        return collaboration.applyEvent(taskId, request);
    }

    @PostMapping("/tasks/{taskId}/analysis/llm-extract")
    public Map<String, Object> llmExtract(@PathVariable String taskId,
                                          @RequestBody TenderLlmExtractionRequest request) {
        workflow.getTask(taskId);
        if (llmAnalysis == null) throw new IllegalStateException("LLM_ANALYSIS_SERVICE_UNAVAILABLE");
        return llmAnalysis.extract(request.artifactType(), request.sourceChunks());
    }

    @GetMapping("/tasks/{taskId}/collaboration/state")
    public Map<String, Object> collaborationState(@PathVariable String taskId) {
        return collaboration.currentState(taskId);
    }

    @PostMapping("/tasks/{taskId}/collaboration/readiness")
    public GenerationReadinessResult evaluateReadiness(@PathVariable String taskId,
                                                        @RequestParam(required = false) String actorId) {
        return collaboration.evaluateReadiness(taskId, actorId);
    }

    @PostMapping("/tasks/{taskId}/generation/run")
    public RunGenerationResult runGeneration(@PathVariable String taskId,
                                              @RequestBody(required = false) RunGenerationRequest request) throws Exception {
        if (generation == null) throw new IllegalStateException("GENERATION_SERVICE_UNAVAILABLE");
        return generation.run(taskId, request);
    }

    @PostMapping("/tasks/{taskId}/pipeline/finalize")
    public FinalizeTenderPipelineResult finalizePipeline(@PathVariable String taskId,
                                                          @RequestBody FinalizeTenderPipelineRequest request) throws Exception {
        if (pipeline == null) throw new IllegalStateException("TENDER_PIPELINE_SERVICE_UNAVAILABLE");
        return pipeline.finalizeAndGenerate(taskId, request);
    }

    @GetMapping("/artifacts/{artifactId}/download")
    public ResponseEntity<FileSystemResource> downloadGenerationArtifact(@PathVariable String artifactId) {
        if (generation == null) throw new IllegalStateException("GENERATION_SERVICE_UNAVAILABLE");
        var artifact = generation.downloadable(artifactId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(artifact.fileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(new FileSystemResource(artifact.path()));
    }
}
