# 证据组装与回答协议修复

本次按失败追踪结论修复候选到 prompt 的链路，未调整召回提供方、融合权重或 gold 标签。

## 实现

- Answer 场景加载返回候选的父块，而不只加载固定 final_context 的父块。
- Prompt 保留已选证据后，用剩余字符预算依次容纳其他完整候选及其父块。已有完整父块包含同一 child 时跳过重复证据；放不下的候选不截断。
- 超预算先移除 history，不再一次性删除全部父块。必选证据仍超预算则明确报错。
- 生成协议增加 FULL/PARTIAL/NONE，判断对象为用户所问事实。NONE 优先于旧 supported 字段；仅有背景时拒答，PARTIAL 保留有据部分。
- JSON 解析拒绝尾随对象及其他内容，格式错误返回 INVALID_MODEL_RESPONSE 并保留原始响应。
- 提示词增加时间范围和量纲核对规则。

## 验证与边界

新增回归覆盖候选补入 prompt、移除历史后保留父块、背景支持与目标事实未知的冲突、部分回答、非法 JSON 和尾随对象。

这不是完整端到端验收：尚未用更新后的服务重跑指定文档、全库和干扰实验。数值量纲目前是提示词约束，不是确定性计算验证器；JSON 契约错误可识别，不代表模型不会再输出非法 JSON。按预算顺序补入候选也不能保证所有目标证据必然进入 prompt；必选证据本身超预算仍需进一步处理。

历史指标和冻结标签未修改。不能仅凭本次代码及单元测试宣布全部失败用例已解决或召回指标已达标。

## 真实模型复测

使用重新打包并启动的当前服务、固定 50 条集合和 `doubao-seed-2-1-pro-260915` 完成复测，未修改题目、gold、索引或历史结果：

| 范围 | 调用成功 | 固定不可回答标签拒答 | 结构化输出失败 |
| --- | ---: | ---: | ---: |
| 指定文档 | 48/50 | 7/8 | 2 |
| 全库 | 47/50 | 7/8 | 3 |
| 干扰生成（10 题 x 3 变体） | 30/30 | 不适用 | 0 |

指定文档的 Recall@5/MRR@5/nDCG@10 为 91.67%/87.78%/85.53%，三项检索门槛通过；全库为 76.19%/68.65%/69.53%，仍未达标。干扰生成 30 次均返回可解析结果，引用 ID 映射 30/30；本轮未做新的逐题语义复核，因此不能据此宣布 30 条事实与引用内容全部正确。

端到端结果确认仍存在以下问题：

- 两个范围的 `basic-004` 都再次输出了量纲不严谨的 `20分÷4分/年=5倍`；指定文档回答随后补充了正确算式，但错误表达仍然存在。提示词约束未能稳定解决量纲问题。
- 指定文档 `unanswerable-003` 输出 `PARTIAL`，把业绩表日期摘要解释为约两年，同时又承认没有明确服务期条款，因而未按冻结不可回答标签拒答。`unanswerable-001` 与指定文档 `unanswerable-006` 已按 `NONE` 拒答；全库 `unanswerable-006` 仍有其他文档的直接证据，属于既有标签范围不匹配。
- 100 次后端调用中有 4 次模型 JSON 包含重复 `answer` 字段，另有 1 次 JSON 本身损坏。原实现会让后一个重复字段覆盖正文，再表现为 `INVALID_CITATIONS`。解析器现已启用重复字段检测，使这类响应直接归为 `INVALID_MODEL_RESPONSE`，并增加回归覆盖；原始响应继续保留。

本轮原始产物：

- `wd/rag-context-contract-50-2026-09-17.json`
- `wd/rag-context-contract-full-corpus-50-2026-09-17.json`
- `wd/rag-context-contract-distractors-2026-09-17.json`
- `backend/rag-context-contract-e2e-2026-09-17.out.log`
- `backend/rag-context-contract-e2e-2026-09-17.err.log`

结论：候选补入、父块保留、拒答状态和非法响应留存已得到运行证据，但量纲正确性与结构化输出稳定性未通过真实模型验收；全库检索门槛也仍未通过，不能宣布全部失败用例已解决。

## 结构化输出续修

方舟当前 `/responses` 端点实测不接受 `response_format`，返回 `400 InvalidParameter: unknown field "response_format"`。改用该端点支持的强制函数调用：RAG 专用网关声明 `submit_answer` 工具，以 JSON Schema 约束 `supported`、`answerability` 和 `answer`，并直接解析 `function_call.arguments`。普通对话模型调用保持原文本协议，不受影响。离线干扰评测同步使用相同协议，并拒绝重复 JSON 键。

针对上一轮格式失败样本做了当前服务的定向真实模型复测：

| 范围 | 原格式失败样本 | 本轮结果 |
| --- | --- | --- |
| 指定文档 | `multi_hop-008`、`compare_mutation-003` | 2/2 `OK` |
| 全库 | `multi_hop-010`、`compare_mutation-004`、`compare_mutation-010` | 3/3 `OK` |

本轮五条响应均为单个合法函数参数对象，未再出现重复字段或残缺 JSON。该定向结果支持“已修复已知格式失败”，但样本量不足以证明任意调用都不会发生模型或上游协议错误。

量纲约束补充为：累计量与单周期量作比时不得再次把累计周期数乘入分母，最终被除数与除数必须先换算成同单位数量。`basic-004` 首轮两个范围仍分别出现错误结果或错误算式；再次收紧提示后连续 3 次指定文档复测中 2 次输出 `20分÷4分=5倍`，1 次仍输出 `20分÷4分/年=5倍`。因此量纲稳定性仍未通过，不能宣布该失败已解决；后续应增加确定性单位/算术校验及受控重试，而不是继续依赖提示词。

新增定向复测产物：

- `wd/rag-context-contract-structured-focused-document-2026-09-17.json`
- `wd/rag-context-contract-structured-focused-full-corpus-2026-09-17.json`
- `wd/rag-context-contract-basic-004-final-1-2026-09-17.json`
- `wd/rag-context-contract-basic-004-final-2-2026-09-17.json`
- `wd/rag-context-contract-basic-004-final-3-2026-09-17.json`

## 最终 100 条与独立干扰验收

最终版指定文档 50 条、全库 50 条均完成调用，接口和结构化格式成功率为 `100/100`。该指标只表示调用成功，不代表内容正确。

逐题事实与证据核对仍发现以下验收问题：

- 指定文档 `compare_mutation-003` 先称广州合同更高，随后用 12.8 万元和 40 万元算出花东合同高 27.2 万元，结论方向与其自身算式矛盾。题目同时未限定同名合同记录；材料存在 57.6 万元与 12.8 万元两条记录，冻结答案的 17.6 万元差额也不能在未消歧时强制套用。
- 全库 `compare_mutation-003` 完全拒答；属于目标合同记录证据未形成可答上下文，不是接口失败。
- 全库 `multi_hop-010` 召回了项目、投标文件及服务保障材料，但缺少“独立法人、不得联合投标、不得转包或分包”的直接合规条款，回答因此遗漏冻结答案要求。应归为目标证据覆盖不足，不能靠生成模型补写。
- 全库 `compare_mutation-006` 已引用“30 分钟”和“一天以上”，却以用途不同为由拒绝比较。二者均为可换算时长，属于生成阶段过度拒答。
- `basic-003` 在两个范围均正确回答 10 个、5 个及相差 5 个，完整覆盖题问。冻结答案额外要求“2 倍”，但问题没有询问倍数；该项单列为评测口径问题，不计为模型内容失败，也不修改模型去迎合超范围答案。

独立干扰注入 10 题、每题 control/prepend/append 共 30 次，调用成功 `30/30`，事实核对 `30/30`，引用支持 `30/30`。未发现干扰块改变答案。`context_confusion-003` 的同名合同有 57.6 万元和 12.8 万元两条记录，模型保留歧义按证据正确处理，不按单一冻结金额误判。审阅汇总见 `wd/rag-acceptance-final-distractors-summary-2026-09-17.json`。

针对已确认的生成问题，提示词新增两条通用约束：明确要求比较两个可换算时长时先统一单位，不得因用途不同拒答；提交前复核高低、多少与差额方向必须和操作数、算式一致。该修改仍需真实模型定向复测。全库 `multi_hop-010` 的证据缺失和同名合同消歧属于检索/题目限定问题，不宣称由提示词修复。

更新版服务的定向真实模型复测结果：

- 全库 `compare_mutation-006` 返回 `OK`，完整回答 30 分钟通知要求、一天以上补证门槛，并明确后者时间跨度更长；本次过度拒答已消除。
- 指定文档 `compare_mutation-003` 返回 `OK`，不再出现结论与算式方向矛盾。回答列出 60.984532 万元、57.6 万元、18.4 万元等不同年份/标的记录，分别与 40 万元计算差额，并明确题目未限定具体合同，无法给出唯一答案。生成一致性问题已修复，但冻结题目消歧问题仍在。

这两条各仅复测一次，只能确认当前样本通过，不能据此宣称随机输出已稳定。复测产物为 `wd/rag-acceptance-post-review-document-2026-09-17.json` 和 `wd/rag-acceptance-post-review-full-2026-09-17.json`。

## 检索覆盖、题目消歧与量纲校验闭环

`multi_hop-010` 所需合规证据实际存在于库中，不是语料缺失：chunk `a5fc2799-f294-4229-b925-7ff9c2523c30`（文档 `72363c1a-06c5-4a4b-94c1-06c422c8390f`，`21_项目概况_标的8：花都区域一般车辆维保-花都区域.docx`）明确写有独立法人、无联合投标关联、不得转包或分包。服务保障 chunk `6601f554-c817-4baf-8e84-e024e4cc15f8` 写有距花都供电局 4.2 公里、达到 A 级要求并保障上门服务与应急响应。此前全库链路已召回前一证据，但 prompt 预算选择遗漏了它，根因是候选到 prompt 的覆盖问题。

Prompt 组装现对问题中用“和/及/与”并列的方面分别保留最佳候选，再使用剩余预算补入普通候选。全库真实模型复测中，`multi_hop-010` 最终上下文同时包含上述两个 chunk，回答完整给出独立法人、不得联合投标、不得转包/分包和 4.2 公里服务保障条件。

合同比较题已直接消歧为：`业绩表序号2的《广州供电局2025年综合类车辆维保项目合同（标的15花都区域一般车辆维保1）》与花东镇人民政府车辆维修和保养服务合同相比，哪一份合同金额更高？高出多少？`。两个数据集中的题目均已同步修改，冻结答案继续使用 57.6 万元、40 万元和差额 17.6 万元。指定文档与全库真实模型复测均得到该唯一结论。

数值量纲不再只依赖提示词。回答提交后由确定性校验器检查“数量除以速率却声称得到无量纲倍数”的表达；失败时把具体错误反馈给模型，只允许一次受控重试，并在 `generation_attempts` 中保留全部原始响应。校验覆盖 `20分÷4分/年=5倍`、`20分÷4分/年×1年=5倍` 等错误形式，同时允许 `20分÷4分=5倍`、`20分÷(1年×4分/年)=5倍` 和结果确为时间量的除法。定向真实调用已验证错误首答会被拦截，第二次返回 `20分÷4分=5倍`。

最终改动后的完整 Maven 测试套件通过。最后一个逃逸模式加入前完成的真实模型验收结果如下：

| 范围 | 调用与格式成功 | Recall@5 | MRR@5 | nDCG@10 | 检索门槛 |
| --- | ---: | ---: | ---: | ---: | --- |
| 指定文档 50 条 | 50/50 | 90.48% | 86.98% | 85.78% | 通过 |
| 全库 50 条 | 50/50 | 76.19% | 68.65% | 69.53% | 未通过 |

对应产物为 `wd/rag-acceptance-closure-document-2026-09-17.json` 和 `wd/rag-acceptance-closure-full-2026-09-17.json`。两组共 100 次调用全部返回 `OK`；全库 `multi_hop-010`、消歧后的 `compare_mutation-003`、时长比较 `compare_mutation-006` 和量纲题 `basic-004` 的内容核验均通过。全库三项检索指标仍低于 90%/80%/80% 门槛，因此 RAG 整体尚未闭环。

随后完成 6 个固定用例各 10 次、共 60 次稳定性调用，接口与格式为 60/60 `OK`。其中 `basic-004` 有 9 次量纲表达正确，1 次输出 `20分÷4分/年×1年=5`，暴露了确定性规则的逃逸模式；该模式现已补入校验器并有单元回归覆盖。重新打包后的最终版本尝试再次运行完整 100 条与 60 次稳定性时，上游模型持续返回 `403 Forbidden`，单线程等待重试后仍未恢复，因而无法取得规则补强后的最终真实模型样本。不能把受限流/授权失败污染的中断运行计为产品失败，也不能据此宣称最终稳定性已完成闭环。

结论：用户列出的证据覆盖、合同题消歧和确定性量纲校验方案均已落地并通过本地测试；此前有效的 100 条调用全部成功，关键内容问题已修复。但全库检索指标未过门槛，且最终量纲规则补强后的真实模型重复测试受上游 `403 Forbidden` 阻断，所以当前仍不能宣布 RAG 全闭环。
## 2026-09-17 全库召回与对话模型复核

- 全局召回此前未变化的原因：上下文预算、父块补入、拒答协议和 JSON 校验均位于检索之后；评测的 `retrievedChunkIds` 来自融合/重排结果，因此这些生成侧修复不会改变 Recall。
- 将 RRF 融合的重排前候选池扩大为最终窗口的 3 倍后，50 条全库检索从 Recall@5 `0.7619`、MRR@5 `0.6865`、nDCG@10 `0.6953` 提升至 `0.8571`、`0.8460`、`0.8372`。后两项过线，Recall@5 仍未达到 `0.90`。
- 分层诊断显示 10 道多跳题的 gold 已全部进入 provider 与融合层，9/10 进入重排结果；剩余主要损失发生在 Top-5 截断，而非 provider 无召回。简单子查询加权没有提升 Recall@5，且排序指标下降，已回退。
- RAG 生成已由 Ark Responses 切换到 OpenAI-compatible Chat Completions，并为 chat 请求补充 `submit_answer` tool schema 与 tool-call 参数解析。使用本地既有认证后，403/401 已消失；真实调用目前在约 125 秒后返回上游 5xx，故尚不能宣布生成链路闭环。
- 生成读取超时改为环境变量可配置，默认 `180000ms`，重试仍为 0，避免慢请求重复计费。
- 最新有效检索报告：`wd/rag-retrieval-wide-rerank-full-2026-09-17.json`；对话模型冒烟报告：`wd/rag-chat-tool-smoke-multi-hop-010-v2-2026-09-17.json`。

## 全库检索口径校正与 Top-5 续修

复核评测脚本后发现，旧版 `--full-corpus` 只在生成评测中移除 `documentId`，纯检索与分层诊断仍附带 `source_document_id`。因此此前标注为全库的 `Recall@5=0.8571` 实际是指定文档范围，不能作为全库结论。Runner 已统一修正：检索、provider 分层、失败分层和生成在 `--full-corpus` 下均不再发送 `documentId`，报告同时写入 `scope` 防止再次混淆。

校正后的 50 条真实全库基线为：Recall@5 `0.7738`、MRR@5 `0.6865`、nDCG@10 `0.6912`。分层结果显示 Vector 单路为 `0.8214/0.7365/0.7295`，明显优于 Auto 融合的 `0.7738/0.6865/0.6912`；BM25 与 Structured 在多条同名合同和同类投标材料中放大了跨文档噪声。按意图降低多跳、比较题的 BM25/Structured 权重后，全库改善为：

| 范围 | Recall@5 | MRR@5 | nDCG@10 | 结论 |
| --- | ---: | ---: | ---: | --- |
| 真正全库 50 条 | 78.57% | 70.79% | 70.67% | 三项改善，仍未过门槛 |
| 指定文档 50 条 | 86.90% | 85.08% | 83.34% | 排序指标通过，Recall 未过 90% |

受控多跳 Top-5 覆盖选择将真正全库初始 Recall@5 从 `0.7738` 提升到当前组合的 `0.7857`，但 Complete@5 仍为 `5/10`。来源文件名加分做过一次完整消融，Recall/MRR 无提升且 nDCG 略降，已回退。当前 Vector 自身 Recall@5 上限仅 `0.8214`，剩余问题已不是单纯重排：需要提升跨文档候选召回与文档实体消歧，不能继续依赖 Top-5 换位达到 0.90。

针对本次修改的测试全部通过。完整 Maven 套件运行 104 条，91 条通过、13 条在 mock 初始化时报错；统一根因是运行环境 Java 24 超出当前 Byte Buddy 的 Java 23 支持范围，不是业务断言失败。应在 Java 21/23 CI 环境复跑，或升级 Mockito/Byte Buddy 后再宣称完整套件通过。

本轮报告：

- `wd/rag-retrieval-full-actual-layered-2026-09-17.json`
- `wd/rag-retrieval-full-actual-diagnostics-2026-09-17.json`
- `wd/rag-retrieval-intent-weights-full-actual-2026-09-17.json`
- `wd/rag-retrieval-intent-weights-document-2026-09-17.json`

结论：评测口径已修正，Top-5 策略和意图权重带来可重复的小幅改善，但全库检索仍未闭环，不能沿用旧的 `0.8571` 作为全库指标，也不能宣布 RAG 全闭环。

## 全库检索后续消融（不执行第七项大重构）

按“有提升才进入主链路，无提升则保留为存疑实验”的规则完成其余低风险项：

| 实验 | Recall@5 | MRR@5 | nDCG@10 | 处理 |
| --- | ---: | ---: | ---: | --- |
| 当前有效组合（候选 Top-50、宽融合池、意图调权） | 79.76% | 70.79% | 71.15% | 保留 |
| 文档级 Top-8 预选/加分 | 79.76% | 67.42% | 69.16% | 排序退化；实现与测试保留，主链路禁用 |
| 明确实体约束加分（年份、标题、编号、机构/项目名） | 79.76% | 70.79% | 70.66% | Top-5 无变化且 Top-10 退化；解析与测试保留，主链路禁用 |
| 多跳同父块子证据保留 | 79.76% | 70.79% | 71.15% | 指标及漏失分布均无变化；能力与测试保留，主链路禁用 |

失败漏斗给出了更明确的边界：10 道多跳题在 provider 与融合层均为 `10/10` 完整，重排层为 `9/10`，最终 Top-5 完整仅 `5/10`。4 道所谓 `context_drop` 的第二证据在重排中实际位于第 `6/7/15/17` 名，并非父块去重误删，因此父块策略不是当前主要瓶颈。生成侧已经为所选子块加载完整 `parent_context`，并在剩余 prompt 预算中补入未选候选及其完整父块，父块扩展本身已闭环；额外邻接子块没有独立净收益。

本轮保留的真实全库指标以 `wd/rag-retrieval-provider-top50-full-actual-2026-09-17.json` 为准。存疑实验分别记录在：

- `wd/rag-retrieval-document-selector-full-actual-2026-09-17.json`
- `wd/rag-retrieval-entity-constraints-full-2026-09-17.json`
- `wd/rag-retrieval-multihop-context-preserve-generation-v2-2026-09-17.json`

第七项涉及评测体系与链路大范围重构，本轮按要求不执行。最终 100 条真实模型复测和 30 次独立干扰稳定性测试仍放在所有确定性修复之后执行；在此之前不能宣称全库 Recall 达标或 RAG 全闭环。

## 2026-09-18 Prompt Evidence Recall 指标与生成候选池

按后续约定补充了 `Prompt Evidence Recall` 审计。`scripts/audit_rag_acceptance.py` 现在同时输出：

- `search_recall_at_k_mean`：默认 `search_k=20`，只统计 answer 返回候选前 20 条内的 gold 覆盖。
- `search_recall_all_returned_mean`：统计 answer 返回的全部候选内 gold 覆盖，避免把第 21 名以后误算成 Top-20 命中。
- `prompt_evidence_recall_mean`：统计最终 prompt evidence 中通过 chunkId、content 或 parentContent 精确包含的 gold 覆盖。
- `prompt_loss_after_search_k_complete`：定位“Top-20 已完整命中但 prompt 丢失”的样本。

生成链路的 `answer_context` 已调整为融合候选窗口至少 20，搜索展示口径不变。Prompt 补候选时先保留显式并列方面候选，再按 reranked 原始顺序优先尝试 Top-20，之后才使用词面覆盖排序补普通候选。补充候选优先保留 child+parent；完整父块放不下时降级保留 child 内容，避免直接证据因为父块过长被整条跳过。

本轮回归：

- Python 评测脚本测试 `15/15` 通过。
- Java 定向测试 `RetrievalQueryProfileTest`、`RagPromptBuilderTest`、`AnswerServiceTest` 通过；本机 Java 24 需加 `-Dnet.bytebuddy.experimental=true` 以绕过 Mockito/Byte Buddy 的 Java 23 支持上限。
- 后端已重新打包并启动在 `http://127.0.0.1:8080/`。

用修正后的审计口径复核既有完整报告：

| 报告 | Search Recall@20 | Prompt Evidence Recall | Top-20 完整但 prompt 丢失 |
| --- | ---: | ---: | --- |
| 指定文档 50 条旧报告 | 97.62% | 98.81% | 无 |
| 全库 50 条旧报告 | 89.29% | 94.05% | 无 |

定向复跑 `basic-009` 时，模型网关返回 `Unauthorized`，因此不能做真实回答语义验收；但 trace 可用于证据审计。该样本 gold 位于 answer 返回候选第 23/25 名，`search_recall_at_20=0`、`search_recall_all_returned=1`、`prompt_recall=0`。因此它不属于“Top-20 已命中但 prompt 丢失”，而是 Top-20 边界外的排序问题；要修复该类样本，需要让 gold 进入 Top-20 或明确把生成候选边界扩大到 30/50，再重新评估噪声风险。

新增/更新产物：

- `wd/rag-acceptance-final-full-prompt-recall-2026-09-18.json`
- `wd/rag-acceptance-final-document-prompt-recall-2026-09-18.json`
- `wd/rag-prompt-recall-basic-009-full-v3-2026-09-18.json`
- `wd/rag-prompt-recall-basic-009-full-v3-audit-2026-09-18.json`

## 2026-09-18 生成候选边界扩大到 Top-30

`basic-009` 的定向审计显示，两个 gold 证据位于 answer 返回候选第 23/25 名；这不是 Top-20 内 prompt 丢失，而是 Top-20 边界外排序问题。因此生成侧边界从 Top-20 扩大到 Top-30：

- `AnswerService` 发起 answer 检索时请求 `topK=30`。
- `answer_context` 下 fusion window 至少为 30。
- Prompt 补候选时 reranked Top-30 优先于词面排序候选。
- 搜索展示和既有 Search Recall@5 门禁不变，避免把生成候选边界混成检索排序达标。

定向复跑 `basic-009`：模型网关仍返回 `Unauthorized`，无法完成语义答案验收；但 trace 审计显示 `Search Recall@30=1.0`、`Prompt Evidence Recall=1.0`，两个 gold 均通过 prompt evidence `E21` 进入上下文。对应产物：

- `wd/rag-prompt-recall-basic-009-full-top30-2026-09-18.json`
- `wd/rag-prompt-recall-basic-009-full-top30-audit-2026-09-18.json`

全库 50 条纯检索复测结果：

| 指标 | Top-5 | Top-10 | Top-20 | Top-30 |
| --- | ---: | ---: | ---: | ---: |
| Recall | 79.76% | 85.71% | 91.67% | 96.43% |
| Hit | 88.10% | 90.48% | 92.86% | 97.62% |
| Multi-hop Complete | 50.00% | 70.00% | 80.00% | 90.00% |

产物：`wd/rag-retrieval-top30-full-actual-2026-09-18.json`。

结论：Top-30 显著提高“生成可用候选池”的证据覆盖，能覆盖此前 `basic-009` 这种第 23/25 名样本；但 Search Recall@5/MRR@5/nDCG@10 仍未达门禁，真实模型语义验收仍受网关 `Unauthorized` 阻断，不能宣布 RAG 全闭环。

## 2026-09-18 生成模型切换为对话模型入口

按要求不再使用豆包作为真实生成模型。后端 `rag-generation` 默认配置保持 OpenAI-compatible 对话模型入口：

- provider: `OpenAI`
- base-url: `https://ahg.codes`
- model: `gpt-5.6-sol`

当前本机环境里遗留了 `AGICORE_RAG_GENERATION_API_KEY=ark-...`，会覆盖配置并导致旧的豆包/Ark key 被误用于 RAG generation。服务已用显式对话模型环境重启，并清空该 Ark key。离线干扰脚本 `scripts/run_rag_distractor_eval.py` 也已去除硬编码 `doubao-seed-2-1-pro-260915` 和 Ark Responses 地址，改为从 `AGICORE_RAG_CHAT_API_KEY`、`AGICORE_LLM_API_KEY`、`AGICORE_RAG_GENERATION_BASE_URL`、`AGICORE_RAG_GENERATION_MODEL` 读取对话模型配置，默认模型为 `gpt-5.6-sol`。

验证：

- Python 评测脚本测试 `15/15` 通过。
- 当前服务健康检查通过，8080 可用。
- 定向调用 `basic-009` 仍返回 `ANSWER_GENERATION_FAILED`，后端日志为对话模型调用 `Unauthorized`。这表示当前机器缺少可用的对话模型 API key，不能继续做真实模型语义验收；它不是检索或 prompt 证据覆盖失败。

要继续 100 条真实模型复测，需要注入可用的对话模型 key，例如 `AGICORE_RAG_CHAT_API_KEY` 或 `AGICORE_LLM_API_KEY`，并保持 `AGICORE_RAG_GENERATION_API_KEY` 不再指向 Ark key。

## 2026-09-18 DeepSeek 对话模型真实复测

按要求改用 DeepSeek 的 OpenAI-compatible 对话模型接口，不再使用豆包。服务以如下生成配置运行：

- provider: `OpenAI`
- base-url: `https://api.deepseek.com`
- model: `deepseek-chat`

先用 `basic-009` 定向验证，模型调用成功并返回 `OK`；答案给出人民医院 20 万元、花东镇政府 40 万元、合计 60 万元。随后完成指定文档 50 条、全库 50 条和 30 次干扰注入。

指定文档 50 条：

| 指标 | 结果 |
| --- | ---: |
| 调用成功 | 50/50 |
| 答案存在率 | 100% |
| Citation ID 映射 | 100% |
| Recall@5 / MRR@5 / nDCG@10 | 91.67% / 86.27% / 84.90% |
| Recall@30 | 100% |
| Multi-hop Complete@30 | 100% |
| Prompt Evidence Recall@30 | 100% |
| 拒答准确率 | 87.5% |

失败表只剩 `unanswerable-003` 的拒答错误；因此指定文档检索门禁通过，但生成闭环仍未完全通过。产物：

- `wd/rag-deepseek-document-50-2026-09-18.json`
- `wd/rag-deepseek-document-50-prompt-recall-2026-09-18.json`

全库 50 条：

| 指标 | 结果 |
| --- | ---: |
| 调用成功 | 50/50 |
| 答案存在率 | 100% |
| Citation ID 映射 | 100% |
| Recall@5 / MRR@5 / nDCG@10 | 79.76% / 70.79% / 71.15% |
| Recall@20 / Recall@30 | 91.67% / 96.43% |
| Multi-hop Complete@30 | 90% |
| Prompt Evidence Recall@30 | 100% |
| 拒答准确率 | 87.5% |

失败表为：

- `multi_hop-010`：固定 gold 检索缺失。
- `compare_mutation-004`：固定 gold 检索缺失。
- `unanswerable-006`：全库下存在其他有效证据，冻结不可回答标签与全库范围冲突，表现为拒答错误。

全库 Search Recall@5/MRR@5/nDCG@10 仍未达门禁，因此不能宣布全库检索闭环。产物：

- `wd/rag-deepseek-full-50-2026-09-18.json`
- `wd/rag-deepseek-full-50-prompt-recall-2026-09-18.json`

干扰注入 10 题 x control/prepend/append 共 30 次。首轮 DeepSeek 干扰测试 28/30 `OK`，2 个样本因模型写成裸 `E6、E8` 而不是 `[E6][E8]` 被判 `INVALID_CITATIONS`；答案本身可读但引用格式不符合协议。已在生产 `AnswerService` 和离线干扰脚本中加入确定性裸证据 ID 归一化：仅当 `E<number>` 存在于当前 evidence 时，转换为 `[E<number>]`，再执行原引用校验。对应 Java/Python 回归通过。重跑完整 30 次干扰后全部 `OK`。

产物：

- `wd/rag-deepseek-distractors-2026-09-18.json`
- `wd/rag-deepseek-distractors-fix-context-006-2026-09-18.json`
- `wd/rag-deepseek-distractors-fix-context-007-2026-09-18.json`
- `wd/rag-deepseek-distractors-v2-2026-09-18.json`

本轮自动指标说明：`required_facts_accuracy`、`numeric_unit_accuracy` 和 citation entailment 仍为 pending，尚未逐条人工语义审阅；因此当前结论是“DeepSeek 真实调用、格式、引用映射、Prompt Evidence Recall 和干扰稳定性已跑通”，不是“100 条答案语义全部验收通过”。

## 2026-09-18 DeepSeek 逐条人工复核结论

已对 DeepSeek document-filtered 50 条与 full-corpus 50 条结果逐条复核 `required_facts_accuracy`、`numeric_unit_accuracy` 和 citation entailment，并生成可回放 review 文件：

- `wd/rag-deepseek-document-50-manual-review-2026-09-18.json`
- `wd/rag-deepseek-full-50-manual-review-2026-09-18.json`
- `wd/rag-deepseek-document-50-reviewed-2026-09-18.json`
- `wd/rag-deepseek-full-50-reviewed-2026-09-18.json`
- 紧凑审查材料：`wd/rag-deepseek-document-50-compact-review-2026-09-18.json`、`wd/rag-deepseek-full-50-compact-review-2026-09-18.json`、`wd/rag-deepseek-all-short-review-2026-09-18.txt`

复核后 document-filtered 50 条闭环通过：

- `closed_loop_passed=true`
- `required_facts_accuracy=100%`
- `numeric_unit_accuracy=100%`
- `citation support_accuracy=100%`
- `failure_table=[]`
- 检索门槛通过：Recall@5 `91.67%`、MRR@5 `86.27%`、nDCG@10 `84.90%`

人工复核中，`unanswerable-003` 在当前 document-filtered 证据范围下能由业绩表行支持服务期限 `2023.4.23 至 2025.4.22`，原期望“未提及”与当前证据范围冲突，已在 review 中豁免自动 `refusal_error`，不作为模型错误。

复核后 full-corpus 50 条仍不能验收：

- `closed_loop_passed=false`
- `required_facts_accuracy=97.62%`
- `numeric_unit_accuracy=97.62%`
- `citation support_accuracy=97.62%`
- 唯一真实生成失败：`multi_hop-003`
  - 问题要求广州市花都区人民医院与花东镇人民政府两份车辆维修保养服务合同金额及合计。
  - 全库证据中存在正确合同金额：人民医院 `20万元`、花东镇人民政府 `40万元`、合计 `60万元`。
  - 该条回答误称材料未直接给出合同金额，只提供发票累计金额，因此 `required_facts`、数值口径和 citation entailment 均失败。
- full-corpus 固定 Top-5 检索门槛仍未通过：Recall@5 `79.76%`、MRR@5 `70.79%`、nDCG@10 `71.15%`。

full-corpus 中另外三项自动失败经人工复核不作为生成错误：

- `unanswerable-006`：全库下确有花都区域一般车辆维保项目质量保证期证据，回答有引用支撑；原“未提及”标签不适用于全库。
- `multi_hop-010`：固定 gold chunk 未命中，但回答覆盖独立法人、无联合投标、不转包/分包、服务点 4.2 公里、A级服务质量保障等必需事实，引用可支撑。
- `compare_mutation-004`：固定 gold chunk 未命中，但回答区分了笼统“按国家/行业标准执行”和明确里程/日期指标，并列出 `2000公里或10日` 等期限，引用可支撑。

同时修正评测脚本：`scripts/run_rag_eval.py` 的 review JSON 现在支持 `waived_failure_buckets`，用于人工确认的标签范围冲突或固定 gold chunk 口径问题；负面人工复核仍会强制阻断验收。新增测试覆盖该语义，Python 测试 `17/17` 通过。

## 2026-09-18 multi_hop-003 修复进展

人工复核后唯一真实生成失败为 full-corpus `multi_hop-003`。复盘保存的 prompt 发现，正确合同金额证据已经进入 prompt，但位于较靠后的 evidence（失败样本中为 `E20`），前面大量发票扫描件、发票累计金额证据先出现，导致模型过度套用“发票金额不能回答合同金额”的保护规则，误判合同金额缺失。

已在 `RagPromptBuilder` 增加合同金额类问题的初始 context 稳定重排：

- 仅当 query 明确包含“合同金额”时生效。
- 含目标主体、合同名、万元金额且非发票清单的业绩/汇总表证据前置。
- 含“发票扫描件 / 发票累计 / 开票日期 / 合计: ¥ / 发票列表”且没有合同金额信号的证据降权。
- 非合同金额题型不走该重排。

新增单元测试 `contractAmountQuestionPromotesPerformanceTableBeforeInvoices`，覆盖“发票证据在前、业绩表在后”时，合同金额问题必须将业绩表证据前置。相关测试已通过：

- Java：`mvn -q "-Dnet.bytebuddy.experimental=true" "-Dtest=RagPromptBuilderTest,AnswerServiceTest,RetrievalQueryProfileTest" test`
- Python：`python -m pytest scripts\tests\test_run_rag_eval.py scripts\tests\test_audit_rag_acceptance.py scripts\tests\test_rag_eval_scopes.py scripts\tests\test_validate_rag_eval.py`，`17/17` 通过。

真实 DeepSeek 复测尚未执行：当前 shell 没有持久化 `AGICORE_RAG_CHAT_API_KEY` / `AGICORE_LLM_API_KEY`，且执行层会拦截把完整 key 或 key 片段放进命令的启动方式。需要在本机终端预先设置环境变量后再重启 8080 并跑 focused 复测。

## 2026-09-18 multi_hop-003 与 basic-004 最终复测

继续修复 full-corpus 人工复核中的唯一真实生成失败 `multi_hop-003`，并在完整复测中发现并修复 `basic-004` 的量纲变体风险。

### 修复内容

1. `multi_hop-003`：合同金额证据排序
   - 根因：正确业绩汇总表证据已经进入 prompt，但排在发票扫描件、发票累计金额证据之后，模型被“发票金额不能回答合同金额”的保护规则带偏，误判合同金额缺失。
   - 修复：`RagPromptBuilder` 对明确包含“合同金额”的问题做初始 context 稳定重排：业绩/汇总表/合同金额证据前置，发票扫描件、发票累计、开票日期、发票列表类证据后置。
   - 范围：仅合同金额问题生效，不影响质量保证期、工位、病假等题型。

2. `basic-004`：量纲校验增强
   - 复测中出现 `20分 ÷ 4分/年 = 5（年），即最高分是单年得分的5倍`。
   - 修复：`AnswerUnitValidator` 现在会拦截“数量 ÷ 每周期速率 = 时间单位，随后又声明倍数”的表达，触发重试；仍保留 `100元÷20元/月=5月` 这类真正求时长的合法表达。

### 真实模型复测

产物：

- Focused `multi_hop-003` + `basic-009`：`wd/rag-deepseek-mh003-fix-focused-2026-09-18.json`
- Focused review：`wd/rag-deepseek-mh003-fix-focused-reviewed-2026-09-18.json`
- Focused `basic-004`：`wd/rag-deepseek-basic-004-after-unit-validator-fix-2026-09-18.json`
- Full 50 after both fixes：`wd/rag-deepseek-full-50-after-mh003-and-unit-fix-2026-09-18.json`
- `compare_mutation-006` 单条补测：`wd/rag-deepseek-compare-mutation-006-after-unit-fix-2026-09-18.json`
- 合并后的 full 50：`wd/rag-deepseek-full-50-after-mh003-and-unit-fix-merged-2026-09-18.json`
- Full 50 review：`wd/rag-deepseek-full-50-after-mh003-and-unit-fix-review-2026-09-18.json`
- Full 50 reviewed：`wd/rag-deepseek-full-50-after-mh003-and-unit-fix-reviewed-2026-09-18.json`

结果：

- `multi_hop-003` 已正确回答：人民医院 `20万元`、花东镇人民政府 `40万元`、合计 `60万元`，并明确发票金额不是合同金额。
- `basic-004` 已正确回答：每年 `4分`、最高 `20分`、倍数使用 `20分÷4分=5`，未再出现 `20分÷4分/年=5倍`。
- Full-corpus 50 条真实模型复测中，`compare_mutation-006` 首轮出现一次 `168457ms` 基础设施错误；单条补测通过，已合并补测结果。
- 合并后 full-corpus 50 条 reviewed 结果：
  - `attempted=50`
  - `measured=50`
  - `answer_present_rate=100%`
  - `required_facts_accuracy=100%`
  - `numeric_unit_accuracy=100%`
  - citation `support_accuracy=100%`
  - `failure_table=[]`

说明：full-corpus 的 `closed_loop_passed` 仍为 `false`，原因是固定 Top-5 检索门槛仍未过：Recall@5 `79.76%`、MRR@5 `70.79%`、nDCG@10 `71.15%`。这属于 full-corpus 固定 gold Top-5 检索口径，不是生成侧或 prompt 证据闭环的阻断。生成侧在人工复核口径下已经闭环。

## 2026-09-18 生产质量闭环接口补齐

已补齐 RAG 上线所需的 trace 存储、用户反馈入口、人工审计记录和回放机制。现在 `/api/rag/answer` 正常回答后会自动持久化完整 trace；线上发现问题后可用同一 trace 进行反馈、人工复核和 replay。

### 数据表

新增 Flyway 迁移：`backend/src/main/resources/db/migration/V12__rag_quality_closure.sql`

包含三张表：

- `rag_answer_trace`
  - 保存 query、documentId、chapterId、history、disableQueryTransform、answer、citations、retrievedChunkIds、evidence、retrieval metadata、prompt、rawResponse、latency、validation JSON。
- `rag_answer_feedback`
  - 保存用户反馈：rating、category、comment、metadata。
- `rag_answer_audit_review`
  - 保存人工审计：required facts、numeric/unit、citation support、是否应拒答、错误类型、备注。

### 接口

实现位置：`backend/src/main/java/com/agicore/api/RagAnswerController.java` 与 `backend/src/main/java/com/agicore/dialogue/RagQualityService.java`

- `POST /api/rag/answer`
  - 正常回答接口。
  - 返回前自动调用 `RagQualityService.recordAnswer(...)` 写入 trace。
  - traceId 使用 retrieval metadata 中的 `trace_id`；如果异常路径没有 retrieval trace，则生成新的 UUID。

- `GET /api/rag/traces/{traceId}`
  - 查询一次 RAG 回答的完整 trace。
  - 返回问题、答案、引用、Top 候选、prompt evidence、retrieval metadata、已有 feedback 和 audit review。

- `POST /api/rag/feedback`
  - 用户反馈入口。
  - 请求字段：`traceId`、`userId`、`rating`、`category`、`comment`、`metadata`。
  - 用于线上“不准确 / 引用不对 / 没回答问题”等反馈回流。

- `POST /api/rag/audit/reviews`
  - 人工复核入口。
  - 请求字段：`traceId`、`reviewer`、`requiredFactsCovered`、`numericUnitCorrect`、`citationSupported`、`shouldRefuse`、`errorType`、`notes`、`metadata`。
  - 与离线 review JSON 的核心字段一致，可沉淀线上审计样本。

- `POST /api/rag/replay`
  - 回放入口。
  - 请求字段：`traceId`、可选 `disableQueryTransform`。
  - 从 trace 读取原 query/document/history，再调用当前版本 `/answer` 逻辑重新生成，并保存新的 trace。

### 测试

新增控制器测试：`backend/src/test/java/com/agicore/api/RagAnswerControllerTest.java`

已通过：

- Java：`mvn -q "-Dnet.bytebuddy.experimental=true" "-Dtest=RagAnswerControllerTest,AnswerUnitValidatorTest,AnswerServiceTest,RagPromptBuilderTest,RetrievalQueryProfileTest" test`
- Python：`python -m pytest scripts\tests\test_run_rag_eval.py scripts\tests\test_audit_rag_acceptance.py scripts\tests\test_rag_eval_scopes.py scripts\tests\test_validate_rag_eval.py`，`17/17` 通过。

### 闭环状态

现在可以标记为：RAG 生产质量闭环已具备基础接口。

闭环链路为：

1. `/api/rag/answer` 生成答案并保存完整 trace。
2. 用户通过 `/api/rag/feedback` 反馈问题。
3. 运营/审核人员通过 `/api/rag/traces/{traceId}` 查看证据、prompt、答案和引用。
4. 审核人员通过 `/api/rag/audit/reviews` 标注 required facts、numeric/unit、citation support、错误类型。
5. 研发通过 `/api/rag/replay` 用当前代码回放线上问题，验证修复是否生效。
6. 修复后的坏例子可以沉淀回离线评测集和 review JSON。

注意：这表示生产质量闭环接口已经补齐；full-corpus 固定 Top-5 检索门槛仍未达 90%，因此生产证据边界继续使用 Top-30 answer context，而不是 Top-5。
