package com.workpro.tender.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GenerationInputPack(
        String artifact,
        @JsonProperty("contract_version") String contractVersion,
        Map<String, Object> task,
        @JsonProperty("analysis_artifacts") Map<String, Object> analysisArtifacts,
        @JsonProperty("template_artifact_id") String templateArtifactId,
        @JsonProperty("confirmed_content_artifact_id") String confirmedContentArtifactId,
        @JsonProperty("readiness_artifact_type") String readinessArtifactType,
        @JsonProperty("template_pack") Map<String, Object> templatePack,
        @JsonProperty("confirmed_content_pack") Map<String, Object> confirmedContentPack,
        Map<String, Object> pricing,
        @JsonProperty("scoring_targets") List<Map<String, Object>> scoringTargets,
        @JsonProperty("blocking_placeholders") List<Map<String, Object>> blockingPlaceholders,
        @JsonProperty("generation_policy") Map<String, Object> generationPolicy,
        Map<String, Object> readiness
) {
    public GenerationInputPack {
        task = map(task);
        analysisArtifacts = map(analysisArtifacts);
        templatePack = map(templatePack);
        confirmedContentPack = map(confirmedContentPack);
        pricing = map(pricing);
        scoringTargets = list(scoringTargets);
        blockingPlaceholders = list(blockingPlaceholders);
        generationPolicy = map(generationPolicy);
        readiness = map(readiness);
    }

    private static Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static List<Map<String, Object>> list(List<Map<String, Object>> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
