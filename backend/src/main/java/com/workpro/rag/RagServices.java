package com.workpro.rag;

import com.workpro.domain.Contracts.*;
import com.workpro.model.ModelServices.EmbeddingClient;
import com.workpro.persistence.RagPersistence;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
public class RagServices {
    /** Hierarchical chunking baseline: 1,000-char parent windows with 250-char children. */
    private static final int PARENT_CHUNK_MAX_CHARS = 1000;
    private static final int CHILD_CHUNK_MAX_CHARS = 250;
    private final Map<String, ChunkRecord> chunks = new ConcurrentHashMap<>();
    private final Map<String, String> files = new ConcurrentHashMap<>();
    private final Map<String, double[]> vectors = new ConcurrentHashMap<>();
    private final Map<String, StructuredDocument> structuredDocuments = new ConcurrentHashMap<>();
    private final Map<String, String> documentHashes = new ConcurrentHashMap<>();
    private final Map<String, List<Integer>> documentFailedPages = new ConcurrentHashMap<>();
    private final Map<String, IngestQuality> ingestQuality = new ConcurrentHashMap<>();
    private final EmbeddingClient embedding;
    private final RagPersistence persistence;
    private final OcrClient ocr;
    private final PdfBoxPageClassifier pdfBoxClassifier = new PdfBoxPageClassifier();
    private final LayeredPagePipeline layeredPipeline;
    private final TextNormalizationTool normalizer = new TextNormalizationTool();
    private final PageTextCleaner pageCleaner = new PageTextCleaner();
    private final DocumentParser documentParser = new DocumentParser();
    private final RecursiveChunker recursiveChunker = new RecursiveChunker();
    private final Semaphore ingestSlots = new Semaphore(1);
    private final double minimumPagePassRate;

    public RagServices(EmbeddingClient embedding, RagPersistence persistence, OcrClient ocr,
                       @Value("${workpro.ocr.minimum-page-pass-rate:0.95}") double minimumPagePassRate) {
        this.embedding = embedding;
        this.persistence = persistence;
        this.ocr = ocr;
        this.minimumPagePassRate = minimumPagePassRate;
        this.layeredPipeline = new LayeredPagePipeline(pageCleaner, new ParseQualityEvaluator());
    }

    @PostConstruct
    void restorePersistedChunks() {
        for (RagPersistence.PersistedChunk persisted : persistence.loadChunks()) {
            ChunkRecord chunk = new ChunkRecord(
                    persisted.chunk().id(),
                    persisted.chunk().parentChunkId(),
                    persisted.documentId(),
                    persisted.sourceType(),
                    persisted.chunk().content(),
                    persisted.chunk().role(),
                    persisted.chunk().location(),
                    persisted.chunk().location(),
                    "emb:" + persisted.chunk().id(),
                    "kw:" + persisted.chunk().id(),
                    persisted.chunk().confidence(),
                    persisted.chunk().evidenceType());
            chunks.put(chunk.chunk_id(), chunk);
            files.put(persisted.documentId(), persisted.sourceFile());
            if (persisted.checksum() != null) documentHashes.put(persisted.documentId(), persisted.checksum());
            if (persisted.vector() != null) vectors.put(chunk.chunk_id(), persisted.vector());
        }
    }

    public Map<String, Object> ingest(MultipartFile file) throws IOException {
        if (!ingestSlots.tryAcquire()) {
            throw new IllegalStateException("R1005 OCR 任务繁忙，请稍后重试");
        }
        try {
            return ingestInternal(file);
        } finally {
            ingestSlots.release();
        }
    }

    private Map<String, Object> ingestInternal(MultipartFile file) throws IOException {
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "upload");
        String type = type(name);
        if (type == null) throw new IllegalArgumentException("R1001 文件类型不支持");
        List<String> pages = new ArrayList<>();
        List<Integer> failedPages = new ArrayList<>();
        Map<Integer, PdfBoxPageClassifier.Kind> pageKinds = new HashMap<>();
        Map<Integer, OcrPage> ocrPages = new LinkedHashMap<>();
        int totalPages = 0;
        byte[] data = file.getBytes();
        String fileHash = sha256(data);
        Optional<RagPersistence.ExistingDocument> existing = persistence.findDocumentByChecksum(fileHash);
        if (existing.isPresent()) return duplicateResult(existing.get(), name, type);
        StructuredDocument structured = null;
        if (type.equals("pdf")) {
            totalPages = addOcrPdfPages(pages, failedPages, pageKinds, ocrPages, data, name);
        } else if (type.equals("image")) {
            try { addOcrPages(pages, data, name); } catch (Exception e) { failedPages.add(1); }
        } else {
            structured = documentParser.parse(data, type, name);
            pages.addAll(toPages(structured));
            totalPages = pages.size();
        }
        var layeredPages = new ArrayList<LayeredPagePipeline.PageInput>();
        for (String page : pages) {
            int pageNumber = extractPageNumber(page, layeredPages.size() + 1);
            layeredPages.add(new LayeredPagePipeline.PageInput(
                    pageNumber,
                    page,
                    pageKinds.getOrDefault(pageNumber, PdfBoxPageClassifier.Kind.TEXT)));
        }
        var layered = layeredPipeline.process(layeredPages);
        pages = new ArrayList<>(layered.pages().stream()
                .map(LayeredPagePipeline.PageResult::cleanedText)
                .map(normalizer::normalize)
                .toList());
        // Pages have already passed document-level cleaning and normalization
        // in LayeredPagePipeline; do not let evaluation run on a second,
        // differently cleaned representation.
        List<String> qualityPages = pages;
        if (type.equals("word") || type.equals("excel")) {
            qualityPages = pages.stream()
                    .map(page -> page.replace("|", " "))
                    .toList();
        }
        var quality = new ParseQualityEvaluator().evaluatePrepared(qualityPages);
        String doc = UUID.randomUUID().toString();
        if (structured == null && type.equals("pdf")) {
            structured = new PdfStructuredDocumentBuilder().build(ocrPages, pages, name);
        }
        files.put(doc, name);
        documentHashes.put(doc, fileHash);
        if (structured != null) structuredDocuments.put(doc, structured);
        if (!failedPages.isEmpty()) documentFailedPages.put(doc, List.copyOf(failedPages));
        String raw = String.join("\n", pages);
        int count = 0;
        int parentCount = 0;
        for (String text : pages) if (!text.isBlank()) {
            String baseLocation = extractPageMarker(text).map(n -> "page:" + n).orElse(null);
            List<RecursiveChunker.ChunkPart> parents = recursiveChunker.split(text, PARENT_CHUNK_MAX_CHARS);
            for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
                RecursiveChunker.ChunkPart parentPart = parents.get(parentIndex);
                if (parentPart.content().isBlank()) continue;
                String parentId = UUID.randomUUID().toString();
                parentCount++;
                String parentLocation = baseLocation == null
                        ? "block-parent:" + parentCount
                        : baseLocation + "#parent:" + (parentIndex + 1);
                put(new ChunkRecord(parentId, null, doc, type, parentPart.content(), "parent", parentLocation,
                        parentLocation, "emb:" + parentId, "kw:" + parentId, .85, "text"));
                List<RecursiveChunker.ChunkPart> children = recursiveChunker.split(parentPart.content(), CHILD_CHUNK_MAX_CHARS);
                for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                    RecursiveChunker.ChunkPart child = children.get(childIndex);
                    if (child.content().isBlank()) continue;
                    String id = UUID.randomUUID().toString();
                    count++;
                    String location = baseLocation == null
                            ? "block:" + count
                            : baseLocation + "#parent:" + (parentIndex + 1) + "#chunk:" + (childIndex + 1);
                    put(new ChunkRecord(id, parentId, doc, type, child.content(), "child", location, location,
                            "emb:" + id, "kw:" + id, .8, type.equals("excel") ? "table" : "text"));
                }
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("doc_id", doc); result.put("source_file", name); result.put("source_type", type);
        int processedPages = totalPages == 0 ? countPageMarkers(pages) : totalPages - failedPages.size();
        double pagePassRate = totalPages == 0 ? 1.0 : (double) processedPages / totalPages;
        boolean complete = pagePassRate >= minimumPagePassRate;
        result.put("status", quality.status().equals("accepted") && complete ? "ready" : "needs_review");
        result.put("parse_status", complete ? quality.status() : "retry_ocr"); result.put("text_readability", quality.textReadability());
        result.put("table_row_consistency", quality.tableRowConsistency()); result.put("row_boundary_confidence", quality.rowBoundaryConfidence());
        result.put("noise_rate", quality.noiseRate()); result.put("coverage", quality.coverage());
        result.put("quality", raw.isBlank() ? "empty" : "text_ok"); result.put("chunk_count", count + parentCount);
        result.put("total_pages", totalPages);
        result.put("processed_pages", processedPages);
        result.put("page_pass_rate", pagePassRate);
        result.put("failed_pages", failedPages);
        ingestQuality.put(doc, new IngestQuality(
                String.valueOf(result.get("status")),
                String.valueOf(result.get("quality")),
                quality.status(),
                complete));
        return result;
    }

    private StructuredDocument structuredFromPdfPages(List<String> pages, String sourceFile) {
        List<StructuredDocument.SourceUnit> units = new ArrayList<>();
        List<StructuredDocument.Block> blocks = new ArrayList<>();
        List<StructuredDocument.TableData> tables = new ArrayList<>();
        for (String pageText : pages) {
            int pageNumber = extractPageNumber(pageText, units.size() + 1);
            String body = pageText.replaceFirst("^\\[第\\s*\\d+\\s*页\\]\\s*", "").trim();
            if (body.isBlank()) continue;
            String unitId = UUID.randomUUID().toString();
            units.add(new StructuredDocument.SourceUnit(unitId, pageNumber, "page:" + pageNumber,
                    "physical", null));
            String[] lines = body.split("\\R");
            boolean table = Arrays.stream(lines).anyMatch(line -> line.contains("|"));
            if (!table) {
                blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), unitId, "text", 0,
                        body, body, Map.of()));
                continue;
            }
            List<String> headers = new ArrayList<>();
            List<StructuredDocument.TableCell> cells = new ArrayList<>();
            List<String> normalizedRows = new ArrayList<>();
            int rowIndex = 0;
            for (String line : lines) {
                List<String> values = Arrays.stream(line.split("\\|", -1))
                        .map(String::trim).toList();
                if (values.stream().allMatch(String::isBlank)) continue;
                if (rowIndex == 0) headers.addAll(values);
                for (int column = 0; column < values.size(); column++) {
                    String value = values.get(column);
                    if (value.isBlank()) continue;
                    String columnName = column < headers.size() && !headers.get(column).isBlank()
                            ? headers.get(column) : "column-" + column;
                    cells.add(new StructuredDocument.TableCell(rowIndex, column, columnName,
                            value, value, null));
                }
                normalizedRows.add(String.join(" | ", values));
                rowIndex++;
            }
            String raw = String.join("\n", normalizedRows).trim();
            blocks.add(new StructuredDocument.Block(UUID.randomUUID().toString(), unitId, "table", 0,
                    raw, raw, Map.of()));
            tables.add(new StructuredDocument.TableData(UUID.randomUUID().toString(), pageNumber, null,
                    headers, cells, Map.of()));
        }
        return new StructuredDocument(UUID.randomUUID().toString(), sourceFile, "pdf", units, blocks, tables);
    }

    private void addOcrPages(List<String> out, byte[] data, String name) {
        var response = ocr.recognize(data, name);
        for (var page : response.pages()) {
            boolean table = false;
            for (var block : page.blocks()) if (block.tableHtml() != null && !block.tableHtml().isBlank()) {
                table = true; out.addAll(parseTableRows(block.tableHtml(), page.page()));
            }
            if (!table) {
                String text = page.blocks().stream().map(OcrBlock::text).filter(Objects::nonNull).filter(x -> !x.isBlank()).reduce((a, b) -> a + "\n" + b).orElse("");
                if (!text.isBlank()) out.add("[第 " + page.page() + " 页]\n" + text);
            }
        }
    }

    private int addOcrPdfPages(List<String> output,
                               List<Integer> failedPages,
                               Map<Integer, PdfBoxPageClassifier.Kind> pageKinds,
                               Map<Integer, OcrPage> ocrPages,
                               byte[] data,
                               String name) throws IOException {
        List<PdfBoxPageClassifier.PageInput> classified = pdfBoxClassifier.extract(data);
        for (PdfBoxPageClassifier.PageInput page : classified) {
            pageKinds.put(page.pageNumber(), page.kind());
            if (page.kind() == PdfBoxPageClassifier.Kind.TEXT) {
                if (!page.text().isBlank()) {
                    output.add("[第 " + page.pageNumber() + " 页]\n" + page.text());
                }
                continue;
            }
            boolean success = false;
            for (int attempt = 1; attempt <= 3 && !success; attempt++) {
                try {
                    success = addResponsePages(output, ocrPages, ocr.recognizePage(data, name, page.pageNumber()));
                    if (!success) throw new IllegalStateException("structure OCR returned no usable text");
                } catch (RuntimeException ex) {
                    if (attempt == 3) {
                        try {
                            success = addResponsePages(output, ocrPages,
                                    ocr.recognizePagePlain(data, name, page.pageNumber()));
                            if (!success) throw new IllegalStateException("plain OCR returned no usable text");
                        } catch (RuntimeException fallbackEx) {
                            if (!addPdfBoxFallback(output, page)) {
                                failedPages.add(page.pageNumber());
                            }
                        }
                    }
                }
            }
        }
        return classified.size();
    }

    static boolean addPdfBoxFallback(List<String> output, PdfBoxPageClassifier.PageInput page) {
        if (page.text() == null || page.text().isBlank()) {
            return false;
        }
        output.add("[第 " + page.pageNumber() + " 页]\n" + page.text());
        return true;
    }

    private int extractPageNumber(String page, int fallback) {
        var matcher = java.util.regex.Pattern.compile("^\\[第\\s*(\\d+)\\s*页\\]").matcher(page);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private Optional<Integer> extractPageMarker(String page) {
        var matcher = java.util.regex.Pattern.compile("^\\[第\\s*(\\d+)\\s*页\\]").matcher(page == null ? "" : page);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private int countPageMarkers(List<String> pages) {
        return (int) pages.stream()
                .map(p -> p.replaceFirst("^\\[第\\s*(\\d+)\\s*页\\].*$", "$1"))
                .filter(p -> p.matches("\\d+")).distinct().count();
    }

    private boolean addResponsePages(List<String> output, Map<Integer, OcrPage> ocrPages,
                                     OcrDocumentResult response) {
        int before = output.size();
        for (var page : response.pages()) {
            ocrPages.put(page.page(), page);
            boolean table = false;
            for (var block : page.blocks()) if (block.tableHtml() != null && !block.tableHtml().isBlank()) {
                table = true;
                output.addAll(parseTableRows(block.tableHtml(), page.page()));
            }
            if (!table) {
                String text = page.blocks().stream().map(OcrBlock::text).filter(Objects::nonNull)
                        .filter(x -> !x.isBlank()).reduce((a, b) -> a + "\n" + b).orElse("");
                if (!text.isBlank()) output.add("[第 " + page.page() + " 页]\n" + text);
            }
        }
        return output.size() > before;
    }

    private List<String> parseTableRows(String html, int page) {
        List<String> rows = new ArrayList<>();
        var rm = java.util.regex.Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(html);
        while (rm.find()) {
            var cm = java.util.regex.Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(rm.group(1));
            List<String> cells = new ArrayList<>();
            while (cm.find()) { String c = cm.group(1).replaceAll("(?is)<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim(); if (!c.isBlank()) cells.add(c); }
            if (!cells.isEmpty()) rows.add("[第 " + page + " 页] " + String.join(" | ", cells));
        }
        return rows;
    }

    private void put(ChunkRecord c) {
        chunks.put(c.chunk_id(), c);
        vectors.put(c.chunk_id(), embedding.embed(c.content()));
    }

    private double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0) {
            return 0.0;
        }
        int length = Math.min(left.length, right.length);
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public List<RetrievalResult> search(String query) {
        return search(query, null, 8);
    }

    public List<RetrievalResult> search(String query, String documentId, int limit) {
        String q = Objects.requireNonNullElse(query, "").toLowerCase();
        double[] v = embedding.embed(q);
        List<RagPersistence.VectorHit> persisted = persistence.searchByVector(v,
                limit > 0 ? limit : 8, documentId, embedding.model(), embedding.dimension());
        return persisted.stream().map(hit -> {
            double keyword = q.isBlank() ? 0.0
                    : (hit.content().toLowerCase(Locale.ROOT).contains(q) ? 1.0 : 0.0);
            double score = 0.7 * hit.similarity() + 0.3 * keyword;
            return new RetrievalResult(hit.chunkId(), hit.parentChunkId(), score,
                    Map.of("semantic", hit.similarity(), "keyword", keyword),
                    hit.content(), hit.location(), hit.sourceFile(), hit.sourceType(), hit.evidenceType());
        }).sorted(Comparator.comparingDouble(RetrievalResult::score).reversed()).toList();
    }

    public RagPersistence.EmbeddingAudit embeddingAudit() {
        return persistence.embeddingAudit(embedding.model(), embedding.dimension());
    }

    public ReindexResult reindexEmbeddings(int limit) {
        if ("mock".equalsIgnoreCase(embedding.provider())) {
            throw new IllegalStateException("E0005 当前 embedding provider 为 mock，拒绝写入生产向量");
        }
        int requested = 0;
        int succeeded = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        List<RagPersistence.ReindexChunk> candidates = persistence.loadChunksForReindex(
                embedding.model(), embedding.dimension(), limit);
        requested = candidates.size();
        int batchSize = "dashscope".equalsIgnoreCase(embedding.provider()) ? 10 : 1;
        for (int offset = 0; offset < candidates.size(); offset += batchSize) {
            List<RagPersistence.ReindexChunk> batch = candidates.subList(offset,
                    Math.min(offset + batchSize, candidates.size()));
            try {
                List<double[]> vectors = embedding.embedBatch(batch.stream()
                        .map(RagPersistence.ReindexChunk::content).toList());
                for (int index = 0; index < batch.size(); index++) {
                    RagPersistence.ReindexChunk candidate = batch.get(index);
                    try {
                        persistence.upsertEmbedding(candidate.chunkId(), vectors.get(index), embedding.model(),
                                embedding.dimension(), embedding.provider());
                        succeeded++;
                    } catch (RuntimeException exception) {
                        failed++;
                        errors.add(candidate.chunkId() + ":" + Objects.requireNonNullElse(exception.getMessage(), "unknown"));
                    }
                }
            } catch (RuntimeException exception) {
                String message = Objects.requireNonNullElse(exception.getMessage(), "unknown");
                for (RagPersistence.ReindexChunk candidate : batch) {
                    failed++;
                    errors.add(candidate.chunkId() + ":" + message);
                }
            }
        }
        return new ReindexResult(requested, succeeded, failed, errors);
    }

    public record ReindexResult(int requested, int succeeded, int failed, List<String> errors) {}

    public void persist(String id) {
        var list = chunks.values().stream().filter(c -> c.doc_id().equals(id))
                .map(c -> new RagPersistence.Chunk(c.chunk_id(), c.chunk_role(), c.evidence_type(),
                        normalizer.normalize(c.content()), c.location(), c.confidence(), c.evidence_type(), c.parent_chunk_id())).toList();
        var parent = chunks.values().stream().filter(c -> c.doc_id().equals(id)
                && "parent".equals(c.chunk_role())).findFirst().orElse(null);
        if (parent != null) {
            IngestQuality quality = ingestQuality.getOrDefault(id,
                    new IngestQuality("ready", "text_ok", "accepted", true));
            persistence.recordDocument(id, files.get(id), parent.source_type(), quality.status(), quality.quality(), list,
                    embedding.dimension(), documentHashes.get(id), vectors, embedding.model(), embedding.provider());
            StructuredDocument structured = structuredDocuments.get(id);
            if (structured != null) persistence.recordStructured(id, structured, quality.qualityStatus());
            persistence.recordFailedPages(
                    id,
                    documentFailedPages.getOrDefault(id, List.of()),
                    "structure OCR and plain OCR both failed");
        }
    }
    private record IngestQuality(String status, String quality, String qualityStatus, boolean pagePasses) {}
    private String type(String n) {
        String s = n.toLowerCase(Locale.ROOT);
        if (s.endsWith(".pdf")) return "pdf";
        if (s.endsWith(".docx")) return "word";
        if (s.endsWith(".xlsx") || s.endsWith(".xls")) return "excel";
        if (s.matches(".*\\.(png|jpg|jpeg|bmp|webp)$")) return "image";
        return null;
    }

    private Map<String, Object> duplicateResult(RagPersistence.ExistingDocument existing,
                                                 String sourceFile,
                                                 String sourceType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("doc_id", existing.documentId());
        result.put("source_file", sourceFile);
        result.put("source_type", sourceType);
        result.put("status", "ready");
        result.put("parse_status", "deduplicated");
        result.put("total_pages", existing.sourceUnits());
        result.put("processed_pages", existing.sourceUnits());
        result.put("page_pass_rate", 1.0);
        result.put("failed_pages", List.of());
        result.put("deduplicated", true);
        return result;
    }

    private String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private List<String> parse(byte[] data, String type) throws IOException {
        if (type.equals("word")) return parseWord(data);
        if (type.equals("excel")) return parseExcel(data);
        return List.of();
    }

    private List<String> parseStructured(byte[] data, String type, String name) throws IOException {
        StructuredDocument document = documentParser.parse(data, type, name);
        return toPages(document);
    }

    private List<String> toPages(StructuredDocument document) {
        List<String> result = new ArrayList<>();
        for (StructuredDocument.SourceUnit unit : document.sourceUnits()) {
            String text = document.blocks().stream()
                    .filter(block -> block.sourceUnitId().equals(unit.id()))
                    .map(StructuredDocument.Block::rawText)
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .findFirst().orElse("");
            if (!text.isBlank()) {
                result.add("[第 " + unit.pageNumber() + " 页]\n" + text);
            }
        }
        return result;
    }

    private List<String> parseWord(byte[] data) throws IOException {
        List<String> pages = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder current = new StringBuilder();
            int page = 1;
            for (IBodyElement element : document.getBodyElements()) {
                String text = bodyElementText(element);
                if (text.isBlank()) continue;
                if (current.length() > 0 && current.length() + text.length() > 5000) {
                    pages.add("[第 " + page++ + " 页]\n" + current);
                    current.setLength(0);
                }
                if (current.length() > 0) current.append('\n');
                current.append(text);
            }
            if (current.length() > 0) pages.add("[第 " + page + " 页]\n" + current);
        }
        return pages;
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

    private List<String> parseExcel(byte[] data) throws IOException {
        List<String> pages = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            int page = 1;
            for (Sheet sheet : workbook) {
                List<String> rows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (int i = row.getFirstCellNum(); i <= row.getLastCellNum(); i++) {
                        if (i < 0) continue;
                        String value = formatter.formatCellValue(row.getCell(i)).replaceAll("\\s+", " ").trim();
                        if (!value.isBlank()) cells.add(value);
                    }
                    if (!cells.isEmpty()) rows.add(String.join(" | ", cells));
                }
                if (!rows.isEmpty()) {
                    pages.add("[第 " + page++ + " 页]\n[Sheet: " + sheet.getSheetName() + "]\n" + String.join("\n", rows));
                }
            }
        }
        return pages;
    }
}
