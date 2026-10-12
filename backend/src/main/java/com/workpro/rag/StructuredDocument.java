package com.workpro.rag;

import java.util.List;
import java.util.Map;

public record StructuredDocument(
        String documentId,
        String sourceFile,
        String sourceType,
        List<SourceUnit> sourceUnits,
        List<Block> blocks,
        List<TableData> tables) {

    public StructuredDocument {
        sourceUnits = List.copyOf(sourceUnits);
        blocks = List.copyOf(blocks);
        tables = List.copyOf(tables);
    }

    public record SourceUnit(
            String id,
            int pageNumber,
            String logicalKey,
            String pageType,
            String sheetName) {
    }

    public record Block(
            String id,
            String sourceUnitId,
            String blockType,
            int orderIndex,
            String rawText,
            String normalizedText,
            Map<String, Object> metadata) {
    }

    public record TableData(
            String id,
            int pageNumber,
            String sheetName,
            List<String> headers,
            List<TableCell> cells,
            Map<String, Object> metadata) {
        public TableData {
            headers = List.copyOf(headers);
            cells = List.copyOf(cells);
        }
    }

    public record TableCell(
            int rowIndex,
            int columnIndex,
            String columnName,
            String rawText,
            String normalizedText,
            String unit) {
    }
}
