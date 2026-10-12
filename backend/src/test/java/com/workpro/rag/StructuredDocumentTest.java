package com.workpro.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructuredDocumentTest {
    @Test
    void preservesSourceAndCellCoordinates() {
        StructuredDocument.SourceUnit source = new StructuredDocument.SourceUnit(
                "source-1", 1, "", "physical", "sheet-a");
        StructuredDocument.TableCell cell = new StructuredDocument.TableCell(
                2, 3, "二类企业", "5", "5", "pcs");
        StructuredDocument.TableData table = new StructuredDocument.TableData(
                "table-1", 1, "sheet-a", List.of("工位名称", "一类企业", "二类企业"),
                List.of(cell), Map.of());
        StructuredDocument document = new StructuredDocument(
                "doc-1", "file.xls", "excel", List.of(source), List.of(), List.of(table));

        assertEquals("sheet-a", document.sourceUnits().get(0).sheetName());
        assertEquals(2, document.tables().get(0).cells().get(0).rowIndex());
        assertEquals("二类企业", document.tables().get(0).cells().get(0).columnName());
        assertEquals("5", document.tables().get(0).cells().get(0).normalizedText());
    }
}
