package com.agicore.tender.service;

import com.agicore.tender.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TenderGenerationService {
    private final TenderWorkflowService workflow;
    private final GenerationPreparationAgent preparationAgent;
    private final GenerationWritingAgent writingAgent;
    private final GenerationQualityAgent qualityAgent;
    private final GenerationRouteAgent routeAgent;
    private final TenderDocxRenderer renderer;
    private final TenderSandboxService sandbox;

    @Autowired
    public TenderGenerationService(TenderWorkflowService workflow, GenerationPreparationAgent preparationAgent,
                                   GenerationWritingAgent writingAgent, GenerationQualityAgent qualityAgent,
                                   GenerationRouteAgent routeAgent, TenderDocxRenderer renderer) {
        this(workflow, preparationAgent, writingAgent, qualityAgent, routeAgent, renderer, new TenderSandboxService(renderer));
    }

    public TenderGenerationService(TenderWorkflowService workflow, GenerationPreparationAgent preparationAgent,
                                   GenerationWritingAgent writingAgent, GenerationQualityAgent qualityAgent,
                                   GenerationRouteAgent routeAgent, TenderDocxRenderer renderer,
                                   TenderSandboxService sandbox) {
        this.workflow = workflow;
        this.preparationAgent = preparationAgent;
        this.writingAgent = writingAgent;
        this.qualityAgent = qualityAgent;
        this.routeAgent = routeAgent;
        this.renderer = renderer;
        this.sandbox = sandbox;
    }

    public RunGenerationResult run(String taskId, RunGenerationRequest request) throws Exception {
        RunGenerationRequest safeRequest = request == null ? new RunGenerationRequest(null, false, Map.of()) : request;
        String actor = safeRequest.actorId() == null || safeRequest.actorId().isBlank() ? "generation-agent" : safeRequest.actorId();
        TenderTask task = workflow.getTask(taskId);
        GenerationPreparationAgent.PreparationResult preparation = preparationAgent.prepare(taskId, safeRequest.inputPack());
        GenerationInputPack pack = preparation.pack();
        if (routeAgent.afterPreparation(preparation.validation()) == GenerationRouteAgent.Route.BLOCKED) {
            throw new IllegalStateException("GENERATION_INPUT_PACK_INVALID:"
                    + String.join(",", preparation.validation().errors()));
        }

        if (task.status() == TenderTaskStatus.COLLABORATION_READY) {
            workflow.transition(taskId, transition(TenderTaskStatus.GENERATING, actor, "generation started"));
        } else if (task.status() != TenderTaskStatus.GENERATING && task.status() != TenderTaskStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("GENERATION_TASK_STATUS_INVALID:" + task.status());
        } else if (task.status() == TenderTaskStatus.REVISION_REQUIRED) {
            workflow.transition(taskId, transition(TenderTaskStatus.GENERATING, actor, "revision generation started"));
        }

        List<TenderSectionWriter.SectionDraft> drafts = writingAgent.write(pack, safeRequest.llmEnabled());
        TenderArtifactVersion draft = workflow.createArtifact(taskId, artifact(TenderArtifactType.DRAFT_PACK,
                TenderArtifactStatus.READY, Map.of("artifact", "DraftPack", "contract_version", "1.0",
                        "sections", drafts.stream().map(this::sectionMap).toList()), actor));
        workflow.transition(taskId, transition(TenderTaskStatus.REVIEWING, actor, "draft generated"));

        TenderGenerationReviewer.ReviewResult review = qualityAgent.review(drafts);
        TenderArtifactVersion reviewArtifact = workflow.createArtifact(taskId, artifact(TenderArtifactType.REVIEW_REPORT,
                review.passed() ? TenderArtifactStatus.READY : TenderArtifactStatus.BLOCKED,
                Map.of("artifact", "ReviewReport", "passed", review.passed(), "issues", review.issues(),
                        "draft_artifact_id", draft.artifactId()), actor));
        if (routeAgent.afterReview(review) == GenerationRouteAgent.Route.REVISION) {
            for (Map<String, Object> issue : review.issues()) {
                workflow.createReviewIssue(taskId, new CreateReviewIssueRequest(draft.artifactId(), draft.version(),
                        String.valueOf(issue.get("issue_type")), TenderIssueSeverity.HIGH,
                        String.valueOf(issue.get("section_id")), String.valueOf(issue.get("description")),
                        "补齐确认内容后重新生成", true, TenderMode.COLLABORATION, actor));
            }
            workflow.transition(taskId, transition(TenderTaskStatus.REVISION_REQUIRED, actor, "generation review blocked"));
            return new RunGenerationResult(taskId, "revision_required", draft.artifactId(), reviewArtifact.artifactId(),
                    null, null, null, drafts.stream().map(TenderSectionWriter.SectionDraft::sectionId).toList());
        }

        workflow.transition(taskId, transition(TenderTaskStatus.EXPORTING, actor, "review passed"));
        TenderDocxRenderer.RenderedDocument rendered = sandbox.renderDocx(taskId, pack, drafts, actor);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("artifact", "ArtifactPack"); output.put("format", "docx");
        output.put("file_name", rendered.fileName()); output.put("storage_uri", rendered.path());
        output.put("checksum", rendered.checksum()); output.put("size_bytes", rendered.sizeBytes());
        output.put("created_at", rendered.createdAt()); output.put("draft_artifact_id", draft.artifactId());
        output.put("review_artifact_id", reviewArtifact.artifactId());
        TenderArtifactVersion artifact = workflow.createArtifact(taskId, artifact(TenderArtifactType.ARTIFACT_PACK,
                TenderArtifactStatus.READY, output, actor));
        workflow.transition(taskId, transition(TenderTaskStatus.EXPORT_READY, actor, "docx export ready"));
        return new RunGenerationResult(taskId, "export_ready", draft.artifactId(), reviewArtifact.artifactId(),
                artifact.artifactId(), rendered.fileName(), "/api/tender/artifacts/" + artifact.artifactId() + "/download",
                drafts.stream().map(TenderSectionWriter.SectionDraft::sectionId).toList());
    }

    public DownloadableArtifact downloadable(String artifactId) {
        TenderArtifactVersion artifact = workflow.latestVersion(artifactId);
        if (workflow.getArtifact(artifactId).type() != TenderArtifactType.ARTIFACT_PACK) {
            throw new IllegalArgumentException("GENERATION_ARTIFACT_TYPE_INVALID");
        }
        String pathValue = String.valueOf(artifact.content().getOrDefault("storage_uri", ""));
        String fileName = String.valueOf(artifact.content().getOrDefault("file_name", "投标文件.docx"));
        if (pathValue.isBlank()) throw new IllegalStateException("GENERATION_ARTIFACT_FILE_MISSING");
        Path path = Path.of(pathValue).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalStateException("GENERATION_ARTIFACT_FILE_NOT_FOUND");
        return new DownloadableArtifact(fileName, path);
    }

    private Map<String, Object> sectionMap(TenderSectionWriter.SectionDraft draft) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("part_id", draft.partId()); value.put("part_title", draft.partTitle());
        value.put("section_id", draft.sectionId()); value.put("title", draft.title());
        value.put("mode", draft.mode()); value.put("content", draft.content());
        value.put("source_pages", draft.sourcePages());
        return value;
    }

    private CreateArtifactRequest artifact(TenderArtifactType type, TenderArtifactStatus status,
                                           Map<String, Object> content, String actor) {
        return new CreateArtifactRequest(type, status, TenderMode.GENERATION, content,
                List.of(), List.of(), List.of(), List.of(), Map.of("contract_version", "1.0"), actor);
    }

    private TransitionRequest transition(TenderTaskStatus status, String actor, String reason) {
        return new TransitionRequest(status, actor, TenderTransitionTrigger.AGENT, reason,
                List.of(), List.of(), Map.of());
    }

    public record DownloadableArtifact(String fileName, Path path) {}
}
