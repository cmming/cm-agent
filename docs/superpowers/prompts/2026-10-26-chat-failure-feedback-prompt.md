# 会话失败反馈执行与问答提示词

## 当前修订 R1（自主模式，优先适用）

先读取本工作包[清单](../checklists/2026-10-26-chat-failure-feedback-checklist.md)、[设计](../specs/2026-10-26-chat-failure-feedback-design.md)、[计划](../plans/2026-10-26-chat-failure-feedback.md)、[实现](../implementation/2026-10-26-chat-failure-feedback-implementation-design.md)、[账本](../progress/2026-10-26-chat-failure-feedback-ledger.md)。默认只围绕当前文档问答，不自动实施、提交或重启服务。

在已有授权范围内完成追加任务 T5～T6：模型 HTTP 失败保留状态码和结构化错误说明；响应正文仅提取 error.message/code/type 或顶层 message/code，脱敏、限长后写入原 errorMessage。OpenAI 与 DashScope 的异常访问方式以实际 2.0.2 依赖源码为据；不把异常 message/toString 当作响应透传，不强制加载可选 Provider 类型。即时结果及历史 Run 的 errorCode/errorId 从失败状态与 Run ID 派生，避免新增数据库字段；模型失败日志使用相同编号。

继续保留 R0 的消息反馈、只读详情查询、防重放和迟到隔离。抑制实际核实会泄露原始响应的第三方 logger，由适配器输出安全日志；保留用户已有配置，只新增必要日志级别。不得输出配置中的密钥或把其纳入本次提交。

使用 JDK 21 执行 ModelFailureSummaryTest、ReAct/Harness RuntimeContract、ModelFactory、OpenCodeGoRequest、RunRecordTest、RunControllerTest、ErrorDiagnosticLoggerTest，以及 console Node 回归。只选择不使用数据库/容器的测试；如扩展容器验证须遵守 Rocky 要求。交付明确说明 HTTP/SSE/历史查询证据、真实模型未验证边界、旧记录不能恢复、后端重启要求和按钮只读性质。

## R0 原提示词（历史，范围已由 R1 扩展）

模式：自主；修订 R0。先读取[清单](../checklists/2026-10-26-chat-failure-feedback-checklist.md)、[设计](../specs/2026-10-26-chat-failure-feedback-design.md)、[计划](../plans/2026-10-26-chat-failure-feedback.md)、[实际实现](../implementation/2026-10-26-chat-failure-feedback-implementation-design.md)和[账本](../progress/2026-10-26-chat-failure-feedback-ledger.md)，按当前证据回答问题。读取本提示词本身不授权重新实施、提交、重启服务或调用真实模型。

如已得到独立实施授权，按 T1→T2→T3→T4 修复会话运行失败不可见问题：核对 `app.js` 的发送、审批恢复和轮询，只有 SUCCEEDED 显示成功；FAILED 必须展示服务端安全原因及运行编号，没有 assistant 正文也要有反馈。历史消息通过 runStatuses 标记失败，用户按需 GET 原运行查询原因；禁止自动重放消息、审批或工具。

保留未提交修改、中文控制台架构、权限与租户边界。仅使用既有 API；原因由服务端安全字段提供，不能从原始异常推测，不伪造 errorCode/errorId。输出通过纯文本写入，不能拼接 HTML。异步响应须复核会话、登录代次与节点存活。其他 v2 入口需同步共享 JS 版本，防止跨页加载旧脚本。

运行 `node --test cm-agent-console/src/test/js/*.test.cjs`，以及 JDK 21 下 `mvn -q -pl cm-agent-console -am test`。用既有本地模拟服务覆盖 FAILED + assistantMessage=null、刷新历史原因、审批失败、轮询失败和迟到响应，并检查 1366×768、390×844。模拟不等于真实模型验收；登录不足时如实记录，不读取或写入真实凭据。容器/数据库验证若后续涉及，遵守 AGENTS.md 的 Rocky 环境要求。

完成后更新同一组六文档和发布说明，报告实际结果、限制、未提交状态及本提示词路径；遇到不确定写入只查询确认，不盲目重试。
