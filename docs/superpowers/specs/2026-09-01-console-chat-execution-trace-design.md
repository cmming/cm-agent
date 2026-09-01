# 会话聊天执行过程展示设计说明

## 背景

控制台 v2 的会话聊天页已经支持历史消息、文本流式回答和最终工具摘要，但当前浏览器只能收到
`started`、`message-started`、`delta`、`completed`、`error` 事件。AgentScope 运行时实际还会产生
`ThinkingBlock`、工具调用和工具结果生命周期事件，这些信息在服务端适配层被消费后没有进入会话页，
使用者无法判断 Agent 正在思考、准备调用工具还是等待工具返回。

AgentScope Studio 的项目页会把 thinking block 和工具消息放入聊天消息流，Trace 页则按调用顺序展示
Agent、模型和工具节点的输入、输出、状态与耗时。本项目采用相同的“消息正文 + 可折叠执行轨迹”信息层级，
但继续遵守 CM Agent 的多租户、工具治理和脱敏边界，不复制 Studio 的服务端、OpenTelemetry 存储或原始载荷展示。

参考资料：

- [AgentScope Studio 项目与消息协议](https://github.com/agentscope-ai/agentscope-studio/blob/main/docs/tutorial/en/develop/project.md)
- [AgentScope Studio 运行追踪](https://github.com/agentscope-ai/agentscope-studio/blob/main/docs/tutorial/en/develop/tracing.md)
- [AgentScope Java 消息与事件](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/message-and-event.md)

## 目标

- 在 `/console/v2/chat.html` 的 assistant 消息中展示模型实际产生的思考块，并明确无思考块时不伪造内容。
- 在当前运行中实时展示工具调用的准备、执行和终态；运行完成后可从持久化会话消息回看受控摘要。
- 以类似 AgentScope Studio 的可折叠执行轨迹降低主回答噪声，同时保留执行顺序和状态辨识度。
- 复用现有会话 SSE、消息 JSON 持久化、运行记录和工具治理链，不新增数据库表或外部追踪服务。
- 保持前端错误码、错误编号、认证 Cookie、权限、租户隔离、审计和脱敏行为不变。

## 范围

### 领域与运行时事件

1. 新增 `THINKING` 会话内容块，只允许 assistant 消息持有；其文本不会参与会话历史提示词拼接。
2. 新增受控执行进度事件，覆盖：
   - 思考开始；
   - 一段思考完成；
   - 工具调用开始；
   - 工具参数准备完成；
   - 工具执行开始；
   - 工具执行结束及其终态。
3. AgentScope 适配器订阅 2.0.2 已验证存在的 `ThinkingBlock*Event`、`ToolCall*Event` 和
   `ToolResult*Event`。思考增量先按 `replyId + blockId` 聚合，块结束后才越过适配层；工具参数增量和
   工具原始结果增量不离开适配器。
4. AgentScope 最终 `ThinkingBlock` 映射为 `THINKING` 内容块；最终工具块继续只从已经过治理的
   `ToolCallRecord` 读取输入、输出和错误摘要。

### SSE 协议

在既有事件序列中增加可重复的 `progress` 事件：

```text
started
progress: THINKING_STARTED
progress: THINKING_COMPLETED
progress: TOOL_CALL_STARTED
progress: TOOL_CALL_COMPLETED
progress: TOOL_EXECUTION_STARTED
progress: TOOL_EXECUTION_COMPLETED
message-started / delta
completed | error
```

`progress` 数据包含事件类型以及必要的 `replyId`、`blockId`、`toolCallId`、`toolName`、
`content`、`status`。字段按事件类型选填；`content` 只用于完整思考块，必须在运行编排边界再次脱敏。
工具事件不携带模型原始参数或工具原始输出。

旧客户端会忽略未知 SSE 事件，因此新增 `progress` 不改变既有 `delta`、`completed` 和 `error` 合同。
不支持结构化进度的替代 Runtime 继续通过默认方法运行，只是不产生 `progress`。

### 页面交互

assistant 消息由两个区域组成：

```text
┌ Agent ──────────────────────────────────────┐
│ ▾ 执行过程 · 2 个步骤 · 已完成              │
│   ◉ 思考过程          已完成                 │
│     [可折叠的已脱敏思考文本]                 │
│   ⬡ search_orders     执行成功 · 128 ms*     │
│     输入摘要 / 输出摘要 / 错误摘要           │
│                                             │
│ 最终回答 Markdown                           │
└─────────────────────────────────────────────┘
```

`*` 当前会话消息内容块不保存耗时；只有接口实际返回耗时时才展示，不由前端估算。

- 流式运行期间执行过程默认展开，按事件到达顺序更新节点状态；最终回答继续使用现有 Markdown 流式区。
- 历史消息按内容块原始顺序重建轨迹。相同 `toolCallId` 的 `TOOL_USE` 和 `TOOL_RESULT` 合并为一张工具卡片。
- 运行完成后沿用现有重新加载消息的机制，以服务端持久化快照替换临时轨迹。
- 思考过程默认折叠并标注“模型提供”；页面不把普通回答文本包装成思考过程。
- JSON 摘要使用既有安全格式化规则，所有非 Markdown 载荷通过 `textContent` 写入 DOM。
- 小屏幕下执行轨迹保持单列，长内容在卡片内部滚动，不横向撑破聊天布局。

## 安全与数据边界

- `tenantId`、Agent、会话和工具归属仍只来自认证主体及服务端已校验对象，客户端不能提交或覆盖。
- 思考文本可能包含用户输入或模型复述，必须经 `SensitiveDataRedactor` 脱敏后才能进入 SSE、响应和持久化。
- 工具调用过程只展示工具名称、阶段和终态；模型生成的原始参数 JSON、工具原始响应、异常堆栈、内部 URL、
  Secret、Token、API Key、Authorization 和 Cookie 均不得进入 `progress`。
- 最终工具输入、输出和错误只使用现有 `ToolCallRecord` 受控摘要，不能回退读取 AgentScope 原始内容。
- `THINKING` 不参与 `ConversationPromptComposer` 的文本投影，避免把上一轮隐藏推理作为下一轮用户上下文重放。
- 不支持 thinking 的模型或 Provider 只展示工具过程和最终回答；页面不得显示“空思考”占位来暗示不存在的推理。

## 非目标

- 不接入 AgentScope Studio 服务、OpenTelemetry/OTLP、Trace 数据库或跨运行调用树。
- 不展示模型请求原文、系统提示词、完整上下文、token 使用量、原始工具参数或原始工具响应。
- 不新增思考开关、工具确认、人工介入、运行取消、重试、会话编辑或消息删除。
- 不修改 Flyway 迁移；`messages.content_blocks_json` 已能保存新增 JSON 枚举值。
- 不把历史思考文本重新发送给模型，也不承诺所有模型都会返回思考内容。

## 兼容性与约束

- Java 代码以 AgentScope `2.0.2` 本地依赖字节码和官方文档为依据；升级框架时需复核事件 getter 与终态枚举映射。
- `MessageContentType` 新增枚举值会出现在会话消息 JSON 中；本仓库 v2 前端同步支持，旧前端若按未知类型兜底则忽略。
- 现有消息 JSON 不需要迁移；新版本读取旧消息不受影响，回滚到不认识 `THINKING` 的版本前需评估已写入消息的反序列化兼容性。
- 当前本机默认 Maven 使用 JDK 17，不满足项目 Java 21 要求；开发验证前必须定位 JDK 21，无法定位时在进度账本和最终输出中明确未执行项。

## 验收标准

1. 支持 thinking 的运行在发送期间出现“正在思考”，思考块结束后展示经脱敏的可折叠文本；不支持时不伪造。
2. 工具调用按准备、执行、成功/失败/拒绝/中断顺序更新，且 SSE 中不包含原始参数和原始结果。
3. 运行完成并刷新后，思考块、工具摘要和最终回答仍按服务端消息顺序可回看。
4. USER/SYSTEM 不能构造 `THINKING`；thinking 不进入历史提示词文本投影。
5. 旧 Runtime 不实现新进度能力时仍可完成会话，旧 SSE 客户端仍可消费文本和终态。
6. 前端动态内容不使用 `innerHTML`；错误仍显示脱敏中文原因、`errorCode` 和 `errorId`。
7. Core、AgentScope adapter、console 和 server 的针对性测试通过；构建使用 JDK 21。

## 关联文档

- [实施计划](../plans/2026-09-01-console-chat-execution-trace.md)
- [实现说明](../implementation/2026-09-01-console-chat-execution-trace-implementation-design.md)
- [进度账本](../progress/2026-09-01-console-chat-execution-trace-ledger.md)
