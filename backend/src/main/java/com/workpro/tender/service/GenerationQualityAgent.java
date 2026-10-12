package com.workpro.tender.service;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GenerationQualityAgent {
    private final TenderGenerationReviewer reviewer;

    public GenerationQualityAgent(TenderGenerationReviewer reviewer) {
        this.reviewer = reviewer;
    }

    public TenderGenerationReviewer.ReviewResult review(List<TenderSectionWriter.SectionDraft> drafts) {
        return reviewer.review(drafts);
    }
}
