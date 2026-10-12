package com.workpro.tender.service;

import com.workpro.tender.domain.GenerationInputPack;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GenerationWritingAgent {
    private final TenderSectionWriter writer;

    public GenerationWritingAgent(TenderSectionWriter writer) {
        this.writer = writer;
    }

    public List<TenderSectionWriter.SectionDraft> write(GenerationInputPack pack, boolean useLlm) {
        return writer.write(pack, useLlm);
    }
}
