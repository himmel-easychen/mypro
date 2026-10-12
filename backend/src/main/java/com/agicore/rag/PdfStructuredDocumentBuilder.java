package com.agicore.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PdfStructuredDocumentBuilder {
    private static final Pattern ROW_PATTERN = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL_PATTERN = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");

    StructuredDocument build(Map<Integer, OcrPage> ocrPages, List<String> fallbackPages, String sourceFile) {
        Map<Integer, OcrPage> pages = new LinkedHashMap<>(ocrPages);
        List<StructuredDocument.SourceUnit> units = new ArrayList<>();
        List<StructuredDocument.Block> blocks = new ArrayList<>();
        List<StructuredDocument.TableData> tables = new ArrayList<>();
        int fallbackNumber = 1;
        for (String fallback : fallbackPages) {
            int pageNumber = pageNumber(fallback, fallbackNumber++);
            pages.putIfAbsent(pageNumber, new OcrPage(pageNumber, List.of(
                    new OcrBlock("text", body(fallback), null, 1.0, 0, null))));
        }
        for (OcrPage page : pages.values()) {
            String unitId = UUID.randomUUID().toString();
            units.add(new StructuredDocument.SourceUnit(unitId, page.page(), "page:" + page.page(),
                    "physical", null));
            int order = 0;
            boolean hasContent = false;
            boolean hasHtmlTable = page.blocks().stream()
                    .anyMatch(block -> block.tableHtml() != null && !block.tableHtml().isBlank());
            InferredTable inferred = hasHtmlTable ? null : inferTable(page.blocks());
            if (inferred != null) {
                String raw = String.join("\n", inferred.parts().rows());
                blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), unitId,
                        "table", order++, raw, raw, Map.of("inferred_from", "bbox")));
                tables.add(new StructuredDocument.TableData(UUID.randomUUID().toString(), page.page(),
                        null, inferred.parts().headers(), inferred.parts().cells(),
                        Map.of("inferred_from", "bbox")));
                hasContent = true;
            }
            for (OcrBlock block : page.blocks()) {
                if (block.tableHtml() != null && !block.tableHtml().isBlank()) {
                    TableParts table = parseTable(block.tableHtml());
                    if (!table.cells().isEmpty()) {
                        String raw = String.join("\n", table.rows());
                        blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), unitId,
                                "table", order++, raw, raw, metadata(block)));
                        tables.add(new StructuredDocument.TableData(UUID.randomUUID().toString(), page.page(),
                                null, table.headers(), table.cells(), metadata(block)));
                        hasContent = true;
                        continue;
                    }
                }
                if (inferred != null && inferred.blocks().contains(block)) continue;
                String text = clean(block.text());
                if (!text.isBlank()) {
                    blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), unitId,
                            "text", order++, text, text, metadata(block)));
                    hasContent = true;
                }
            }
            if (!hasContent) {
                blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), unitId,
                        "text", 0, "", "", Map.of()));
            }
        }
        return new StructuredDocument(UUID.randomUUID().toString(), sourceFile, "pdf", units, blocks, tables);
    }

    private InferredTable inferTable(List<OcrBlock> blocks) {
        List<PositionedBlock> positioned = blocks.stream()
                .map(this::positioned)
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingDouble(PositionedBlock::centerY)
                        .thenComparingDouble(PositionedBlock::centerX))
                .toList();
        List<List<PositionedBlock>> rows = new ArrayList<>();
        for (PositionedBlock block : positioned) {
            List<PositionedBlock> row = rows.stream()
                    .filter(candidate -> Math.abs(averageY(candidate) - block.centerY())
                            <= Math.max(12.0, block.height() * .6))
                    .findFirst().orElse(null);
            if (row == null) {
                row = new ArrayList<>();
                rows.add(row);
            }
            row.add(block);
            row.sort(java.util.Comparator.comparingDouble(PositionedBlock::centerX));
        }
        for (int headerIndex = 0; headerIndex < rows.size() - 2; headerIndex++) {
            List<PositionedBlock> header = rows.get(headerIndex);
            if (header.size() < 3) continue;
            List<List<PositionedBlock>> dataRows = new ArrayList<>();
            for (int row = headerIndex + 1; row < rows.size(); row++) {
                if (mapsToHeader(rows.get(row), header)) dataRows.add(rows.get(row));
                else if (!dataRows.isEmpty()) break;
            }
            if (dataRows.size() < 2) continue;
            return buildInferred(header, dataRows);
        }
        return null;
    }

    private InferredTable buildInferred(List<PositionedBlock> header, List<List<PositionedBlock>> dataRows) {
        List<String> headers = header.stream().map(value -> clean(value.block().text())).toList();
        List<StructuredDocument.TableCell> cells = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        List<OcrBlock> consumed = new ArrayList<>();
        List<List<PositionedBlock>> allRows = new ArrayList<>();
        allRows.add(header);
        allRows.addAll(dataRows);
        for (int rowIndex = 0; rowIndex < allRows.size(); rowIndex++) {
            String[] values = new String[header.size()];
            for (PositionedBlock value : allRows.get(rowIndex)) {
                int column = nearestColumn(value.centerX(), header);
                String text = clean(value.block().text());
                values[column] = values[column] == null ? text : values[column] + " " + text;
                consumed.add(value.block());
            }
            for (int column = 0; column < values.length; column++) {
                String value = Objects.requireNonNullElse(values[column], "");
                cells.add(new StructuredDocument.TableCell(rowIndex, column, headers.get(column),
                        value, value, null));
            }
            rows.add(String.join(" | ", values));
        }
        return new InferredTable(new TableParts(headers, cells, rows), consumed);
    }

    private boolean mapsToHeader(List<PositionedBlock> row, List<PositionedBlock> header) {
        double maximumDistance = headerSpacing(header) * .55;
        return row.stream().anyMatch(value -> nearestDistance(value.centerX(), header) <= maximumDistance);
    }

    private int nearestColumn(double centerX, List<PositionedBlock> header) {
        int nearest = 0;
        for (int i = 1; i < header.size(); i++) {
            if (Math.abs(header.get(i).centerX() - centerX)
                    < Math.abs(header.get(nearest).centerX() - centerX)) nearest = i;
        }
        return nearest;
    }

    private double nearestDistance(double centerX, List<PositionedBlock> header) {
        return header.stream().mapToDouble(value -> Math.abs(value.centerX() - centerX)).min()
                .orElse(Double.MAX_VALUE);
    }

    private double headerSpacing(List<PositionedBlock> header) {
        double total = 0;
        for (int i = 1; i < header.size(); i++) total += header.get(i).centerX() - header.get(i - 1).centerX();
        return total / (header.size() - 1);
    }

    private double averageY(List<PositionedBlock> row) {
        return row.stream().mapToDouble(PositionedBlock::centerY).average().orElse(0);
    }

    private PositionedBlock positioned(OcrBlock block) {
        if (!(block.bbox() instanceof List<?> bbox) || bbox.size() < 4 || clean(block.text()).isBlank()) return null;
        try {
            double x0 = ((Number) bbox.get(0)).doubleValue();
            double y0 = ((Number) bbox.get(1)).doubleValue();
            double x1 = ((Number) bbox.get(2)).doubleValue();
            double y1 = ((Number) bbox.get(3)).doubleValue();
            return new PositionedBlock(block, (x0 + x1) / 2, (y0 + y1) / 2, Math.max(1, y1 - y0));
        } catch (ClassCastException ex) {
            return null;
        }
    }

    private TableParts parseTable(String html) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = ROW_PATTERN.matcher(html);
        while (rowMatcher.find()) {
            List<String> values = new ArrayList<>();
            Matcher cellMatcher = CELL_PATTERN.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) {
                String value = clean(TAG_PATTERN.matcher(cellMatcher.group(1)).replaceAll(" "));
                values.add(value);
            }
            if (!values.isEmpty()) rows.add(values);
        }
        if (rows.isEmpty()) return new TableParts(List.of(), List.of(), List.of());
        List<String> headers = new ArrayList<>(rows.get(0));
        List<StructuredDocument.TableCell> cells = new ArrayList<>();
        List<String> normalizedRows = new ArrayList<>();
        for (int row = 0; row < rows.size(); row++) {
            List<String> values = rows.get(row);
            normalizedRows.add(String.join(" | ", values));
            for (int column = 0; column < values.size(); column++) {
                String value = values.get(column);
                String columnName = column < headers.size() && !headers.get(column).isBlank()
                        ? headers.get(column) : "column-" + column;
                cells.add(new StructuredDocument.TableCell(row, column, columnName, value, value, null));
            }
        }
        return new TableParts(headers, cells, normalizedRows);
    }

    private Map<String, Object> metadata(OcrBlock block) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (block.bbox() != null) metadata.put("bbox", block.bbox());
        metadata.put("confidence", block.confidence());
        metadata.put("order_index", block.orderIndex());
        return metadata;
    }

    private String body(String page) {
        return page.replaceFirst("^\\[第\\s*\\d+\\s*页\\]\\s*", "").trim();
    }

    private int pageNumber(String page, int fallback) {
        Matcher matcher = Pattern.compile("^\\[第\\s*(\\d+)\\s*页\\]").matcher(page == null ? "" : page);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private String clean(String value) {
        return Objects.requireNonNullElse(value, "").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private record TableParts(List<String> headers, List<StructuredDocument.TableCell> cells,
                              List<String> rows) {}

    private record PositionedBlock(OcrBlock block, double centerX, double centerY, double height) {}

    private record InferredTable(TableParts parts, List<OcrBlock> blocks) {}
}
