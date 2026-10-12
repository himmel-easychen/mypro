# Chunk 切分质量与递归降级方案（2026-09-13）

## 当前基线检查

数据源：`wd/all-document-chunks.jsonl`。

- 51 个文档，2,057 个 child chunk。
- child 长度中位数约 212 字符，P90 约 1,007 字符。
- 209 个 child 超过 1,000 字符，最长约 4,996 字符。
- 超长块主要来自 DOCX 的整页文本，以及少量 PDF/Excel 块；当前 ingest 基本是“每页/每个块一个 child”，没有语义递归切分。

## 已实现的第一版

`RecursiveChunker` 按以下顺序递归降级：

1. 标题边界（章节、数字编号、括号编号标题）；
2. 空行段落；
3. 句号/问号/感叹号/分号；
4. 逗号/顿号；
5. 空白字符；
6. 固定字符长度兜底。

此前 child 上限基线为 1,200 字符。现已确认并实现严格层级基线：parent window ≤1,000 字符，child ≤250 字符。每个 page/block 可产生多个 parent，child 通过显式 parent UUID 关联；位置使用 `page:N#parent:M#chunk:K`。

## 验证结果

- 标题优先并保留标题与其后内容：通过。
- 递归降级到段落、句子、逗号、空白：通过。
- 无分隔符时字符兜底且不丢字符：通过。
- 空输入不生成空 chunk：通过。
- 定向 Maven 测试与打包：通过（2026-09-13）；全量测试需依赖可用的 PostgreSQL/Redis/ES 运行环境。

## 严格层级变更验证

- `RagPersistence.Chunk` 携带显式 `parentChunkId`，持久化先写 parent 再写 child，避免多 parent 文档下的错挂关系。
- 服务恢复直接读取数据库中的 `parent_chunk_id`，不再按文档只保留一个 parent。
- 结构化召回同时支持原始 `page:N` 与切分后的 `page:N#...` 位置。
- 定向测试与 `mvn -q -DskipTests package` 通过；全量测试本次运行受 PostgreSQL 连接池失效影响，出现 `HikariPool ... Connection is not available`，需重启测试数据库后复跑。

## 全量重灌与首轮评测结果（2026-09-13）

- Docker 中 PostgreSQL、Redis、Elasticsearch 已恢复；全量导入 33 个源文件后按 checksum 得到 32 个唯一文档。
- 数据库层级统计：3,721 个 parent、8,964 个 child；parent 最大 985 字符，child 最大 250 字符。ES 已成功索引 8,964 个 child。
- 评估集已基于本次新 UUID 重新生成并通过静态校验：`VALID 50 records`，文件为 `wd/retrieval-eval-50-hierarchical-2026-09-13.jsonl`。
- 首轮检索评测报告：`wd/rag-eval-report-hierarchical-2026-09-13.json`。Auto：Recall@5=0.45、MRR@5=0.26625、NDCG@10=0.38311，未达到 0.90/0.80/0.80 验收线。
- 分层定位：BM25 Recall@5=0.6375，Vector=0.10，Structured=0.0875，Auto=0.45；本轮使用 1024 维确定性本地 embedding 完成闭环，Vector 数值不代表生产语义 embedding 的最终能力。

## 生产向量复核与复评（2026-09-13）

- 根因确认：数据库中原先有 `7002` 条 `mock-embedding`，虽然 `embedding` 列非空，但不是语义向量；只有 `1962` 条为 `qwen3.7-text-embedding`。RRF 权重无需调整。
- 处理方式：保持 chunk、chunk ID、切分参数和 RRF 配置不变，通过 `POST /api/rag/embedding-reindex?limit=0` 重算生产向量；接口结果 `requested=10722,succeeded=10722,failed=0`。
- 清理结果：删除 `7002` 条 mock embedding；当前 `12685/12685` 个 parent/child chunk 均为 `qwen3.7-text-embedding`、1024 维、ready，`legacy_vectors=0`、`reindex_required=0`。
- 生产向量复评报告：`wd/rag-eval-report-production-embedding-2026-09-13.json`。Vector：Recall@5=0.70、MRR@5=0.69458、NDCG@10=0.67340；Auto：Recall@5=0.775、MRR@5=0.61333、NDCG@10=0.65186。相较 mock 混入时 Vector Recall@5=0.10，说明主要问题确为向量质量/向量模型混用，而不是 RRF 权重。

### 下一步

1. 保留两份报告作为对照基线；继续定位 Auto 与 Vector 的差距，重点检查 RRF 后裁剪、Structured 误召回和黄金样本覆盖。
2. 将“全量生产向量、无 mock、无孤儿 child”设为评测前置门禁，避免再次把 mock 向量带入运行态。
3. 对 14 个 `needs_review` 文档按失败页/表格结构补做解析质量复核，不直接删除其已入库内容。

## 重新入库前的注意事项

这次代码变更只影响后续 ingest。已有数据库和 Elasticsearch 中的旧 child 不会自动重切分。重新 ingest 会产生新的随机 chunk UUID，因此现有评估集里的 `relevant_chunk_ids` 不能直接复用；应在重建索引时按 `source_document_id + location + content hash` 做映射，或先生成新的黄金 chunk ID 映射，再运行 Recall@5、MRR@5、NDCG@10。

建议顺序：先在副本/测试库重建全部文档，检查长度分布和表格行完整性，再切换索引别名，最后更新评估集引用。
