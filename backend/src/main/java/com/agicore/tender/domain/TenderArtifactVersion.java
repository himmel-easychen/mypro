package com.agicore.tender.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TenderArtifactVersion(String artifactId, String taskId, int version, TenderArtifactStatus status,
                                    Map<String, Object> content, List<Object> sourceRefs, List<Object> evidenceRefs,
                                    List<Object> confirmationRefs, List<Object> issues, Map<String, Object> metadata,
                                    String createdBy, Instant createdAt) {}
