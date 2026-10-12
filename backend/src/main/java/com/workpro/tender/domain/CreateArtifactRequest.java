package com.workpro.tender.domain;

import java.util.List;
import java.util.Map;

public record CreateArtifactRequest(TenderArtifactType type, TenderArtifactStatus status, TenderMode mode,
                                    Map<String, Object> content, List<Object> sourceRefs, List<Object> evidenceRefs,
                                    List<Object> confirmationRefs, List<Object> issues, Map<String, Object> metadata,
                                    String createdBy) {}
