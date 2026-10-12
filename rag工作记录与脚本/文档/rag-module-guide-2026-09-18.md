# RAG 功能模块说明

更新日期：2026-09-18

本文说明当前 RAG 功能模块的生产链路、主要接口、证据策略、质量校验和线上闭环机制。适用于后续接入、维护和验收。

## 1. 模块目标

RAG 模块负责基于企业文档回答用户问题，要求：

- 仅依据检索证据回答。
- 每个事实句带证据引用。
- 能区分同名合同、合同金额与发票金额、服务期限与签订/验收日期。
- 对数值、单位、比值、时间跨度做校验。
- 能保存线上 trace，支持反馈、人工审计和 replay 回放。

当前生产证据边界采用 **Top-30 answer context**，不是 Top-5。

## 2. 核心链路

```mermaid
flowchart TD
  A[用户问题] --> B[/api/rag/answer]
  B --> C[RetrievalServiceAdapter]
  C --> D[Top-30 answer context]
  D --> E[RagPromptBuilder]
  E --> F[LLM 结构化生成]
  F --> G[AnswerService 校验与重试]
  G --> H[返回答案]
  G --> I[RagQualityService 保存 trace]
  I --> J[feedback / audit / replay]
```

核心代码：

- `backend/src/main/java/com/workpro/api/RagAnswerController.java`
- `backend/src/main/java/com/workpro/dialogue/AnswerService.java`
- `backend/src/main/java/com/workpro/dialogue/RagPromptBuilder.java`
- `backend/src/main/java/com/workpro/rag/RetrievalQueryProfile.java`
- `backend/src/main/java/com/workpro/dialogue/RagQualityService.java`

## 3. 检索与证据策略

### 3.1 生产证据边界

生产回答使用 Top-30 证据池：

- `/api/rag/answer` 请求检索时使用 `topK=30`。
- `answer_context` 的 `fusionTopK` 至少为 `30`。
- Top-5 只作为排序健康指标，不作为最终证据边界。

相关代码：

- `AnswerService.answer(...)`
- `RetrievalQueryProfile`

### 3.2 Prompt evidence 构造

`RagPromptBuilder` 会在 Top-30 基础上做：

- 保留 final context。
- 补充 parent context。
- 在剩余预算内补充 answer candidates。
- 多方面问题为每个方面预留证据。
- 合同金额问题前置业绩/汇总表证据，后置发票扫描件证据。
- parent 过长时回退为 child-only，避免整条证据被预算挤掉。

合同金额重排只在 query 明确包含“合同金额”时生效，避免影响质量保证期、工位、病假等其他题型。

## 4. 生成与结构化输出

RAG 生成使用强制结构化输出，要求模型提交：

- `answerability`: `FULL` / `PARTIAL` / `NONE`
- `supported`: boolean
- `answer`: 含证据引用的答案文本

普通对话不受该结构化输出约束。

相关代码：

- `AnswerService`
- `RagPromptBuilder`
- `RagGenerationGateway`

## 5. 答案校验与重试

`AnswerService` 对生成结果做多层校验：

- JSON 合法性与重复字段检测。
- `answerability` 合法性检测。
- citation ID 是否存在。
- bare citation 归一化，例如 `E1` → `[E1]`。
- 拒答策略判断。
- 数值量纲校验。
- 校验失败时向模型追加反馈并重试。

### 5.1 数值量纲校验

`AnswerUnitValidator` 会拦截错误表达，例如：

```text
20分÷4分/年=5倍
20分 ÷ 4分/年 = 5（年），即最高分是单年得分的5倍
```

允许真正求时长的表达，例如：

```text
100元÷20元/月=5月
```

相关代码：

- `backend/src/main/java/com/workpro/dialogue/AnswerUnitValidator.java`
- `backend/src/test/java/com/workpro/dialogue/AnswerUnitValidatorTest.java`

## 6. 生产质量闭环接口

### 6.1 Trace 存储

`POST /api/rag/answer` 返回前会自动保存 trace。

保存内容包括：

- query
- documentId / chapterId / history
- disableQueryTransform
- status / errorCode
- answer / citations / refusal
- retrievedChunkIds
- evidence
- retrieval metadata
- prompt
- rawResponse
- latency / modelLatency
- validation JSON

数据库表：`rag_answer_trace`

迁移文件：

- `backend/src/main/resources/db/migration/V12__rag_quality_closure.sql`

### 6.2 Trace 查询

```http
GET /api/rag/traces/{traceId}
```

用于查看一次回答的完整证据、答案、引用、反馈和人工审计记录。

### 6.3 用户反馈

```http
POST /api/rag/feedback
```

请求字段：

```json
{
  "traceId": "...",
  "userId": "optional-user",
  "rating": "down",
  "category": "citation",
  "comment": "引用不支持答案",
  "metadata": {}
}
```

数据库表：`rag_answer_feedback`

### 6.4 人工审计

```http
POST /api/rag/audit/reviews
```

请求字段：

```json
{
  "traceId": "...",
  "reviewer": "qa",
  "requiredFactsCovered": true,
  "numericUnitCorrect": true,
  "citationSupported": true,
  "shouldRefuse": false,
  "errorType": null,
  "notes": "复核通过",
  "metadata": {}
}
```

数据库表：`rag_answer_audit_review`

### 6.5 Replay 回放

```http
POST /api/rag/replay
```

请求字段：

```json
{
  "traceId": "...",
  "disableQueryTransform": false
}
```

回放会读取原始 query/document/history，用当前代码重新执行 `/answer` 逻辑，并保存新的 trace。

## 7. 验收指标

当前推荐分三层看指标：

### 7.1 检索排序健康指标

保留 Top-5 / Top-10 / Top-20 / Top-30 指标。

Top-5 不作为生产证据边界，只用于排序健康监控。

### 7.2 Prompt Evidence Recall

衡量正确证据是否进入模型实际可见的 prompt evidence。

生产回答以该层为核心，而不是只看 Search Recall@5。

### 7.3 生成侧人工复核指标

人工复核字段：

- `required_facts_accuracy`
- `numeric_unit_accuracy`
- citation `support_accuracy`

最新 full-corpus 50 条真实模型复测，采用 Top-30 生成链路后：

- `required_facts_accuracy=100%`
- `numeric_unit_accuracy=100%`
- citation `support_accuracy=100%`
- `failure_table=[]`

产物：

- `wd/rag-deepseek-full-50-after-mh003-and-unit-fix-reviewed-2026-09-18.json`

## 8. 线上建议

上线时建议：

- 普通请求直接自动回答。
- 全量保存 trace。
- 用户反馈进入反馈队列。
- 高风险问题抽检或进入人工审计。
- 线上坏例子通过 replay 复现，再沉淀进离线评测集。
- Top-5 继续优化，但不作为阻断生产回答的证据边界。

高风险问题包括：

- 金额
- 期限
- 质量保证期
- 资格条件
- 废标/无效响应
- 合同比较
- 多个同名主体或同名合同

## 9. 关键测试

Java：

```bash
mvn -q "-Dnet.bytebuddy.experimental=true" "-Dtest=RagAnswerControllerTest,AnswerUnitValidatorTest,AnswerServiceTest,RagPromptBuilderTest,RetrievalQueryProfileTest" test
```

Python：

```bash
python -m pytest scripts\tests\test_run_rag_eval.py scripts\tests\test_audit_rag_acceptance.py scripts\tests\test_rag_eval_scopes.py scripts\tests\test_validate_rag_eval.py
```

当前结果：

- Java 定向测试通过。
- Python `17/17` 通过。
