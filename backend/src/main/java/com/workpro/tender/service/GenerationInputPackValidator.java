package com.workpro.tender.service;

import com.workpro.tender.domain.GenerationInputPack;
import com.workpro.tender.domain.GenerationValidationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GenerationInputPackValidator {
    public GenerationValidationResult validate(GenerationInputPack pack) {
        List<String> errors = new ArrayList<>();
        if (pack == null) return new GenerationValidationResult(false, List.of("generation_input_pack:missing"));
        required("artifact", pack.artifact(), errors);
        if (!"GenerationInputPack".equals(pack.artifact())) errors.add("artifact:unsupported");
        required("contract_version", pack.contractVersion(), errors);
        if (!"1.0".equals(pack.contractVersion())) errors.add("contract_version:unsupported");
        if (pack.task().isEmpty()) errors.add("task:missing");
        if (pack.templatePack().isEmpty() && blank(pack.templateArtifactId())) errors.add("template_pack:missing");
        if (pack.confirmedContentPack().isEmpty() && blank(pack.confirmedContentArtifactId())) errors.add("confirmed_content_pack:missing");

        boolean allowUnconfirmed = booleanValue(pack.generationPolicy(), "allow_unconfirmed_artifacts", false);
        if (!allowUnconfirmed && !isConfirmed(pack.templatePack())) errors.add("template_pack:not_confirmed");
        if (!allowUnconfirmed && !isConfirmed(pack.confirmedContentPack())) errors.add("confirmed_content_pack:not_confirmed");

        for (Map<String, Object> placeholder : pack.blockingPlaceholders()) {
            if (booleanValue(placeholder, "required_before_docx", false)
                    && !booleanValue(placeholder, "resolved", false)) {
                errors.add("blocking_placeholder:" + String.valueOf(placeholder.getOrDefault("field", "unknown")));
            }
        }
        Object ready = pack.readiness().get("ready_for_final_docx");
        if (ready != null && !Boolean.TRUE.equals(ready)) errors.add("readiness:not_ready_for_final_docx");
        return new GenerationValidationResult(errors.isEmpty(), errors);
    }

    private boolean isConfirmed(Map<String, Object> value) {
        if (value.isEmpty()) return true;
        String status = String.valueOf(value.getOrDefault("status", ""));
        return status.equalsIgnoreCase("confirmed") || status.equalsIgnoreCase("confirmed_for_test")
                || status.equalsIgnoreCase("ready");
    }

    private void required(String field, String value, List<String> errors) {
        if (blank(value)) errors.add(field + ":missing");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static boolean booleanValue(Map<String, Object> value, String key, boolean fallback) {
        Object raw = value.get(key);
        return raw instanceof Boolean bool ? bool : fallback;
    }
}
