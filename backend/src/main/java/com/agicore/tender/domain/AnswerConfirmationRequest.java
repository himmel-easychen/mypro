package com.agicore.tender.domain;

import java.util.Map;

public record AnswerConfirmationRequest(Map<String, Object> selectedOption, String customAnswer, String answeredBy) {}
