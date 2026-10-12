package com.workpro.tender.domain;

import java.util.Map;

public record AnswerConfirmationRequest(Map<String, Object> selectedOption, String customAnswer, String answeredBy) {}
