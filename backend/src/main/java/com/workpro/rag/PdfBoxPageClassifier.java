package com.workpro.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Classifies PDF pages for deciding whether PDFBox text extraction is sufficient. */
public final class PdfBoxPageClassifier {
    public enum Kind {
        TEXT,
        TABLE,
        MIXED,
        EMPTY
    }

    public record PageStat(int pageNumber, int characters, int lines, int numericLines,
                           int shortLines, Kind kind) {
    }

    public record Summary(int totalPages, int textPages, int tablePages, int mixedPages,
                          int emptyPages, double textPageRatio, List<PageStat> pages) {
    }

    public Summary analyze(byte[] pdfBytes) throws IOException {
        List<PageInput> extracted = extract(pdfBytes);
        List<PageStat> pages = extracted.stream()
                .map(page -> new PageStat(page.pageNumber(), page.text().length(),
                        countLines(page.text()), countNumericLines(page.text()),
                        countShortLines(page.text()), page.kind()))
                .toList();

        int textPages = count(pages, Kind.TEXT);
        int tablePages = count(pages, Kind.TABLE);
        int mixedPages = count(pages, Kind.MIXED);
        int emptyPages = count(pages, Kind.EMPTY);
        int totalPages = pages.size();
        double ratio = totalPages == 0 ? 0 : (double) textPages / totalPages;

        return new Summary(totalPages, textPages, tablePages, mixedPages, emptyPages, ratio,
                List.copyOf(pages));
    }

    public List<PageInput> extract(byte[] pdfBytes) throws IOException {
        List<PageInput> pages = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();

            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String text = stripper.getText(document).trim();
                PageStat stat = classify(page, text);
                pages.add(new PageInput(page, text, stat.kind()));
            }
        }

        return List.copyOf(pages);
    }

    public record PageInput(int pageNumber, String text, Kind kind) {
    }

    private PageStat classify(int pageNumber, String text) {
        if (text.isBlank()) {
            return new PageStat(pageNumber, 0, 0, 0, 0, Kind.EMPTY);
        }

        String[] lines = text.split("\\R");
        int numericLines = 0;
        int shortLines = 0;

        for (String line : lines) {
            String normalized = line.trim();
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.length() <= 12) {
                shortLines++;
            }
            if (normalized.matches(".*\\d.*") && normalized.length() <= 24) {
                numericLines++;
            }
        }

        int contentLines = lines.length;
        double numericRatio = contentLines == 0 ? 0 : (double) numericLines / contentLines;
        double shortRatio = contentLines == 0 ? 0 : (double) shortLines / contentLines;
        boolean hasTableSignals = numericRatio >= 0.25 && shortRatio >= 0.35;
        boolean hasParagraphs = text.length() >= 500 && contentLines >= 8;

        Kind kind;
        if (hasTableSignals && hasParagraphs) {
            kind = Kind.MIXED;
        } else if (hasTableSignals) {
            kind = Kind.TABLE;
        } else {
            kind = Kind.TEXT;
        }

        return new PageStat(pageNumber, text.length(), contentLines, numericLines, shortLines, kind);
    }

    private int count(List<PageStat> pages, Kind kind) {
        return (int) pages.stream().filter(page -> page.kind() == kind).count();
    }

    private int countLines(String text) {
        return text.isBlank() ? 0 : text.split("\\R").length;
    }

    private int countNumericLines(String text) {
        return countLinesMatching(text, "numeric");
    }

    private int countShortLines(String text) {
        return countLinesMatching(text, "short");
    }

    private int countLinesMatching(String text, String mode) {
        int count = 0;
        for (String line : text.split("\\R")) {
            String normalized = line.trim();
            if (normalized.isBlank()) {
                continue;
            }
            if ("short".equals(mode) && normalized.length() <= 12) {
                count++;
            }
            if ("numeric".equals(mode)
                    && normalized.matches(".*\\d.*")
                    && normalized.length() <= 24) {
                count++;
            }
        }
        return count;
    }
}
