# RAG 项目答辩级复盘：从检索、生成到生产质量闭环

更新日期：2026-09-18

本文面向答辩、面试和后续项目复盘，重点说明：这次 RAG 功能到底完成了什么闭环、源码中每个关键点的价值、每一类问题如何被定位和解决、指标发生了什么变化，以及当前短板和下一轮优化方向。

与普通说明文档不同，本文不只列模块，而是回答面试中常见追问：

- 为什么 Recall@5 只有 79.76%，还敢说生成侧闭环了？
- 为什么生产不用 Top-5，而用 Top-30？
- 检索到了为什么模型还会答错？
- 你怎么证明不是 prompt 碰巧好了？
- 结构化文档 RAG 的难点在哪里？
- 生成侧准确率怎么验证？
- 线上出问题后怎么回放和修复？
- 当前短板是什么，下一轮怎么把检索 Top-5 拉上去？

---

## 1. 一句话总结

本轮 RAG 构建完成的是：

> 在强格式化办公文档场景下，将 RAG 从“检索 Top-5 命中不稳定、生成阶段证据丢失、答案格式和数值单位不稳定”的状态，推进到“Top-30 证据池 + prompt 内证据重排 + 结构化生成 + 程序化校验重试 + 人工复核指标 + 线上 trace/feedback/audit/replay 闭环”的可验收生产链路。

最终结果：

- full-corpus 固定 Top-5 检索排序仍未达标：Recall@5 `79.76%`。
- 但生产生成链路不再以 Top-5 截断，而是使用 Top-30 证据池。
- 采用 Top-30 evidence pool + prompt 内重排 + 校验重试后，full-corpus 50 条真实模型复测，经人工复核：
  - `required_facts_accuracy=100%`
  - `numeric_unit_accuracy=100%`
  - citation `support_accuracy=100%`
  - `failure_table=[]`

这说明：

> 当前瓶颈不是“正确证据完全召不回来”，而是“Top-5 排序不够强”。生产链路通过 Top-30 证据池和生成侧控制绕过了 Top-5 截断风险，但下一轮仍要补强 rerank，把 Top-5 排序指标推上去。

---

## 2. 项目场景为什么难

本项目处理的是强格式化办公文档，不是普通网页纯文本。

文档类型包含：

- Word 招标文件
- Word/PDF 合同与业绩材料
- 表格型评分标准
- 发票清单
- 车辆维保服务承诺
- 结构化条款、表头、行列关系、父子块上下文

这类文档的 RAG 难点不在“把文本塞进向量库”这么简单，而在以下几类问题。

### 2.1 表格结构被拍平

例如：

```text
序号 | 合同名称 | 合同金额 | 发票金额 | 服务期限
6 | 人民医院车辆维修和保养服务合同 | 20万元 | 9.315887万元 | 2023.4.23-2025.4.22
7 | 花东镇人民政府车辆维修和保养服务合同 | 40万元 | 10.612110万元 | 2024.9.1-2025.8.31
```

如果被切成普通 chunk，模型可能只看到：

```text
人民医院 ... 20万元 ... 9.315887万元 ... 花东镇 ... 40万元 ...
```

但它不一定知道哪个金额是合同金额，哪个金额是发票金额。

这会导致：

- 合同金额和发票金额混用。
- 标题前后的金额归属错位。
- 同名主体不同合同被混在一起。

### 2.2 同名实体多、近邻噪声强

例如“广州市花都区人民医院”可能同时出现：

- 救护车维修维保合同，合同金额 65 万元，发票金额 31.426453 万元。
- 车辆维修和保养服务合同，合同金额 20 万元，发票金额 9.315887 万元。

如果模型只靠相似度，很容易拿错合同。

### 2.3 正确证据常在 Top-6 到 Top-30

本轮 full-corpus 指标显示：

| 指标 | 值 | 解释 |
|---|---:|---|
| Recall@5 | 79.76% | 前 5 条里有一部分正确证据缺失 |
| Recall@10 | 85.71% | Top-5 到 Top-10 增加约 6 个点 |
| Recall@30 | 96.43% | 大部分正确证据已在候选池里 |

这说明：

> 候选召回能力已经较强，瓶颈主要是排序。

### 2.4 生成阶段也会丢证据

即使正确证据被检索回来，也可能因为：

- prompt 预算限制；
- parent block 太长；
- final_context 只取前几条；
- 发票证据排在合同金额证据前面；
- 通用条款排在具体条款前面；

导致模型没有看到正确证据，或看到了但没有优先使用。

这正是本轮最核心的修复方向。

---

## 3. 本轮解决的问题总览

本轮并不是只做一个优化点，而是围绕 RAG 闭环拆成多个小点逐一修复。

可以概括为 7 大类、20+ 个小点。

| 类别 | 小点 | 解决的问题 | 结果 |
|---|---|---|---|
| 检索策略 | Top-30 answer context | Top-5 截断漏证据 | 正确证据进入候选池 |
| 检索策略 | fusionTopK 至少 30 | 融合池太窄 | Recall@30 达 96.43% |
| Prompt 构造 | parent context | 表头/行信息缺失 | 表格语义更完整 |
| Prompt 构造 | child-only fallback | parent 太长挤掉证据 | 保住关键子块 |
| Prompt 构造 | 多方面证据预留 | 多跳问题只答一半 | 投标合规/服务保障类问题恢复 |
| Prompt 构造 | 合同金额证据前置 | 发票证据压制合同金额 | multi_hop-003 修复 |
| Prompt 构造 | 发票证据降权 | 合同金额/发票金额混淆 | basic-009、multi_hop-003 稳定 |
| 生成控制 | 强制结构化输出 | JSON 残缺、重复字段 | 格式失败样本恢复 OK |
| 生成控制 | answerability | 拒答/可答不统一 | FULL/PARTIAL/NONE 明确 |
| Citation | ID 校验 | 引用不存在 | 阻断无效引用 |
| Citation | 裸引用归一化 | E1 未写成 [E1] | citation mapping 100% |
| 数值校验 | quantity/rate 校验 | 20分÷4分/年=5倍 | basic-004 稳定 |
| 数值校验 | 错误反馈重试 | 一次生成不稳定 | 自动修正部分错误 |
| 拒答策略 | NONE 统一拒答 | 背景相关但事实未知 | 减少幻觉 |
| 评测 | document/full 双口径 | 指定文档与全库混淆 | 标签冲突可解释 |
| 评测 | manual review JSON | 自动指标无法判语义 | 三项人工指标落地 |
| 评测 | waived buckets | gold 标签范围冲突 | 不误判模型错误 |
| 线上闭环 | trace 存储 | 线上错误不可定位 | 可追踪每次回答 |
| 线上闭环 | feedback | 用户反馈无法回流 | 可形成坏例队列 |
| 线上闭环 | audit review | 人工复核无结构 | 可统计线上质量 |
| 线上闭环 | replay | 修复无法复现验证 | 可用当前代码回放 |

下面逐层展开。

---

## 4. 检索层：为什么不是简单追 Top-5

### 4.1 初始问题

full-corpus 固定 gold 指标：

```text
Recall@5 = 79.76%
MRR@5    = 70.79%
nDCG@10  = 71.15%
Recall@10 = 85.71%
Recall@30 = 96.43%
```

从这些指标可以看出：

- Top-5 不够强。
- Top-10 有明显提升。
- Top-30 基本能把正确证据召回来。

所以核心判断是：

> 召回池够大后，正确证据基本存在；瓶颈是排序和生成阶段证据选择。

### 4.2 为什么生产不用 Top-5

如果生产只把 Top-5 送给模型，那么 Recall@5 只有 `79.76%`，意味着约 20% 的问题存在证据缺失风险。

但如果使用 Top-30 作为 answer context，Recall@30 达到 `96.43%`，正确证据大概率已经在候选池里。

因此本轮决策：

> 生产生成链路使用 Top-30 evidence pool，Top-5 只作为排序健康指标。

### 4.3 代码落点

`AnswerService` 中 answer 请求使用 Top-30：

```java
pack = retrieval.retrieve(new RetrievalRequest(query, documentId, chapterId, 30,
        Map.of("disable_query_transform", disableTransform, "answer_context", true)));
```

`RetrievalQueryProfile` 中 answer context 的 `fusionTopK` 至少为 30。

价值：

- 防止正确证据在 Top-6 到 Top-30 时被截断。
- 为 prompt 内重排和生成侧选择提供足够候选。
- 使生产准确率不被 Top-5 排序短板直接决定。

---

## 5. Prompt 证据层：本轮最关键的收益来源

检索到 Top-30 只是第一步，真正关键的是：怎么把 Top-30 变成模型可用的 evidence。

### 5.1 parent context：解决表头和行归属缺失

办公文档的表格题经常需要表头和同一行上下文。

如果只给 child chunk，模型可能只看到：

```text
20万元 9.315887万元
```

但不知道哪个是合同金额，哪个是发票金额。

因此 `RagPromptBuilder` 会将 parent context 放进 evidence：

```java
new Evidence("E" + (++index), hit.chunk_id(), documentId,
        hit.source_file(), hit.location(), hit.content_preview(), hit.parent_chunk_id(),
        value(p.get("content")))
```

价值：

- 保留表格标题、列名、前后行。
- 提升合同金额、发票金额、服务期限等字段归属判断。

### 5.2 child-only fallback：解决 parent 太长导致证据无法进入 prompt

parent context 有价值，但也可能太长。

如果 parent 太长导致 prompt 超预算，系统不会直接放弃该证据，而是回退成 child-only：

```java
if (candidateText.length() <= budget) {
    text = candidateText;
} else {
    expanded.remove(expanded.size() - 1);
    if (!parentContent.isBlank()) {
        var childOnly = new Evidence(..., "");
        ...
    }
}
```

价值：

- 优先保留完整 parent。
- parent 放不下时至少保留关键 child。
- 避免“证据被检索到但因为预算完全丢失”。

### 5.3 Top-30 优先于词面噪声

本轮曾遇到问题：词面匹配很强的噪声会挤掉 rerank 后的有效候选。

因此在补充候选时采用：

1. 多方面问题 evidence reserve。
2. 原 answer candidates Top-30。
3. lexical candidates。

代码逻辑：

```java
prioritized.addAll(originalAnswerCandidates.stream().limit(30).toList());
prioritized.addAll(lexicalCandidates);
```

价值：

- 尊重检索层已经给出的候选边界。
- 避免单纯词面重复的噪声挤掉正确证据。

### 5.4 多方面问题证据预留

样本：`multi_hop-010`

问题：

```text
如果参与花都供电局一般车辆维保项目，服务商在投标合规和服务保障方面需要同时满足哪些条件？
```

模型需要同时找到：

- 投标合规
- 服务保障

如果只按整体相似度，可能只找到一个方面。

因此 `RagPromptBuilder` 用正则识别：

```java
private static final Pattern PARALLEL_ASPECTS = Pattern.compile("([\\p{IsHan}]{2,4})(?:和|与|及)([\\p{IsHan}]{2,6})方面");
```

然后为每个 aspect 保留候选。

价值：

- 降低多跳题“只答一半”的概率。
- 对投标合规/服务保障、商务/技术等并列结构有效。

### 5.5 合同金额证据前置：multi_hop-003 的关键修复

失败样本：`multi_hop-003`

问题：

```text
广州市花都区人民医院和花东镇人民政府的车辆维修保养服务合同金额分别是多少，合计金额是多少？
```

修复前现象：

- 正确业绩汇总表证据已经进入 prompt。
- 但排在约 E20。
- 前面大量是发票扫描件和发票累计金额。
- 模型过度套用“发票金额不能回答合同金额”，误判合同金额缺失。

修复前答案核心错误：

```text
材料未直接给出合同金额，只能查到对应发票累计金额，因此无法回答合同金额及合计。
```

实际正确答案：

```text
人民医院合同金额 20万元；花东镇人民政府合同金额 40万元；合计 60万元。
```

修复方式：

`RagPromptBuilder` 对 query 包含“合同金额”的问题调用：

```java
selected = prioritizeInitialContext(query, selected, parents);
```

核心打分：

```java
if (text.contains("合同金额")) score += 5_000;
if (text.contains("万元")) score += 1_000;
if (text.contains("定点议价采购合同") || text.contains("定点服务") || text.contains("车辆维修和保养服务")) score += 600;

boolean invoiceOnly = (text.contains("发票扫描件") || text.contains("发票累计")
        || text.contains("开票日期") || text.contains("合计: ¥") || text.contains("发票列表"))
        && !text.contains("合同金额");
if (invoiceOnly) score -= 10_000;
```

修复后答案：

```text
广州市花都区人民医院车辆维修和保养服务合同金额为 20 万元 [E20]。
广州市花都区花东镇人民政府车辆维修和保养服务合同金额为 40 万元 [E20]。
合计合同金额：20 万元 + 40 万元 = 60 万元。
```

价值：

- 不再让发票证据压制合同金额证据。
- 同时保留发票证据用于说明“发票金额不是合同金额”。
- 修复了 full-corpus 唯一真实生成失败。

---

## 6. 生成控制层：为什么不能只靠 prompt

### 6.1 强制结构化输出

早期问题：模型可能输出：

- JSON 残缺
- 重复字段
- 字段缺失
- answer 为 null
- supported/refusal 不一致

修复方式：

- RAG 生成强制使用 submit_answer 函数式结构。
- `AnswerService` 严格解析 JSON。
- 开启重复字段检测。
- 非法 JSON 触发重试。

相关逻辑：

```java
parsed = mapper.reader()
    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .readTree(json);
```

价值：

- 生成结果可机器判定。
- 拒答、支持性、答案文本分离。
- 避免格式错误污染指标。

结果：

- 之前 5 个格式失败样本复测全部恢复 OK。

### 6.2 answerability 三态

RAG 答案不是简单 supported true/false。

本轮定义：

- `FULL`：全部所问事实可回答。
- `PARTIAL`：部分可回答。
- `NONE`：所问事实均未知。

prompt 中明确：

```text
FULL/PARTIAL 对应 supported=true；NONE 对应 supported=false。
相关背景不算回答了所问事实。
```

价值：

- 避免“有相关背景就硬答”。
- 支持部分回答。
- 拒答协议更统一。

### 6.3 citation 校验

每个事实句必须引用 evidence ID。

`AnswerService` 会检查：

- 是否有引用。
- 引用 ID 是否存在。
- 裸引用是否可以归一化。

裸引用归一化示例：

```text
E1 -> [E1]
```

但只对实际存在的 evidence ID 生效。

价值：

- 防止模型编造引用。
- 兼容模型偶发的格式小偏差。
- citation ID mapping 达到 100%。

---

## 7. 数值和单位校验：basic-004 的修复逻辑

样本：`basic-004`

问题：

```text
企业信誉评分中，A级纳税人每满一年的得分与连续五年达到的最高分分别是多少？最高分是单年得分的几倍？
```

证据：

```text
每一年得4分，最高得20分。
```

正确答案：

```text
每年4分，连续五年最高20分，最高分是单年得分的5倍。
20分÷4分=5。
```

错误表达：

```text
20分÷4分/年=5倍
```

为什么错？

- `4分/年` 是速率。
- `20分 ÷ 4分/年 = 5年`，结果是时间，不是倍数。
- 如果问倍数，应该比较同单位数量：`20分 ÷ 4分 = 5`。

### 7.1 第一轮校验

`AnswerUnitValidator` 先拦截：

```text
20分÷4分/年=5倍
```

### 7.2 复测发现的新变体

完整 full 50 复测中又出现：

```text
20分 ÷ 4分/年 = 5（年），即最高分是单年得分的5倍
```

这更隐蔽：前半句算成了“年”，后半句又把它说成“倍”。

### 7.3 最终修复

校验器现在逻辑是：

- 如果 `数量 ÷ 每周期速率 = 倍数`，非法。
- 如果 `数量 ÷ 每周期速率 = 时间单位`，本身可合法。
- 但如果后文又声明“倍”，非法。

代码：

```java
if (!matcher.group(2).equals(matcher.group(3))) return true;
String tail = answer.substring(matcher.end(), Math.min(answer.length(), matcher.end() + 20));
if (matcher.group(3) != null && tail.contains("倍")) return true;
```

保留合法情况：

```text
100元÷20元/月=5月
```

最终 `basic-004` focused 复测输出：

```text
最高分是单年得分的5倍：20分÷4分=5 [E1]。
```

价值：

- 把 prompt 约束升级为程序校验。
- 可确定性拦截量纲错误。
- 避免“模型这次答对，下次又错”的不稳定。

---

## 8. 拒答与标签范围冲突

本轮发现一个重要问题：full-corpus 评测和 document-filtered 评测的标签语义不同。

例如某些样本在指定文档中“未提及”，但 full-corpus 检索到其他文档里有有效证据。

典型样本：

- `unanswerable-006`
- `unanswerable-003`

如果坚持原标签，会误判模型错误。

解决方式：

- review JSON 支持 `waived_failure_buckets`。
- 人工复核确认属于标签范围冲突时，不计为模型错误。
- 固定 gold chunk 未命中但答案引用可支撑时，不计为生成错误。

代码位置：

- `scripts/run_rag_eval.py`
- `scripts/tests/test_run_rag_eval.py`

价值：

- 让评测口径更严谨。
- 避免“全库下有证据却要求拒答”的矛盾。
- 区分模型错误、检索 gold 口径问题、标签范围问题。

---

## 9. 评测体系：怎么证明闭环了

本轮不是只看单次回答，而是建立了多层评测。

### 9.1 检索指标

用于判断搜索排序能力：

- Recall@5
- MRR@5
- nDCG@10
- Recall@10
- Recall@20/30

当前 full-corpus 固定 gold：

```text
Recall@5  = 79.76%
MRR@5     = 70.79%
nDCG@10   = 71.15%
Recall@10 = 85.71%
Recall@30 = 96.43%
```

判断：

- Top-5 排序未达生产级优秀线。
- Top-30 召回池足够支持生成侧。

### 9.2 Prompt Evidence Recall

用于判断：正确证据是否真正进入模型 prompt。

这是本轮最关键的中间指标，因为很多失败不是检索失败，而是 prompt evidence 选择失败。

### 9.3 生成侧人工复核指标

人工复核三个维度：

- required facts 是否覆盖。
- numeric/unit 是否正确。
- citation 是否支撑答案。

最终 full-corpus 50 条 reviewed：

```text
required_facts_accuracy = 100%
numeric_unit_accuracy   = 100%
citation support        = 100%
failure_table           = []
```

产物：

```text
wd/rag-deepseek-full-50-after-mh003-and-unit-fix-reviewed-2026-09-18.json
```

### 9.4 为什么 closed_loop_passed 仍可能 false

full-corpus 报告里的 `closed_loop_passed=false` 是因为脚本仍把固定 Top-5 检索门槛作为总门槛之一。

但生产链路已经不以 Top-5 截断。

所以正确解读是：

- 检索 Top-5 排序闭环：未完成。
- 生成侧答案质量闭环：已完成。
- 生产质量追踪闭环：已补齐基础接口。

---

## 10. 线上生产闭环：trace → feedback → audit → replay

上线后最怕的问题不是“离线 50 条测过”，而是线上坏例无法定位。

本轮新增了生产质量闭环。

### 10.1 Trace 存储

`POST /api/rag/answer` 会自动写入：

- query
- documentId / chapterId / history
- retrievedChunkIds
- evidence
- retrieval metadata
- prompt
- answer
- rawResponse
- citations
- latency
- validation JSON

表：`rag_answer_trace`

价值：

- 线上每次回答可追溯。
- 可以定位问题发生在检索、prompt、生成、校验还是引用。

### 10.2 用户反馈

接口：

```http
POST /api/rag/feedback
```

字段：

```json
{
  "traceId": "...",
  "rating": "down",
  "category": "citation",
  "comment": "引用不支持答案"
}
```

表：`rag_answer_feedback`

价值：

- 用户负反馈可回流。
- 能形成线上 bad case 队列。

### 10.3 人工审计

接口：

```http
POST /api/rag/audit/reviews
```

字段：

```json
{
  "requiredFactsCovered": true,
  "numericUnitCorrect": true,
  "citationSupported": true,
  "shouldRefuse": false,
  "errorType": null
}
```

表：`rag_answer_audit_review`

价值：

- 线上审计结果结构化。
- 可以统计真实线上 required facts / citation / unit accuracy。

### 10.4 Replay 回放

接口：

```http
POST /api/rag/replay
```

价值：

- 用同一个 trace 在新代码上重跑。
- 验证修复是否有效。
- 支持将线上坏例沉淀到离线评测集。

### 10.5 闭环链路

```mermaid
flowchart TD
  A[线上回答] --> B[保存 trace]
  B --> C[用户反馈]
  B --> D[人工审计]
  C --> E[bad case 队列]
  D --> E
  E --> F[定位：检索/prompt/生成/校验]
  F --> G[修复代码或规则]
  G --> H[replay 回放]
  H --> I[沉淀回归集]
```

---

## 11. 指标变化总表

| 阶段 | 指标 | 结果 | 说明 |
|---|---:|---:|---|
| full 检索 | Recall@5 | 79.76% | Top-5 排序未达 90% |
| full 检索 | MRR@5 | 70.79% | 正确证据排序偏后 |
| full 检索 | nDCG@10 | 71.15% | Top-10 排序质量一般 |
| full 检索 | Recall@10 | 85.71% | Top-6 到 Top-10 有较多正确证据 |
| full 检索 | Recall@30 | 96.43% | 候选召回能力够 |
| 修复前生成 | required facts | 97.62% | multi_hop-003 失败 |
| 修复前生成 | numeric/unit | 97.62% | multi_hop-003 失败 |
| 修复前生成 | citation support | 97.62% | multi_hop-003 引用不支撑结论 |
| 修复后生成 | required facts | 100% | full 50 reviewed |
| 修复后生成 | numeric/unit | 100% | basic-004 量纲变体已修 |
| 修复后生成 | citation support | 100% | 引用均可支撑 |

---

## 12. 面试/答辩可讲的 bad case

### 12.1 multi_hop-003：检索到了但生成没用上

问题：

```text
广州市花都区人民医院和花东镇人民政府的车辆维修保养服务合同金额分别是多少，合计金额是多少？
```

失败原因：

- 正确证据已进入 prompt。
- 但排在发票证据之后。
- 模型误以为只有发票金额，没有合同金额。

修复：

- 合同金额问题中，业绩/汇总表前置。
- 发票证据降权。

结果：

```text
20万元 + 40万元 = 60万元
```

这说明我定位到的问题不是“检索不到”，而是“prompt evidence 排序不合理”。

### 12.2 basic-004：prompt 无法保证量纲稳定

问题：

```text
最高分是单年得分的几倍？
```

失败表达：

```text
20分÷4分/年=5倍
```

修复：

- 程序化量纲校验。
- 错误反馈重试。

结果：

```text
20分÷4分=5
```

这说明我没有只靠 prompt，而是把可确定的问题下沉为程序校验。

### 12.3 unanswerable-006：评测标签范围冲突

问题：

```text
花都区域一般车辆维保项目规定的质量保证期具体是多长时间？
```

原标签：未提及。

full-corpus 下实际有证据：

- 整车/总成：20000 公里或 100 日。
- 二级维护：5000 公里或 30 日。
- 一级维护/小修/专项修理：2000 公里或 10 日。

处理：

- 不把模型回答当错。
- 标记为 gold label scope issue。
- 用 `waived_failure_buckets` 从 failure_table 中豁免。

这说明我区分了模型错误和评测集标签问题。

---

## 13. 目前短板

### 13.1 Top-5 排序仍不够强

当前最大短板仍是：

```text
Recall@5 = 79.76%
```

如果面试官只看检索排序，这个数字不够漂亮。

本轮解决方案是生产链路用 Top-30 绕过 Top-5 截断，但这不代表 Top-5 排序问题已经解决。

### 13.2 结构化信息还没有充分参与 rerank

项目已有结构化解析、source unit、document table、table cell、parent-child chunk。

但排序阶段还没有完全做到：

- 问“合同金额”时强制匹配“合同金额”列。
- 问“服务期限”时优先服务期列，而非签订日期/验收日期。
- 问“评分项”时识别评分项行和分值列。

当前合同金额前置是针对性规则，还不是通用结构化 reranker。

### 13.3 还没有真正 cross-encoder reranker

目前排序主要依赖融合、规则和 prompt 内重排。

下一轮如果要把 Recall@5 推到 90%，需要引入真正的 rerank：

- cross-encoder reranker，例如 bge-reranker-v2-m3；
- 或轻量 LLM reranker；
- 或结构化特征 reranker。

### 13.4 线上审计还缺前端页面

后端接口已经具备：

- trace
- feedback
- audit
- replay

但运营/审核人员还需要一个界面来查看：

- 问题
- 答案
- 引用证据
- Top-30 候选
- prompt evidence
- 用户反馈
- 审计表单
- replay 对比

---

## 14. 下一轮优化方向

### 14.1 引入 cross-encoder rerank

目标：把 Recall@5 从 `79.76%` 推到 `85%~90%+`。

方案：

1. 保持当前 Top-30 召回池。
2. 对 Top-30 使用 cross-encoder 打分。
3. 重排后取 Top-5 / Top-10。
4. 继续保留 Top-30 进入生成，以防 rerank 漏召。

评估指标：

- Recall@5
- MRR@5
- nDCG@10
- full-corpus generation accuracy
- P95 latency

风险：

- rerank 增加延迟。
- 中文办公文档表格场景，通用 reranker 不一定理解列关系。

### 14.2 做结构化表格 reranker

比通用 reranker 更贴合当前数据。

特征可以包括：

- query 是否包含合同金额、发票金额、服务期限、质量保证期、评分项。
- chunk/parent 是否包含对应列名。
- 主体名称是否同一行出现。
- 金额单位是否与问题匹配。
- 是否为发票扫描件。
- 是否为业绩汇总表。
- 是否为合同正文条款。

目标：

- 合同金额题优先合同金额列。
- 服务期限题优先服务期列。
- 发票题优先发票累计金额。
- 评分题优先评分标准表。

### 14.3 增加线上审计面板

后端已有接口，下一步补前端或后台工具。

页面建议：

- trace 列表
- 低置信度过滤
- 用户差评过滤
- evidence 对比
- 审计表单
- replay 按钮
- 修复前后答案 diff

### 14.4 扩大评测集

当前 50 条适合迭代，但要更稳定，需要扩大：

- 100~200 条 full-corpus 样本。
- 更多合同金额/发票金额混淆题。
- 更多服务期限 vs 签订日期题。
- 更多表格行列定位题。
- 更多 unanswerable 标签范围冲突题。

### 14.5 加入延迟和成本指标

生产 Agent 不只看准确率。

下一轮需要记录：

- P50/P95 latency
- prompt token 数
- completion token 数
- rerank latency
- retry rate
- 单次 query 成本

---

## 15. 最终答辩口径

如果需要在面试或答辩中总结，可以这样说：

> 这个项目的难点不是简单向量检索，而是强格式化办公文档中的结构化证据定位。第一轮 full-corpus Recall@5 只有 79.76%，但 Recall@30 达到 96.43%，说明候选召回够，瓶颈在排序和生成阶段证据选择。我没有直接把 Top-5 当生产边界，而是把生产链路改成 Top-30 evidence pool，再在 prompt 内做结构化重排、父块补充、合同金额证据前置、发票证据降权，并加入结构化输出、citation 校验、数值量纲校验和失败重试。最终在 50 条真实模型 full-corpus 复测中，人工复核 required facts、numeric/unit、citation support 都达到 100%。同时我补了 trace、feedback、audit、replay 接口，使线上坏例可以回流和回放。当前短板是 Top-5 排序仍只有 79.76%，下一轮会引入 cross-encoder 和结构化表格 reranker，把 Top-5 排序指标推到 90% 附近。

---

## 16. 结论

本轮 RAG 的完成闭环包括三层：

1. **生成闭环**：Top-30 证据池 + prompt 重排 + 结构化生成 + 校验重试，full-corpus 50 条人工复核达到 100%。
2. **评测闭环**：检索指标、Prompt Evidence Recall、人工 review JSON、waived buckets、真实模型回放齐全。
3. **生产质量闭环**：trace、feedback、audit、replay 接口已补齐。

仍未完成的是：

- Top-5 排序指标未达到 90%。
- 结构化表格信息尚未充分进入 rerank。
- 线上审计界面还未产品化。

下一轮核心目标：

> 在保持 Top-30 生产生成准确率的同时，通过 cross-encoder rerank + 结构化表格 reranker，把 Recall@5 从 79.76% 提升到 85%~90%+。
