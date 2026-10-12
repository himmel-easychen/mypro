# RAG 生成模型独立接入豆包

后续三项实测已完成：指定文档 50 条、全库 50 条、上下文干扰 30 次，全部调用成功，但内容质量闭环未通过。完整结果见 [三项评测报告](rag-doubao-three-scope-eval-2026-09-17.md)。下文两题冒烟保留为接入历史记录。

RAG 的 AnswerService 使用独立的 RagGenerationGateway，调用火山方舟 Responses 接口。原 ModelGateway / WriterAgent 继续使用既有对话模型配置；Qwen Embedding 配置保持不变。两条生成入口拥有独立的限流与熔断状态。

配置位于 backend/src/main/resources/application.yml 的 workpro.model.rag-generation：

- provider：ark-responses
- base-url：https://ark.cn-beijing.volces.com/api/v3
- model：doubao-seed-2-1-pro-260915
- thinking：disabled
- max-tokens：4096（请求字段 max_output_tokens）
- 连接超时10秒，读取超时90秒，不自动重试。
- 密钥：WORKPRO_RAG_GENERATION_API_KEY 环境变量。本机已配置为 Windows 用户环境变量，并显式注入本轮后端进程；后续已有终端/IDE需要重新加载环境变量或重启。代码和报告不保存密钥。

输入只发送 RAG 文本提示词，使用 input / input_text，不携带示例图片。解析只读取 output 中 assistant message 的 output_text，跳过 reasoning 项；不完整、空正文响应返回失败，不冒充有效回答。

## 调用验证

短请求健康检查返回 completed / OK。默认思考模式对同一免费拖车提示词约88秒返回 incomplete、无正文；关闭思考后直连约14秒返回 completed，明确回答免费，并引用现场处理建议与24小时免费拖车承诺。该对比不能解释原模型的所有失败，但说明本次豆包需要显式配置生成模式。

定向测试覆盖 Responses 请求结构、正文提取、未完成/空响应拒绝、RAG失败不影响对话模型配置及熔断，以及 AnswerService 和普通RAG路由。构建成功。

后端真实两题冒烟结果另存 wd/rag-doubao-smoke-2026-09-17.json。这两题不代替完整50条闭环验收；上一轮未通过报告仍保留。

实际结果：两条均返回OK，模型耗时5650ms和2737ms。免费拖车回答与引用通过；岗位题取到完整父表，却把17×1+5×2误算为30，并将岗位写成人数，内容验收不通过。该错误归于生成数值/单位与引用支持，不是检索缺失。逐条复核见 wd/rag-doubao-smoke-review-2026-09-17.json，离线汇总见 wd/rag-doubao-smoke-reviewed-2026-09-17.json。模型接入成功不等于闭环业务验收通过。
