# Multi-hop Failure Stratification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Identify whether each missing multi-hop gold chunk was absent from provider top-20 or removed by fusion, query-chunk rerank, or final context selection.

**Architecture:** Add an opt-in diagnostic response to the existing search endpoint so one execution exposes the exact candidate order at every retrieval stage. Extend the Python evaluator to consume that response and produce mutually exclusive per-case classifications, per-gold ranks, and a layer funnel without changing normal search responses.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Python 3, unittest

## Global Constraints

- Preserve the existing `/api/rag/search` list response unless `diagnostics=true`.
- Use the real online candidate lists; do not reconstruct RRF or rerank offline.
- Classify only `multi_hop` rows and retain every gold chunk in case details.
- Treat provider membership against each provider's configured top-20 window.

---

### Task 1: Backend staged retrieval trace

**Files:**
- Modify: `backend/src/main/java/com/agicore/rag/RetrievalServiceAdapter.java`
- Modify: `backend/src/main/java/com/agicore/api/RagController.java`
- Test: `backend/src/test/java/com/agicore/rag/RetrievalServiceAdapterDiagnosticsTest.java`

**Interfaces:**
- Produces: `metadata.stage_candidates`, mapping `vector`, `bm25`, `structured`, `fused`, `reranked`, and `final_context` to ordered candidate snapshots.
- Produces: `GET /api/rag/search?...&diagnostics=true` response containing `results` and `diagnostics`.

- [ ] Write a failing adapter test asserting exact chunk order and rank at all six stages.
- [ ] Run `mvn -q -Dtest=RetrievalServiceAdapterDiagnosticsTest test` and confirm the missing diagnostic metadata failure.
- [ ] Retain provider and intermediate lists in `RetrievalExecution`, serialize compact candidate snapshots, and expose them only from the diagnostic controller path.
- [ ] Run the focused backend test and existing retrieval tests.

### Task 2: Multi-hop failure classifier

**Files:**
- Modify: `scripts/run_rag_eval.py`
- Modify: `scripts/tests/test_run_rag_eval.py`

**Interfaces:**
- Produces: `classify_multihop_failures(records, layered)` with categories `provider_miss`, `partial_provider_recall`, `fusion_drop`, `rerank_drop`, `context_drop`, and `complete`.
- Produces: report key `multi_hop_failure_analysis` containing category counts/rates, stage funnel, and per-case/per-gold ranks.

- [ ] Write failing unit tests covering all six categories and rank details.
- [ ] Run `python -m unittest scripts.tests.test_run_rag_eval -v` and confirm the missing classifier failure.
- [ ] Implement diagnostic HTTP parsing, deterministic classification precedence, counts/rates, funnel, and detailed case output.
- [ ] Run the focused Python tests.

### Task 3: End-to-end verification

**Files:**
- Modify: `docs/rag-status-and-todos-2026-09-13.md`

**Interfaces:**
- Consumes: backend diagnostic endpoint and evaluator classifier.
- Produces: documented command for generating a layered multi-hop failure report.

- [ ] Document `python scripts/run_rag_eval.py <eval.jsonl> --failure-analysis --output <report.json>`.
- [ ] Run the backend retrieval test suite and Python script tests.
- [ ] Run a live diagnostic evaluation when the local backend is reachable; otherwise report the exact external prerequisite.
- [ ] Inspect the generated JSON structure and confirm categories are mutually exclusive and sum to all multi-hop cases.
