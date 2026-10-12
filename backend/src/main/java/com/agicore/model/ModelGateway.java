package com.agicore.model;

import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Unified model entry point; provider-specific clients remain behind this boundary. */
@Service
public class ModelGateway {
    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);
    private final ModelServices.LlmClient llm;
    private int failures;
    private Instant openUntil = Instant.MIN;
    private final Deque<Instant> calls = new ArrayDeque<>();
    public ModelGateway(ModelServices.LlmClient llm) { this.llm = llm; }

    public ModelResponse complete(String prompt) {
        return complete(prompt, false);
    }

    public ModelResponse completeRag(String prompt) {
        return complete(prompt, true);
    }

    private ModelResponse complete(String prompt, boolean structuredRag) {
        synchronized (calls) {
            Instant now = Instant.now();
            while (!calls.isEmpty() && calls.peekFirst().isBefore(now.minusSeconds(60))) calls.removeFirst();
            if (calls.size() >= 60) throw new IllegalStateException("E0005 模型限流中，请稍后重试");
            calls.addLast(now);
        }
        if (Instant.now().isBefore(openUntil)) throw new IllegalStateException("E0005 模型熔断中，请稍后重试");
        Instant started = Instant.now();
        String content;
        try { content = structuredRag ? llm.completeRag(prompt) : llm.complete(prompt); failures = 0; }
        catch (RuntimeException e) {
            failures++;
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            // Do not log response bodies, prompts, headers, or exception messages containing credentials.
            log.warn("model_call_failed input_chars={} failures={} error={} cause={} latency_ms={}",
                    prompt == null ? 0 : prompt.length(), failures, e.getClass().getSimpleName(),
                    cause.getClass().getSimpleName(), Duration.between(started, Instant.now()).toMillis());
            if (failures >= 3) { openUntil = Instant.now().plusSeconds(15); failures = 0; }
            throw e;
        }
        long inputTokens = estimateTokens(prompt);
        long outputTokens = estimateTokens(content);
        long latency = Duration.between(started, Instant.now()).toMillis();
        log.info("model_call_success input_tokens={} output_tokens={} latency_ms={}", inputTokens, outputTokens, latency);
        return new ModelResponse(content, inputTokens, outputTokens, latency);
    }

    private long estimateTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        return Math.max(1, (long) Math.ceil(value.codePointCount(0, value.length()) / 4.0));
    }

    public record ModelResponse(String content, long inputTokens, long outputTokens, long latencyMs) { }
}
