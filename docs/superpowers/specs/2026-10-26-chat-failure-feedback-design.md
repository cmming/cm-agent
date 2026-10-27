# 会话失败反馈设计

## 当前修订 R1（自主模式）

新增后端目标 T5：不再把已知模型 Provider HTTP 失败统一压成“Agent 运行失败”，向前端返回实际 HTTP 状态和可安全展示的结构化错误说明。T6 验证立即返回、SSE completed 与“查看失败原因”的历史 GET 使用同一持久化 errorMessage 与错误编号。

采用 `ModelFailureSummary` 处理真实异常链：只读 error.message/code/type 或顶层 message/code；最大解析 32768 字符，脱敏后正文最多 512 个 Unicode 字符。去掉当前实际 API Key、凭据赋值、Bearer/JWT、URL、常见宿主路径和堆栈；其他响应字段不返回。HTML、损坏或过大的响应明确兜底，不能复制异常消息。

AgentScope 2.0.2 的 ModelHttpException 只承诺状态，OpenAIException 另有公开 responseBody；DashScopeHttpClient.DashScopeHttpException 未实现该接口但有公开状态与正文方法。仅对已核实的类型反射读取，维持可选 Provider 依赖。失败的 AgentRunResult/RunRecord 派生 errorCode=RUNTIME_EXECUTION_FAILED、errorId=runId；不改构造器、数据库列或历史迁移。

模型失败在适配器转换边界记录一次结构化安全日志；Server 的 JdkHttpTransport logger 保留 ERROR、DashScope Provider 原始日志关闭，避免完整响应绕过脱敏。R1 因此扩展了 R0 的“无后端与配置变更”边界；不改变租户权限、审计、模型重试、停止和审批优先级。

非目标：不恢复旧记录从未保存的上游响应，不透传完整原始载荷，不自动重启服务，不以本地 Stub 冒充真实供应商验收。按钮仍是只读查询；新版本生效后新失败记录才有更具体的说明。

## R0 原设计与验收（历史）

修订 R0；模式：自主。

## 背景与目标

用户提供的运行结果为 FAILED、空 output、无 assistant 消息。原页面将 completed 当作成功，消息刷新后只有用户消息，无法辨认运行已失败。目标是让失败在输入区与对应消息之后均可见，并保留服务器提供的安全原因和关联标识。

## 方案与范围

1. T1 核对发送、轮询与审批恢复。completed 仅代表流结束；只有 SUCCEEDED 提示回答完成。停止、拒绝、等待审批及未知状态分别显示中性提示。
2. T2 在 `console-core.js` 提供统一终态文案；`app.js` 三条结束路径统一使用。FAILED 显示 errorMessage，缺失时提供中性原因说明；有 errorCode/code、errorId 时沿用，否则只显示 runId/id，不伪造诊断字段。
3. 历史消息依据既有 runStatuses，在同一 Run 的最后一条消息后插入独立失败反馈。没有 assistant 消息也成立，不写入消息仓库或模型上下文。历史原因按需查询，避免为每条失败自动发起请求；查询失败保留错误与只读重试入口。
4. T3 使用模拟接口和真实前端控制函数回归，T4 维护发布说明、版本及工作包。

## 约束与非目标

保留受控工作台外观、既有 API、权限、租户、审计和停止/审批协议。此任务不调查特定 Run 的底层模型失败，不扩展后端诊断合同，不改数据库或配置，不重启当前服务。纯文本渲染；异步详情校验登录代次、Agent、会话与节点存活。真实原因仍取决于后端已有安全说明。

## 验收

FAILED + null assistant 不再显示成功；消息区含失败与运行编号；刷新后仍能看到失败并读取原因；原始标签作为文本显示；迟到结果不改写其他会话；没有额外写入重放；桌面及手机输入和错误提示可用。真实登录及模型调用结果须与模拟证据分别报告。

关联：[清单](../checklists/2026-10-26-chat-failure-feedback-checklist.md)、[计划](../plans/2026-10-26-chat-failure-feedback.md)、[实现](../implementation/2026-10-26-chat-failure-feedback-implementation-design.md)、[账本](../progress/2026-10-26-chat-failure-feedback-ledger.md)、[提示词](../prompts/2026-10-26-chat-failure-feedback-prompt.md)。
