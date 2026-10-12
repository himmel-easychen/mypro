package com.workpro.rag;

import com.workpro.rag.RetrievalContracts.RetrievalCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic first-stage reranker; it only reorders existing candidates. */
public class QueryChunkReranker {
    private static final Pattern TOKEN = Pattern.compile("[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9._-]*");
    private static final Pattern IDENTIFIER = Pattern.compile("(?i)[a-z]{2,}[-_]?[0-9]{2,}");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?(?:-\\d+(?:\\.\\d+)?)?");
    private static final Pattern EXPLICIT_ENTITY = Pattern.compile(
            "《[^》]{2,40}》|20\\d{2}(?:[-—至]20?\\d{2})?|[A-Za-z]{2,}[-_]?[0-9]{2,}|[\\p{IsHan}A-Za-z0-9]{4,32}(?:项目|合同|医院|供电局|公司|中心|学校|委员会)");

    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates,
                                           RetrievalQueryProfile.Intent intent, int topK) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        String normalizedQuery = normalize(query);
        List<String> terms = terms(normalizedQuery);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate c = candidates.get(i);
            String content = normalize(c.content());
            double coverage = terms.isEmpty() ? 0 : terms.stream().filter(content::contains).distinct().count() / (double) terms.size();
            double phrase = normalizedQuery.length() >= 3 && content.contains(normalizedQuery) ? 1.0 : 0.0;
            double numeric = terms.stream().filter(t -> t.chars().anyMatch(Character::isDigit) && content.contains(t)).count() * 0.01;
            double evidence = evidenceScore(normalizedQuery, content, intent);
            double identifier = IDENTIFIER.matcher(normalizedQuery).results()
                    .map(m -> m.group()).filter(content::contains).findAny().isPresent() ? 1.0 : 0.0;
            double agreement = Math.min(0.03, Math.max(0, c.providerScores().size() - 1) * 0.015);
            double base = c.finalScore();
            // RRF remains the primary signal. Lexical evidence is a bounded
            // tie-breaker; only an exact identifier match may make a large jump.
            double score = base + phrase * 0.05 + coverage * 0.02 + numeric + evidence + identifier + agreement;
            if (intent == RetrievalQueryProfile.Intent.COMPARE && numeric > 0) score += 0.02;
            scored.add(new Scored(c.withFinalScore(score, RetrievalContracts.CandidateStatus.FUSED), score, i));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingInt(Scored::originalIndex)
                .thenComparing(s -> s.candidate().chunkId()));
        int limit = Math.max(1, topK);
        return scored.stream().limit(limit).map(Scored::candidate).toList();
    }

    private record Scored(RetrievalCandidate candidate, double score, int originalIndex) {}

    static double entityScore(String query, String candidateText) {
        if (query == null || candidateText == null) return 0.0;
        Set<String> entities = new LinkedHashSet<>();
        EXPLICIT_ENTITY.matcher(query).results().map(m -> normalize(m.group()).replace("《", "").replace("》", ""))
                .filter(entity -> entity.length() >= 4).forEach(entities::add);
        long matches = entities.stream().filter(candidateText::contains).count();
        return Math.min(0.12, matches * 0.06);
    }

    private static List<String> terms(String query) {
        return TOKEN.matcher(query).results().map(m -> m.group()).filter(t -> t.length() > 1).distinct().toList();
    }

    private static double evidenceScore(String query, String content, RetrievalQueryProfile.Intent intent) {
        if (query.isBlank() || content.isBlank()) return 0.0;
        double numberScore = NUMBER.matcher(query).results()
                .map(match -> match.group())
                .filter(term -> term.length() >= 2 && content.contains(term))
                .distinct()
                .count() * 0.045;
        List<String> grams = hanGrams(query, intent == RetrievalQueryProfile.Intent.MULTI_HOP ? 4 : 5);
        long matched = grams.stream().filter(content::contains).count();
        double phraseScore = grams.isEmpty() ? 0.0 : Math.min(0.07, matched / (double) grams.size() * 0.18);
        return Math.min(0.14, numberScore + phraseScore);
    }


    private static List<String> hanGrams(String query, int width) {
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') han.append(ch);
        }
        if (han.length() < width) return List.of();
        List<String> grams = new ArrayList<>();
        for (int i = 0; i + width <= han.length(); i++) {
            String gram = han.substring(i, i + width);
            if (!isWeakGram(gram)) grams.add(gram);
        }
        return grams.stream().distinct().limit(80).toList();
    }

    private static boolean isWeakGram(String gram) {
        return gram.contains("是什么") || gram.contains("是多少") || gram.contains("哪些")
                || gram.contains("如果") || gram.contains("需要") || gram.contains("项目")
                || gram.contains("对应") || gram.contains("分别") || gram.contains("合计");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}，。！？；：、（）【】]", " ").replaceAll("\\s+", "").trim();
    }
}
