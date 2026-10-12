package com.workpro.tender.service;

import com.workpro.tender.domain.GenerationValidationResult;
import org.springframework.stereotype.Component;

@Component
public class GenerationRouteAgent {
    public Route afterPreparation(GenerationValidationResult validation) {
        return validation.valid() ? Route.WRITE : Route.BLOCKED;
    }

    public Route afterReview(TenderGenerationReviewer.ReviewResult review) {
        return review.passed() ? Route.EXPORT : Route.REVISION;
    }

    public enum Route { WRITE, EXPORT, REVISION, BLOCKED }
}
