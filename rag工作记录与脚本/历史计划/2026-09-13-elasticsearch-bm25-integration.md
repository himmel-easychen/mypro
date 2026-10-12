# Elasticsearch BM25 Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the ineffective PostgreSQL Chinese keyword route with a real Elasticsearch 8.17 BM25 provider and include it in the RAG retrieval evaluation loop.

**Architecture:** Start the existing single-node Elasticsearch container, create a `rag_chunks` index with BM25 scoring and a Chinese-compatible analyzer, and bulk-index the current PostgreSQL child chunks. The Java provider queries Elasticsearch with `multi_match` plus mandatory document filtering, converts hits to `RetrievalCandidate`, and preserves Vector + Structured fallback when Elasticsearch is unavailable. Auto mode fuses Vector, Elasticsearch BM25, and Structured SQL with the existing RRF contract.

**Tech Stack:** Spring Boot 3.3, Java Elasticsearch REST client, Elasticsearch 8.17.0, PostgreSQL 16 + pgvector, Python 3.11 evaluation runner.

## Global Constraints

- The public endpoint remains `GET /api/rag/search` and continues returning `List<RetrievalResult>`.
- Elasticsearch is configured at `http://localhost:9200` with security disabled in the existing Docker container.
- BM25 must be implemented by Elasticsearch, not PostgreSQL `simple` FTS or an `ILIKE` approximation.
- Every hit must preserve `chunk_id`, `parent_chunk_id`, `document_id`, `source_file`, `location`, content, rank, and score.
- `source_document_id` filtering is mandatory whenever supplied by an evaluation row or caller.
- Elasticsearch failure must be observable in provider metadata and must not fabricate candidates.
- Tests are written before production changes and must first fail for the new behavior.
- Acceptance gates remain Recall@5 >= 0.90, MRR@5 >= 0.80, and nDCG@10 >= 0.80.

---

### Task 1: Elasticsearch runtime and client configuration

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml` (or the active application configuration file)
- Create: `backend/src/main/java/com/agicore/config/ElasticsearchProperties.java`
- Create: `backend/src/main/java/com/agicore/rag/ElasticsearchClientConfig.java`
- Test: `backend/src/test/java/com/agicore/rag/ElasticsearchClientConfigTest.java`

- [ ] Write a failing test asserting the client resolves `http://localhost:9200` and the index name `rag_chunks` from defaults.
- [ ] Run `mvn -q -Dtest=ElasticsearchClientConfigTest test`; expect failure because the properties and client bean do not exist.
- [x] Use the JDK HTTP client plus Jackson instead of adding a heavyweight ES client dependency.
- [x] Add properties `agicore.elasticsearch.url`, `agicore.elasticsearch.username`, `agicore.elasticsearch.password`, `agicore.elasticsearch.index`, defaulting to `http://localhost:9200`, blank credentials, and `rag_chunks`.
- [x] Create an injectable REST wrapper using the existing Jackson mapper.
- [x] Run the complete Maven test suite; existing tests pass.

### Task 2: Index mapping and bulk synchronization

**Files:**
- Create: `backend/src/main/java/com/agicore/rag/ElasticsearchIndexService.java`
- Create: `backend/src/main/java/com/agicore/api/ElasticsearchAdminController.java`
- Create: `backend/src/test/java/com/agicore/rag/ElasticsearchIndexServiceTest.java`
- Create: `scripts/sync_rag_chunks_to_elasticsearch.py` only if Java bulk sync cannot reuse existing persistence safely

- [ ] Write a failing test asserting the index mapping contains `chunk_id`, `document_id`, `source_file`, `location`, `content`, and `normalized_content`, with BM25 text fields.
- [ ] Run the focused test and verify it fails before implementation.
- [x] Implement idempotent index creation with stable mapping, built-in `cjk_bigram` analyzer, and BM25 similarity.
- [x] Implement bulk synchronization from PostgreSQL child chunks with deterministic `_id=chunk_id` and parent/document metadata.
- [x] Expose `POST /api/rag/elasticsearch/reindex` plus `GET /api/rag/elasticsearch/health`.
- [x] Start the existing Docker container, verify `GET /` health, and bulk-sync 2049 chunks.
- [x] Verify the runtime reindex result: indexed=2049, failed=0.

### Task 3: Elasticsearch BM25 retrieval provider

**Files:**
- Create: `backend/src/main/java/com/agicore/rag/ElasticsearchBm25RetrievalProvider.java`
- Modify: `backend/src/main/java/com/agicore/rag/RetrievalServiceAdapter.java`
- Remove or retire: `backend/src/main/java/com/agicore/rag/KeywordRetrievalProvider.java`
- Create: `backend/src/test/java/com/agicore/rag/ElasticsearchBm25RetrievalProviderTest.java`

- [ ] Write a failing test asserting a Chinese query produces an Elasticsearch `multi_match` request and that `documentId` becomes a filter clause.
- [ ] Run the focused test and verify it fails because the provider does not exist.
- [x] Implement the provider with fixed name `ELASTICSEARCH_BM25`, provider topK 20, ES BM25 score, and compact source metadata.
- [x] Query `content^4`, `normalized_content^3`, `source_file`, `location`, and block text fields using `multi_match`; add `term document_id` filter when present.
- [x] Convert each hit to `RetrievalCandidate` with rank starting at 1 and status `RETRIEVED`.
- [x] On connection or response failure, throw a descriptive runtime exception for adapter-level degradation handling.
- [x] Replace Auto and explicit keyword routing with `ELASTICSEARCH_BM25`; retain `mode=keyword` only as a compatibility alias.
- [x] Run the complete Maven test suite successfully.

### Task 4: RRF metadata, failure handling, and API compatibility

**Files:**
- Modify: `backend/src/main/java/com/agicore/rag/RetrievalServiceAdapter.java`
- Modify: `backend/src/main/java/com/agicore/api/RagController.java` only if a reindex endpoint is needed
- Modify: `backend/src/test/java/com/agicore/rag/RagSearchDelegationTest.java`
- Modify: `backend/src/test/java/com/agicore/rag/RetrievalFusionServiceTest.java`

- [ ] Write a failing test asserting Auto enables `VECTOR`, `ELASTICSEARCH_BM25`, and `STRUCTURED`, and records an Elasticsearch failure without returning fake hits.
- [ ] Run the test and verify the old provider names/behavior fail the assertion.
- [x] Update adapter provider lists, RRF weight keys, metadata, and trace fields to use `ELASTICSEARCH_BM25`.
- [ ] Keep the existing RRF `rrf_k=60`, Vector weight 1.0, Elasticsearch weight 1.0, Structured weight 0.5, and fusion output cap 20.
- [ ] Run focused Java tests and then the complete Maven test suite.

### Task 5: Runtime indexing and layered evaluation

**Files:**
- Modify: `scripts/run_rag_eval.py` only if provider mode names need updating
- Create: `wd/rag-eval-elasticsearch-bm25-2026-09-13.json`
- Modify: `wd/rag-eval-layered-diagnosis-2026-09-13.md`

- [x] Verify Elasticsearch health and index document count.
- [x] Run the evaluation with `--k 1 3 5 10 --diagnostics`, using each row's `source_document_id` filter.
- [x] Record Vector, Elasticsearch BM25, Structured, and Auto/RRF Recall@5, MRR@5, and nDCG@10.
- [x] Compare Auto against the best single route and identify the remaining fusion/query bottleneck.
- [x] Update the diagnosis report with measured values and the next bottleneck.
- [x] Keep the service running and preserve the JSON report as the acceptance artifact.
