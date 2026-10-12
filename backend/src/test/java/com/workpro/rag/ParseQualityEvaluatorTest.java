package com.workpro.rag;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ParseQualityEvaluatorTest {
    @Test void acceptsReadableConsistentRows() {
        var result = new ParseQualityEvaluator().evaluate(List.of(
                "[第 6 页] 14 | 标的 14：花都区域特种车辆维保 | 69 | 单价限价",
                "[第 6 页] 15 | 标的 15：增城区域特种车辆维保 | 90.05 | 单价限价"));
        assertEquals("accepted", result.status());
        assertTrue(result.textReadability() >= .95);
    }

    @Test void routesFragmentedRowsToStructureFallback() {
        var result = new ParseQualityEvaluator().evaluate(List.of(
                "[第 6 页] 番禺区域 | 特种车辆 | 维保",
                "[第 6 页] 域特种 | 车辆维 | 保"));
        assertEquals("retry_structure", result.status());
        assertTrue(result.noiseRate() > .10);
    }

    @Test void rejectsRowsWithSplitChineseWordsOrSectionLeakage() {
        var result = new ParseQualityEvaluator().evaluate(List.of(
                "[第 6 页] 14 | 标的 14： | 花都区域 | 特种车辆 | 维保 | 花都区 | 域特种 | 车辆维 | 保 | 69",
                "[第 6 页] 16 | 标的 16： | 从化区域 | 特种车辆 | 维保 | 72.2 | 3.投标人资格要求"));
        assertEquals("retry_structure", result.status());
        assertTrue(result.noiseRate() > .10);
    }

    @Test void normalLineBreaksDoNotReduceReadability() {
        var result = new ParseQualityEvaluator().evaluatePrepared(List.of(
                "[第 1 页]\n广州供电局车辆维保项目\n招标文件正文内容完整",
                "[第 2 页]\n投标人须知\n本项目采用公开招标方式进行采购"));
        assertEquals(1.0, result.textReadability());
    }
}
