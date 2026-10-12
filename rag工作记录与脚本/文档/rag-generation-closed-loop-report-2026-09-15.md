# RAG 生成闭环验收记录

2026-09-17 修复进展：已按用户授权继续修复并完成三范围复测，详见 [最新修复报告](rag-repair-and-retest-2026-09-17.md)。指定文档/全库事实覆盖均为 40/42；100 次端到端成功，干扰 29/30 成功。原对话模型保留，生成已切换豆包；质量闭环仍未通过。下文保留历史状态。

执行日期：2026-09-16。当前范围：指定文档 RAG，非全库验收。结论：功能链路已接通，质量验收未通过；触发计划 T6 后人工确认点，等待确认后开展下一轮修复。

## 实现与复现

普通 `force_mode=rag` 聊天调用独立 AnswerService；文档/Graph 写作链路保留。`POST /api/rag/answer` 接受 query、documentId、chapterId、history、disableQueryTransform，返回实际 prompt、rawResponse、答案、引用映射、refusal、检索 trace 和耗时。生成错误返回 HTTP 503 与非空错误消息，不记作拒答成功。

Evidence Prompt 用 JSON 区分用户问题、历史与证据；每块保留 E 编号、chunk/document/source/location、child 文本及父块上下文。超长时先移除可选父块与历史，仍超长则明确失败，不静默丢失某一子问题的全部 child 证据。引用 ID 必须存在，语义支持仍需逐条复核。

拒答固定为“根据已检索到的材料，未找到可以支持该问题的明确依据。” 无证据直接拒答；相关性与充分性由模型基于实际证据判断，不将 expected.answer 或 gold 传入生产路径。查询保留 H 的规范化原问题优先及 0.35 辅助路；辅助查询异常不丢弃原问题结果。

LLM 继续走 ModelGateway，凭据变量修正为 AGICORE_LLM_API_KEY。当前测试服务端口 8081，模型 gpt-5.6-sol，单次调用超时 90000ms、重试 0。启动时使用项目既有评测脚本约定的本地认证来源，仅通过进程环境传递，不在报告中记录密钥。

## 检索与自动测试

- 冻结清单：`wd/rag-h-baseline-manifest-2026-09-15.md`。
- 改动前独立复跑：`wd/rag-eval-h-baseline-rerun-2026-09-16.json`。
- 改动后唯一检索回归：`wd/rag-query-regression-2026-09-16.json`。
- 两次均 Recall@5=0.9125、MRR@5=0.8716666667、nDCG@10=0.8482106514，多跳 complete@5=0.70，H 基线保留。
- 26 项定向后端测试通过（AnswerPolicy、RagPromptBuilder、AnswerService、DialogueGraphIntegration、DialogueRouter、DialogueRagRoute、EvidenceContextReranker、QueryTransformation、RetrievalQueryProfile、RetrievalServiceAdapterDiagnostics）。本机 Java 24 需 `-Dnet.bytebuddy.experimental=true` 兼容项目现有 Mockito/ByteBuddy。
- `python -m pytest scripts/tests/test_run_rag_eval.py -q`：6 passed。
- 首个真实 API 样例：二类维修企业 5 个机修工位、底盘测功机允许外协，返回 [E1]/[E2]；回答正常。

## 生成评测

运行命令：

```powershell
python scripts/run_rag_eval.py wd/retrieval-eval-50-hierarchical-source-fixed-2026-09-15.jsonl --base-url http://localhost:8081 --generation --output wd/rag-generation-eval-2026-09-15.json
```

首次 50 条已执行，35 条成功、15 条调用失败。原始响应保留在 `wd/rag-generation-eval-2026-09-15.json`；逐条证据复核在 `wd/rag-generation-review-2026-09-16.json`；复核报告在 `wd/rag-generation-eval-reviewed-2026-09-16.json`，`closed_loop_passed=false`。复核由 Codex 对照保存的答案与实际证据完成，不代表业务人员已签字验收。调用失败样本只完成错误检查，答案质量未测。

| 指标 | 本轮结果及分母 |
| --- | --- |
| 生成调用成功率 | 35/50=70% |
| 生成路径 Recall@5 / MRR@5 / nDCG@10 | 0.875 / 0.855 / 0.823391，Recall 未达 0.90 |
| 多跳 complete@5 | 0.70 |
| 可回答样本答案非空 / 整体误拒答 | 28/28 / 0条；不含12条调用失败，部分子问题遗漏另计 |
| 必要事实覆盖 | 24/28=85.71% |
| 数字单位正确 | 17/18=94.44%；只统计适用且成功的样本，遗漏事实另计 |
| 引用 ID 映射 / 引用语义支持 | 100% / 25/28=89.29%（可回答成功样本） |
| 原标签拒答率 / 非拒答率 | 5/7=71.43% / 2/7=28.57%；另3条失败 |
| 延迟 p50 / p95 | 10.142秒 / 75.722秒 |
| 注入干扰证据抗混淆 | not_measured，未执行，不声明通过 |

数字匹配只作筛查，不等于事实/单位正确；引用 ID 映射不等于语义支持。拒答原标签存在下述争议，未擅自更改数据集或重新美化分数。

## 失败证据与定位

- 基础设施：`context_confusion-006/007`、`unanswerable-008..010`、全部10条 basic 生成失败。后13条 trace 同时记录 VECTOR 调用 DashScope 的 `Remote host terminated the handshake`，BM25仍返回结果；模型侧还出现调用失败及熔断。无法将全部错误归为同一个根因，也不能把降级报告当作健康依赖条件下的代码回归。此前改动后独立检索回归保持 H；尚未证实需要回滚哪个代码改动。
- `multi_hop-004/010`：分别遗漏现场修复30分钟，以及独立法人/联合体/转分包条件。所选 context 未覆盖必要子问题；本轮未发现所选 child 在 prompt 构造时被截掉。后续应检查候选到 context 的证据覆盖，不扩大调参。
- `compare_mutation-003`：把发票7.731285/10.612110万元当成合同金额，比较方向错误。表格父块存在列名；同名合同的对象区分也需要澄清。
- `multi_hop-007`：计算正确，但第二笔金额只来自用户问题，未由实际证据支持；`compare_mutation-007` 虽 gold chunk 未命中，替代证据能支持正确差额，保留自动 retrieval_miss 并注明差别。
- `compare_mutation-009`：原文“上班开始的前30分钟内，即8:30-9:00”自相矛盾，回答擅自改成“开始后”并否定“上班前”；应该保留歧义，而不是替源文作决定。
- `unanswerable-002`：岗位下限相加确为27，但包含管理/业务等岗位，且未明确不兼岗；不能无条件推定“至少27名维修人员”。需确认岗位与独立人数口径。
- `unanswerable-004`：实际 E2（chunk `ad80995e-3d3e-4058-b8ff-a17d5030addd`）明确支持24小时免费拖车。因此其非拒答有依据，是指定文档范围下的标签冲突，不能笼统说两条均为模型编造。

## API 冒烟与下一步（待人工确认）

真实 `/api/rag/answer` 已验证5个机修工位与允许外协的带引用答案，`unanswerable-001` 验证固定拒答。`/api/chat` 的 `force_mode=rag` 普通对话真实任务 `rag-closure-smoke-20260916` 返回 SUCCEEDED，引用 E2/E3 及对应文档/chunk；未进行前端界面验收。

1. 恢复并验证模型与 embedding 依赖稳定性，补充分阶段错误诊断；保留失败首轮，不用无限重试覆盖失败。
2. 针对本轮具体样本补齐 context 子问题覆盖，约束表格列/对象、数字来源与矛盾文本处理；保持 H 参数，不开展全局调参。
3. 人工确认免费拖车标签、岗位人数口径和歧义合同/时间后，版本化修订评估集，再执行定向回归及完整验收。

计划要求 “Stop for human review after Task 6 if any of these happen”，本轮 Recall@5=0.875 已触发。此文为该确认点的失败交接记录，不宣称 T7 成功闭环。

Runner 支持 `--generation --replay <报告> --review <逐条复核JSON>`，复核与报告刷新不重复调用模型。所有生成响应完整保存，包括失败的实际 prompt 和原始输出。
