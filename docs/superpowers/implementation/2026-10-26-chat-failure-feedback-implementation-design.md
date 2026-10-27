# 会话失败反馈实际实现

## 当前修订 R1（自主模式，已纳入本次本地提交，未推送）

- T5：`ModelFailureSummary` 从 AgentScope 实际 HTTP 异常链提取状态与白名单错误说明，执行脱敏和限长。`AgentScopeReActExecutor` 的已知 Provider 失败分支保存该摘要，ReAct/Harness 共用；模型调用失败日志包含 errorId/runId、错误码、可信 tenant、主体、Agent 与安全原因。
- core 的 `AgentRunResult#getErrorCode/getErrorId` 与 `RunRecord` 同名方法从 FAILED 和已有 Run ID 派生响应元数据。Jackson 自动输出 getter 属性，JDBC 继续保存原 errorMessage；没有新增数据库列或构造器参数。
- 同步问答与 SSE completed 仍返回既有运行结果包装，模型业务失败通过 run.status=FAILED 表达，不把外部 HTTP 状态误当作本系统认证失败。后续 GET 原 Run 返回相同说明；R0 前端已支持新字段，无需再次改动 UI。
- `application.yml` 仅新增 logger 级别，保留已有配置：JdkHttpTransport 的 WARN 包含原 URL/响应，保留 ERROR；DashScope Provider 原日志可能含 SSE 原文/堆栈，关闭该包日志，由适配器统一安全诊断。嵌入式宿主需自行采用同样策略。
- T6：新增 ModelFailureSummaryTest，扩展真实本地 HTTP Provider 合同、错误日志脱敏、core 元数据、HTTP/SSE 与历史 GET 一致性测试；原脚本、权限、审计和停止测试继续保留。最终数量见账本 R1。

R0 的“只修改前端”是历史交付范围，R1 已按用户追加要求扩展到上述后端与日志配置。旧 Run 无法凭空恢复上游原始响应；新后端需重启生效。2026-10-27 按用户要求纳入本地提交，未推送或重启当前运行服务，提交与独立副本验证见账本。

## R0 已交付实现（历史）

修订 R0；模式：自主；提交状态：未提交。

## 已落地

- T1：核实 SSE completed 可以携带 FAILED；失败没有 assistant 正文符合现有运行合同。原发送与轮询错误提示与该合同不一致。
- T2：`cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js` 的 `chatRunOutcome` 对业务状态统一映射，只让 SUCCEEDED 为 success。失败沿用 errorMessage 与可选错误码/编号；兼容结果的 runId 和 RunRecord 的 id。
- `assets/app.js` 的 `showChatRunOutcome` 统一输入区反馈和本轮消息反馈；发送、审批恢复和轮询全部使用。审批恢复后的反馈颜色也匹配真实终态。
- `renderChatMessages` 按 runStatuses，在每个失败 Run 的最后一条已加载消息后插入 `createChatRunFailure`，无 assistant 也能显示。原因按需 GET `/api/agents/{agentId}/conversations/{conversationId}/runs/{runId}`；失败可只读重试；节点移除、退出或切换会话后丢弃迟到结果。
- `assets/chat-stop.css` 添加克制的失败反馈，长编号可折行。v2 全部九个 HTML 入口同步 app.js 2.0.35、console-core.js 2.0.17，聊天错误样式 1.0.1；保持跨页入口一致。
- T3：新增 chat-failure.test.cjs；扩展真实发送、历史渲染、详情读取、SSE error、轮询与审批终态测试。模拟服务加入既有失败历史及“模拟失败”输入场景。
- T4：更新发布说明与本六份工作包。保留任务开始前已有的 UI、README、配置等改动。

## 边界与差异

与方案一致，无后端 API、HTTP 状态码、日志、数据库或配置变更。前端不会额外构造服务端 errorId，也无法解释仅有“Agent 运行失败”的具体底层原因。错误反馈使用纯文本，不把标签解释为 HTML。历史失败的详细原因需要点击读取，不对整页历史批量请求。

结果：[账本](../progress/2026-10-26-chat-failure-feedback-ledger.md)。其余：[清单](../checklists/2026-10-26-chat-failure-feedback-checklist.md)、[提示词](../prompts/2026-10-26-chat-failure-feedback-prompt.md)、[设计](../specs/2026-10-26-chat-failure-feedback-design.md)、[计划](../plans/2026-10-26-chat-failure-feedback.md)。
