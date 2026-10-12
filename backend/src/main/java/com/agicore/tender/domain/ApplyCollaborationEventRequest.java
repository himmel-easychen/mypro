package com.agicore.tender.domain;

import java.util.List;
import java.util.Map;

public record ApplyCollaborationEventRequest(
        String eventId,
        CollaborationEventType type,
        String requestId,
        String decisionId,
        Map<String, Object> value,
        String actorId,
        String actorRole,
        List<Object> sourceRefs,
        List<String> affectedArtifactIds,
        String supersedesConfirmationId
) {}
