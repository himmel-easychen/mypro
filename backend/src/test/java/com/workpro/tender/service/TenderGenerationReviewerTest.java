package com.workpro.tender.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenderGenerationReviewerTest {
    private final TenderGenerationReviewer reviewer = new TenderGenerationReviewer();

    @Test
    void blocksProcessLanguageThatMustNotAppearInFinalDocuments() {
        for (String marker : List.of("待填写", "待补录", "待补充", "待核验", "待确认", "TBD")) {
            TenderSectionWriter.SectionDraft draft = new TenderSectionWriter.SectionDraft(
                    "TECHNICAL", "技术投标文件", "OTHER-TECH", "其他技术文件",
                    "llm_generate", "本节" + marker + "。", List.of(162));

            TenderGenerationReviewer.ReviewResult result = reviewer.review(List.of(draft));

            assertThat(result.passed()).as(marker).isFalse();
            assertThat(result.issues()).singleElement()
                    .extracting(issue -> issue.get("issue_type"))
                    .isEqualTo("unresolved_placeholder");
        }
    }

    @Test
    void passesCompleteFinalContent() {
        TenderSectionWriter.SectionDraft draft = new TenderSectionWriter.SectionDraft(
                "TECHNICAL", "技术投标文件", "SERVICE-PLAN", "服务方案",
                "llm_generate", "项目采用接车、诊断、维修、质检和交付闭环流程。", List.of(134));

        assertThat(reviewer.review(List.of(draft)).passed()).isTrue();
    }

    @Test
    void blocksMissingEvidenceMarkdownAndDuplicateSections() {
        TenderSectionWriter.SectionDraft first = new TenderSectionWriter.SectionDraft(
                "TECHNICAL", "技术投标文件", "SERVICE-PLAN", "服务方案",
                "llm_generate", "# 服务方案\n执行维修流程。", List.of());
        TenderSectionWriter.SectionDraft duplicate = new TenderSectionWriter.SectionDraft(
                "TECHNICAL", "技术投标文件", "SERVICE-PLAN", "服务方案",
                "fixed_form", "执行维修流程。", List.of(134));

        TenderGenerationReviewer.ReviewResult result = reviewer.review(List.of(first, duplicate));

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).extracting(issue -> issue.get("issue_type"))
                .contains("invalid_word_format", "missing_source_evidence", "duplicate_section");
    }
}
