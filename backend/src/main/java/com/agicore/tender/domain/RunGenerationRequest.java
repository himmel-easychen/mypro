package com.agicore.tender.domain;

import java.util.Map;

public record RunGenerationRequest(
        String actorId,
        Boolean useLlm,
        Map<String, Object> inputPack
) {
    public boolean llmEnabled() {
        return !Boolean.FALSE.equals(useLlm);
    }
}
