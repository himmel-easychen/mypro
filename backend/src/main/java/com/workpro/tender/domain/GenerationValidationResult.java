package com.workpro.tender.domain;

import java.util.List;

public record GenerationValidationResult(boolean valid, List<String> errors) {
    public GenerationValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
