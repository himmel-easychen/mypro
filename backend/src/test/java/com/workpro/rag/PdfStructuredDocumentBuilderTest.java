package com.workpro.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfStructuredDocumentBuilderTest {
    @Test
    void preservesOcrTableAsTableAndCells() {
        String html = "<table><tr><th>项目</th><th>金额</th></tr>"
                + "<tr><td>维修</td><td>1000</td></tr></table>";
        OcrBlock block = new OcrBlock("table", "", List.of(1, 2, 3, 4), .96, 2, html);
        Map<Integer, OcrPage> pages = new LinkedHashMap<>();
        pages.put(6, new OcrPage(6, List.of(block)));

        StructuredDocument document = new PdfStructuredDocumentBuilder().build(pages, List.of(), "sample.pdf");

        assertEquals(1, document.tables().size());
        assertEquals(List.of("项目", "金额"), document.tables().get(0).headers());
        assertEquals(4, document.tables().get(0).cells().size());
        assertEquals("金额", document.tables().get(0).cells().get(3).columnName());
        assertEquals("1000", document.tables().get(0).cells().get(3).rawText());
        assertEquals("table", document.blocks().get(0).blockType());
        assertTrue(document.blocks().get(0).metadata().containsKey("bbox"));
    }

    @Test
    void recoversTableFromAlignedTextBboxesWhenHtmlIsMissing() {
        List<OcrBlock> blocks = List.of(
                text("序号", 100, 100, 160, 130, 0),
                text("车牌号", 260, 100, 350, 130, 1),
                text("车型", 500, 100, 570, 130, 2),
                text("品牌", 740, 100, 810, 130, 3),
                text("1", 110, 160, 140, 190, 4),
                text("粤A12345", 260, 160, 360, 190, 5),
                text("轿车", 500, 160, 570, 190, 6),
                text("丰田", 740, 160, 810, 190, 7),
                text("2", 110, 220, 140, 250, 8),
                text("粤A67890", 260, 220, 360, 250, 9),
                text("SUV", 500, 220, 570, 250, 10),
                text("本田", 740, 220, 810, 250, 11));

        StructuredDocument document = new PdfStructuredDocumentBuilder().build(
                Map.of(65, new OcrPage(65, blocks)), List.of(), "sample.pdf");

        assertEquals(1, document.tables().size());
        assertEquals(List.of("序号", "车牌号", "车型", "品牌"), document.tables().get(0).headers());
        assertEquals("粤A67890", document.tables().get(0).cells().get(9).rawText());
        assertEquals(2, document.tables().get(0).cells().get(9).rowIndex());
        assertEquals(1, document.tables().get(0).cells().get(9).columnIndex());
    }

    @Test
    void doesNotTreatTwoColumnLayoutAsTable() {
        List<OcrBlock> blocks = List.of(
                text("条款", 100, 100, 180, 130, 0),
                text("说明", 400, 100, 480, 130, 1),
                text("一", 100, 160, 140, 190, 2),
                text("普通段落", 400, 160, 520, 190, 3),
                text("二", 100, 220, 140, 250, 4),
                text("更多文字", 400, 220, 520, 250, 5));

        StructuredDocument document = new PdfStructuredDocumentBuilder().build(
                Map.of(1, new OcrPage(1, blocks)), List.of(), "sample.pdf");

        assertTrue(document.tables().isEmpty());
    }

    private OcrBlock text(String value, int x0, int y0, int x1, int y1, int order) {
        return new OcrBlock("text", value, List.of(x0, y0, x1, y1), .99, order, null);
    }
}
