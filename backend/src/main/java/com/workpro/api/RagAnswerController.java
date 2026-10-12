package com.workpro.api;

import com.workpro.dialogue.AnswerService;
import com.workpro.dialogue.RagQualityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/rag")
public class RagAnswerController {
    private final AnswerService answers;
    private final RagQualityService quality;
    public RagAnswerController(AnswerService answers, RagQualityService quality) {
        this.answers = answers;
        this.quality = quality;
    }
    public record Request(String query, String documentId, String chapterId, List<Object> history,
                          boolean disableQueryTransform) {}
    @PostMapping("/answer")
    public ResponseEntity<AnswerService.Answer> answer(@RequestBody Request request) {
        var result = answers.answer(request.query(), request.documentId(), request.chapterId(),
                request.history(), request.disableQueryTransform());
        quality.recordAnswer(new RagQualityService.AnswerRequest(request.query(), request.documentId(),
                request.chapterId(), request.history(), request.disableQueryTransform()), result);
        return ResponseEntity.status("OK".equals(result.status()) ? 200 : 503).body(result);
    }

    @GetMapping("/traces/{traceId}")
    public Map<String, Object> trace(@PathVariable String traceId) {
        return quality.trace(traceId);
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody RagQualityService.FeedbackRequest request) {
        return quality.recordFeedback(request);
    }

    @PostMapping("/audit/reviews")
    public Map<String, Object> review(@RequestBody RagQualityService.ReviewRequest request) {
        return quality.recordReview(request);
    }

    @PostMapping("/replay")
    public ResponseEntity<AnswerService.Answer> replay(@RequestBody RagQualityService.ReplayRequest request) {
        var seed = quality.replaySeed(request);
        var result = answers.answer(seed.query(), seed.documentId(), seed.chapterId(), seed.history(), seed.disableQueryTransform());
        quality.recordAnswer(new RagQualityService.AnswerRequest(seed.query(), seed.documentId(), seed.chapterId(),
                seed.history(), seed.disableQueryTransform()), result);
        return ResponseEntity.status("OK".equals(result.status()) ? 200 : 503).body(result);
    }
}
