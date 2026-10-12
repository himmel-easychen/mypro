package com.workpro.tender.domain;

import java.util.Map;

public record CollaborationEventResult(String eventId, String confirmationId, String status,
                                       Map<String, Object> collaborationState) {}
