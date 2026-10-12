package com.workpro.tender.domain;

import java.time.Instant;

public record TenderArtifact(String artifactId, String taskId, TenderArtifactType type, int latestVersion,
                             TenderArtifactStatus status, TenderMode mode, Instant createdAt, Instant updatedAt) {}
