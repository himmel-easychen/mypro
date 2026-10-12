# RAG 完整闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在今天把现有资料入库、混合召回、证据返回、拒答判断和自动评估串成一条可重复执行的 RAG 闭环。

**Architecture:** 保留现有 PostgreSQL/pgvector 作为向量底座，新增统一 `RetrievalCandidate` 作为三路检索的边界对象；先实现可用的结构化精确检索和 PostgreSQL 全文关键词召回，再用 RRF 融合，最后统一做 parent 扩展和评估。评估 Runner 通过现有 `/api/rag/search` 接口执行 50 条黄金样本，输出指标、失败样本和 JSON 报告。

**Tech Stack:** Java 21+/Spring Boot、PostgreSQL 16 + pgvector、Flyway、Python 3.11、现有 qwen3.7-text-embedding、React/Vite。

## Global Constraints

- 不破坏现有 `/api/rag/ingest`、`/api/rag/search`、异步入库和真实 Embedding 链路。
- 所有检索结果必须带 `chunkId`、`parentChunkId`、`documentId`、来源文件、位置和 provider 分数。
- `unanswerable` 样本只能输出“未提及”，不得用相似主题内容强行回答。
- 不在本计划中实现 Tool Registry、投标协作 Workflow 或模板化文档生成。
- 每个任务完成后必须运行对应测试；最终必须运行后端测试、评估 Runner 和前端构建。

---

### Task 1: 固定 P0 数据和评估输入

**Files:**
- Modify: `docs/p0-acceptance-report-2026-09-10.md`
- Create: `scripts/validate_rag_eval.py`
- Test: `wd/retrieval-eval-50-regenerated-2026-09-13.jsonl`

**Interfaces:**
- `validate_rag_eval.py <eval.jsonl>` 返回非零退出码表示 schema、ID、答案或四类样本不合格。
- 评估记录必须包含 `id`、`type`、`query`、`expected.answer`、`relevant_chunk_ids`。

- [ ] 校验 50 行 JSONL、ID 唯一、四类增强样本各 10 条。
- [ ] 校验每条 `relevant_chunk_ids` 存在于 `all-document-chunks.jsonl`。
- [ ] 校验 `unanswerable.expected.answer == "未提及"`。
- [ ] 对 PDF 表格尾项记录当前状态，不阻塞已有文本和向量链路。
- [ ] 运行：`python scripts/validate_rag_eval.py wd/retrieval-eval-50-regenerated-2026-09-13.jsonl`；期望输出 `VALID 50 records`。

### Task 2: 建立统一 RetrievalCandidate 契约

**Files:**
- Modify: `backend/src/main/java/com/workpro/rag/RetrievalContracts.java`
- Modify: `backend/src/main/java/com/workpro/rag/RetrievalServiceAdapter.java`
- Test: `backend/src/test/java/com/workpro/rag/RetrievalCandidateTest.java`

**Interfaces:**
- `RetrievalCandidate(String chunkId, String parentChunkId, String documentId, String sourceFile, String content, String location, Map<String,Double> providerScores, double finalScore, CandidateStatus status)`。
- `CandidateStatus = RETRIEVED | FUSED | EXPANDED`。

- [ ] 先写构造、provider 分数合并、同 chunk 去重测试。
- [ ] 将现有 `RetrievalResult` 映射到 `RetrievalCandidate`，保留旧 API 输出兼容性。
- [ ] 测试通过后再让 ResearchPack 使用统一候选对象。
- [ ] 运行：`mvn -q -Dtest=RetrievalCandidateTest,RagSearchDelegationTest test`。

### Task 3: 实现关键词和结构化精确召回

**Files:**
- Modify: `backend/src/main/java/com/workpro/persistence/RagPersistence.java`
- Modify: `backend/src/main/java/com/workpro/rag/RagServices.java`
- Create: `backend/src/main/java/com/workpro/rag/ExactRetrievalProvider.java`
- Create: `backend/src/main/java/com/workpro/rag/KeywordRetrievalProvider.java`
- Test: `backend/src/test/java/com/workpro/rag/ExactRetrievalProviderTest.java`

**Interfaces:**
- `List<RetrievalCandidate> searchExact(String query, RetrievalFilter filter, int limit)`。
- `List<RetrievalCandidate> searchKeyword(String query, RetrievalFilter filter, int limit)`。
- `RetrievalFilter(documentId, sourceFile, page, blockType, tableId)`。

- [ ] 为金额、数量、日期、条款号、项目编号做规范化提取。
- [ ] 用 PostgreSQL `to_tsvector`/`websearch_to_tsquery` 实现关键词召回；没有 ES 时不得伪称 BM25。
- [ ] Exact 命中优先匹配数字、单位和条款号，返回明确 provider 分数。
- [ ] 为金额“204.516 万元”、项目编号、日期和表格字段各写一个回归测试。
- [ ] 运行：`mvn -q -Dtest=ExactRetrievalProviderTest,PgVectorRetrievalTest test`。

### Task 4: 三路融合、去重和 parent 扩展

**Files:**
- Create: `backend/src/main/java/com/workpro/rag/RetrievalFusionService.java`
- Modify: `backend/src/main/java/com/workpro/rag/RagServices.java`
- Modify: `backend/src/main/java/com/workpro/api/RagController.java`
- Test: `backend/src/test/java/com/workpro/rag/RetrievalFusionServiceTest.java`

**Interfaces:**
- `RetrievalResponse search(RetrievalRequest request)`。
- `RetrievalFusionService.fuse(vectorHits, keywordHits, exactHits, topK)` 使用 RRF `1/(k+rank)`，默认 `k=60`。

- [ ] 先写同一 chunk 出现在多路结果时只保留一个候选的测试。
- [ ] 实现 RRF、providerScores、finalScore 和状态迁移 `RETRIEVED → FUSED → EXPANDED`。
- [ ] 融合后再做 parent 扩展，禁止每路召回各自扩展造成重复。
- [ ] 任一路失败时保留其他路；三路均失败时返回明确错误，不返回伪造候选。
- [ ] 运行：`mvn -q -Dtest=RetrievalFusionServiceTest,ResearchPackTest test`。

### Task 5: 接入统一检索 API 和可观测日志

**Files:**
- Modify: `backend/src/main/java/com/workpro/api/RagController.java`
- Modify: `backend/src/main/java/com/workpro/rag/RagServices.java`
- Create: `backend/src/main/java/com/workpro/rag/RetrievalTrace.java`
- Test: `backend/src/test/java/com/workpro/rag/RagSearchDelegationTest.java`

**Interfaces:**
- `GET /api/rag/search?query=...&topK=8&documentId=...&mode=auto|vector|keyword|exact`。
- 日志字段：`trace_id`、`query`、`providers`、`candidate_count`、`selected_chunk_ids`、`latency_ms`、`fallback`。

- [ ] 保持不带新参数的旧调用仍返回原结构。
- [ ] 增加 `mode=auto`，按 query 是否含数字、日期、编号和表格词选择 exact/keyword/vector。
- [ ] 将每次召回写成 JSON 日志，便于评估失败复现。
- [ ] 启动后用 curl 验证：`curl "http://localhost:8080/api/rag/search?query=采购编号&topK=5"`。

### Task 6: 实现评估 Runner 和质量门禁

**Files:**
- Create: `scripts/run_rag_eval.py`
- Create: `scripts/tests/test_run_rag_eval.py`
- Create: `wd/rag-eval-report-2026-09-13.json`

**Interfaces:**
- `python scripts/run_rag_eval.py --eval wd/retrieval-eval-50-regenerated-2026-09-13.jsonl --base-url http://localhost:8080 --output wd/rag-eval-report-2026-09-13.json`。
- 输出 `total`、`by_type`、`hit_at_1`、`hit_at_3`、`hit_at_5`、`hit_at_8`、`recall_at_1`、`recall_at_3`、`recall_at_5`、`recall_at_8`、`mrr_at_5`、`multi_hop_complete_at_5`、`numeric_accuracy`、`source_location_accuracy`、`refusal_accuracy`、`context_target_preservation`、`distractor_adoption_rate`、`failures`。

- [ ] 对 answerable 样本调用 `/api/rag/search`，用 `relevant_chunk_ids` 计算 Recall@1/@3/@5/@8、Hit@K 和 MRR@5。
- [ ] 对 multi_hop 样本计算全部相关 Chunk 是否进入 Top5 的 `multi_hop_complete_at_5`。
- [ ] `unanswerable` 单独计算拒答准确率，不把“相似内容命中”算正确。
- [ ] 对 compare/mutation 检查 expected.answer 中的数字、单位和比较方向；对 table/field 样本检查来源位置。
- [ ] 对 context_confusion 注入 distractor context，计算目标证据保持率和干扰采纳率。
- [ ] 失败记录完整保存 query、返回 chunk IDs、期望 IDs 和 trace ID。
- [ ] 设置初始门禁：`recall_at_5 >= 0.80`、`hit_at_5 >= 0.90`、`mrr_at_5 >= 0.70`、`multi_hop_complete_at_5 >= 0.70`、`refusal_accuracy >= 0.90`；未达到则退出码 2。
- [ ] 运行 Python 单测，再运行真实 Runner 生成 JSON 报告。

### Task 7: 前端检索实验室最小可用版

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- 页面输入 query、topK、mode，展示 provider 分数、最终分数、来源文件、页码、parent context 和失败原因。

- [ ] 将“即将上线”的检索实验室改为可用入口。
- [ ] 增加单 query 调试和评估报告路径链接，不在前端实现指标计算。
- [ ] 运行：`npm run build`；期望 Vite build 成功。

### Task 8: 全链路验收和文档回写

**Files:**
- Modify: `docs/rag-status-and-todos-2026-09-13.md`
- Modify: `docs/p1-retrieval-design-2026-09-12.md`
- Modify: `docs/p0-acceptance-report-2026-09-10.md`

- [ ] 运行 `mvn -q test`，确认全部测试 0 failures、0 errors。
- [ ] 运行 `python scripts/validate_rag_eval.py ...`。
- [ ] 启动后端并运行真实 `run_rag_eval.py`。
- [ ] 运行 `npm run build`。
- [ ] 记录 Recall/MRR/拒答/数字/位置指标及未通过样本。
- [ ] 只有“入库 → 召回 → 融合 → parent → API → 评估报告”可重复通过，才把 RAG 标记为闭环完成。

## 执行顺序

严格按 Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8；Task 1–6 是后端和评估闭环，Task 7 可并行但不能替代 Task 6，Task 8 是最终验收门。

## 完成后的边界

本计划完成后，RAG 可以被评估、追踪和回归；但投标协作 Workflow、Tool Registry、人工确认、模板化文档生成和生产多 Worker 仍属于下一阶段，不应在本次 RAG 验收中混入。
