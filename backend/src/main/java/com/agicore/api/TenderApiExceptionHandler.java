package com.agicore.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = TenderWorkflowController.class)
public class TenderApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(error(ex.getMessage(), false, null));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(ex.getMessage(), false, rollback(ex.getMessage())));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(ex.getMessage(), false, null));
    }

    private Map<String, Object> error(String code, boolean retryable, String rollbackTarget) {
        return Map.of("code", code == null || code.isBlank() ? "TENDER_ERROR" : code,
                "retryable", retryable,
                "rollbackTarget", rollbackTarget == null ? "" : rollbackTarget);
    }

    private String rollback(String code) {
        if (code == null) return null;
        if (code.contains("GENERATION_INPUTS_NOT_READY") || code.contains("GENERATION_READINESS_REQUIRED") || code.contains("PENDING_CONFIRMATIONS_EXIST")) return "collaboration";
        if (code.contains("BLOCKING_ISSUES_EXIST")) return "collaboration";
        if (code.contains("PASSED_REVIEW_REQUIRED")) return "generation";
        if (code.contains("ILLEGAL_TENDER_TRANSITION")) return "current";
        return null;
    }
}
