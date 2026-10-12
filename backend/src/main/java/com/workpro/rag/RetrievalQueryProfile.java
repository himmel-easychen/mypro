package com.workpro.rag;

import java.util.Locale;
import java.util.List;
import java.util.Map;

/** Explainable query routing policy for the first retrieval baseline. */
public record RetrievalQueryProfile(Intent intent, int providerTopK, int structuredTopK,
                                    int fusionTopK, int contextTopK, boolean structuredEnabled) {
    public enum Intent { BASIC, MULTI_HOP, COMPARE, CONFUSION, UNANSWERABLE, IDENTIFIER }

    public static RetrievalQueryProfile from(String query, Map<String, Object> filters) {
        String explicit = filters == null ? null : String.valueOf(filters.getOrDefault("query_type", ""));
        Intent intent = parseExplicit(explicit);
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (intent == null) {
            if (q.contains("拒答") || q.contains("无法回答") || q.contains("未提及")) intent = Intent.UNANSWERABLE;
            else if (q.contains("混淆") || q.contains("干扰")) intent = Intent.CONFUSION;
            else if (q.contains("比较") || q.contains("高于") || q.contains("低于") || q.contains("哪个") && q.contains("金额")) intent = Intent.COMPARE;
            else if (isMultiHop(q)) intent = Intent.MULTI_HOP;
            else if (q.contains("编号") || q.contains("代码") || q.matches(".*[a-z]{2,}[-_]?[0-9]{2,}.*")) intent = Intent.IDENTIFIER;
            else intent = Intent.BASIC;
        }
        var profile = switch (intent) {
            // Multi-hop needs a deeper provider window to recover a second
            // answer facet; fusion still caps the reranker input at 50.
            case MULTI_HOP -> new RetrievalQueryProfile(intent, 50, 20, 50, 5, true);
            case COMPARE -> new RetrievalQueryProfile(intent, 50, 20, 50, 5, true);
            case CONFUSION -> new RetrievalQueryProfile(intent, 15, 10, 15, 5, true);
            case UNANSWERABLE -> new RetrievalQueryProfile(intent, 12, 0, 12, 5, false);
            case IDENTIFIER -> new RetrievalQueryProfile(intent, 12, 0, 12, 5, false);
            case BASIC -> new RetrievalQueryProfile(intent, 12, 0, 12, 5, false);
        };
        // Answering needs more diverse evidence than the five-item search preview.
        // Keep provider limits, fusion and ranking unchanged for a comparable baseline.
        if (filters != null && Boolean.TRUE.equals(filters.get("answer_context")))
            return new RetrievalQueryProfile(intent, profile.providerTopK(), profile.structuredTopK(),
                    Math.max(profile.fusionTopK(), 30), 10, profile.structuredEnabled());
        return profile;
    }

    private static boolean isMultiHop(String query) {
        long questionMarks = query.chars().filter(ch -> ch == '?' || ch == '？').count();
        if (questionMarks >= 2) return true;
        if (query.contains("同时") && (query.contains("条件") || query.contains("要求"))) return true;
        long numbers = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(query).results().count();
        if (numbers >= 2 && (query.contains("合计") || query.contains("加起来")
                || query.contains("总计") || query.contains("相差"))) return true;
        boolean hasJoiner = query.contains("结合") || query.contains("同时") || query.contains("分别")
                || query.contains("以及") || query.contains("另外") || query.contains("并且")
                || query.contains("和") || query.contains("及") || query.contains("与")
                || query.contains("既") && query.contains("又")
                || query.contains("一方面") && query.contains("另一方面");
        long interrogatives = List.of("什么", "多少", "多久", "是否", "哪个", "哪些", "如何").stream()
                .filter(query::contains).count();
        // A single interrogative can still request multiple evidence records,
        // for example "A 和 B 的金额分别是多少".
        return hasJoiner && (interrogatives >= 2
                || query.contains("分别") && interrogatives >= 1
                || query.contains("既") && query.contains("又"));
    }

    private static Intent parseExplicit(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("auto")) return null;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "multi_hop", "multihop", "semantic" -> Intent.MULTI_HOP;
            case "compare", "comparison", "table_numeric" -> Intent.COMPARE;
            case "confusion" -> Intent.CONFUSION;
            case "unanswerable", "unanswerable_query" -> Intent.UNANSWERABLE;
            case "identifier" -> Intent.IDENTIFIER;
            default -> Intent.BASIC;
        };
    }
}
