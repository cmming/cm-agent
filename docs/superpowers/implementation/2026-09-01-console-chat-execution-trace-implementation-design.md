# 会话聊天执行过程展示实现说明

## 关联文档

- [设计说明](../specs/2026-09-01-console-chat-execution-trace-design.md)
- [实施计划](../plans/2026-09-01-console-chat-execution-trace.md)
- [进度账本](../progress/2026-09-01-console-chat-execution-trace-ledger.md)

## 当前状态

实现与定向验证均已完成。开发全程位于 `codex/chat-trace-ui` 分支对应的独立 worktree，
文档基线提交 `4859027` 早于代码开发。

## 最终实现

### Core 合同

- `MessageContentType` 新增 `THINKING`，`MessageContentBlock` 增加对应工厂并限制字段组合；
  用户消息不能伪造思考块，思考文本不计入面向模型的普通文本内容。
- 新增 `AgentProgressEventType` 与 `AgentProgressEvent`，通过构造期校验保证工具事件只能包含
  `replyId`、`toolCallId`、已授权工具名和可选终态，不能夹带参数或结果载荷。
- `AgentRuntime` 增加带进度消费者的默认重载，旧实现继续回退到原结构化执行入口。

### AgentScope 事件映射

- `AgentScopeReActExecutor` 监听 AgentScope Java 2.0.2 的 `ThinkingBlock*Event`，在适配器内部按块聚合，
  仅于块结束后转发完整内容；最终 assistant 快照同时保留 `ThinkingBlock`。
- 工具调用只转发已授权工具的 `ToolCallStart/End` 与 `ToolResultStart/End` 生命周期，明确忽略
  参数增量和工具结果文本增量；结果状态映射到稳定的 `RunStatus`。
- `AgentScopeExecutor` 和 `AgentScopeRuntimeAdapter` 增加向后兼容重载，执行进度与最终文本沿不同通道传递。

### 服务端调用链与持久化

`ConversationController` → `ConversationService` → `RunExecutionService` → `AgentRuntime` 共同增加进度消费者。
`RunExecutionService` 在可信租户、授权工具均已解析后对完整思考内容执行纵深脱敏，再由 Controller 发送
新增的 `progress` SSE 事件。运行完成后，脱敏的 `THINKING`、`TOOL_USE`、`TOOL_RESULT` 与 `TEXT`
仍作为有序内容块保存到既有 `content_blocks_json`，没有新增表、字段或 Flyway 迁移。

`ConversationPromptComposer` 会显式跳过 `THINKING`，因此思考仅供观察和历史回看，不会在续聊时
重新进入模型提示词。工具最终摘要仍沿用原有受治理、脱敏后的内容块语义。

### 控制台交互

- 会话聊天页在流式 assistant 气泡内展开实时“执行过程”，展示思考开始/完成、工具参数准备、
  工具执行开始和成功、失败或拒绝终态；实时工具卡不显示原始参数和原始结果。
- 历史 assistant 消息把连续的思考和工具内容块组合为可折叠轨迹，工具使用与结果按
  `toolCallId` 合并，并展示服务端保存的脱敏输入/结果摘要。
- 最终回答仍独立使用 Markdown 呈现；窄屏下工具摘要改为单列，静态资源版本更新为 `2.0.13`。

## 验证证据

- JDK 21 / Maven 3.9.4 环境下，Core、Console、AgentScope Adapter 联合测试共 137 项通过。
- 服务端 `RunControllerTest` 与 `ConversationPromptComposerTest` 共 34 项通过，覆盖 SSE 进度事件、
  思考脱敏与持久化、旧运行路径兼容和思考不回灌提示词。
- Node 语法检查通过；控制台 JavaScript 测试 39 项通过。
- `git diff --check` 通过，仅输出仓库现有换行符转换提示。

## 与设计的差异

实现遵循设计基线，无范围扩张。实时思考仍采用“完整块结束后一次发送”，没有逐 token 展示；
工具实时轨迹只显示阶段和终态，原始参数、原始结果继续留在治理边界内。历史工具卡使用系统原有的
脱敏摘要，而不是新增一套持久化格式。
