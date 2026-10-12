# RAG 当前进度与待办（2026-09-13）

## 结论

当前 RAG 已完成“文件入库 → 真实向量 → 基础召回 → 来源回填”的基础闭环，尚未完成“混合检索 → 评估门禁 → 稳定发布”的完整闭环。
按 RAG 本身估算：基础能力约 70%，生产级完整闭环约 40%–50%。

## 已完成

1. PDF、Word、Excel 解析与统一结构化输出。
2. PDF 文本层检测、OCR fallback、页级失败记录和重试信息。
3. 文本清洗、质量评估、bbox/confidence、表格和单元格元数据。
4. Parent/child Chunk、source anchor、页码和来源文件持久化。
5. PostgreSQL + pgvector 持久化，服务重启后可恢复检索。
6. 文件 hash 去重和异步入库任务状态。
7. qwen3.7-text-embedding 真实 1024 维向量已重算，历史 mock 已清理。
8. 向量相似度召回、child 命中后的 parent context 回填。
9. 基础 RetrievalPack/ResearchPack 和证据引用结构。
10. 50 条评估集已生成：10 条基础、10 条拒答、10 条多跳、10 条属性变异、10 条上下文混淆。
11. 后端当前 Surefire 报告 39 项测试全部 0 失败、0 错误；前端构建通过。

## 尚未完成

12. PDF 表格全量运行态重入库及表格真值集。
13. Elasticsearch/BM25 关键词召回。
14. 结构化精确检索：金额、数量、日期、条款号、表格字段。
15. 统一 RetrievalCandidate、三路去重、RRF 融合和降级策略。
16. 查询分类、query rewrite、召回阈值和 topK 评估。
17. 可选 reranker 及超时降级。
18. 自动评测 Runner：Recall@K、MRR、nDCG、数字准确率、来源定位准确率。
19. 拒答率、引用准确率、跨 Chunk 完整性、抗混淆等质量门禁。
20. 将评估集接入 CI/CD，形成可重复基线。
21. 检索实验室前端和错误样本分析界面。

## 新顶层设计带来的边界

22. 当前 Agent/Graph 仍是演示级静态链路，不是投标协作 Workflow。
23. TenderTask、TenderBrief、RequirementMatrix、EvidencePack 等任务产物尚未落地。
24. Tool Registry/Executor、权限、审计和结构化参数校验尚未落地。
25. HUMAN 确认、checkpoint/resume、动态分支和人工后续执行尚未落地。
26. 模板化 Word/Excel、引用映射、渲染校验和 ArtifactPack 尚未落地。

## 今日 RAG 完成定义

27. 真实语料可重复入库，三路召回可运行，结果可融合并返回来源。
28. 50 条评估集可自动执行并输出指标和失败样本。
29. 拒答、多跳、属性变异、上下文混淆均有独立指标。
30. 关键错误有日志、可复现输入和明确降级行为。
31. 后端测试、评估 Runner 和前端构建全部通过。

参考：[项目状态](D:/studyprogram/mypro/docs/project-status-2026-09-09.md)、[P1 检索设计](D:/studyprogram/mypro/docs/p1-retrieval-design-2026-09-12.md)、[50 条评估集](D:/studyprogram/mypro/wd/retrieval-eval-50-regenerated-2026-09-13.jsonl)。
## 2026-09-13 实施进度（RAG 闭环第一个人工确认点）

- 已完成：50 条评估集校验、RetrievalCandidate 契约、Vector/Keyword/Exact 三路 Provider、`rrf_k=60` 等权融合、按 `chunkId` 去重、最多 8 条结果、旧搜索参数兼容及 `topK/documentId/mode` API 参数。
- 已验证：对应单元测试与 Spring 应用上下文测试通过；全量 `mvn -q test` 通过；数据库 2108 条生产向量无 mock/孤儿 child。
- 尚未完成：Provider 异常降级与三路全失败状态、普通上下文 Top-5/多跳 Top-8 的最终裁剪、检索 trace、真实评估 Runner/门禁、服务重启后的新 API 运行态验证。
- 已完成运行态确认：重启后的 8080 服务对 `采购编号` 返回 `EXACT + VECTOR + KEYWORD + rrf` breakdown；`mode=exact` 与 `mode=keyword` 路由均可返回结果，说明“真实数据库 → 三路召回 → RRF → API”第一个闭环已打通。
- 已生成首份真实评估报告：[rag-eval-report-2026-09-13.json](D:/studyprogram/mypro/wd/rag-eval-report-2026-09-13.json)。当前 `Recall@5=0.425`、`Hit@5=0.50`、`MRR@5=0.3475`、`MultiHopComplete@5=0.10`，未达到门禁，Runner 按约定返回退出码 2；这是真实基线，不是伪造通过。
- 已补充 `RetrievalTrace`：记录 trace_id、启用 provider、失败 provider、候选数、RRF K、最终 chunk 列表和总延迟。
- 当前人工确认点转移为：是否继续实现 Provider 异常降级/全失败状态、上下文 Top-5/Top-8 裁剪、trace，以及针对真实基线指标的召回质量改进。
# Multi-hop 失败分层诊断（2026-09-14）

后端搜索接口支持可选 `diagnostics=true`，返回同一次线上执行中的 `vector`、`bm25`、`structured`、`fused`、`reranked`、`final_context` 有序候选。普通请求仍返回原有列表结构。

运行命令：

```powershell
python scripts/run_rag_eval.py wd/retrieval-eval-50-regenerated-2026-09-13.jsonl --base-url http://localhost:8080 --failure-analysis --output wd/rag-multihop-failure-analysis.json
```

报告中的 `multi_hop_failure_analysis` 将样本互斥归类为 `provider_miss`、`partial_provider_recall`、`fusion_drop`、`rerank_drop`、`context_drop` 或 `complete`，并记录每个 gold chunk 在各层的实际 rank。
