package com.agicore.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps page classification, cleaning, evaluation and routing together.
 * Every page remains independently addressable so table rows cannot leak into
 * the neighbouring text page during the first cleaning pass.
 */
public final class LayeredPagePipeline {
    public enum Route {
        PDFBOX,
        PPSTRUCTURE
    }

    public record PageInput(int pageNumber, String rawText, PdfBoxPageClassifier.Kind kind) {
    }

    public record PageResult(int pageNumber, PdfBoxPageClassifier.Kind kind, Route route,
                             String cleanedText, ParseQualityEvaluator.Result quality) {
    }

    public record Result(List<PageResult> pages) {
        public Result {
            pages = List.copyOf(pages);
        }

        public boolean allAccepted() {
            return pages.stream().allMatch(page -> "accepted".equals(page.quality().status()));
        }
    }

    private final PageTextCleaner cleaner;
    private final ParseQualityEvaluator evaluator;

    public LayeredPagePipeline(PageTextCleaner cleaner, ParseQualityEvaluator evaluator) {
        this.cleaner = cleaner;
        this.evaluator = evaluator;
    }

    public Result process(List<PageInput> inputs) {
        List<PreparedPage> prepared = new ArrayList<>();
        List<String> rawPages = inputs.stream()
                .map(input -> input.rawText() == null ? "" : input.rawText())
                .toList();
        // Clean the complete document in one pass so repeated headers/footers
        // can be removed across page boundaries before quality evaluation.
        List<String> cleanedPages = cleaner.cleanPages(rawPages);
        TextNormalizationTool normalizer = new TextNormalizationTool();
        for (int i = 0; i < inputs.size(); i++) {
            PageInput input = inputs.get(i);
            String cleaned = normalizer.normalize(cleanedPages.get(i));
            Route route = input.kind() == PdfBoxPageClassifier.Kind.TEXT
                    ? Route.PDFBOX
                    : Route.PPSTRUCTURE;
            prepared.add(new PreparedPage(input, route, cleaned));
        }

        List<PageResult> results = new ArrayList<>();
        for (Route route : Route.values()) {
            List<PreparedPage> group = prepared.stream()
                    .filter(page -> page.route() == route)
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            // The evaluator receives already-cleaned and normalized text.
            ParseQualityEvaluator.Result quality = evaluator.evaluatePrepared(
                    group.stream().map(PreparedPage::cleanedText).toList());
            for (PreparedPage page : group) {
                results.add(new PageResult(page.input().pageNumber(), page.input().kind(),
                        page.route(), page.cleanedText(), quality));
            }
        }
        return new Result(results);
    }

    private record PreparedPage(PageInput input, Route route, String cleanedText) {
    }
}
