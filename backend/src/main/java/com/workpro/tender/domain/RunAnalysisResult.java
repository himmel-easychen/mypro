package com.workpro.tender.domain;

import java.util.List;
import java.util.Map;

public record RunAnalysisResult(String taskId, TenderTaskStatus status, List<String> executedNodes,
                                Map<String, String> artifactIdsByType, List<String> confirmationIds,
                                List<String> reviewIssueIds) {}
