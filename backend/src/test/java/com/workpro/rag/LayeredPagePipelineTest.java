package com.workpro.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LayeredPagePipelineTest {
    @Test
    void keepsPageSourceAndRoutesTableAndMixedPagesToStructureLayer() {
        var pipeline = new LayeredPagePipeline(
                new PageTextCleaner(),
                new ParseQualityEvaluator());

        var result = pipeline.process(List.of(
                new LayeredPagePipeline.PageInput(1, "合同服务期限为两年，供应商应满足通用资格要求。", PdfBoxPageClassifier.Kind.TEXT),
                new LayeredPagePipeline.PageInput(6,
                        "14 | 标的 14：花都区域特种车辆维保 | 69 | 单价限价",
                        PdfBoxPageClassifier.Kind.TABLE),
                new LayeredPagePipeline.PageInput(7,
                        "15 | 标的 15：增城区域特种车辆维保 | 90.05 | 单价限价\n\n本项目还包含资格要求及合同条款说明。",
                        PdfBoxPageClassifier.Kind.MIXED)));

        assertEquals(3, result.pages().size());
        assertEquals(LayeredPagePipeline.Route.PDFBOX, result.pages().get(0).route());
        assertEquals(LayeredPagePipeline.Route.PPSTRUCTURE, result.pages().get(1).route());
        assertEquals(LayeredPagePipeline.Route.PPSTRUCTURE, result.pages().get(2).route());
        assertTrue(result.pages().stream().allMatch(page -> page.pageNumber() > 0));
        assertTrue(result.pages().stream().allMatch(page -> !page.cleanedText().isBlank()));
    }

    @Test
    void keepsPpsSourceWhenEvaluatingRows() {
        var pipeline = new LayeredPagePipeline(
                new PageTextCleaner(),
                new ParseQualityEvaluator());

        var result = pipeline.process(List.of(
                new LayeredPagePipeline.PageInput(6,
                        "[第 6 页] 14 | 标的 14：花都区域特种车辆维保 | 69 | 单价限价",
                        PdfBoxPageClassifier.Kind.TABLE),
                new LayeredPagePipeline.PageInput(7,
                        "[第 7 页] 15 | 标的 15：增城区域特种车辆维保 | 90.05 | 单价限价",
                        PdfBoxPageClassifier.Kind.MIXED)));

        assertTrue(result.pages().stream()
                .allMatch(page -> page.route() == LayeredPagePipeline.Route.PPSTRUCTURE));
        assertTrue(result.pages().stream()
                .allMatch(page -> page.kind() != PdfBoxPageClassifier.Kind.TEXT));
    }

    @Test
    void cleansAcrossPagesBeforeEvaluation() {
        var pipeline = new LayeredPagePipeline(new PageTextCleaner(), new ParseQualityEvaluator());
        var result = pipeline.process(List.of(
                new LayeredPagePipeline.PageInput(1, "招标文件\n这是第一页的有效正文内容，长度足够。", PdfBoxPageClassifier.Kind.TEXT),
                new LayeredPagePipeline.PageInput(2, "招标文件\n这是第二页的有效正文内容，长度足够。", PdfBoxPageClassifier.Kind.TEXT)));
        assertFalse(result.pages().get(0).cleanedText().contains("招标文件"));
        assertFalse(result.pages().get(1).cleanedText().contains("招标文件"));
    }
}
