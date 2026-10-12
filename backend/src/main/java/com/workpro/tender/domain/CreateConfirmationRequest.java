package com.workpro.tender.domain;

import java.util.List;

public record CreateConfirmationRequest(String question, String reason, List<Object> options,
                                        List<String> affectedArtifactIds, List<String> affectedSections,
                                        String createdBy) {}
