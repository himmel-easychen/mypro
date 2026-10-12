package com.agicore.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OcrDocumentResult(@JsonProperty("document_id") String documentId, List<OcrPage> pages) {}
