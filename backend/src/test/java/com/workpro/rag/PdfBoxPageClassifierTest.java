package com.workpro.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxPageClassifierTest {
    @Test
    void usesExtractedTextWhenBothOcrModesFail() {
        var output = new ArrayList<String>();
        var page = new PdfBoxPageClassifier.PageInput(17, "可提取的招标正文", PdfBoxPageClassifier.Kind.MIXED);

        assertTrue(RagServices.addPdfBoxFallback(output, page));
        assertEquals(List.of("[第 17 页]\n可提取的招标正文"), output);
    }

    @Test
    void printsTestPdfPageClassification() throws Exception {
        Path pdf = Path.of("D:/studyprogram/mypro/self-knowledge/1_广州供电局2026-2027年综合类车辆维保项目招标文件.pdf");
        var summary = new PdfBoxPageClassifier().analyze(Files.readAllBytes(pdf));
        System.out.println("PDF_BOX_SUMMARY " + summary);
    }
}
