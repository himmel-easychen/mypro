# Hierarchical Chunking Implementation Plan

> **For agentic workers:** This plan is executed inline in the current session.

**Goal:** Implement strict 1,000-character parent windows and 250-character child chunks with durable parent relationships.

**Architecture:** Ingest creates multiple parent windows per page/block, then children under each parent. Persistence stores explicit parent UUIDs; restore and structured retrieval preserve the hierarchy.

**Tech Stack:** Java 24, Spring Boot, PostgreSQL, Maven, JUnit 5.

## Tasks

- [x] Add a regression test for explicit parent IDs.
- [x] Extend `RagPersistence.Chunk` and persistence writes to carry parent IDs.
- [x] Generate parent windows (1000) and child chunks (250) in `RagServices`.
- [x] Restore persisted parent IDs directly instead of deriving one parent per document.
- [x] Match split page locations in structured retrieval.
- [x] Run focused tests and package verification.

## Verification

`mvn -q '-Dtest=RecursiveChunkerTest,RagSearchDelegationTest,PgVectorRetrievalTest,RetrievalProviderTest,ResearchPackTest' test`

`mvn -q -DskipTests package`
