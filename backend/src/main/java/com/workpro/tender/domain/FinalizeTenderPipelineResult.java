package com.workpro.tender.domain;

import java.util.List;

public record FinalizeTenderPipelineResult(String taskId, String status,
                                           GenerationReadinessResult readiness,
                                           RunGenerationResult generation,
                                           List<CollaborationEventResult> events) {}
