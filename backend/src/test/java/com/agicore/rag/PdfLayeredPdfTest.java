package com.agicore.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

class PdfLayeredPdfTest {
    @Test
    void printsFullPdfLayeredQualitySummary() throws Exception {
        Path pdf = Path.of("D:/studyprogram/mypro/self-knowledge/1_广州供电局2026-2027年综合类车辆维保项目招标文件.pdf");
        var classifier = new PdfBoxPageClassifier();
        var pages = classifier.extract(Files.readAllBytes(pdf));
        var inputs = new ArrayList<LayeredPagePipeline.PageInput>();
        for (var page : pages) {
            inputs.add(new LayeredPagePipeline.PageInput(page.pageNumber(), page.text(), page.kind()));
        }

        var result = new LayeredPagePipeline(new PageTextCleaner(), new ParseQualityEvaluator())
                .process(inputs);
        long accepted = result.pages().stream()
                .filter(page -> "accepted".equals(page.quality().status()))
                .count();
        long pdfBox = result.pages().stream()
                .filter(page -> page.route() == LayeredPagePipeline.Route.PDFBOX)
                .count();
        long ppStructure = result.pages().stream()
                .filter(page -> page.route() == LayeredPagePipeline.Route.PPSTRUCTURE)
                .count();
        long nonEmpty = result.pages().stream()
                .filter(page -> !page.cleanedText().isBlank())
                .count();
        System.out.printf("LAYERED_PDF_SUMMARY total=%d nonEmpty=%d accepted=%d pdfbox=%d ppstructure=%d%n",
                result.pages().size(), nonEmpty, accepted, pdfBox, ppStructure);
        result.pages().stream().map(LayeredPagePipeline.PageResult::quality).distinct()
                .forEach(quality -> System.out.println("LAYERED_QUALITY " + quality));
        result.pages().stream().limit(3).forEach(page -> System.out.printf(
                "LAYERED_PAGE page=%d kind=%s route=%s chars=%d text=%s%n",
                page.pageNumber(), page.kind(), page.route(), page.cleanedText().length(),
                page.cleanedText().replace("\n", "\\n").substring(0, Math.min(80, page.cleanedText().length()))));
    }
}
