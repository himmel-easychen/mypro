package com.agicore.rag;

import java.util.*;

/** Quality gate for deterministic PDF/table parsing before persistence. */
public final class ParseQualityEvaluator {
    public record Result(String status, double textReadability, double tableRowConsistency,
                         double rowBoundaryConfidence, double noiseRate, double coverage) {}

    public Result evaluate(List<String> chunks) {
        if (chunks == null) return evaluatePrepared(List.of());
        var normalizer = new TextNormalizationTool();
        var pageCleaner = new PageTextCleaner();
        return evaluatePrepared(pageCleaner.cleanPages(chunks).stream()
                .map(normalizer::normalize).toList());
    }

    /** Evaluates text after the caller has completed document-level cleaning. */
    public Result evaluatePrepared(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) return new Result("retry_structure", 0, 0, 0, 1, 0);
        int readable = 0, fragmented = 0, rows = 0;
        Set<Integer> widths = new HashSet<>();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) continue;
            String body = chunk.replaceFirst("^\\[第 \\d+ 页\\]\\s*", "");
            if (body.length() < 25 && (body.contains("招标公告") || body.matches("第\\s*\\d+\\s*页"))) continue;
            rows++;
            long bad = body.chars()
                    .filter(c -> c == '\ufffd' || (Character.isISOControl(c) && !Character.isWhitespace(c)))
                    .count();
            boolean plainTextPage = !body.contains("|") && body.length() >= 40;
            if (bad == 0 && (body.length() >= 8 || plainTextPage)) readable++;
            int width = body.split("\\s*\\|\\s*", -1).length;
            widths.add(width);
            String[] cells = body.split("\\s*\\|\\s*", -1);
            long tiny = Arrays.stream(cells).filter(c -> c.trim().length() <= 2).count();
            boolean sectionLeak = body.matches("(?s)^3\\s*[.．、]?\\s*投标人资格要求.*")
                    || body.matches("(?s)^3\\s*[.．、]?\\s*1\\s*通用资格要求.*")
                    || body.matches("(?s)^3\\s*[.．、]?\\s*2\\s*专用资格要求.*");
            boolean splitWord = body.matches("(?s).*区\\s*\\|\\s*域.*")
                    || body.matches("(?s).*车辆维\\s*\\|\\s*保.*")
                    || body.matches("(?s).*报价\\s*\\|\\s*表.*");
            if (body.length() < 12 || tiny >= Math.max(2, cells.length - 1)
                    || (cells.length <= 3 && tiny >= 1) || sectionLeak || splitWord) {
                fragmented++;
            }
        }
        double readability = rows == 0 ? 0 : (double) readable / rows;
        double consistency = widths.isEmpty() ? 0 : (widths.size() <= 2 ? 1 : 1.0 / widths.size());
        double noise = rows == 0 ? 1 : (double) fragmented / rows;
        double coverage = rows == 0 ? 0 : Math.min(1, rows / 2.0);
        String status = readability >= .95 && consistency >= .90 && coverage >= .90 && noise <= .10
                ? "accepted" : "retry_structure";
        return new Result(status, readability, consistency, Math.max(0, 1 - noise), noise, coverage);
    }
}
