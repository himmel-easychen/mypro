package com.workpro.tender.service;

import com.workpro.tender.domain.GenerationInputPack;
import com.workpro.tender.domain.TenderArtifactType;
import com.workpro.tender.domain.TenderArtifactVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GenerationContextAssembler {
    private final TenderWorkflowService workflow;
    private final ObjectMapper mapper;

    public GenerationContextAssembler(TenderWorkflowService workflow, ObjectMapper mapper) {
        this.workflow = workflow;
        this.mapper = mapper;
    }

    public GenerationInputPack assemble(String taskId, Map<String, Object> override) {
        boolean suppliedOverride = override != null && !override.isEmpty();
        Map<String, Object> raw = !suppliedOverride
                ? workflow.latestArtifactByType(taskId, TenderArtifactType.GENERATION_INPUT_PACK).content()
                : override;
        Map<String, Object> merged = new LinkedHashMap<>(raw);
        resolveSnapshot(taskId, merged, "template_pack", "template_artifact_id", TenderArtifactType.TENDER_TEMPLATE_PACK);
        resolveSnapshot(taskId, merged, "confirmed_content_pack", "confirmed_content_artifact_id", TenderArtifactType.CONFIRMED_CONTENT_PACK);
        merged.computeIfAbsent("task", ignored -> taskContext(taskId));
        merged.computeIfAbsent("blocking_placeholders", ignored -> List.of());
        if (suppliedOverride) merged.computeIfAbsent("readiness", ignored -> readiness(taskId));
        else merged.put("readiness", readiness(taskId));
        return mapper.convertValue(merged, GenerationInputPack.class);
    }

    private void resolveSnapshot(String taskId, Map<String, Object> target, String snapshotKey,
                                 String idKey, TenderArtifactType fallbackType) {
        if (target.get(snapshotKey) instanceof Map<?, ?> map && !map.isEmpty()) return;
        Object id = target.get(idKey);
        TenderArtifactVersion artifact = id instanceof String value && !value.isBlank()
                ? workflow.latestVersion(value)
                : workflow.latestArtifactByType(taskId, fallbackType);
        target.put(snapshotKey, artifact.content());
        target.putIfAbsent(idKey, artifact.artifactId());
    }

    private Map<String, Object> taskContext(String taskId) {
        var task = workflow.getTask(taskId);
        return Map.of("task_id", task.taskId(), "project_name", task.name());
    }

    private Map<String, Object> readiness(String taskId) {
        try {
            Map<String, Object> content = workflow.latestArtifactByType(taskId, TenderArtifactType.GENERATION_READINESS_REPORT).content();
            return Map.of("status", content.getOrDefault("readiness", "not_ready"),
                    "ready_for_final_docx", "ready_for_generation".equals(content.get("readiness")));
        } catch (RuntimeException ignored) {
            return Map.of("status", "not_ready", "ready_for_final_docx", false);
        }
    }
}
