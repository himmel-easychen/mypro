# RAG 生成闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not restart broad retrieval tuning unless a task below produces a concrete regression or failure sample.

**Goal:** 在已达标的 H 检索基线上，完成“用户问题 -> 查询处理 -> 检索证据 -> Prompt -> LLM 回答/引用/拒答 -> 评测报告”的第一版 RAG 功能闭环。

**Architecture:** RAG 模块负责 query transformation、召回、融合、重排、证据选择和证据包格式化；Dialogue/Answer 服务负责问答 prompt、拒答规则、ModelGateway 调用、回答和引用返回；Writer 只负责文档/章节生成，不承担普通 RAG 问答的业务归属。当前 `dialogue.WriterAgent` 可短期复用模型调用方式，但需要把普通问答职责独立出来，避免和文档写作混在一起。

**Tech Stack:** Spring Boot、PostgreSQL + pgvector、Elasticsearch BM25、Python eval runner、OpenAI-compatible `ModelGateway`、当前 50 条 RAG 评估集。

## Global Constraints

### 执行进度（2026-09-16）

2026-09-17 第二轮已完成：新版50条真实评测及离线复核已保存；检索Recall@5=91.67%，原40条=91.25%（恢复H基线）。生成6条成功、44条调用失败，拒答仍未测得，闭环未验收通过。当前阻塞为生成调用稳定性及两条内容复核问题，详情见 `docs/rag-generation-closed-loop-report-2026-09-17.md`。下述首轮T6/T7状态仅为历史。

2026-09-17 续跑：用户已明确确认免费拖车为可回答、岗位问题改为至少 27 个维修岗位，并授权修改后继续闭环。新版数据集为 `wd/retrieval-eval-50-generation-v2-2026-09-17.jsonl`（42 可回答、8 拒答），不覆盖原 H 集。本轮清单见 `wd/rag-generation-v2-manifest-2026-09-17.md`；下述首轮结果保留作历史记录，不再表示仍在等待这两条标签的确认。

- T1：冻结清单及改动前真实复跑已完成；三项指标与 H 一致。
- T2–T5：拒答策略、可审计 prompt、独立 AnswerService、普通 RAG 路由和定向测试已落地；查询保持原问题优先，辅助路异常回退。改动后检索回归仍与 H 一致。
- T6：真实50条已完成，35条成功、15条调用失败；Codex已逐条复核响应和证据，业务人工确认尚未完成。生成路径Recall@5=0.875，触发下方人工确认点。详见 `docs/rag-generation-closed-loop-report-2026-09-15.md` 和 `wd/rag-generation-eval-reviewed-2026-09-16.json`。
- T7：已整理失败交接报告及三个后续方向；闭环验收未通过，尚未开展确认点之后的新一轮修复。以下原始清单保留为验收要求，不以实现完成代替业务验收勾选。
- 查询处理本轮保留确定性规范化/多跳拆解，不声明已经实现多轮指代消解。历史传入回答 prompt；本轮 50 条均为独立问题。
- 当前环境未安装计划所述 superpowers:executing-plans 技能，按本计划逐任务直接执行。

- 当前检索基线以 H 实验为准：`Recall@5=0.9125`、`MRR@5=0.8717`、`nDCG@10=0.8482`，数据集为 `D:/studyprogram/mypro/wd/retrieval-eval-50-hierarchical-source-fixed-2026-09-15.jsonl`，报告为 `D:/studyprogram/mypro/wd/rag-eval-exp-h-rerank-evidence-2026-09-15.json`。
- 当前只声明“指定文档范围内的检索回归集达标”，不要把它写成全库检索或生成链路已验收。
- RRF 参数暂不再调：`rrf_k=60`，Vector/BM25/Structured 权重为 `1.0/1.0/0.5`；多跳当前每路 Top20、融合 Top20、context Top5。
- `unanswerable` 的标准输出语义是“材料中未提及/无法从给定材料确认”，不能返回 `null`、空字符串或编造答案。
- Query 改写不得读取评估答案、gold chunk、expected answer 或任何评分信息；必须保留原 query 作为第一路或兜底。
- 每个代码任务完成后只跑相关回归；不要为了没有代码变化的阶段反复跑完整检索调参。

---

### Task 1: 冻结可复现检索基线 T1

**Files:**
- Create: `D:/studyprogram/mypro/wd/rag-h-baseline-manifest-2026-09-15.md`
- Modify if needed: `D:/studyprogram/mypro/docs/rag-优化实验复盘与关键结论-2026-09-15.md`
- Read/check: `D:/studyprogram/mypro/scripts/run_rag_eval.py`
- Read/check: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/rag/RetrievalServiceAdapter.java`
- Read/check: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/rag/QueryChunkReranker.java`
- Read/check: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/rag/RetrievalQueryProfile.java`

**Interfaces:**
- Consumes: existing `/api/rag/search` and eval runner.
- Produces: a manifest that later tasks can cite when verifying no retrieval regression occurred.

- [ ] Record exact dataset path, report path, service port, Java command, Git status summary, database/index scope, embedding model/provider, RRF parameters, provider weights, provider/context TopK, and known limitations.
- [ ] Re-run the H eval once only if the service and indexes are available; save the new report as `D:/studyprogram/mypro/wd/rag-eval-h-baseline-rerun-2026-09-15.json`.
- [ ] If rerun differs materially from H, stop and diagnose reproducibility before touching generation. Material difference: any main metric drops below gate, or `Recall@5` changes by more than 0.02.
- [ ] Confirm the frozen gate in the manifest: `Recall@5>=0.90`、`MRR@5>=0.80`、`nDCG@10>=0.80`.
- [ ] Explicitly mark generation/refusal metrics from old reports as “not measured”, not “failed” and not “passed”.

**Verification:**
- Run: `python scripts/run_rag_eval.py --help`
- Prefer existing project command for real eval if already documented in `scripts/run_rag_eval.py`; do not invent a new endpoint contract without reading the script first.

### Task 2: 固定拒答契约 T6 前置

**Files:**
- Create: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/dialogue/AnswerPolicy.java`
- Create test: `D:/studyprogram/mypro/backend/src/test/java/com/agicore/dialogue/AnswerPolicyTest.java`
- Modify later consumer: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/dialogue/DialogueOrchestrator.java`

**Interfaces:**
- Produces: `AnswerPolicy` with stable refusal text and evidence sufficiency rules.
- Later tasks consume: prompt builder and answer service.

- [ ] Define refusal text: `根据已检索到的材料，未找到可以支持该问题的明确依据。` The final answer may add a short explanation, but must not be `null`.
- [ ] Define refusal trigger types: no evidence returned; retrieved evidence has no answer-bearing facts; LLM says answer cannot be determined from evidence.
- [ ] Define non-refusal rule: if evidence contains enough facts, answer normally even when phrasing differs from expected answer.
- [ ] Add unit tests for empty evidence, irrelevant evidence, explicit `expected.answer=未提及`, and normal answerable evidence.
- [ ] Keep refusal wording in the answer layer, not inside retrieval scoring.

**Verification:**
- Run: `mvn -q -f backend/pom.xml -Dtest=AnswerPolicyTest test`

### Task 3: 完成 Query 改写但保持回退

**Files:**
- Modify: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/rag/QueryTransformation.java`
- Modify/test: `D:/studyprogram/mypro/backend/src/test/java/com/agicore/rag/QueryTransformationTest.java`
- Inspect: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/rag/RetrievalQueryProfile.java`

**Interfaces:**
- Consumes: original user query and optional dialogue history.
- Produces: ordered query list where original query is preserved and transformed queries are optional.

- [ ] Add tests that rewritten queries preserve object, time, quantity, negation, condition, region, document scope, and comparison direction.
- [ ] Add tests that simple direct questions are passed through without forced decomposition.
- [ ] Add tests that multi-hop questions may add subqueries, but original query remains first or has highest priority.
- [ ] Ensure transformation failure returns original query only.
- [ ] Run one retrieval regression after this task. If main metrics drop or multi-hop complete rate drops, keep the code path disabled by default and document why.

**Verification:**
- Run: `mvn -q -f backend/pom.xml -Dtest=QueryTransformationTest,RetrievalQueryProfileTest test`
- Run current eval only once after implementation and compare with Task 1 manifest.

### Task 4: 构造可审计的 Evidence Prompt

**Files:**
- Create: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/dialogue/RagPromptBuilder.java`
- Create test: `D:/studyprogram/mypro/backend/src/test/java/com/agicore/dialogue/RagPromptBuilderTest.java`
- Read/possibly modify: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/rag/EvidenceContextReranker.java`
- Read/possibly modify: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/rag/RetrievalContracts.java`

**Interfaces:**
- Consumes: user query, optional history, selected retrieval candidates/evidence context.
- Produces: prompt string plus evidence metadata list; every cited fact must map back to an evidence ID.

- [ ] Prompt must separate instructions, question, and evidence. Evidence text must be quoted as data, not instructions.
- [ ] Each evidence block must include stable evidence ID, chunk ID, document ID/source file, location/page if available, and content.
- [ ] Prompt must instruct the model: answer only from evidence; cite evidence IDs for factual claims; refuse clearly when evidence is insufficient; do not output `null`.
- [ ] Add tests that numbers, units, dates, regions, and comparison facts survive prompt formatting.
- [ ] Add tests that prompt length limiting does not remove all evidence for a required sub-question.
- [ ] If H context_drop cases still lose necessary text in the actual prompt, fix context selection narrowly and rerun retrieval/context checks.

**Verification:**
- Run: `mvn -q -f backend/pom.xml -Dtest=RagPromptBuilderTest,EvidenceContextRerankerTest test`

### Task 5: 接通真实 LLM 回答服务

**Files:**
- Create: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/dialogue/AnswerService.java`
- Create test: `D:/studyprogram/mypro/backend/src/test/java/com/agicore/dialogue/AnswerServiceTest.java`
- Modify: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/dialogue/DialogueOrchestrator.java`
- Reuse: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/model/ModelGateway.java`
- Keep separate: `D:/studyprogram/mypro/backend/src/main/java/com/agicore/dialogue/WriterAgent.java`

**Interfaces:**
- Consumes: query, retrieval evidence pack, prompt builder, `ModelGateway`.
- Produces: answer text, cited evidence IDs, refusal flag, prompt/response metadata, latency/error status.

- [ ] Add `AnswerService.answer(...)` with a narrow public API; do not move document-writing behavior into it.
- [ ] Use `ModelGateway` for the LLM call, not a new raw HTTP client.
- [ ] Make LLM failures explicit user-facing failures; do not silently return empty answer.
- [ ] Parse or preserve citations in a simple stable format. First version may accept inline `[E1]` style citations if tests verify mapping.
- [ ] Wire ordinary RAG chat path to retrieval -> prompt -> model -> answer. Keep non-RAG/document generation paths unchanged unless they already route through the same answer path.
- [ ] Save enough trace data for eval: query, rewritten queries, evidence IDs, prompt, answer, citations, refusal flag, latency, error.

**Verification:**
- Run: `mvn -q -f backend/pom.xml -Dtest=AnswerServiceTest,DialogueGraphIntegrationTest,DialogueRouterTest test`

### Task 6: 生成端评测 T6

**Files:**
- Modify: `D:/studyprogram/mypro/scripts/run_rag_eval.py`
- Modify tests: `D:/studyprogram/mypro/scripts/tests/test_run_rag_eval.py`
- Output: `D:/studyprogram/mypro/wd/rag-generation-eval-2026-09-15.json`

**Interfaces:**
- Consumes: current 50-row eval set and the real answer endpoint/service path.
- Produces: report with retrieval metrics, answer correctness fields, refusal metrics, citation metrics, latency, and failure buckets.

- [ ] Extend the runner to call the actual answer path, not only `/api/rag/search`.
- [ ] Persist actual prompt and final answer for every row.
- [ ] Score `unanswerable` separately: refusal accuracy and false answer rate.
- [ ] Score answerable rows separately: answer present, no false refusal, required facts covered, numeric/unit correctness, citation support.
- [ ] For context confusion rows, inject distractor evidence after retrieval in the eval harness if supported; otherwise mark anti-confusion as not measured and do not claim pass.
- [ ] Generate a failure table grouped as retrieval miss, context loss, prompt loss, LLM answer error, citation error, refusal error, or infrastructure error.
- [ ] Manually review all 50 first-run answers before calling the generation loop closed.

**Verification:**
- Run: `python -m pytest scripts/tests/test_run_rag_eval.py -q`
- Run the real 50-row generation eval and save report.

### Task 7: 闭环验收与交接文档

**Files:**
- Modify: `D:/studyprogram/mypro/docs/rag-优化实验复盘与关键结论-2026-09-15.md`
- Create: `D:/studyprogram/mypro/docs/rag-generation-closed-loop-report-2026-09-15.md`

**Interfaces:**
- Consumes: Task 1 manifest and Task 6 report.
- Produces: final project-facing status: passed, failed, or blocked with concrete next action.

- [ ] Report current confirmed scope: document-filtered RAG or full-corpus RAG. Do not mix them.
- [ ] Include retrieval metrics, generation metrics, refusal results, citation results, latency summary, and top failure samples.
- [ ] If generation quality fails but retrieval remains stable, mark retrieval baseline preserved and list answer-side fixes only.
- [ ] If retrieval regresses, revert or disable the responsible change before continuing generation polish.
- [ ] Update next actions to at most three items.

**Verification:**
- Run relevant backend tests from changed files.
- Run script tests.
- If frontend/manual path is in scope, manually ask at least one answerable question and one unanswerable question through the available UI/API and record result.

## Execution Order

1. Task 1: freeze and optionally rerun H baseline.
2. Task 2: refusal contract, so later LLM output has a stable target.
3. Task 3: query transformation with original-query fallback and regression check.
4. Task 4: evidence prompt builder and context handoff.
5. Task 5: real LLM answer path through `ModelGateway`.
6. Task 6: 50-row generation eval and manual review.
7. Task 7: write final closure report and decide whether RAG is closed or which one to three fixes remain.

## First Human Confirmation Point

Stop for human review after Task 6 if any of these happen:

- `Recall@5<0.90`、`MRR@5<0.80`、or `nDCG@10<0.80` after query/prompt changes.
- Any unanswerable sample returns a fabricated concrete answer.
- More than two answerable samples become false refusals.
- Citation support cannot be reliably mapped back to evidence IDs.
- LLM API/auth/config is unavailable, causing generation metrics to be unmeasured.

If none of the above occurs, continue to Task 7 and produce the closure report.

## Notes For The Next Window

- The old report field values of `null` mean generation was not measured; they do not mean the model returned null.
- H's strongest unresolved issue is not global recall anymore; it is whether the final prompt keeps all necessary evidence and whether the LLM uses it correctly.
- Do not add Neo4j, a learning reranker, or broad row-level table retrieval in this pass. Those are later optimization directions only if the Task 6 failure table proves they are needed.
- Keep every generated eval report under `D:/studyprogram/mypro/wd/` and every durable decision under `D:/studyprogram/mypro/docs/`.
