package com.agicore.tender.service;

import com.agicore.tender.domain.GenerationInputPack;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class TenderDocxRenderer {
    private final Path storageDir;

    public TenderDocxRenderer(@Value("${agicore.tender.generation-storage-dir:runtime-artifacts/tender}") String storageDir) {
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    public RenderedDocument render(String taskId, GenerationInputPack pack,
                                   List<TenderSectionWriter.SectionDraft> drafts) throws Exception {
        Files.createDirectories(storageDir);
        Path template = templatePath(pack);
        try (XWPFDocument document = open(template)) {
            configure(document);
            Set<String> inserted = fillMarkers(document, drafts);
            appendMissing(document, pack, drafts, inserted);
            String safeTask = taskId.replaceAll("[^A-Za-z0-9_-]", "_");
            String fileName = safeTask + "-投标文件-" + UUID.randomUUID() + ".docx";
            Path output = storageDir.resolve(fileName).normalize();
            if (!output.startsWith(storageDir)) throw new IllegalStateException("INVALID_GENERATION_OUTPUT_PATH");
            try (OutputStream stream = Files.newOutputStream(output)) { document.write(stream); }
            byte[] bytes = Files.readAllBytes(output);
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new RenderedDocument(fileName, output.toString(), checksum, bytes.length, Instant.now().toString());
        }
    }

    private XWPFDocument open(Path template) throws Exception {
        if (template == null) return new XWPFDocument();
        try (InputStream stream = Files.newInputStream(template)) { return new XWPFDocument(stream); }
    }

    private Path templatePath(GenerationInputPack pack) {
        Object raw = pack.templatePack().get("template_file_path");
        if (!(raw instanceof String value) || value.isBlank()) return null;
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase().endsWith(".docx"))
            throw new IllegalStateException("GENERATION_TEMPLATE_FILE_INVALID");
        return path;
    }

    private void configure(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr() : document.getDocument().getBody().addNewSectPr();
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(11906));
        size.setH(BigInteger.valueOf(16838));
        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margins.setTop(BigInteger.valueOf(1440)); margins.setBottom(BigInteger.valueOf(1440));
        margins.setLeft(BigInteger.valueOf(1588)); margins.setRight(BigInteger.valueOf(1588));

        XWPFStyles styles = document.createStyles();
        ensureStyle(styles, "Title", 22, true, ParagraphAlignment.CENTER);
        ensureStyle(styles, "Heading1", 16, true, ParagraphAlignment.LEFT);
        ensureStyle(styles, "Heading2", 14, true, ParagraphAlignment.LEFT);
        ensureStyle(styles, "BodyText", 11, false, ParagraphAlignment.BOTH);
    }

    private void ensureStyle(XWPFStyles styles, String id, int size, boolean bold, ParagraphAlignment alignment) {
        XWPFStyle style = styles.styleExist(id) ? styles.getStyle(id) : new XWPFStyle(CTStyle.Factory.newInstance());
        style.setStyleId(id);
        style.setType(STStyleType.PARAGRAPH);
        if (style.getCTStyle().getName() == null) style.getCTStyle().addNewName().setVal(id);
        CTPPrGeneral ppr = style.getCTStyle().isSetPPr() ? style.getCTStyle().getPPr() : style.getCTStyle().addNewPPr();
        ppr.addNewJc().setVal(switch (alignment) { case CENTER -> STJc.CENTER; case BOTH -> STJc.BOTH; default -> STJc.LEFT; });
        CTRPr rpr = style.getCTStyle().isSetRPr() ? style.getCTStyle().getRPr() : style.getCTStyle().addNewRPr();
        rpr.addNewRFonts().setEastAsia("宋体");
        rpr.addNewSz().setVal(BigInteger.valueOf(size * 2L));
        rpr.addNewSzCs().setVal(BigInteger.valueOf(size * 2L));
        if (bold) rpr.addNewB().setVal(true);
        if (!styles.styleExist(id)) styles.addStyle(style);
    }

    private Set<String> fillMarkers(XWPFDocument document, List<TenderSectionWriter.SectionDraft> drafts) {
        Set<String> inserted = new LinkedHashSet<>();
        for (TenderSectionWriter.SectionDraft draft : drafts) {
            String marker = "{{SECTION:" + draft.sectionId() + "}}";
            for (XWPFParagraph paragraph : allParagraphs(document)) {
                if (!paragraph.getText().contains(marker)) continue;
                replaceParagraph(paragraph, marker, draft.content());
                inserted.add(draft.sectionId());
            }
        }
        return inserted;
    }

    private List<XWPFParagraph> allParagraphs(XWPFDocument document) {
        java.util.ArrayList<XWPFParagraph> result = new java.util.ArrayList<>(document.getParagraphs());
        for (XWPFTable table : document.getTables())
            for (XWPFTableRow row : table.getRows())
                for (XWPFTableCell cell : row.getTableCells()) result.addAll(cell.getParagraphs());
        return result;
    }

    private void replaceParagraph(XWPFParagraph paragraph, String marker, String content) {
        String replaced = paragraph.getText().replace(marker, content);
        for (int index = paragraph.getRuns().size() - 1; index >= 0; index--) paragraph.removeRun(index);
        XWPFRun run = paragraph.createRun();
        String[] lines = replaced.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) { if (i > 0) run.addBreak(); run.setText(lines[i]); }
        setRunFont(run, 11, false);
    }

    private void appendMissing(XWPFDocument document, GenerationInputPack pack,
                               List<TenderSectionWriter.SectionDraft> drafts, Set<String> inserted) {
        String currentPart = null;
        for (TenderSectionWriter.SectionDraft draft : drafts) {
            if (inserted.contains(draft.sectionId())) continue;
            if (!draft.partId().equals(currentPart)) {
                if (currentPart != null || !document.getBodyElements().isEmpty()) document.createParagraph().setPageBreak(true);
                addCover(document, pack, draft.partTitle());
                currentPart = draft.partId();
            }
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.setSpacingBefore(240); heading.setSpacingAfter(120);
            XWPFRun headingRun = heading.createRun(); headingRun.setText(draft.title()); setRunFont(headingRun, 16, true);
            if (draft.sectionId().equals("PRICE-SCHEDULE") || draft.sectionId().equals("OPENING-SCHEDULE"))
                addPricingTable(document, pack);
            else addBody(document, draft.content());
        }
    }

    private void addCover(XWPFDocument document, GenerationInputPack pack, String partTitle) {
        XWPFParagraph title = document.createParagraph();
        title.setStyle("Title"); title.setSpacingBefore(2400); title.setSpacingAfter(600);
        XWPFRun titleRun = title.createRun();
        titleRun.setText(String.valueOf(pack.task().getOrDefault("project_name", "投标项目"))); setRunFont(titleRun, 22, true);
        XWPFParagraph subtitle = document.createParagraph(); subtitle.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun subtitleRun = subtitle.createRun(); subtitleRun.setText(partTitle); setRunFont(subtitleRun, 18, true);
        XWPFParagraph meta = document.createParagraph(); meta.setAlignment(ParagraphAlignment.CENTER); meta.setSpacingBefore(1200);
        XWPFRun metaRun = meta.createRun();
        metaRun.setText("投标人：" + String.valueOf(pack.task().getOrDefault("bidder_name", ""))); setRunFont(metaRun, 12, false);
        document.createParagraph().setPageBreak(true);
    }

    private void addBody(XWPFDocument document, String content) {
        for (String line : content.split("\\R", -1)) {
            XWPFParagraph paragraph = document.createParagraph(); paragraph.setStyle("BodyText");
            paragraph.setIndentationFirstLine(480); paragraph.setSpacingAfter(120); paragraph.setSpacingBetween(1.5);
            XWPFRun run = paragraph.createRun(); run.setText(line); setRunFont(run, 11, false);
        }
    }

    private void addPricingTable(XWPFDocument document, GenerationInputPack pack) {
        XWPFTable table = document.createTable(4, 2); table.setWidth("100%");
        String[][] values = {
                {"报价项目", "优惠率"},
                {"工时费", String.valueOf(pack.pricing().getOrDefault("labor_discount_rate_a_percent", "")) + "%"},
                {"材料费", String.valueOf(pack.pricing().getOrDefault("material_discount_rate_b_percent", "")) + "%"},
                {"综合优惠率", String.valueOf(pack.pricing().getOrDefault("composite_discount_rate_c_percent", "")) + "%"}
        };
        for (int row = 0; row < values.length; row++) for (int col = 0; col < 2; col++) {
            XWPFTableCell cell = table.getRow(row).getCell(col); cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
            cell.setText(""); XWPFParagraph paragraph = cell.getParagraphs().get(0); paragraph.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = paragraph.createRun(); run.setText(values[row][col]); setRunFont(run, 10, row == 0);
            if (row == 0) cell.getCTTc().addNewTcPr().addNewShd().setFill("D9EAF7");
        }
    }

    private void setRunFont(XWPFRun run, int size, boolean bold) {
        run.setFontFamily("宋体"); run.setFontFamily("宋体", XWPFRun.FontCharRange.eastAsia);
        run.setFontSize(size); run.setBold(bold); run.setColor("000000");
    }

    public record RenderedDocument(String fileName, String path, String checksum, long sizeBytes, String createdAt) {}
}
