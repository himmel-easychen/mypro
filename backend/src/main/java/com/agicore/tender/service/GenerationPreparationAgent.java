package com.agicore.tender.service;

import com.agicore.tender.domain.GenerationInputPack;
import com.agicore.tender.domain.GenerationValidationResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class GenerationPreparationAgent {
    private final GenerationContextAssembler assembler;
    private final GenerationInputPackValidator validator;

    public GenerationPreparationAgent(GenerationContextAssembler assembler, GenerationInputPackValidator validator) {
        this.assembler = assembler;
        this.validator = validator;
    }

    public PreparationResult prepare(String taskId, Map<String, Object> override) {
        GenerationInputPack pack = assembler.assemble(taskId, override);
        GenerationValidationResult validation = validator.validate(pack);
        return new PreparationResult(pack, validation);
    }

    public record PreparationResult(GenerationInputPack pack, GenerationValidationResult validation) {}
}
