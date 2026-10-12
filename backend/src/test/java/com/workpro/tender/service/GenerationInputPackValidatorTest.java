package com.workpro.tender.service;

import com.workpro.tender.domain.GenerationInputPack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationInputPackValidatorTest {
    private final GenerationInputPackValidator validator = new GenerationInputPackValidator();

    @Test void acceptsConfirmedReadyPack() {
        var result = validator.validate(pack(List.of(), true));
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test void rejectsUnresolvedRequiredPlaceholderAndReadiness() {
        var blockers = List.of(Map.<String, Object>of(
                "field", "bidder.full_name", "required_before_docx", true, "resolved", false));
        var result = validator.validate(pack(blockers, false));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("blocking_placeholder:bidder.full_name", "readiness:not_ready_for_final_docx");
    }

    private GenerationInputPack pack(List<Map<String, Object>> blockers, boolean ready) {
        return new GenerationInputPack("GenerationInputPack", "1.0", Map.of("project_name", "测试项目"),
                Map.of(), "template-1", "content-1", "GENERATION_READINESS_REPORT",
                Map.of("status", "confirmed", "document_parts", List.of()),
                Map.of("status", "confirmed", "content_by_section", List.of()), Map.of(), List.of(), blockers,
                Map.of("allow_unconfirmed_artifacts", false),
                Map.of("ready_for_final_docx", ready));
    }
}
