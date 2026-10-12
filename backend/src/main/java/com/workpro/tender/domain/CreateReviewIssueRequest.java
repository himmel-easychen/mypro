package com.workpro.tender.domain;

public record CreateReviewIssueRequest(String artifactId, Integer artifactVersion, String issueType,
                                       TenderIssueSeverity severity, String location, String description,
                                       String suggestedFix, boolean blocked, TenderMode rollbackTarget,
                                       String createdBy) {}
