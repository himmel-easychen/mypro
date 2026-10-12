package com.agicore.tender.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class TenderGenerationReviewer {
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(待填写|待补录|待补充|待核验|待确认|TBD|\\{\\{.+?}}|\\[.+?待.+?])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN = Pattern.compile("(?m)^\\s*(#{1,6}\\s|```)");

    public ReviewResult review(List<TenderSectionWriter.SectionDraft> drafts) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Set<String> sectionIds = new HashSet<>();
        for (TenderSectionWriter.SectionDraft draft : drafts) {
            if (!sectionIds.add(draft.sectionId())) {
                issues.add(issue(draft.sectionId(), "duplicate_section", "章节编号重复", true));
            }
            if (draft.content() == null || draft.content().isBlank()) {
                issues.add(issue(draft.sectionId(), "empty_section", "章节内容为空", true));
            } else if (PLACEHOLDER.matcher(draft.content()).find()) {
                issues.add(issue(draft.sectionId(), "unresolved_placeholder", "章节仍包含待填写内容", true));
            } else if (MARKDOWN.matcher(draft.content()).find()) {
                issues.add(issue(draft.sectionId(), "invalid_word_format", "章节包含Markdown标题或代码块", true));
            }
            if (requiresEvidence(draft.mode()) && draft.sourcePages().isEmpty()) {
                issues.add(issue(draft.sectionId(), "missing_source_evidence", "生成章节未绑定招标文件来源页", true));
            }
        }
        return new ReviewResult(issues.stream().noneMatch(v -> Boolean.TRUE.equals(v.get("blocked"))), List.copyOf(issues));
    }

    private boolean requiresEvidence(String mode) {
        return "llm_generate".equals(mode) || "evidence_grounded_generate".equals(mode)
                || "confirmed_commitment_generate".equals(mode);
    }

    private Map<String, Object> issue(String sectionId, String type, String description, boolean blocked) {
        return Map.of("section_id", sectionId, "issue_type", type, "description", description, "blocked", blocked);
    }

    public record ReviewResult(boolean passed, List<Map<String, Object>> issues) {}
}
