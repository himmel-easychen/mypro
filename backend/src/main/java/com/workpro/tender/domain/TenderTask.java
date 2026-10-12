package com.workpro.tender.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TenderTask(String taskId, String name, String ownerId, TenderTaskStatus status,
                         TenderMode currentMode, String currentStage, List<String> sourceDocumentIds,
                         Map<String, Object> metadata, String createdBy, Instant createdAt,
                         Instant updatedAt, Instant archivedAt) {}
