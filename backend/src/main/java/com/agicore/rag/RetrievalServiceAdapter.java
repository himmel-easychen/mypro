package com.agicore.rag;

import com.agicore.domain.Contracts.EvidenceRef;
import com.agicore.domain.Contracts.RetrievalResult;
import com.agicore.rag.RetrievalContracts.RetrievalPack;
import com.agicore.rag.RetrievalContracts.RetrievalRequest;
import com.agicore.rag.RetrievalContracts.RetrievalService;
import com.agicore.rag.RetrievalContracts.CandidateStatus;
import com.agicore.rag.RetrievalContracts.ProviderScore;
import com.agicore.rag.RetrievalContracts.RetrievalCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RetrievalServiceAdapter implements RetrievalService {
    private final RagServices rag;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VectorRetrievalProvider vectorProvider;
    private final ElasticsearchBm25RetrievalProvider bm25Provider;
    private final StructuredRetrievalProvider structuredProvider;
    private final RetrievalFusionService fusion;
    private final CrossEncoderReranker crossEncoderReranker;
    private final QueryChunkReranker queryChunkReranker = new QueryChunkReranker();
    private final EvidenceContextReranker contextReranker = new EvidenceContextReranker();

    public static final int RRF_K = 60;

    public static Map<String, Double> defaultProviderWeights() {
        return Map.of("VECTOR", 1.0, "ELASTICSEARCH_BM25", 1.0, "STRUCTURED", 0.5, "EXACT", 1.0);
    }

    /** Compatibility constructor retained for unit tests and legacy callers. */
    public RetrievalServiceAdapter(RagServices rag, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(rag, jdbc, objectMapper, null, null, null, null, null);
    }

    public RetrievalServiceAdapter(RagServices rag, JdbcTemplate jdbc, ObjectMapper objectMapper,
                                   VectorRetrievalProvider vectorProvider,
                                   ElasticsearchBm25RetrievalProvider bm25Provider,
                                   StructuredRetrievalProvider structuredProvider,
                                   RetrievalFusionService fusion) {
        this(rag, jdbc, objectMapper, vectorProvider, bm25Provider, structuredProvider, fusion, null);
    }

    @Autowired
    public RetrievalServiceAdapter(RagServices rag, JdbcTemplate jdbc, ObjectMapper objectMapper,
                                   VectorRetrievalProvider vectorProvider,
                                   ElasticsearchBm25RetrievalProvider bm25Provider,
                                   StructuredRetrievalProvider structuredProvider,
                                   RetrievalFusionService fusion,
                                   CrossEncoderReranker crossEncoderReranker) {
        this.rag = rag;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.vectorProvider = vectorProvider;
        this.bm25Provider = bm25Provider;
        this.structuredProvider = structuredProvider;
        this.fusion = fusion;
        this.crossEncoderReranker = crossEncoderReranker;
    }

    public RetrievalPack retrieve(RetrievalRequest request) {
        long started = System.currentTimeMillis();
        var execution = retrieveResults(request);
        var results = execution.results();
        var context = execution.context();
        var refs = results.stream()
                .map(result -> new EvidenceRef(
                        result.chunk_id(), null, null, result.source_file(), result.location(),
                        result.chunk_id(), result.score()))
                .toList();
        jdbc.update(
                "insert into rag_retrieval_log(retrieval_id,workspace_id,query,top_k," +
                        "filters_json,results_json,duration_ms) " +
                        "select ?,id,?,?,?::jsonb,?::jsonb,? from workspace where code='default'",
                UUID.randomUUID(), request.query(), request.topK(), toJson(request.filters()),
                toJson(results), System.currentTimeMillis() - started);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retrieval_mode", fusion == null ? "pgvector" : "rrf_pgvector_bm25_structured");
        metadata.put("query_intent", execution.profile().intent().name());
        metadata.put("query_original", execution.transformation().originalQuery());
        metadata.put("query_normalized", execution.transformation().normalizedQuery());
        metadata.put("query_retrieval_queries", execution.transformation().retrievalQueries());
        metadata.put("query_transform_strategy", execution.transformation().strategy());
        metadata.put("provider_top_k", execution.profile().providerTopK());
        metadata.put("structured_top_k", execution.profile().structuredTopK());
        metadata.put("fusion_top_k", execution.profile().fusionTopK());
        metadata.put("context_top_k", execution.profile().contextTopK());
        metadata.put("structured_enabled", execution.profile().structuredEnabled());
        metadata.put("provider_weights", defaultProviderWeights());
        metadata.put("enabled_providers", execution.enabledProviders());
        metadata.put("provider_failures", execution.providerFailures());
        metadata.put("retrieval_status", execution.providerFailures().size() == execution.enabledProviders().size()
                ? "FAILED" : execution.providerFailures().isEmpty() ? "OK" : "DEGRADED");
        String traceId = UUID.randomUUID().toString();
        RetrievalTrace trace = new RetrievalTrace(
                traceId,
                String.valueOf(request.filters() == null ? "auto" : request.filters().getOrDefault("query_type", "auto")),
                execution.enabledProviders(), execution.providerFailures(), execution.profile().intent().name(),
                Map.of("VECTOR", execution.profile().providerTopK(), "ELASTICSEARCH_BM25", execution.profile().providerTopK(),
                        "STRUCTURED", execution.profile().structuredTopK()), execution.profile().fusionTopK(),
                execution.profile().contextTopK(), results.size(), RRF_K, defaultProviderWeights(),
                context.stream().map(RetrievalResult::chunk_id).toList(),
                System.currentTimeMillis() - started);
        metadata.put("trace_id", traceId);
        metadata.put("trace", trace);
        metadata.put("stage_candidates", stageCandidates(execution));
        metadata.put("query_coverage", queryCoverage(execution));
        metadata.put("targeted_query_indices", execution.targetedQueryIndices());
        metadata.put("candidates", results.stream()
                .map(result -> toCandidate(result, "VECTOR"))
                .toList());
        metadata.put("final_context", context);
        Map<String, RetrievalCandidate> answerCandidates = new LinkedHashMap<>();
        execution.reranked().forEach(c -> answerCandidates.putIfAbsent(c.chunkId(), c));
        if (request.filters() != null && Boolean.TRUE.equals(request.filters().get("answer_context"))) {
            execution.vector().forEach(c -> answerCandidates.putIfAbsent(c.chunkId(), c));
            execution.bm25().forEach(c -> answerCandidates.putIfAbsent(c.chunkId(), c));
            execution.structured().forEach(c -> answerCandidates.putIfAbsent(c.chunkId(), c));
            metadata.put("answer_candidates", answerCandidates.values().stream().map(this::toResult).toList());
        }
        metadata.put("context_document_ids", answerCandidates.values().stream()
                .filter(c -> c.documentId() != null)
                .collect(java.util.stream.Collectors.toMap(RetrievalCandidate::chunkId,
                        RetrievalCandidate::documentId, (left, right) -> left)));
        metadata.put("parent_context", loadParentContext(
                request.filters() != null && Boolean.TRUE.equals(request.filters().get("answer_context"))
                        ? answerCandidates.values().stream().map(this::toResult).toList() : context));
        return new RetrievalPack(results, refs, metadata);
    }

    private RetrievalExecution retrieveResults(RetrievalRequest request) {
        if (fusion == null) {
            var profile = RetrievalQueryProfile.from(request.query(), request.filters());
            var basicResults = rag.search(request.query(), request.docId(), request.topK() > 0 ? request.topK() : 8);
            var basicCandidates = basicResults.stream().map(result -> toCandidate(result, "VECTOR")).toList();
            return new RetrievalExecution(
                    basicResults,
                    List.of("VECTOR"), Map.of(), profile,
                    basicResults.stream().limit(profile.contextTopK()).toList(),
                    QueryTransformation.transform(request.query(), profile, request.filters()),
                    basicCandidates, List.of(), List.of(), basicCandidates, basicCandidates,
                    basicCandidates.stream().limit(profile.contextTopK()).toList(), List.of());
        }
        Map<String, Object> filters = request.filters() == null ? Map.of() : request.filters();
        RetrievalQueryProfile profile = RetrievalQueryProfile.from(request.query(), filters);
        QueryTransformation transformation = QueryTransformation.transform(request.query(), profile, filters);
        String mode = String.valueOf(filters.getOrDefault("mode", "auto")).toLowerCase();
        if (mode.equals("exact")) mode = "structured";
        if (mode.equals("keyword") || mode.equals("elasticsearch")) mode = "bm25";
        if (!List.of("auto", "vector", "bm25", "structured").contains(mode)) {
            throw new IllegalArgumentException("mode must be auto|vector|bm25|structured");
        }
        List<String> enabled = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<RetrievalCandidate> vector = List.of();
        List<RetrievalCandidate> bm25 = List.of();
        List<RetrievalCandidate> structured = List.of();
        if (mode.equals("auto") || mode.equals("vector")) {
            enabled.add("VECTOR");
            try { vector = searchAcrossQueries(vectorProvider, request, filters, transformation, profile.providerTopK()); }
            catch (RuntimeException e) { failures.put("VECTOR", safeMessage(e)); }
        }
        if (mode.equals("auto") || mode.equals("bm25")) {
            enabled.add("ELASTICSEARCH_BM25");
            try { bm25 = searchAcrossQueries(bm25Provider, request, filters, transformation, profile.providerTopK()); }
            catch (RuntimeException e) { failures.put("ELASTICSEARCH_BM25", safeMessage(e)); }
        }
        if ((mode.equals("auto") && profile.structuredEnabled()) || mode.equals("structured")) {
            enabled.add("STRUCTURED");
            int structuredLimit = profile.structuredTopK() > 0 ? profile.structuredTopK() : profile.providerTopK();
            try { structured = searchAcrossQueries(structuredProvider, request, filters, transformation, structuredLimit); }
            catch (RuntimeException e) { failures.put("STRUCTURED", safeMessage(e)); }
        }
        List<Integer> targetedQueryIndices = missingProviderQueries(
                List.of(vector, bm25, structured), transformation);
        if (!targetedQueryIndices.isEmpty() && profile.intent() == RetrievalQueryProfile.Intent.MULTI_HOP) {
            int targetedLimit = Math.min(60, Math.max(profile.providerTopK(), profile.structuredTopK()));
            vector = augmentMissingQueries(vectorProvider, vector, request, filters, transformation,
                    targetedQueryIndices, targetedLimit);
            bm25 = augmentMissingQueries(bm25Provider, bm25, request, filters, transformation,
                    targetedQueryIndices, targetedLimit);
            if (profile.structuredEnabled()) {
                structured = augmentMissingQueries(structuredProvider, structured, request, filters, transformation,
                        targetedQueryIndices, targetedLimit);
            }
        }
        // Keep a wider fusion pool until the lexical/evidence reranker has had
        // a chance to rescue strong single-provider candidates. The externally
        // visible result window remains profile.fusionTopK().
        int preRerankTopK = Math.max(profile.fusionTopK(), profile.fusionTopK() * 3);
        List<RetrievalCandidate> fused = fuse(vector, bm25, structured, profile, preRerankTopK);
        List<RetrievalCandidate> reranked = rerank(transformation, fused, profile, preRerankTopK);
        if (profile.intent() == RetrievalQueryProfile.Intent.MULTI_HOP) {
            List<Integer> missingAfterRerank = missingProviderQueries(List.of(reranked), transformation);
            List<Integer> newTargets = missingAfterRerank.stream()
                    .filter(index -> !targetedQueryIndices.contains(index)).toList();
            if (!newTargets.isEmpty()) {
                int targetedLimit = Math.min(60, Math.max(profile.providerTopK(), profile.structuredTopK()));
                vector = augmentMissingQueries(vectorProvider, vector, request, filters, transformation,
                        newTargets, targetedLimit);
                bm25 = augmentMissingQueries(bm25Provider, bm25, request, filters, transformation,
                        newTargets, targetedLimit);
                if (profile.structuredEnabled()) {
                    structured = augmentMissingQueries(structuredProvider, structured, request, filters, transformation,
                            newTargets, targetedLimit);
                }
                targetedQueryIndices.addAll(newTargets);
                fused = fuse(vector, bm25, structured, profile, preRerankTopK);
                reranked = rerank(transformation, fused, profile, preRerankTopK);
            }
        }
        var contextCandidates = contextReranker.selectContext(
                transformation.normalizedQuery(), reranked, profile.contextTopK(),
                profile.intent() == RetrievalQueryProfile.Intent.MULTI_HOP,
                profile.intent() == RetrievalQueryProfile.Intent.MULTI_HOP
                        ? transformation.retrievalQueries().subList(1, transformation.retrievalQueries().size())
                        : List.of());
        return new RetrievalExecution(reranked.stream().map(this::toResult).toList(), enabled, failures, profile,
                contextCandidates.stream().map(this::toResult).toList(), transformation,
                vector, bm25, structured, fused, reranked, contextCandidates, targetedQueryIndices);
    }

    private List<RetrievalCandidate> fuse(List<RetrievalCandidate> vector,
                                          List<RetrievalCandidate> bm25,
                                          List<RetrievalCandidate> structured,
                                          RetrievalQueryProfile profile,
                                          int preRerankTopK) {
        return fusion.fuse(vector, bm25, structured,
                providerWeightsFor(profile.intent()), RRF_K, preRerankTopK);
    }

    private List<RetrievalCandidate> rerank(QueryTransformation transformation,
                                            List<RetrievalCandidate> fused,
                                            RetrievalQueryProfile profile,
                                            int preRerankTopK) {
        var reranked = queryChunkReranker.rerank(transformation.normalizedQuery(), fused,
                profile.intent(), preRerankTopK);
        if (crossEncoderReranker != null)
            return crossEncoderReranker.rerank(transformation.normalizedQuery(), reranked, profile.fusionTopK());
        return reranked.stream().limit(profile.fusionTopK()).toList();
    }

    private static Map<String, Double> providerWeightsFor(RetrievalQueryProfile.Intent intent) {
        if (intent == RetrievalQueryProfile.Intent.MULTI_HOP
                || intent == RetrievalQueryProfile.Intent.COMPARE) {
            return Map.of("VECTOR", 1.4, "ELASTICSEARCH_BM25", 0.65,
                    "STRUCTURED", 0.2, "EXACT", 1.0);
        }
        return defaultProviderWeights();
    }

    private List<RetrievalCandidate> searchAcrossQueries(RetrievalContracts.RetrievalProvider provider,
                                                          RetrievalRequest request,
                                                          Map<String, Object> filters,
                                                          QueryTransformation transformation,
                                                          int limit) {
        Map<String, RetrievalCandidate> bestByChunk = new LinkedHashMap<>();
        for (int queryIndex = 0; queryIndex < transformation.retrievalQueries().size(); queryIndex++) {
            String retrievalQuery = transformation.retrievalQueries().get(queryIndex);
            double queryWeight = transformation.retrievalQueryWeights().get(queryIndex);
            List<RetrievalCandidate> hits;
            try {
                hits = provider.search(retrievalQuery, request.docId(), withLimit(filters, limit));
            } catch (RuntimeException failure) {
                if (queryIndex == 0) throw failure;
                // An optional rewrite failure must not discard successful original-query evidence.
                break;
            }
            for (RetrievalCandidate candidate : hits) {
                // The original query is the primary intent. Decomposed queries are
                // evidence-expansion paths and receive a deterministic rank penalty,
                // so a local sub-query hit cannot displace a stronger full-query hit.
                int effectiveRank = effectiveRank(candidate, queryIndex, queryWeight, limit);
                RetrievalCandidate weightedCandidate = withProviderRank(
                        candidate.withMatchedQueryIndex(queryIndex), effectiveRank);
                RetrievalCandidate previous = bestByChunk.get(weightedCandidate.chunkId());
                if (previous == null) {
                    bestByChunk.put(weightedCandidate.chunkId(), weightedCandidate);
                    continue;
                }
                ProviderScore oldScore = previous.providerScores().values().stream().findFirst().orElse(null);
                ProviderScore newScore = weightedCandidate.providerScores().values().stream().findFirst().orElse(null);
                if (oldScore == null || (newScore != null && newScore.rank() < oldScore.rank())) {
                    bestByChunk.put(weightedCandidate.chunkId(), weightedCandidate.withMatchedQueryIndices(
                            previous.matchedQueryIndices()));
                } else {
                    bestByChunk.put(weightedCandidate.chunkId(), previous.withMatchedQueryIndex(queryIndex));
                }
            }
        }
        return new ArrayList<>(bestByChunk.values());
    }

    private List<Integer> missingProviderQueries(List<List<RetrievalCandidate>> providerStages,
                                                 QueryTransformation transformation) {
        List<Integer> missing = new ArrayList<>();
        for (int queryIndex = 1; queryIndex < transformation.retrievalQueries().size(); queryIndex++) {
            int currentQueryIndex = queryIndex;
            boolean found = providerStages.stream().flatMap(List::stream)
                    .anyMatch(candidate -> candidate.matchedQueryIndices().contains(currentQueryIndex));
            if (!found) missing.add(queryIndex);
        }
        return missing;
    }

    private List<RetrievalCandidate> augmentMissingQueries(RetrievalContracts.RetrievalProvider provider,
                                                            List<RetrievalCandidate> existing,
                                                            RetrievalRequest request,
                                                            Map<String, Object> filters,
                                                            QueryTransformation transformation,
                                                            List<Integer> missingQueryIndices,
                                                            int limit) {
        Map<String, RetrievalCandidate> byChunk = new LinkedHashMap<>();
        existing.forEach(candidate -> byChunk.put(candidate.chunkId(), candidate));
        for (int queryIndex : missingQueryIndices) {
            String query = transformation.retrievalQueries().get(queryIndex);
            double weight = transformation.retrievalQueryWeights().get(queryIndex);
            List<RetrievalCandidate> hits;
            try {
                hits = provider.search(query, request.docId(), withLimit(filters, limit));
            } catch (RuntimeException ignored) {
                continue;
            }
            for (RetrievalCandidate hit : hits) {
                int rawRank = hit.providerScores().values().stream().findFirst()
                        .map(ProviderScore::rank).orElse(limit);
                // A targeted rescue is only used for a query aspect that has no
                // evidence in the current ranked set. Keep its local provider rank
                // visible so it can enter the normal fusion window without raising
                // the window for unrelated aspects.
                int rank = rawRank + queryIndex * 2;
                RetrievalCandidate candidate = withProviderRank(hit.withMatchedQueryIndex(queryIndex), rank);
                RetrievalCandidate previous = byChunk.get(candidate.chunkId());
                if (previous == null) {
                    byChunk.put(candidate.chunkId(), candidate);
                    continue;
                }
                ProviderScore oldScore = previous.providerScores().values().stream().findFirst().orElse(null);
                ProviderScore newScore = candidate.providerScores().values().stream().findFirst().orElse(null);
                if (oldScore == null || (newScore != null && newScore.rank() < oldScore.rank())) {
                    byChunk.put(candidate.chunkId(), candidate.withMatchedQueryIndices(previous.matchedQueryIndices()));
                } else {
                    byChunk.put(candidate.chunkId(), previous.withMatchedQueryIndex(queryIndex));
                }
            }
        }
        return new ArrayList<>(byChunk.values());
    }

    static int effectiveRank(RetrievalCandidate candidate, int queryIndex, double queryWeight, int limit) {
        ProviderScore score = candidate.providerScores().values().stream().findFirst().orElse(null);
        int rank = score == null ? limit : score.rank();
        if (queryIndex == 0) return rank;
        int penalty = Math.max(2, (int) Math.ceil(1.0 / Math.max(0.1, queryWeight)));
        return rank + limit / 2 + queryIndex * penalty;
    }

    private RetrievalCandidate withProviderRank(RetrievalCandidate candidate, int rank) {
        Map<String, ProviderScore> scores = new LinkedHashMap<>();
        candidate.providerScores().forEach((provider, score) -> scores.put(provider, new ProviderScore(rank, score.score())));
        return new RetrievalCandidate(candidate.chunkId(), candidate.parentChunkId(), candidate.documentId(),
                candidate.sourceFile(), candidate.content(), candidate.location(), scores,
                candidate.finalScore(), candidate.status(), candidate.matchedQueryIndices());
    }

    private Map<String, Object> withLimit(Map<String, Object> filters, int limit) {
        Map<String, Object> copy = new LinkedHashMap<>(filters == null ? Map.of() : filters);
        copy.put("_provider_top_k", limit);
        return copy;
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record RetrievalExecution(List<RetrievalResult> results, List<String> enabledProviders,
                                      Map<String, String> providerFailures, RetrievalQueryProfile profile,
                                      List<RetrievalResult> context, QueryTransformation transformation,
                                      List<RetrievalCandidate> vector, List<RetrievalCandidate> bm25,
                                      List<RetrievalCandidate> structured, List<RetrievalCandidate> fused,
                                      List<RetrievalCandidate> reranked, List<RetrievalCandidate> finalContext,
                                      List<Integer> targetedQueryIndices) {}

    private Map<String, List<RetrievalStageCandidate>> stageCandidates(RetrievalExecution execution) {
        Map<String, List<RetrievalStageCandidate>> stages = new LinkedHashMap<>();
        stages.put("vector", snapshotCandidates(execution.vector()));
        stages.put("bm25", snapshotCandidates(execution.bm25()));
        stages.put("structured", snapshotCandidates(execution.structured()));
        stages.put("fused", snapshotCandidates(execution.fused()));
        stages.put("reranked", snapshotCandidates(execution.reranked()));
        stages.put("final_context", snapshotCandidates(execution.finalContext()));
        return stages;
    }

    private Map<String, Object> queryCoverage(RetrievalExecution execution) {
        List<String> queries = execution.transformation().retrievalQueries();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("queries", queries);
        coverage.put("provider", queryCoverageFor(List.of(execution.vector(), execution.bm25(), execution.structured()), queries));
        coverage.put("fused", queryCoverageFor(List.of(execution.fused()), queries));
        coverage.put("reranked", queryCoverageFor(List.of(execution.reranked()), queries));
        coverage.put("final_context", queryCoverageFor(List.of(execution.finalContext()), queries));
        return coverage;
    }

    private List<Map<String, Object>> queryCoverageFor(List<List<RetrievalCandidate>> stages,
                                                        List<String> queries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int queryIndex = 1; queryIndex < queries.size(); queryIndex++) {
            int currentQueryIndex = queryIndex;
            List<RetrievalCandidate> matched = stages.stream().flatMap(List::stream)
                    .filter(candidate -> candidate.matchedQueryIndices().contains(currentQueryIndex))
                    .toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query_index", queryIndex);
            row.put("query", queries.get(queryIndex));
            row.put("matched", !matched.isEmpty());
            row.put("candidate_ids", matched.stream().map(RetrievalCandidate::chunkId).distinct().toList());
            row.put("parent_ids", matched.stream().map(this::parentKey).distinct().toList());
            result.add(row);
        }
        return result;
    }

    private String parentKey(RetrievalCandidate candidate) {
        return candidate.parentChunkId() == null || candidate.parentChunkId().isBlank()
                ? candidate.chunkId() : candidate.parentChunkId();
    }

    static List<RetrievalStageCandidate> snapshotCandidates(List<RetrievalCandidate> candidates) {
        return java.util.stream.IntStream.range(0, candidates.size()).mapToObj(index -> {
            RetrievalCandidate candidate = candidates.get(index);
            Map<String, Integer> ranks = new LinkedHashMap<>();
            Map<String, Double> scores = new LinkedHashMap<>();
            candidate.providerScores().forEach((provider, score) -> {
                ranks.put(provider, score.rank());
                scores.put(provider, score.score());
            });
            return new RetrievalStageCandidate(candidate.chunkId(), candidate.parentChunkId(), index + 1,
                    candidate.finalScore(), ranks, scores, candidate.matchedQueryIndices());
        }).toList();
    }

    private RetrievalResult toResult(RetrievalCandidate candidate) {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        candidate.providerScores().forEach((provider, score) -> breakdown.put(provider, score.score()));
        breakdown.put("rrf", candidate.finalScore());
        return new RetrievalResult(candidate.chunkId(), candidate.parentChunkId(), candidate.finalScore(),
                breakdown, candidate.content(), candidate.location(), candidate.sourceFile(),
                sourceType(candidate.sourceFile()), "text");
    }

    private String sourceType(String sourceFile) {
        if (sourceFile == null) return "unknown";
        String name = sourceFile.toLowerCase();
        if (name.endsWith(".pdf")) return "pdf";
        if (name.endsWith(".doc") || name.endsWith(".docx")) return "word";
        if (name.endsWith(".xls") || name.endsWith(".xlsx")) return "excel";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "html";
        return "unknown";
    }

    RetrievalCandidate toCandidate(RetrievalResult result, String provider) {
        return new RetrievalCandidate(
                result.chunk_id(),
                result.parent_chunk_id(),
                null,
                result.source_file(),
                result.content_preview(),
                result.location(),
                Map.of(provider, new ProviderScore(1, result.score())),
                result.score(),
                CandidateStatus.RETRIEVED);
    }

    /**
     * Expand each hit with its parent chunk. The parent id is the stable
     * document_chunk.chunk_id UUID returned by the pgvector query, so this
     * remains valid after a service restart and does not depend on in-memory
     * chunk state.
     */
    private Map<String, Map<String, Object>> loadParentContext(List<RetrievalResult> results) {
        Map<String, Map<String, Object>> parents = new LinkedHashMap<>();
        results.stream()
                .map(RetrievalResult::parent_chunk_id)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .forEach(parentId -> {
                    List<Map<String, Object>> rows = jdbc.queryForList(
                            "select c.chunk_id,d.document_id,d.source_file,d.source_type,c.content,c.location " +
                                    "from document_chunk c join document d on d.id=c.document_id " +
                                    "where c.chunk_id=?::uuid",
                            parentId);
                    if (!rows.isEmpty()) {
                        parents.put(parentId, rows.get(0));
                    }
                });
        return parents;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("E0005 检索日志序列化失败", exception);
        }
    }
}
