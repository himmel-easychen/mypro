package com.agicore.rag;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

public class DocumentParser {
    public StructuredDocument parse(byte[] data, String sourceType, String sourceFile) throws IOException {
        if ("excel".equals(sourceType)) return parseExcel(data, sourceFile);
        if ("word".equals(sourceType)) return parseWord(data, sourceFile);
        return new StructuredDocument(UUID.randomUUID().toString(), sourceFile, sourceType,
                List.of(), List.of(), List.of());
    }

    private StructuredDocument parseWord(byte[] data, String sourceFile) throws IOException {
        List<StructuredDocument.SourceUnit> units = new ArrayList<>();
        List<StructuredDocument.Block> blocks = new ArrayList<>();
        List<StructuredDocument.TableData> tables = new ArrayList<>();
        int page = 1;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder text = new StringBuilder();
            int order = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFTable table) {
                    if (text.length() > 0) {
                        addWordUnit(units, blocks, page++, text.toString(), order++);
                        text.setLength(0);
                    }
                    addWordTable(units, blocks, tables, page++, order++, table);
                    continue;
                }
                String value = bodyElementText(element);
                if (value.isBlank()) continue;
                if (text.length() > 0 && text.length() + value.length() > 5000) {
                    addWordUnit(units, blocks, page++, text.toString(), order++);
                    text.setLength(0);
                }
                if (text.length() > 0) text.append('\n');
                text.append(value);
            }
            if (text.length() > 0) addWordUnit(units, blocks, page, text.toString(), order);
        }
        return new StructuredDocument(UUID.randomUUID().toString(), sourceFile, "word", units, blocks, tables);
    }

    private void addWordTable(List<StructuredDocument.SourceUnit> units,
                              List<StructuredDocument.Block> blocks,
                              List<StructuredDocument.TableData> tables,
                              int page,
                              int order,
                              XWPFTable table) {
        List<String> headers = new ArrayList<>();
        List<StructuredDocument.TableCell> cells = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int rowIndex = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<String> values = new ArrayList<>();
            int columnIndex = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                String value = cell.getText().replaceAll("\\s+", " ").trim();
                values.add(value);
                if (rowIndex == 0) headers.add(value);
                if (!value.isBlank()) {
                    String columnName = rowIndex == 0 && !value.isBlank()
                            ? value
                            : (columnIndex < headers.size() && !headers.get(columnIndex).isBlank()
                            ? headers.get(columnIndex) : "column-" + columnIndex);
                    cells.add(new StructuredDocument.TableCell(rowIndex, columnIndex,
                            columnName, value, value, null));
                }
                columnIndex++;
            }
            if (values.stream().anyMatch(value -> !value.isBlank())) {
                lines.add(String.join(" | ", values));
            }
            rowIndex++;
        }
        if (cells.isEmpty()) return;
        String raw = String.join("\n", lines).trim();
        String unitId = UUID.randomUUID().toString();
        units.add(new StructuredDocument.SourceUnit(unitId, page, "page:" + page, "physical", null));
        blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), unitId, "table", order,
                raw, raw, Map.of()));
        tables.add(new StructuredDocument.TableData(UUID.randomUUID().toString(), page, null,
                headers, cells, Map.of()));
    }

    private void addWordUnit(List<StructuredDocument.SourceUnit> units,
                             List<StructuredDocument.Block> blocks,
                             int page, String text, int order) {
        String id = UUID.randomUUID().toString();
        units.add(new StructuredDocument.SourceUnit(id, page, "page:" + page, "physical", null));
        blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), id, "text", order,
                text, text.trim(), Map.of()));
    }

    private StructuredDocument parseExcel(byte[] data, String sourceFile) throws IOException {
        List<StructuredDocument.SourceUnit> units = new ArrayList<>();
        List<StructuredDocument.Block> blocks = new ArrayList<>();
        List<StructuredDocument.TableData> tables = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        int page = 1;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            for (Sheet sheet : workbook) {
                List<StructuredDocument.TableCell> cells = new ArrayList<>();
                List<String> headers = new ArrayList<>();
                StringBuilder raw = new StringBuilder();
                int maxColumn = 0;
                for (Row row : sheet) maxColumn = Math.max(maxColumn, row.getLastCellNum());
                for (Row row : sheet) {
                    List<String> values = new ArrayList<>();
                    for (int column = 0; column < maxColumn; column++) {
                        String value = formatter.formatCellValue(row.getCell(column)).replaceAll("\\s+", " ").trim();
                        values.add(value);
                        if (!value.isBlank()) cells.add(new StructuredDocument.TableCell(row.getRowNum(), column,
                                "column-" + column, value, value, null));
                    }
                    if (row.getRowNum() == 0) headers.addAll(values);
                    if (values.stream().anyMatch(v -> !v.isBlank())) raw.append(String.join(" | ", values)).append('\n');
                }
                if (cells.isEmpty()) continue;
                String unitId = UUID.randomUUID().toString();
                units.add(new StructuredDocument.SourceUnit(unitId, page, "sheet:" + sheet.getSheetName(),
                        "logical", sheet.getSheetName()));
                blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), unitId, "table", 0,
                        raw.toString().trim(), raw.toString().trim(), Map.of("sheet", sheet.getSheetName())));
                tables.add(new StructuredDocument.TableData(UUID.randomUUID().toString(), page++, sheet.getSheetName(),
                        headers, cells, Map.of()));
            }
        }
        return new StructuredDocument(UUID.randomUUID().toString(), sourceFile, "excel", units, blocks, tables);
    }

    private String bodyElementText(IBodyElement element) {
        if (element instanceof XWPFParagraph paragraph) return paragraph.getText().trim();
        if (!(element instanceof XWPFTable table)) return "";
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String value = cell.getText().replaceAll("\\s+", " ").trim();
                if (!value.isBlank()) cells.add(value);
            }
            if (!cells.isEmpty()) rows.add(String.join(" | ", cells));
        }
        return String.join("\n", rows);
    }
}
