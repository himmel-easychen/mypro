package com.agicore.api;

import com.agicore.dialogue.AnswerService;
import com.agicore.dialogue.RagPromptBuilder;
import com.agicore.dialogue.RagQualityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagAnswerControllerTest {
    AnswerService.Answer okAnswer() {
        return new AnswerService.Answer("q", "a[E1]", List.of("E1"), false, "NONE", "OK", null,
                "prompt", "raw", List.of(new RagPromptBuilder.Evidence("E1", "c1", "d1", "s.pdf", "p1", "a", null, "")),
                Map.of("trace_id", "11111111-1111-1111-1111-111111111111"), List.of("c1"), 12, 10);
    }

    @Test void answerPersistsTraceBeforeReturning() {
        var answers = mock(AnswerService.class);
        var quality = mock(RagQualityService.class);
        when(answers.answer(eq("q"), eq("d"), isNull(), anyList(), eq(false))).thenReturn(okAnswer());
        var controller = new RagAnswerController(answers, quality);
        var response = controller.answer(new RagAnswerController.Request("q", "d", null, List.of(), false));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(quality).recordAnswer(any(RagQualityService.AnswerRequest.class), same(response.getBody()));
    }

    @Test void feedbackReviewTraceAndReplayDelegateToQualityClosure() {
        var answers = mock(AnswerService.class);
        var quality = mock(RagQualityService.class);
        var controller = new RagAnswerController(answers, quality);
        when(quality.trace("t")).thenReturn(Map.of("traceId", "t"));
        assertEquals("t", controller.trace("t").get("traceId"));
        var feedback = new RagQualityService.FeedbackRequest("t", "u", "down", "citation", "bad", Map.of());
        when(quality.recordFeedback(feedback)).thenReturn(Map.of("status", "recorded"));
        assertEquals("recorded", controller.feedback(feedback).get("status"));
        var review = new RagQualityService.ReviewRequest("t", "r", true, true, true, false, null, "ok", Map.of());
        when(quality.recordReview(review)).thenReturn(Map.of("status", "recorded"));
        assertEquals("recorded", controller.review(review).get("status"));
        when(quality.replaySeed(any())).thenReturn(new RagQualityService.ReplaySeed("t", "q", "d", null, List.of(), false));
        when(answers.answer(eq("q"), eq("d"), isNull(), anyList(), eq(false))).thenReturn(okAnswer());
        assertEquals(HttpStatus.OK, controller.replay(new RagQualityService.ReplayRequest("t", null)).getStatusCode());
        verify(quality, atLeastOnce()).recordAnswer(any(RagQualityService.AnswerRequest.class), any());
    }
}
