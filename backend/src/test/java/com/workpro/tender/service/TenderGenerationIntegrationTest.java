package com.workpro.tender.service;

import com.workpro.tender.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.data.redis.host=127.0.0.1",
        "workpro.ocr.enabled=false",
        "workpro.tender.generation-storage-dir=target/test-artifacts/generation-e2e"
})
class TenderGenerationIntegrationTest {
    @Autowired TenderWorkflowService workflow;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void runsHttpGenerationWorkflowThroughDownloadableDocx() throws Exception {
        TenderTask task = workflow.createTask(new CreateTaskRequest(
                "广州车辆维保生成闭环测试", "tester", List.of(), Map.of(), "tester"));
        String taskId = task.taskId();
        transition(taskId, TenderTaskStatus.ANALYZING);
        addRequiredAnalysisArtifacts(taskId);
        transition(taskId, TenderTaskStatus.ANALYSIS_READY);
        transition(taskId, TenderTaskStatus.AWAITING_CONFIRMATION);
        transition(taskId, TenderTaskStatus.COLLABORATING);
        workflow.createArtifact(taskId, artifact(TenderArtifactType.GENERATION_READINESS_REPORT,
                TenderArtifactStatus.READY, Map.of("readiness", "ready_for_generation")));
        transition(taskId, TenderTaskStatus.COLLABORATION_READY);

        Map<String, Object> pack = mapper.readValue(
                getClass().getResourceAsStream("/fixtures/tender/generation-input-pack-guangzhou-vehicle-maintenance.json"),
                new TypeReference<>() {});
        pack.put("blocking_placeholders", List.of());
        pack.put("readiness", Map.of("status", "ready_for_generation", "ready_for_final_docx", true));
        Map<String, Object> request = Map.of("actorId", "tester", "useLlm", false, "inputPack", pack);

        String response = mvc.perform(post("/api/tender/tasks/{taskId}/generation/run", taskId)
                        .contentType("application/json").content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        RunGenerationResult result = mapper.readValue(response, RunGenerationResult.class);

        assertThat(result.status()).isEqualTo("export_ready");
        assertThat(result.generatedSections()).hasSize(26);
        assertThat(workflow.getTask(taskId).status()).isEqualTo(TenderTaskStatus.EXPORT_READY);
        TenderArtifactVersion output = workflow.latestVersion(result.outputArtifactId());
        assertThat(Files.size(Path.of(String.valueOf(output.content().get("storage_uri"))))).isGreaterThan(0);
        assertThat(workflow.latestArtifactByType(taskId, TenderArtifactType.DRAFT_PACK).content())
                .containsEntry("artifact", "DraftPack").containsKey("sections");
        assertThat(workflow.latestArtifactByType(taskId, TenderArtifactType.REVIEW_REPORT).content())
                .containsEntry("artifact", "ReviewReport").containsEntry("passed", true);
        assertThat(output.content()).containsEntry("artifact", "ArtifactPack")
                .containsEntry("format", "docx").containsKeys("checksum", "draft_artifact_id", "review_artifact_id");
        mvc.perform(get(result.downloadUrl()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    private void addRequiredAnalysisArtifacts(String taskId) {
        create(taskId, TenderArtifactType.TENDER_BRIEF);
        create(taskId, TenderArtifactType.REQUIREMENT_MATRIX);
        create(taskId, TenderArtifactType.SCORING_MATRIX);
        create(taskId, TenderArtifactType.EVIDENCE_BINDING_PACK);
        create(taskId, TenderArtifactType.SCORE_PROJECTION_PACK);
        create(taskId, TenderArtifactType.STRATEGY_EVALUATION_PACK);
        create(taskId, TenderArtifactType.TENDER_TEMPLATE_PACK);
        create(taskId, TenderArtifactType.CONFIRMED_CONTENT_PACK);
        create(taskId, TenderArtifactType.GENERATION_INPUT_PACK);
    }

    private void create(String taskId, TenderArtifactType type) {
        workflow.createArtifact(taskId, artifact(type, TenderArtifactStatus.READY, new LinkedHashMap<>()));
    }

    private CreateArtifactRequest artifact(TenderArtifactType type, TenderArtifactStatus status,
                                           Map<String, Object> content) {
        return new CreateArtifactRequest(type, status, TenderMode.ANALYSIS, content,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), Map.of(), "tester");
    }

    private void transition(String taskId, TenderTaskStatus status) {
        workflow.transition(taskId, new TransitionRequest(status, "tester", TenderTransitionTrigger.USER,
                "generation e2e test", List.of(), List.of(), Map.of()));
    }
}
