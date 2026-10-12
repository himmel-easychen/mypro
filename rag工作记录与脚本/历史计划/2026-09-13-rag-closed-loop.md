# RAG 完整闭环（P1 第一版基线）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按已确认的 P1 第一版基线，完成 Vector、Keyword、Structured 三路召回、RRF 融合、parent 扩展、50 条评估和验收门禁。

**Architecture:** 三路 Provider 各自最多返回 20 个候选，统一转换为 `RetrievalCandidate`，按 `chunkId` 去重后使用 `rrf_k=60` 融合；Structured 首版权重 0.5，Vector/Keyword 权重 1.0。融合后 API 最多返回 20 个候选，以支持 nDCG@10；生成上下文默认保留 5 个。评估固定输出 K=1、3、5、10。

**Tech Stack:** Spring Boot、PostgreSQL 16 + pgvector、Flyway、Python 3.11、qwen3.7-text-embedding、React/Vite。

## 已确认的第一版基线

```text
providerTopK = 20
rrf_k = 60
providerWeights = { VECTOR: 1.0, KEYWORD: 1.0, STRUCTURED: 0.5 }
fusionTopK = 20
contextTopK = 5
multiHopContextTopK = 8
evaluationK = [1, 3, 5, 10]
acceptanceK = 5
```

本轮主门禁：`Recall@5≥0.90`、`MRR@5≥0.80`、`nDCG@10≥0.80`。用户口中的 `MMR@5` 按项目既有指标解释为 MRR@5；Maximal Marginal Relevance 另行设计。

## Global Constraints

- 不破坏现有 `/api/rag/ingest`、`/api/rag/search`、异步入库和真实 Embedding 链路。
- 不把 PostgreSQL `contains` 结果称为 BM25；Keyword Provider 必须使用 PostgreSQL 全文检索或真实 Elasticsearch BM25。
- `unanswerable` 不计入普通 Recall/MRR，只计入拒答与干扰采纳指标。
- 每个任务先写测试，再实现，再运行对应验收命令。

---

### Task 1: 固定评估集和数据库真实性

**Files:**
- Create: `scripts/validate_rag_eval.py`
- Modify: `docs/p0-acceptance-report-2026-09-10.md`
- Test data: `wd/retrieval-eval-50-regenerated-2026-09-13.jsonl`

- [x] 校验 JSONL 恰好 50 行、ID 唯一、四类增强题各 10 条。
- [x] 校验 `relevant_chunk_ids` 存在于 `all-document-chunks.jsonl`。
- [x] 校验拒答题 expected.answer 严格为“未提及”。
- [x] 调用 embedding audit，确认无遗留 mock 向量和孤儿 child。
- [x] 运行 `python scripts/validate_rag_eval.py wd/retrieval-eval-50-regenerated-2026-09-13.jsonl`，输出 `VALID 50 records`。

### Task 2: 统一 RetrievalCandidate 契约

**Files:**
- Modify: `backend/src/main/java/com/workpro/rag/RetrievalContracts.java`
- Modify: `backend/src/main/java/com/workpro/rag/RetrievalServiceAdapter.java`
- Create: `backend/src/test/java/com/workpro/rag/RetrievalCandidateTest.java`

**Interface:** `RetrievalCandidate(chunkId, parentChunkId, documentId, sourceFile, content, location, providerScores, finalScore, status)`。

- [x] 增加 `RETRIEVED`、`FUSED`、`EXPANDED` 状态。
- [x] 统一保存每路 rank/score，不暴露 Embedding 向量。
- [x] 测试同一 chunk 多路出现时可合并且保留 providerScores。
- [x] 运行 `mvn -q -Dtest=RetrievalCandidateTest,RagSearchDelegationTest test`。

### Task 3: 实现 Vector、Keyword、Exact 三路 Provider

**Files:**
- Create: `backend/src/main/java/com/workpro/rag/VectorRetrievalProvider.java`
- Create: `backend/src/main/java/com/workpro/rag/KeywordRetrievalProvider.java`
- Create: `backend/src/main/java/com/workpro/rag/ExactRetrievalProvider.java`
- Modify: `backend/src/main/java/com/workpro/persistence/RagPersistence.java`
- Modify: `backend/src/main/java/com/workpro/rag/RagServices.java`
- Create: `backend/src/test/java/com/workpro/rag/RetrievalProviderTest.java`

- [x] Vector Provider 调用真实 pgvector，固定 `limit=20`。
- [x] Keyword Provider 使用 `to_tsvector/websearch_to_tsquery` 或已部署的 Elasticsearch BM25，固定 `limit=20`。
- [x] Exact Provider 支持金额、数量、日期、编号、条款号、表格字段和 document/page/table 过滤，固定 `limit=20`。
- [x] 三路都返回统一候选、rank、score、来源和位置。
- [x] 运行 `mvn -q -Dtest=RetrievalProviderTest,PgVectorRetrievalTest test`。

### Task 4: 实现等权 RRF 融合和 parent 扩展

**Files:**
- Create: `backend/src/main/java/com/workpro/rag/RetrievalFusionService.java`
- Modify: `backend/src/main/java/com/workpro/rag/RagServices.java`
- Create: `backend/src/test/java/com/workpro/rag/RetrievalFusionServiceTest.java`

**Interface:** `fuse(vectorHits, keywordHits, exactHits, providerWeights, rrfK=60, fusionTopK=8)`。

- [x] 采用 `Σ weight(provider)/(60 + rank_provider)`。
- [x] 第一版三路权重均为 `1.0`。
- [x] 以 `chunkId` 去重，融合后最多返回 8 个候选。
- [x] 融合结果通过现有 parent 查询扩展；普通 API 默认最多 8 个候选（上下文裁剪仍待 Task 5/6 接入）。
- [ ] 任一路失败保留其他路；三路均失败返回明确失败状态。
- [x] 运行 `mvn -q -Dtest=RetrievalFusionServiceTest,ResearchPackTest test`。

### Task 5: 接入 API、路由和追踪日志

**Files:**
- Modify: `backend/src/main/java/com/workpro/api/RagController.java`
- Modify: `backend/src/main/java/com/workpro/rag/RagServices.java`
- Create: `backend/src/main/java/com/workpro/rag/RetrievalTrace.java`
- Modify: `backend/src/test/java/com/workpro/rag/RagSearchDelegationTest.java`

- [x] 保持旧的 `GET /api/rag/search?query=...` 兼容。
- [x] 新增 `topK`、`documentId`、`mode=auto|vector|keyword|exact`。
- [x] 运行态验证 `GET /api/rag/search?query=采购编号&topK=8`，返回 RRF 分数及 provider breakdown。
- [ ] `auto` 按数字、日期、编号、表格词选择路由，但默认基线仍允许三路并行。
- [x] 检索 metadata/trace 记录 query_type、enabled_providers、provider_failure、candidate_count、rrf_k、final_chunk_ids、total_latency（provider_latency 后续补充）。
- [ ] 运行 `curl "http://localhost:8080/api/rag/search?query=采购编号&topK=8"`。

### Task 6: 生成评估报告和门禁

**Files:**
- Create: `scripts/run_rag_eval.py`
- Create: `scripts/tests/test_run_rag_eval.py`
- Create: `wd/rag-eval-report-2026-09-13.json`

- [x] 对可回答样本计算 Recall/Hit@1/@3/@5/@8 和 MRR@5。
- [x] 对 multi_hop 计算 `MultiHopComplete@5`。
- [ ] 对 compare/table 计算 `NumericAccuracy` 和 `SourceLocationAccuracy`。
- [ ] 对 unanswerable 计算 `RefusalAccuracy`，不计入 Recall/MRR。
- [ ] 对 context_confusion 计算目标证据保持率和干扰采纳率。
- [ ] 输出总体及 basic、multi_hop、compare_mutation、context_confusion、unanswerable 分项结果。
- [ ] 失败样本保存 query、期望/返回 Chunk、答案、trace ID、失败原因。
- [x] 达不到门禁时退出码为 2。
- [x] 运行评估核心单测，再运行真实评估命令生成报告。

### Task 7: 运行态验收与状态回写

**Files:**
- Modify: `docs/rag-评估指标和验收标准-2026-09-13.md`
- Modify: `docs/rag-status-and-todos-2026-09-13.md`
- Modify: `docs/p1-retrieval-design-2026-09-12.md`

- [ ] 运行 `mvn -q test`，确认全部 0 failures、0 errors。
- [ ] 运行评估集校验和真实评估 Runner。
- [ ] 验证服务重启后仍能召回同一 Chunk。
- [ ] 运行 `npm run build`。
- [ ] 只有“入库 → 三路召回 → RRF → parent → API → 评估门禁”全部通过，才把 RAG 标记为闭环完成。

## 执行顺序

严格执行 Task 1 → 2 → 3 → 4 → 5 → 6 → 7。Task 3 内部先 Vector，再 Keyword，再 Exact；Task 6 必须使用同一批 50 条数据比较每次改动前后的指标。
