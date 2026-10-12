# RAG 修复与三范围复测（2026-09-17）

本轮完成两轮代码修复，最终版本重新执行指定文档 50 条、全库 50 条及干扰 30 次测试。事实覆盖显著改善，但闭环质量验收仍未通过。生成继续使用 doubao-seed-2-1-pro-260915，原对话模型保留；未更改评测题、答案、gold 或文档索引。

## 本轮修复

1. 查询拆解不再拆开“参与”；保留原问题和原前三个辅助子句，仅补入此前完全遗漏的后续整句，避免重复加权导致全库召回回退。
2. 生成上下文从最多 5 个扩展到 10 个。前五个不同父块保持排序，后续位置优先覆盖未覆盖的问题词，避免重复父块挤占多跳证据。普通搜索的上下文策略保留。
3. 对表格中显式“≥/不少于”数量列使用确定性逐行求和，把操作数、行名、列名、证据 ID 和求和范围交给生成模型。岗位题得到 27 个岗位，不推断为 27 名员工，不将答案写入生产规则。
4. 强化表头、记录所属项目、合同金额与发票金额、日期含义、外协条件、评分项与强制条件的区分；要求核对所有上下文并披露原文矛盾。
5. 评测脚本增加实时进度；干扰脚本增加单题/单变体诊断，以及非法 JSON/契约错误时保留原始响应和 provider ID。错误不计作拒答或内容正确。

关键代码：`QueryTransformation.java`、`RetrievalQueryProfile.java`、`EvidenceContextReranker.java`、`AnswerService.java`、`EvidenceArithmetic.java`、`RagPromptBuilder.java`。

## 最终版本结果

固定集合为 [50 条评测集](../wd/retrieval-eval-50-generation-v2-2026-09-17.jsonl)，SHA256：`14d488116d4bdb7cdb502c9f5040d3ccf0ad517bf16aa7cd9dfbfd9ba8238f0f`。其中 42 条可回答、8 条不可回答。复核依据实际回答、最终 prompt、父块及引用；复核者为 Codex，不代表人工签署验收。

| 指标 | 指定文档：修复前 → 后 | 全库：修复前 → 后 |
| --- | ---: | ---: |
| 调用成功 | 50/50 → 50/50 | 50/50 → 50/50 |
| Recall@5 | 91.67% → 91.67% | 76.19% → 76.19% |
| MRR@5 | 87.78% → 87.78% | 68.65% → 68.65% |
| nDCG@10 | 85.54% → 85.53% | 69.53% → 69.53% |
| 多跳完整率@5 / @10（修复后） | 70% / 80% | 50% / 60% |
| 必需事实覆盖 | 34/42 → 40/42 | 31/42 → 40/42 |
| 数值/单位正确 | 26/31 → 29/31 | 22/31 → 28/31 * |
| 引用语义支持 | 37/42 → 42/42 | 32/39 → 40/40 |
| 固定不可回答标签拒答 | 8/8 → 6/8 | 8/8 → 7/8 |
| 可回答题被完全拒答 | 0 → 0 | 3 → 2 |
| 延迟 P50 / P95（ms） | 5270 / 22685 → 4170 / 18066 | 5101 / 25550 → 4974 / 19593 |
| 闭环验收 | 未通过 | 未通过 |

* 本轮严格扣除 basic-004 的量纲错误：结论 4 分、20 分、5 倍正确，但 `20分÷4分/年=5倍` 不成立，应为 `20分÷(1年×4分/年)=5`。原全库基线同样存在该表达且旧复核未扣分，按本轮口径应是 21/31；上表保留已发布基线 22/31，不覆盖历史。引用支持衡量原文事实和操作数来源，算式量纲单独计入数值/单位失败。

100 次端到端 trace 的 VECTOR 与 ELASTICSEARCH_BM25 均无 provider_failures；本次 Qwen 向量链路没有观测到错误。最大生成 prompt 分别为 11689、11409 字符，低于 24000 的预算。指定文档三项检索门槛通过；全库仍低于 Recall@5≥90%、MRR@5≥80%、nDCG@10≥80%。这次没有全库召回回退，也不能说召回已达标。

## 干扰实验与格式失败

仍对 10 条 context_confusion 题分别运行原上下文、真实干扰块置前、真实干扰块置后，保留原证据和父块。

| 变体 | 调用成功 | 事实通过 / 全部尝试 | 引用通过 / 全部尝试 |
| --- | ---: | ---: | ---: |
| control | 10/10 | 10/10 | 10/10 |
| prepend | 9/10 | 9/10 | 9/10 |
| append | 10/10 | 10/10 | 10/10 |
| 合计 | 29/30 | 29/30 | 29/30 |

29 个有效回答均通过事实与引用复核，先前外协条件误读没有在本轮三变体中重现。`context_confusion-003:prepend` 原始完整测试出现 JSONDecodeError，未取得可评价答案。当时脚本没有保留非法输出，因此不能判断是截断、转义还是其他格式问题。已补上诊断留存功能；同题同变体独立重试成功，但不替换原始失败、不宣称 30/30 或结构化输出已稳定。

## 尚未解决的具体问题

- 全库 `multi_hop-010` 与 `compare_mutation-003` 缺少足够目标证据而完全拒答。需要提升项目/同名记录定位及表头、行的关联，不宜仅继续扩大上下文。
- 指定文档 `compare_mutation-003` 有据地比较了同名序号 4 的 12.8 万元，而冻结答案指向序号 2 的 57.6 万元。当前答案不满足冻结答案，但不能将其归类成合同/发票混用或编造。
- 指定文档 `basic-002` 未重述已在问题中假设完成的登记/证书手续的截止时点，按冻结答案保留事实失分。该题问法和必需事实范围有歧义，本轮没有据结果改题。
- 指定文档 `unanswerable-001/003` 已说明具体时限/服务期限未知，没有编造，但 `supported=true` 导致拒答标记错误；需要统一“回答了相关背景”和“回答了所问事实”的协议。
- 全库 `unanswerable-006` 从其他项目文件检索到同一项目明确质保期限，答案有据。指定文档的不可回答标签不适用于全库；保留冻结标签的拒答失分，并标注 `label_scope_mismatch`，不把它算成幻觉。
- 两范围 `basic-004` 存在上述量纲表达错误；干扰实验另有一次结构化输出失败，诊断重试通过尚不足以证明稳定。

部分 gold chunk 未命中，但替代父块足以支持答案，例如金额合计/差额题。自动 `retrieval_miss` 和 `llm_answer_error` 是门禁桶，不等同根因；应同时读逐题复核。引用 100% 也不代表答案完整或拒答协议通过。

## 验证与复现

后端 31 个相关测试通过：EvidenceArithmetic 3、QueryTransformation 9、RetrievalQueryProfile 5、EvidenceContextReranker 3、RagPromptBuilder 2、AnswerService 5、RagSearchDelegation 2、DialogueGraphIntegration 1、DialogueRouter 1。后端已打包并运行于 8081。Java 24 测试使用 `-Dnet.bytebuddy.experimental=true`。Python 评测测试 11 个通过，另有 1 个 subtest 通过；包含 gold/文档范围隔离、干扰保留及非法输出留存检查。

最终 100 条逐题复核已经以原始响应回放汇总，没有再次调用模型。回放返回码 1 表示质量门禁未通过，不是接口调用失败。

```powershell
python -X utf8 scripts/run_rag_eval.py wd/retrieval-eval-50-generation-v2-2026-09-17.jsonl --generation --replay wd/rag-repair-v2-50-2026-09-17.json --review wd/rag-repair-v2-50-review-2026-09-17.json --output wd/rag-repair-v2-50-reviewed-2026-09-17.json
python -X utf8 scripts/run_rag_eval.py wd/retrieval-eval-50-generation-v2-2026-09-17.jsonl --generation --full-corpus --replay wd/rag-repair-v2-full-corpus-50-2026-09-17.json --review wd/rag-repair-v2-full-corpus-50-review-2026-09-17.json --output wd/rag-repair-v2-full-corpus-50-reviewed-2026-09-17.json
```

## 证据产物

- [指定文档原始结果](../wd/rag-repair-v2-50-2026-09-17.json)、[逐题复核](../wd/rag-repair-v2-50-review-2026-09-17.json)、[汇总](../wd/rag-repair-v2-50-reviewed-2026-09-17.json)。
- [全库原始结果](../wd/rag-repair-v2-full-corpus-50-2026-09-17.json)、[逐题复核](../wd/rag-repair-v2-full-corpus-50-review-2026-09-17.json)、[汇总](../wd/rag-repair-v2-full-corpus-50-reviewed-2026-09-17.json)。
- [30 次干扰原始结果](../wd/rag-repair-v2-distractors-2026-09-17.json)、[逐题复核](../wd/rag-repair-v2-distractors-review-2026-09-17.json)、[汇总](../wd/rag-repair-v2-distractors-summary-2026-09-17.json)、[单次诊断重试](../wd/rag-repair-v2-distractors-diagnostic-2026-09-17.json)。
- [修复前基线](rag-doubao-three-scope-eval-2026-09-17.md)。`wd/rag-repair-*`（不含 v2）保留第一轮中间实验；本报告结论只使用最终 v2 完整实测，不挑选较好单次答案替换失败。
