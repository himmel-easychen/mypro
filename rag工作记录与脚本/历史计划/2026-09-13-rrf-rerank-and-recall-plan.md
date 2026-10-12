# RRF 与自适应 TopK、两层 Rerank 实施计划（第一版基线）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变已经确认的 RRF 权重的前提下，引入与评估集解耦的 Query Transformation、按题型自适应的候选窗口、选择性 Structured 参与、第一层 Query-Chunk rerank 和第二层 evidence/context rerank，改善多跳题的候选覆盖与最终上下文质量，并保留可分层评测的边界。

**Architecture:** 先对用户 Query 做与语料和评估集无关的格式归一化；仅对自然多问题按规则拆成最多 3 条证据 Query，并保留原始 Query。三路召回仍为 pgvector、Elasticsearch BM25 和 Structured SQL。RRF 权重保持当前基线 `VECTOR=1.0`、`ELASTICSEARCH_BM25=1.0`、`STRUCTURED=0.5`、`rrf_k=60`；仅按题型选择是否启用 Structured，不动态改权重。多跳题各路粗召回 Top20，普通事实题 Top12，比较/数值题 Vector/BM25 Top15 且 Structured 选择性 Top10，混淆题 Top15。各子 Query 结果先按 provider 取最佳 rank，再统一 RRF；RRF 后先做确定性 Query-Chunk rerank，再在生成前做 evidence/context rerank；第二层不使用已经生成的答案作为输入。

**Tech Stack:** Spring Boot/Java、PostgreSQL pgvector、Elasticsearch 8.17 BM25、JUnit 5、Python 评测脚本。

## Global Constraints

- 评测硬门禁固定为 `Recall@5 >= 0.90`、`MRR@5 >= 0.80`、`nDCG@10 >= 0.80`。
- `MMR@5` 在项目中继续作为 `MRR@5` 的兼容别名，不引入 MMR 多样性指标替代 MRR。
- Provider 每路最多返回 20 个候选；RRF 使用 `rrf_k=60`；最终检索 API 至少保留 Top10 以支持 nDCG@10。
- Rerank 不能替代候选召回；Recall 必须同时测量召回层和 rerank 层。
- 不在本阶段引入 Neo4j；先验证动态门控、父 Chunk 扩展和查询拆解对 Recall 的贡献。
- 所有行为变更先写失败测试，再写最小实现。

## 当前基线与根因

- 当前实际权重：`VECTOR=1.0`、`ELASTICSEARCH_BM25=1.0`、`STRUCTURED=0.5`、`EXACT=1.0`，`rrf_k=60`；本轮不修改权重。
- 当前分层结果：Vector `Recall@5=0.6250`，BM25 `0.5625`，Structured `0.2500`，Auto/RRF `0.5500`。
- Structured 在 `table_numeric` 上有效，但当前实现按 page 级关联子块，容易把同页无关行带入候选；因此采用“选择性参与”，而不是修改已确认的 `0.5` 权重。
- 第一版门控基线：普通事实、编号和拒答题不调用 Structured；比较、数值和混淆题选择性调用；多跳题各路扩大粗召回窗口后再统一 rerank。

### Task 1: 固化 RRF 基线与自适应候选窗口

**Files:**
- Modify: `backend/src/main/java/com/agicore/rag/RetrievalServiceAdapter.java`
- Modify: `backend/src/main/java/com/agicore/rag/RetrievalTrace.java`
- Test: `backend/src/test/java/com/agicore/rag/RetrievalFusionServiceTest.java`

**Interfaces:**
- Produce `static Map<String, Double> defaultProviderWeights()`，返回当前确认的 `VECTOR=1.0`、`ELASTICSEARCH_BM25=1.0`、`STRUCTURED=0.5`、`EXACT=1.0`。
- Produce a query profile exposing `intent`, per-provider coarse limits, fusion limit, and final context limit.
- Trace 必须记录最终使用的 `provider_weights`，不能只记录 provider 名称和 `rrf_k`。

- [x] **Step 1: Write the failing test**

```java
@Test
void usesFirstVersionProviderWeightsThatLimitStructuredNoise() {
    assertEquals(Map.of(
            "VECTOR", 1.0,
            "ELASTICSEARCH_BM25", 1.0,
            "STRUCTURED", 0.5,
            "EXACT", 1.0), RetrievalServiceAdapter.defaultProviderWeights());
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RetrievalFusionServiceTest test`

Expected: FAIL because `defaultProviderWeights()` does not yet exist.

- [x] **Step 3: Write minimal implementation**

在 `RetrievalServiceAdapter` 中增加 `defaultProviderWeights()`，并按 query profile 选择 provider limit；将实际窗口、权重和 intent 写入 retrieval metadata 和 trace。

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RetrievalFusionServiceTest test`

Expected: PASS。

### Task 2: 增加查询意图门控，避免普通查询调用 Structured

**Files:**
- Create: `backend/src/main/java/com/agicore/rag/RetrievalQueryIntent.java`
- Modify: `backend/src/main/java/com/agicore/rag/RetrievalServiceAdapter.java`
- Test: `backend/src/test/java/com/agicore/rag/RetrievalQueryIntentTest.java`

**Interfaces:**
- `RetrievalQueryIntent.classify(String query, Map<String,Object> filters)` 返回 `SEMANTIC`、`IDENTIFIER`、`TABLE_NUMERIC`、`TABLE_FIELD`、`COMPARE` 之一。
- `providerWeights(intent)` 返回：
  - `SEMANTIC`: `1.0/1.0/0.1`
  - `IDENTIFIER`: `0.5/1.0/0.25`
  - `TABLE_NUMERIC`: `0.75/0.75/1.0`
  - `TABLE_FIELD`: `0.75/0.75/1.0`
  - `COMPARE`: `0.5/0.75/1.0`

- [x] **Step 1: Write the failing tests**

覆盖“金额/数量/高于/低于/比较/表格/编号/采购编号”等关键词，并断言分类及权重。

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -q -Dtest=RetrievalQueryIntentTest test`

Expected: FAIL because the classifier and weight policy do not exist。

- [x] **Step 3: Write minimal implementation**

先使用可解释的规则分类，不引入 LLM；filters 中显式的 `query_type` 优先于规则推断。将选定 intent 和权重写入 trace。

- [x] **Step 4: Run tests and regression tests**

Run: `mvn -q -Dtest=RetrievalQueryIntentTest,RetrievalFusionServiceTest test`

Expected: PASS。

### Task 3: 第一层 Query-Chunk rerank

**Files:**
- Create: `backend/src/main/java/com/agicore/rag/QueryChunkReranker.java`
- Modify: `backend/src/main/java/com/agicore/rag/RetrievalServiceAdapter.java`
- Test: `backend/src/test/java/com/agicore/rag/QueryChunkRerankerTest.java`

**Interfaces:**
- `List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates, RetrievalQueryIntent intent, int topK)`。
- 输入为 RRF 合并后的 Top30~50，输出排序后的 Top10~20。

- [x] **Step 1: Write the failing test**

测试同一候选集内：包含完整采购编号的 Chunk 必须高于只包含“采购编号”字样的模板 Chunk；金额比较题中同时包含字段名和数值的 Chunk 必须优先。

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=QueryChunkRerankerTest test`

Expected: FAIL because reranker does not exist。

- [x] **Step 3: Write minimal implementation**

第一版使用可解释分数：精确 token/短语命中、数字/日期命中、query term 覆盖率、provider agreement、Chunk 置信度；不调用 LLM，不改变候选集合。

- [x] **Step 4: Run tests and compare metrics**

Run: `mvn -q -Dtest=QueryChunkRerankerTest test`；随后运行 50 条评测。当前保守 rerank 已验证不会继续打乱 RRF，但尚未带来正向指标增益。

### Task 4: 第二层 evidence/context rerank

**Files:**
- Create: `backend/src/main/java/com/agicore/rag/EvidenceContextReranker.java`
- Modify: `backend/src/main/java/com/agicore/rag/RetrievalServiceAdapter.java`
- Test: `backend/src/test/java/com/agicore/rag/EvidenceContextRerankerTest.java`

**Interfaces:**
- `List<RetrievalCandidate> selectContext(String query, List<RetrievalCandidate> reranked, Map<String,Object> parentContext, int contextTopK)`。
- 输入 Top10~20，输出默认 Top5；保证多跳题的必要 Chunk 不因重复或父 Chunk 扩展被全部裁掉。

- [x] **Step 1: Write the failing test**

测试父子 Chunk 去重、跨 Chunk 覆盖、同页重复抑制和混淆 Chunk 排除。

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=EvidenceContextRerankerTest test`

Expected: FAIL because context selector does not exist。

- [x] **Step 3: Write minimal implementation**

按答案要素覆盖、query term 覆盖、来源多样性和 parent/child 关系选择上下文；禁止读取已生成答案。

- [x] **Step 4: Run tests**

Run: `mvn -q -Dtest=EvidenceContextRerankerTest test`

Expected: PASS。

### Task 5: 分层评测与负增益门禁

**Files:**
- Modify: `scripts/run_rag_eval.py`
- Create: `scripts/tests/test_rrf_gain.py`
- Modify: `wd/rag-eval-layered-diagnosis-2026-09-13.md`

**Interfaces:**
- 评测报告必须同时包含 `vector`、`bm25`、`structured`、`rrf`、`query_chunk_rerank`、`context_rerank` 六层结果。
- 增加 `gain_vs_best_single`，并在 `Recall@5`、`MRR@5`、`nDCG@10` 任一指标低于最佳单路时标记 `negative_gain=true`。

- [ ] **Step 1: Write the failing test**

给定固定 provider 排名，断言 Structured 噪声导致旧权重负增益、默认权重或门控权重降低负增益；断言报告生成 `negative_gain` 字段。

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest scripts/tests/test_rrf_gain.py -q`

Expected: FAIL because the report has no gain comparison。

- [ ] **Step 3: Write minimal implementation**

将每条样本的各层排名保存后计算增益；保留失败样本、provider failure、intent、weights 和最终 chunk IDs。

- [ ] **Step 4: Run the 50-case evaluation**

Run: `python scripts/run_rag_eval.py <eval-jsonl> --diagnostics --output wd/rag-eval-rrf-rerank-2026-09-13.json`

Expected: RRF 不得低于最佳单路；若仍负增益，停止继续加 reranker，回到候选召回和意图门控诊断。

## Recall 提升讨论入口（本计划之后）

本计划只处理“排序负增益”，不宣称解决 Recall 门禁。下一轮应按以下顺序讨论并单独评测：

1. **评估集与 Chunk 真值**：检查每条多跳题是否覆盖全部依赖 Chunk，清理错误或过窄的 relevant IDs。
2. **Vector Recall**：查询改写、中文实体/编号标准化、父文档扩展、相邻 Chunk 扩展。
3. **BM25 Recall**：中文分词/字段 mapping、短语和 keyword 子字段、编号与日期规范化。
4. **Structured Recall**：字段级索引、表格行/列语义、条件解析和 document/page 过滤。
5. **多跳召回**：问题拆解为子查询，分别召回后做 union，再进行跨 Chunk 聚合。
6. **抗噪与拒答**：混淆 Chunk 注入、拒答阈值和引用完整性单独作为 answer-chain 评估，不混入纯检索分数。

## Self-review

- 方案未关闭 Structured，而是区分默认查询和表格/数值意图。
- 第一层 rerank 不会制造新候选，因此不会虚假提升 Recall；Recall 与排序指标分开记录。
- 第二层 rerank 不读取生成答案，避免答案泄漏。
- 当前计划没有把 Neo4j 作为前置依赖，符合现阶段证据和 YAGNI 原则。
