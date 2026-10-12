package com.agicore.tender.domain;

import java.util.List;
import java.util.Map;

public record GenerationReadinessResult(String readiness, List<String> blockingItems,
                                        Map<String, String> requiredArtifacts,
                                        String artifactId, int version) {}
