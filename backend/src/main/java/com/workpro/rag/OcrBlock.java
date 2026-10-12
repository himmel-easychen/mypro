package com.workpro.rag;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OcrBlock(@JsonProperty("block_type") String blockType, String text, Object bbox,
                       double confidence, @JsonProperty("order_index") int orderIndex,
                       @JsonProperty("table_html") String tableHtml) {}
