package com.workpro.tender.domain;

import java.util.List;
import java.util.Map;

public record CreateTaskRequest(String name, String ownerId, List<String> sourceDocumentIds,
                                Map<String, Object> metadata, String createdBy) {}
