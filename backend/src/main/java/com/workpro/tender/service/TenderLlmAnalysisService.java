package com.workpro.tender.service;

import com.workpro.model.ModelGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Structured LLM boundary for tender extraction. The caller supplies normalized
 * document chunks with source locations; model output is validated before it can
 * be persisted as an analysis artifact.
 */
@Service
public class TenderLlmAnalysisService {
    private final ModelGateway gateway;
    private final ObjectMapper mapper;

    public TenderLlmAnalysisService(ModelGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    public Map<String, Object> extract(String artifactType, List<Map<String, Object>> sourceChunks) {
        if (artifactType == null || artifactType.isBlank()) throw new IllegalArgumentException("EMPTY_ANALYSIS_ARTIFACT_TYPE");
        List<Map<String, Object>> chunks = sourceChunks == null ? List.of() : List.copyOf(sourceChunks);
        if (chunks.isEmpty()) throw new IllegalArgumentException("ANALYSIS_SOURCE_CHUNKS_REQUIRED");
        String prompt = "你是投标文件分析器。只返回合法 JSON，不要 Markdown。\n"
                + "目标产物: " + artifactType + "\n"
                + "要求：每个结论必须包含 source_refs，source_refs 至少包含 document_id 和 page；不确定时 requires_human_review=true；禁止补造原文没有的事实。\n"
                + "输出结构：{\"items\":[{\"id\":\"...\",\"type\":\"...\",\"conclusion\":\"...\",\"source_refs\":[{\"document_id\":\"...\",\"page\":1}],\"confidence\":0.0,\"requires_human_review\":true}]}\n"
                + mapper.valueToTree(chunks).toString();
        String raw = gateway.complete(prompt).content();
        try {
            Map<String, Object> result = mapper.readValue(raw, new TypeReference<>() {});
            validate(result);
            result.put("artifact", artifactType);
            result.put("generated_by", "TenderLlmAnalysisService");
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("LLM_ANALYSIS_SCHEMA_INVALID", e);
        }
    }

    public Map<String, Object> analyzeTender(List<Map<String, Object>> tenderChunks,
                                             List<Map<String, Object>> enterpriseChunks) {
        if (tenderChunks == null || tenderChunks.isEmpty()) throw new IllegalArgumentException("TENDER_SOURCE_CHUNKS_REQUIRED");
        String prompt = "你是严谨的招投标分析器。只返回合法 JSON，不要 Markdown，不得补造事实。"
                + "每一项必须带 source_refs，引用输入中的 document_id、page、location；无法确认的字段标记 requires_human_review=true。\n"
                + "输出必须包含 tender_brief(object)、requirements(array)、scoring_items(array)、submission_sections(array)、"
                + "pricing_rules(array)、fatal_risks(array)、evidence_matches(array)、gaps(array)、score_projections(array)。"
                + "requirements 覆盖资格条件、实质性条款、合同/交付要求；scoring_items 包含 max_score、rule、deductions；"
                + "evidence_matches 对每项要求给出 status=available|missing|conflict|needs_confirmation。\n"
                + "招标文件块=" + mapper.valueToTree(tenderChunks) + "\n企业材料块=" + mapper.valueToTree(enterpriseChunks == null ? List.of() : enterpriseChunks);
        String raw = gateway.complete(prompt).content();
        try {
            String json = stripFence(raw);
            Map<String, Object> result = mapper.readValue(json, new TypeReference<>() {});
            for (String key : List.of("requirements", "scoring_items", "submission_sections", "pricing_rules",
                    "fatal_risks", "evidence_matches", "gaps", "score_projections")) {
                if (!(result.get(key) instanceof List<?>)) throw new IllegalArgumentException(key + " required");
            }
            if (!(result.get("tender_brief") instanceof Map<?, ?>)) throw new IllegalArgumentException("tender_brief required");
            result.put("generated_by", "TenderLlmAnalysisService");
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("LLM_TENDER_ANALYSIS_SCHEMA_INVALID", e);
        }
    }

    private String stripFence(String raw) {
        String value = Objects.requireNonNullElse(raw, "").trim();
        if (!value.startsWith("```")) return value;
        int firstBreak = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        return firstBreak >= 0 && lastFence > firstBreak ? value.substring(firstBreak + 1, lastFence).trim() : value;
    }

    private void validate(Map<String, Object> result) {
        Object itemsValue = result.get("items");
        if (!(itemsValue instanceof List<?> items) || items.isEmpty()) throw new IllegalArgumentException("items required");
        for (Object itemValue : items) {
            if (!(itemValue instanceof Map<?, ?> item)) throw new IllegalArgumentException("item must be object");
            if (blank(item.get("id")) || blank(item.get("conclusion"))) throw new IllegalArgumentException("item identity/conclusion required");
            Object refsValue = item.get("source_refs");
            if (!(refsValue instanceof List<?> refs) || refs.isEmpty()) throw new IllegalArgumentException("source_refs required");
            for (Object refValue : refs) {
                if (!(refValue instanceof Map<?, ?> ref) || blank(ref.get("document_id")) || ref.get("page") == null)
                    throw new IllegalArgumentException("source reference must contain document_id/page");
            }
        }
    }

    private boolean blank(Object value) { return value == null || String.valueOf(value).isBlank(); }
}
