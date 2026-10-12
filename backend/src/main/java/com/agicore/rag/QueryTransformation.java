package com.agicore.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Query transformation used before retrieval. It is intentionally explainable
 * and corpus-independent: evaluation case wording is never used as a rule.
 */
public record QueryTransformation(String originalQuery, String normalizedQuery,
                                  List<String> retrievalQueries, List<Double> retrievalQueryWeights,
                                  String strategy) {
    private static final Pattern SENTENCE_SEPARATOR = Pattern.compile("[?？!！。；;]+\\s*");
    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile("(?:、|，|,|以及|并且|分别|同时|既|又|和|及|(?<!参)与)\\s*");

    public QueryTransformation {
        retrievalQueries = List.copyOf(retrievalQueries);
        retrievalQueryWeights = List.copyOf(retrievalQueryWeights);
        if (retrievalQueries.size() != retrievalQueryWeights.size()) {
            throw new IllegalArgumentException("retrieval queries and weights must have the same size");
        }
    }

    public static QueryTransformation transform(String query, RetrievalQueryProfile profile,
                                                Map<String, Object> filters) {
        String original = query == null ? "" : query;
        String normalized = normalize(original);
        if (normalized.isBlank()) {
            return new QueryTransformation(original, normalized, List.of(normalized), List.of(1.0), "rewrite");
        }
        if (profile != null && profile.intent() == RetrievalQueryProfile.Intent.MULTI_HOP
                && !isDisabled(filters)) {
            List<String> parts = splitClauses(normalized);
            if (parts.size() >= 2) {
                List<String> retrievalQueries = new ArrayList<>();
                retrievalQueries.add(normalized);
                retrievalQueries.addAll(parts);
                List<Double> weights = new ArrayList<>();
                weights.add(1.0);
                parts.forEach(part -> weights.add(0.35));
                return new QueryTransformation(original, normalized, retrievalQueries, weights, "decompose");
            }
        }
        return new QueryTransformation(original, normalized, List.of(normalized), List.of(1.0), "rewrite");
    }



    private static boolean isDisabled(Map<String, Object> filters) {
        if (filters == null) return false;
        Object value = filters.get("disable_query_transform");
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ')
                .replace('：', ':')
                .replace('？', '?')
                .replace('！', '!')
                .replace('；', ';')
                .replace('，', ',');
        return normalized.trim().replaceAll("\\s+", " ");
    }

    private static List<String> splitClauses(String query) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        List<String> sentences = Arrays.stream(SENTENCE_SEPARATOR.split(query))
                .map(String::trim).filter(s -> s.length() >= 3).toList();
        List<List<String>> bySentence = new ArrayList<>();
        for (String sentence : sentences) {
            String cleanSentence = sentence.trim();
            if (cleanSentence.isBlank()) continue;
            List<String> clauses = new ArrayList<>();
            if (cleanSentence.length() >= 4 && CLAUSE_SEPARATOR.matcher(cleanSentence).find()) {
                clauses.addAll(Arrays.stream(CLAUSE_SEPARATOR.split(cleanSentence))
                        .map(String::trim).filter(QueryTransformation::isUsefulClause).toList());
            }
            if (clauses.size() < 2) clauses = List.of(cleanSentence);
            bySentence.add(clauses);
            parts.addAll(clauses);
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>(parts.stream().limit(3).toList());
        for (int i = 0; i < bySentence.size() && selected.size() < 6; i++) {
            if (bySentence.get(i).stream().noneMatch(selected::contains)) selected.add(sentences.get(i));
        }
        return selected.stream().limit(6).toList();
    }

    private static boolean isUsefulClause(String value) {
        String clause = value == null ? "" : value.trim();
        if (clause.length() < 3) return false;
        return !clause.matches("^(是多少|是什么|有什么|有哪些|如何|为什么|多久|多少)[?？]?$");
    }
}
