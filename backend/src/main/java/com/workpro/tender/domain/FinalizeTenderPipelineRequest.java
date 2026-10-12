package com.workpro.tender.domain;

import java.util.List;

public record FinalizeTenderPipelineRequest(List<ApplyCollaborationEventRequest> events,
                                            boolean llmEnabled,
                                            String actorId) {}
