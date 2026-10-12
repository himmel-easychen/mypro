package com.agicore.rag;

import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserTest {
    @Test
    void parsesWordTableIntoLocatedCells() throws Exception {
        byte[] data;
        try (XWPFDocument source = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable table = source.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("金额");
            table.getRow(1).getCell(0).setText("维修费");
            table.getRow(1).getCell(1).setText("1000元");
            source.write(output);
            data = output.toByteArray();
        }

        StructuredDocument document = new DocumentParser().parse(data, "word", "table.docx");

        assertFalse(document.sourceUnits().isEmpty());
        assertFalse(document.tables().isEmpty());
        assertTrue(document.tables().get(0).cells().stream().anyMatch(cell ->
                cell.rowIndex() == 1 && cell.columnIndex() == 1
                        && "金额".equals(cell.columnName()) && "1000元".equals(cell.rawText())));
    }

    @Test
    void parsesExcelIntoSourceUnitTableAndCells() throws Exception {
        Path file = Path.of("D:/studyprogram/mypro/test-files/测试.xlsx");
        if (!Files.exists(file)) return;
        StructuredDocument document = new DocumentParser().parse(Files.readAllBytes(file), "excel", file.getFileName().toString());
        assertFalse(document.sourceUnits().isEmpty());
        assertFalse(document.tables().isEmpty());
        assertTrue(document.tables().stream().flatMap(t -> t.cells().stream()).anyMatch(c -> !c.rawText().isBlank()));
    }
}
