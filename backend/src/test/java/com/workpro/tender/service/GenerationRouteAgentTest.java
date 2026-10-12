package com.workpro.tender.service;

import com.workpro.tender.domain.GenerationValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationRouteAgentTest {
    private final GenerationRouteAgent router = new GenerationRouteAgent();

    @Test
    void routesOnlyValidPreparationToWriting() {
        assertThat(router.afterPreparation(new GenerationValidationResult(true, List.of())))
                .isEqualTo(GenerationRouteAgent.Route.WRITE);
        assertThat(router.afterPreparation(new GenerationValidationResult(false, List.of("missing"))))
                .isEqualTo(GenerationRouteAgent.Route.BLOCKED);
    }

    @Test
    void routesReviewToExportOrRevision() {
        assertThat(router.afterReview(new TenderGenerationReviewer.ReviewResult(true, List.of())))
                .isEqualTo(GenerationRouteAgent.Route.EXPORT);
        assertThat(router.afterReview(new TenderGenerationReviewer.ReviewResult(false, List.of())))
                .isEqualTo(GenerationRouteAgent.Route.REVISION);
    }
}
