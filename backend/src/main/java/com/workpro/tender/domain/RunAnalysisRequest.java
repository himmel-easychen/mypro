package com.workpro.tender.domain;

import java.util.List;
import java.util.Map;

public record RunAnalysisRequest(List<String> tenderDocumentIds, List<String> enterpriseDocumentIds,
                                 List<String> referenceDocumentIds, Map<String, Object> options,
                                 String triggeredBy) {}
