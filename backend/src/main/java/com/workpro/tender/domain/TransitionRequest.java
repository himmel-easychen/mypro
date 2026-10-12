package com.workpro.tender.domain;

import java.util.List;
import java.util.Map;

public record TransitionRequest(TenderTaskStatus toStatus, String triggeredBy, TenderTransitionTrigger triggerType,
                                String reason, List<String> relatedArtifactIds, List<String> relatedIssueIds,
                                Map<String, Object> metadata) {}
