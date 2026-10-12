package com.workpro.tender.service;

import com.workpro.tender.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TenderWorkflowServiceTest {
    TenderWorkflowService service = new TenderWorkflowService(mock(org.springframework.jdbc.core.JdbcTemplate.class), new ObjectMapper());

    @Test void allowsDocumentedForwardTransitions() {
        assertDoesNotThrow(() -> service.assertTransitionAllowed(TenderTaskStatus.CREATED, TenderTaskStatus.ANALYZING));
        assertDoesNotThrow(() -> service.assertTransitionAllowed(TenderTaskStatus.ANALYZING, TenderTaskStatus.ANALYSIS_READY));
        assertDoesNotThrow(() -> service.assertTransitionAllowed(TenderTaskStatus.COLLABORATION_READY, TenderTaskStatus.GENERATING));
        assertDoesNotThrow(() -> service.assertTransitionAllowed(TenderTaskStatus.REVIEWING, TenderTaskStatus.REVISION_REQUIRED));
        assertDoesNotThrow(() -> service.assertTransitionAllowed(TenderTaskStatus.REVIEWING, TenderTaskStatus.EXPORTING));
        assertThrows(IllegalStateException.class, () -> service.assertTransitionAllowed(TenderTaskStatus.REVIEWING, TenderTaskStatus.EXPORT_READY));
        assertDoesNotThrow(() -> service.assertTransitionAllowed(TenderTaskStatus.EXPORTING, TenderTaskStatus.EXPORT_READY));
        assertDoesNotThrow(() -> service.assertTransitionAllowed(TenderTaskStatus.EXPORT_READY, TenderTaskStatus.DELIVERED));
    }

    @Test void rejectsIllegalTransitionThatWouldSkipFoundationGates() {
        var ex = assertThrows(IllegalStateException.class,
                () -> service.assertTransitionAllowed(TenderTaskStatus.CREATED, TenderTaskStatus.GENERATING));
        assertTrue(ex.getMessage().contains("ILLEGAL_TENDER_TRANSITION"));
    }

    @Test void derivesModeFromStatus() {
        assertEquals(TenderMode.ANALYSIS, service.modeFor(TenderTaskStatus.ANALYZING));
        assertEquals(TenderMode.COLLABORATION, service.modeFor(TenderTaskStatus.AWAITING_CONFIRMATION));
        assertEquals(TenderMode.GENERATION, service.modeFor(TenderTaskStatus.REVIEWING));
        assertEquals(TenderMode.NONE, service.modeFor(TenderTaskStatus.ARCHIVED));
    }
}
