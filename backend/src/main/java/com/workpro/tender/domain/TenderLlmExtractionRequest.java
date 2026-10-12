package com.workpro.tender.domain;

import java.util.List;
import java.util.Map;

public record TenderLlmExtractionRequest(String artifactType, List<Map<String, Object>> sourceChunks) {}
