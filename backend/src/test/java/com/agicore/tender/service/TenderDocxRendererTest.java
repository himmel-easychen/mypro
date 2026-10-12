package com.agicore.tender.service;

import com.agicore.tender.domain.GenerationInputPack;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TenderDocxRendererTest {
    @Test void rendersThreePartDocumentAndPricingTable() throws Exception {
        Path tempDir = Path.of("target", "test-artifacts", "tender-renderer");
        Files.createDirectories(tempDir);
        var renderer = new TenderDocxRenderer(tempDir.toString());
        var pack = new GenerationInputPack("GenerationInputPack", "1.0",
                Map.of("project_name", "车辆维保测试项目", "bidder_name", "测试投标人"), Map.of(), null, null,
                "GENERATION_READINESS_REPORT", Map.of("status", "confirmed"), Map.of("status", "confirmed"),
                Map.of("labor_discount_rate_a_percent", 10, "material_discount_rate_b_percent", 5,
                        "composite_discount_rate_c_percent", 8.5), List.of(), List.of(), Map.of(), Map.of());
        var drafts = List.of(
                new TenderSectionWriter.SectionDraft("PRICE", "报价投标文件", "PRICE-SCHEDULE", "报价表", "fixed_table", "", List.of(98)),
                new TenderSectionWriter.SectionDraft("BUSINESS", "商务投标文件", "BUSINESS-DEVIATION", "商务偏差表", "fixed_table", "无偏差。", List.of(108)),
                new TenderSectionWriter.SectionDraft("TECHNICAL", "技术投标文件", "SERVICE-PLAN", "服务方案", "llm_generate", "执行接车、诊断、维修、质检和交付流程。", List.of(134)));

        var rendered = renderer.render("task-1", pack, drafts);
        assertThat(Path.of(rendered.path())).isRegularFile();
        assertThat(rendered.checksum()).hasSize(64);
        try (var document = new XWPFDocument(Files.newInputStream(Path.of(rendered.path())))) {
            String text = document.getParagraphs().stream().map(p -> p.getText()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(text).contains("车辆维保测试项目", "商务投标文件", "服务方案");
            assertThat(document.getTables()).hasSize(1);
            assertThat(document.getTables().get(0).getText()).contains("10%", "8.5%");
        }
    }
}
