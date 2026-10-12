package com.agicore.tender.domain;

import java.util.List;

public record RunGenerationResult(
        String taskId,
        String status,
        String draftArtifactId,
        String reviewArtifactId,
        String outputArtifactId,
        String fileName,
        String downloadUrl,
        List<String> generatedSections
) {}
