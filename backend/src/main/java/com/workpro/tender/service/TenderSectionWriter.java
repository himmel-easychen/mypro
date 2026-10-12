package com.workpro.tender.service;

import com.workpro.model.RagGenerationGateway;
import com.workpro.tender.domain.GenerationInputPack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TenderSectionWriter {
    private final RagGenerationGateway gateway;
    private final ObjectMapper mapper;

    public TenderSectionWriter(RagGenerationGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    public List<SectionDraft> write(GenerationInputPack pack, boolean useLlm) {
        List<SectionDraft> drafts = new ArrayList<>();
        Map<String, List<String>> instructions = instructions(pack.confirmedContentPack());
        for (Map<String, Object> part : maps(pack.templatePack().get("document_parts"))) {
            String partId = text(part, "part_id", "DOCUMENT");
            String partTitle = text(part, "title", partId);
            for (Map<String, Object> section : maps(part.get("sections"))) {
                String id = text(section, "section_id", "SECTION-" + (drafts.size() + 1));
                String title = text(section, "title", id);
                String mode = text(section, "mode", "fixed_form");
                List<String> sectionInstructions = instructions.getOrDefault(id, List.of());
                String content = shouldUseLlm(mode, useLlm)
                        ? generateWithLlm(pack, partTitle, id, title, mode, sectionInstructions)
                        : deterministic(pack, id, title, mode, sectionInstructions);
                drafts.add(new SectionDraft(partId, partTitle, id, title, mode, content, sourcePages(section, part)));
            }
        }
        if (drafts.isEmpty()) throw new IllegalStateException("GENERATION_TEMPLATE_HAS_NO_SECTIONS");
        return List.copyOf(drafts);
    }

    private String generateWithLlm(GenerationInputPack pack, String partTitle, String id, String title,
                                   String mode, List<String> instructions) {
        String prompt = "你是投标文件正文生成器。只使用下方已确认信息，不得虚构企业资质、业绩、人员、设备、地址或证明材料。"
                + "证据不足的事项直接省略，不得输出‘待填写’‘待补录’‘待补充’‘待核验’‘待确认’等占位或过程性措辞。"
                + "输出本章节可直接放入Word的中文正文，不要输出Markdown标题、代码块或解释。\n"
                + "项目=" + pack.task() + "\n卷册=" + partTitle + "\n章节=" + id + " " + title
                + "\n生成模式=" + mode + "\n写作要求=" + instructions
                + "\n已确认内容=" + pack.confirmedContentPack() + "\n报价=" + pack.pricing()
                + "\n评分目标=" + pack.scoringTargets();
        String response = gateway.complete(prompt).content();
        return answerText(response);
    }

    private String answerText(String response) {
        if (response == null || response.isBlank()) throw new IllegalStateException("GENERATION_EMPTY_LLM_RESPONSE");
        try {
            JsonNode node = mapper.readTree(response);
            String answer = node.path("answer").asText("").trim();
            if (!answer.isBlank()) return answer;
        } catch (Exception ignored) {
            // Non-JSON providers return the section text directly.
        }
        return response.trim();
    }

    private String deterministic(GenerationInputPack pack, String id, String title, String mode,
                                 List<String> instructions) {
        if ("PRICE-SCHEDULE".equals(id) || "OPENING-SCHEDULE".equals(id)) {
            return "工时费优惠率：" + value(pack.pricing(), "labor_discount_rate_a_percent", "待填写") + "%\n"
                    + "材料费优惠率：" + value(pack.pricing(), "material_discount_rate_b_percent", "待填写") + "%\n"
                    + "综合优惠率：" + value(pack.pricing(), "composite_discount_rate_c_percent", "待填写") + "%";
        }
        if (id.contains("DEVIATION")) return "无偏差。";
        if ("COST-ANALYSIS".equals(id) && !GenerationInputPackValidator.booleanValue(pack.pricing(), "cost_analysis_required", false)) {
            return "本次报价未触发成本分析报告提交条件。";
        }
        if (!instructions.isEmpty()) return String.join("\n", instructions);
        if (mode.startsWith("fixed") || "exclusive_choice".equals(mode) || "evidence_attachment".equals(mode)) {
            return "本节按招标文件固定格式填写，内容来自已确认企业资料和证明材料。";
        }
        return title + "内容按已确认的投标策略和证据材料编制。";
    }

    private boolean shouldUseLlm(String mode, boolean enabled) {
        return enabled && ("llm_generate".equals(mode) || "evidence_grounded_generate".equals(mode)
                || "confirmed_commitment_generate".equals(mode));
    }

    private Map<String, List<String>> instructions(Map<String, Object> confirmed) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> item : maps(confirmed.get("content_by_section"))) {
            result.put(text(item, "section_id", ""), strings(item.get("instructions")));
        }
        return result;
    }

    private List<Integer> sourcePages(Map<String, Object> section, Map<String, Object> part) {
        Object raw = section.get("source_pages");
        if (raw == null && section.get("source_page") != null) raw = List.of(section.get("source_page"));
        if (raw == null) raw = part.get("source_pages");
        if (raw == null && part.get("source_page") != null) raw = List.of(part.get("source_page"));
        List<Integer> pages = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Number n) pages.add(n.intValue());
        return List.copyOf(pages);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String value(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value.toString();
    }

    public record SectionDraft(String partId, String partTitle, String sectionId, String title,
                               String mode, String content, List<Integer> sourcePages) {}
}
