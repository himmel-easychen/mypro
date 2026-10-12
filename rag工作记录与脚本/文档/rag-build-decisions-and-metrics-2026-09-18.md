# RAG 构建关键决策、指标变化与痛点解决记录

更新日期：2026-09-18

本文记录本轮 RAG 功能构建中的关键决策、指标变化、解决的痛点和仍需关注的问题。

## 1. 背景问题

最初观察到的主要问题是：检索层并非完全找不到正确证据，但生成阶段经常没有把正确片段稳定送入 prompt，或在 prompt 中被噪声证据压制。

典型表现：

- 正确 chunk 被检索回来，但不在 Top-5。
- 正确证据进入 prompt 后排位靠后，被前面的发票、同名合同、通用条款干扰。
- 模型混淆合同金额和发票金额。
- 模型在数值/单位题中写出量纲不成立的算式。
- 结构化输出偶发重复字段或残缺 JSON。
- 线上缺少 trace、反馈、审计和 replay，无法形成生产闭环。

## 2. 关键决策

### 2.1 生产证据边界从 Top-5 改为 Top-30

决策：生产生成链路不再用 Top-5 作为证据边界，而是使用 Top-30 answer context。

原因：full-corpus 固定 Top-5 长期无法达到 90%，但 Top-20/30 已经能覆盖大多数正确证据。继续卡 Top-5 会误判生产链路不可用。

当前口径：

- Top-5：排序健康指标。
- Top-20/30：证据池召回指标。
- Prompt Evidence Recall：模型实际可见证据指标。
- reviewed generation accuracy：最终答案质量指标。

相关代码：

- `AnswerService`：RAG answer 检索 `topK=30`
- `RetrievalQueryProfile`：answer context `fusionTopK >= 30`
- `RagPromptBuilder`：Top-30 内做 prompt 证据重排与补充

### 2.2 生成侧强制结构化输出

决策：RAG 调用强制 `submit_answer` 结构化输出，普通对话不受影响。

解决痛点：

- 重复字段
- 残缺 JSON
- 无法稳定识别 supported/refusal
- citation 解析不稳定

效果：之前 5 个格式失败样本复测全部恢复为 OK。

### 2.3 Prompt evidence 不能只按检索顺序拼接

决策：检索 Top-30 后，在 prompt 内继续做意图感知重排。

已实现策略：

- 多方面问题为每个方面预留证据。
- 合同金额问题前置业绩/汇总表证据。
- 发票扫描件、发票累计、开票日期、发票列表证据降权。
- parent 太长时 child-only fallback，避免正确证据被预算挤掉。

相关代码：

- `RagPromptBuilder`
- `RagPromptBuilderTest`

### 2.4 合同金额与发票金额必须硬隔离

决策：问“合同金额”时，发票金额不能代替合同金额；但如果业绩汇总表里有合同金额，应优先使用业绩表。

触发样本：`multi_hop-003`

修复前：

- 正确证据已在 prompt 中，但排在 E20。
- 前面大量发票证据导致模型误判“合同金额缺失”。

修复后：

- 业绩表证据被前置。
- 答案正确输出：人民医院 `20万元`、花东镇人民政府 `40万元`、合计 `60万元`。

### 2.5 数值量纲必须程序校验，不能只靠 prompt

决策：对“数量 ÷ 每周期速率 = 倍数”类错误做确定性校验并重试。

典型错误：

```text
20分÷4分/年=5倍
20分 ÷ 4分/年 = 5（年），即最高分是单年得分的5倍
```

修复：

- `AnswerUnitValidator` 拦截上述表达。
- 保留真正求时长的合法表达：`100元÷20元/月=5月`。

触发样本：`basic-004`

修复后输出：

```text
20分÷4分=5
```

### 2.6 人工复核用于离线验收和线上审计，不进入普通实时链路

决策：线上普通请求不逐条人工复核；人工复核用于：

- 高风险样本审计
- 用户负反馈处理
- 发版验收
- 坏例沉淀

因此补齐了 trace、feedback、audit、replay 接口。

相关代码：

- `RagQualityService`
- `RagAnswerController`
- `V12__rag_quality_closure.sql`

## 3. 指标变化

### 3.1 检索指标

full-corpus 固定 gold 口径：

| 指标 | 当前值 | 说明 |
|---|---:|---|
| Recall@5 | 79.76% | 未达 90%，不作为生产证据边界 |
| MRR@5 | 70.79% | 未达 80% |
| nDCG@10 | 71.15% | 未达 80% |
| Recall@10 | 85.71% | 比 Top-5 明显改善 |
| Recall@20/30 | 约 91.67% / 96.43% | 用于证据池覆盖判断 |

结论：Top-5 排序仍需长期优化，但生产回答不应被 Top-5 截断。

### 3.2 Document-filtered 50 条复测

人工复核后：

| 指标 | 结果 |
|---|---:|
| required_facts_accuracy | 100% |
| numeric_unit_accuracy | 100% |
| citation support_accuracy | 100% |
| failure_table | 空 |
| closed_loop_passed | true |

### 3.3 Full-corpus 50 条修复前复测

人工复核后曾有唯一真实生成失败：`multi_hop-003`。

| 指标 | 修复前 |
|---|---:|
| required_facts_accuracy | 97.62% |
| numeric_unit_accuracy | 97.62% |
| citation support_accuracy | 97.62% |

失败原因：合同金额证据在 prompt 中靠后，被前面的发票证据干扰。

### 3.4 Full-corpus 50 条最终复测

最终产物：

- `wd/rag-deepseek-full-50-after-mh003-and-unit-fix-reviewed-2026-09-18.json`

合并补测后结果：

| 指标 | 最终结果 |
|---|---:|
| attempted | 50 |
| measured | 50 |
| answer_present_rate | 100% |
| required_facts_accuracy | 100% |
| numeric_unit_accuracy | 100% |
| citation support_accuracy | 100% |
| failure_table | 空 |

说明：`compare_mutation-006` 首轮出现一次 168 秒基础设施错误，单条补测通过并已合并。

`closed_loop_passed` 在 full-corpus 报告中仍为 false，原因是固定 Top-5 检索门槛未过，不是生成侧失败。

## 4. 解决的关键痛点

### 4.1 “检索到了但模型看不到 / 没用上”

解决方式：

- Top-30 answer context。
- prompt 内意图重排。
- parent context 扩展。
- child-only fallback。
- 合同金额证据前置。

效果：生成侧 full-corpus 50 条人工复核达到 100%。

### 4.2 合同金额与发票金额混淆

解决方式：

- prompt 规则强调不可混用。
- 合同金额问题中，业绩/汇总表证据前置。
- 发票证据后置但保留，作为反向说明。

代表样本：`multi_hop-003`、`basic-009`

### 4.3 数值量纲不稳定

解决方式：

- `AnswerUnitValidator` 程序校验。
- 失败后携带反馈重试。
- 扩展覆盖“算成年后又说倍数”的变体。

代表样本：`basic-004`

### 4.4 结构化输出不稳定

解决方式：

- 强制 RAG 走结构化输出。
- JSON 严格解析。
- 重复字段检测。
- 非法输出重试。

效果：格式失败样本复测恢复 OK。

### 4.5 线上无法定位错误

解决方式：

新增生产质量闭环：

- trace 存储
- 用户反馈
- 人工审计
- replay 回放

接口：

- `GET /api/rag/traces/{traceId}`
- `POST /api/rag/feedback`
- `POST /api/rag/audit/reviews`
- `POST /api/rag/replay`

## 5. 仍需关注的问题

### 5.1 Top-5 排序仍未达标

Top-5 仍未达到 90%。该问题不阻断生产回答，但应作为后续检索排序优化方向。

建议继续观察：

- Search Recall@5
- MRR@5
- nDCG@10
- Top-20/30 证据池召回
- Prompt Evidence Recall

### 5.2 全库 gold 标签存在范围冲突

部分样本原标签是指定文档口径；full-corpus 下会检索到其他有效证据。

典型样本：

- `unanswerable-006`
- `unanswerable-003`

处理方式：

- review JSON 支持 `waived_failure_buckets`。
- 标签范围冲突不计为模型错误。
- 固定 gold chunk 未命中但答案有证据支撑时，不计为生成错误。

### 5.3 线上人工审计流程还需要产品化

后端接口已具备，但如果要给运营使用，还需要前端页面或后台工具展示：

- 用户问题
- 答案
- 引用证据
- Top-30 候选
- prompt evidence
- feedback
- review 表单
- replay 结果对比

## 6. 最终上线判断

可以标记为：**RAG 生成侧与生产质量闭环已经具备上线基础。**

上线口径：

- 生产证据边界：Top-30 answer context。
- Top-5：排序健康监控，不作为生产阻断门槛。
- 准确率口径：人工复核后的生成侧 required facts、numeric/unit、citation support。
- 线上闭环：trace → feedback → audit → replay → 修复 → 回归集沉淀。

最新 full-corpus 50 条真实模型复测显示，生成侧三项人工复核指标均为 100%。
