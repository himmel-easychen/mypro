package com.workpro.dialogue;

import com.workpro.model.RagGenerationGateway;
import com.workpro.rag.RetrievalContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class AnswerService {
    private final RetrievalService retrieval;
    private final RagPromptBuilder prompts;
    private final AnswerPolicy policy;
    private final RagGenerationGateway gateway;
    private final ObjectMapper mapper;
    private static final Pattern CITATION = Pattern.compile("\\[E(\\d+)\\]");
    private static final Pattern BARE_CITATION = Pattern.compile("(?<![\\[A-Za-z0-9_])E(\\d+)(?![\\]A-Za-z0-9_])");
    public AnswerService(RetrievalService retrieval, RagPromptBuilder prompts, AnswerPolicy policy,
                         RagGenerationGateway gateway, ObjectMapper mapper) {
        this.retrieval = retrieval; this.prompts = prompts; this.policy = policy;
        this.gateway = gateway; this.mapper = mapper;
    }
    public record Answer(String query, String answer, List<String> citations, boolean refusal,
                         String refusalReason, String status, String error, String prompt, String rawResponse,
                         List<RagPromptBuilder.Evidence> evidence, Map<String,Object> retrieval,
                         List<String> retrievedChunkIds, long latencyMs, long modelLatencyMs) {}

    public Answer answer(String query, String documentId, String chapterId, List<?> history, boolean disableTransform) {
        long start = System.nanoTime();
        RetrievalPack pack = null;
        RagPromptBuilder.Prompt prompt = new RagPromptBuilder.Prompt("", List.of());
        String raw = "";
        long modelLatency = 0;
        List<Map<String, Object>> attempts = new ArrayList<>();
        String feedback = "";
        try {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("EMPTY_QUERY");
            pack = retrieval.retrieve(new RetrievalRequest(query, documentId, chapterId, 30,
                    Map.of("disable_query_transform", disableTransform, "answer_context", true)));
            if ("FAILED".equals(pack.metadata().get("retrieval_status")))
                throw new IllegalStateException("RETRIEVAL_UNAVAILABLE");
            prompt = prompts.build(query, history, pack);
            var reason = policy.assess(prompt.evidence(), null);
            if (reason != AnswerPolicy.Reason.NONE)
                return result(query, AnswerPolicy.REFUSAL, List.of(), true, reason.name(), "OK", null,
                        prompt, raw, pack, start, 0);
            for (int attempt = 0; attempt < 2; attempt++) {
            String modelPrompt = prompt.text();
            if (attempt > 0) modelPrompt += "\n校验反馈：" + feedback;
            var response = gateway.complete(modelPrompt);
            raw = Objects.requireNonNullElse(response.content(), "");
            modelLatency += response.latencyMs();
            attempts.add(Map.of("attempt", attempt + 1, "prompt", modelPrompt, "rawResponse", raw,
                    "latencyMs", response.latencyMs()));
            var metadata = new LinkedHashMap<String, Object>(pack.metadata());
            metadata.put("generation_attempts", List.copyOf(attempts));
            pack = new RetrievalPack(pack.results(), pack.evidenceRefs(), metadata);
            String json = raw.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            com.fasterxml.jackson.databind.JsonNode parsed;
            try {
            parsed = mapper.reader()
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                feedback = "函数参数 JSON 非法或包含重复字段。请调用 submit_answer 一次，提交完整、合法且字段唯一的参数对象。";
                if (attempt < 1) continue;
                throw invalid;
            }
            if (parsed == null || !parsed.path("supported").isBoolean() || !parsed.path("answer").isTextual()
                    || parsed.path("answer").asText().isBlank() || "null".equalsIgnoreCase(parsed.path("answer").asText().strip()))
                throw new IllegalStateException("INVALID_MODEL_RESPONSE");
            boolean supported = parsed.path("supported").asBoolean();
            // Prefer the explicit question-level decision; retain compatibility with older providers.
            if (parsed.has("answerability")) {
                String answerability = parsed.path("answerability").asText();
                if (!Set.of("FULL", "PARTIAL", "NONE").contains(answerability))
                    throw new IllegalStateException("INVALID_MODEL_RESPONSE");
                supported = !"NONE".equals(answerability);
            }
            reason = policy.assess(prompt.evidence(), supported);
            if (reason != AnswerPolicy.Reason.NONE)
                return result(query, AnswerPolicy.REFUSAL, List.of(), true, reason.name(), "OK", null,
                        prompt, raw, pack, start, modelLatency);
            String answer = normalizeBareCitations(parsed.path("answer").asText(), prompt.evidence());
            if (AnswerUnitValidator.invalidRatio(answer)) {
                feedback = "上一回答把数量除以每周期速率写成倍数，量纲不成立。请重新依据原始证据回答；比较倍数先将两项换成同单位数量。不得改变原始事实或引用。";
                if (attempt < 1) continue;
                throw new IllegalStateException("INVALID_ANSWER_UNITS");
            }
            Set<String> ids = new LinkedHashSet<>();
            var matcher = CITATION.matcher(answer);
            while (matcher.find()) ids.add("E" + matcher.group(1));
            Set<String> allowed = new HashSet<>();
            prompt.evidence().forEach(e -> allowed.add(e.id()));
            if (ids.isEmpty() || !allowed.containsAll(ids)) throw new IllegalStateException("INVALID_CITATIONS");
            return result(query, answer, List.copyOf(ids), false, "NONE", "OK", null,
                    prompt, raw, pack, start, modelLatency);
            }
            throw new IllegalStateException("INVALID_ANSWER_UNITS");
        } catch (Exception e) {
            String code = e instanceof IllegalArgumentException ? "ANSWER_INPUT_ERROR" : "ANSWER_GENERATION_FAILED";
            if ("INVALID_CITATIONS".equals(e.getMessage())) code = "INVALID_CITATIONS";
            if ("INVALID_ANSWER_UNITS".equals(e.getMessage())) code = "INVALID_ANSWER_UNITS";
            if (e instanceof com.fasterxml.jackson.core.JsonProcessingException || "INVALID_MODEL_RESPONSE".equals(e.getMessage()))
                code = "INVALID_MODEL_RESPONSE";
            if ("RETRIEVAL_UNAVAILABLE".equals(e.getMessage())) code = "RETRIEVAL_UNAVAILABLE";
            return result(query, "回答生成失败，请稍后重试。", List.of(), false, "NONE", "ERROR", code,
                    prompt, raw, pack, start, modelLatency);
        }
    }

    private Answer result(String query, String answer, List<String> citations, boolean refusal, String reason,
                          String status, String error, RagPromptBuilder.Prompt prompt, String raw,
                          RetrievalPack pack, long start, long modelLatency) {
        return new Answer(query, answer, citations, refusal, reason, status, error, prompt.text(), raw,
                prompt.evidence(), pack == null ? Map.of() : pack.metadata(),
                pack == null ? List.of() : pack.results().stream().map(r -> r.chunk_id()).toList(),
                (System.nanoTime() - start) / 1_000_000, modelLatency);
    }

    private String normalizeBareCitations(String answer, List<RagPromptBuilder.Evidence> evidence) {
        Set<String> allowed = new HashSet<>();
        evidence.forEach(e -> allowed.add(e.id()));
        var matcher = BARE_CITATION.matcher(answer);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            String id = "E" + matcher.group(1);
            matcher.appendReplacement(normalized, allowed.contains(id) ? "[" + id + "]" : id);
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }
}
