# 严格层级 Chunk 设计（2026-09-13）

## 目标

将后续 ingest 的分块基线固定为：每个来源页/块先递归切成不超过 1000 字符的 parent window，再将每个 parent window 递归切成不超过 250 字符的 child；每个 child 持有明确的 parent UUID。

## 数据流

`页面文本 → parent window(≤1000) → child(≤250) → embedding → PostgreSQL parent_chunk_id → ES/PG child 检索 → parent context 扩展`。

parent 不再是整篇文档的单一根节点。持久化层先写入全部 parent，再按 child.parentChunkId 建立外键关系；服务重启时直接从数据库恢复该关系，不再按文档猜测 parent。

## 兼容与边界

- 现有数据库 schema 的 `parent_chunk_id` 已支持多 parent，无需迁移。
- 旧数据若 parent_chunk_id 为空，仍可被读取；新 ingest 必须写入显式关系。
- 页面位置采用 `page:N#parent:M#chunk:K`，结构化召回同时匹配原始 `page:N` 和该前缀。
- 现有评估集 chunk UUID 在重切分后不可复用，必须完成重建索引和 ID 映射后再评测。

## 验收

1. 定向 Maven 测试与打包通过。
2. 新 ingest 的每个 parent 长度 ≤1000、每个 child 长度 ≤250。
3. 服务重启后 child.parent_chunk_id 与写入前一致。
4. 结构化路径能命中 `page:N#parent:M#chunk:K`。
