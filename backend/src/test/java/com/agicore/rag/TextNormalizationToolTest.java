package com.agicore.rag;

import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextNormalizationToolTest {

    @Test
    void convertsEscapedLineBreaksIntoRealLineBreaks() {
        TextNormalizationTool tool = new TextNormalizationTool();

        String result = tool.normalize("[第 1 页]\\n项目名称\\n金额");

        assertEquals("[第 1 页]\n项目名称\n金额", result);
    }

    @Test
    void wordTableRowsUseRealLineBreaks() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("字段");
            table.getRow(0).getCell(1).setText("值");
            table.getRow(1).getCell(0).setText("名称");
            table.getRow(1).getCell(1).setText("广州");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.write(bytes);

            StructuredDocument parsed = new DocumentParser().parse(bytes.toByteArray(), "word", "test.docx");

            assertEquals("字段 | 值\n名称 | 广州", parsed.blocks().get(0).rawText());
        }
    }
}
