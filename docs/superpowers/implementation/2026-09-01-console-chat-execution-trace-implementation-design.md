# 会话聊天执行过程展示实现说明

## 关联文档

- [设计说明](../specs/2026-09-01-console-chat-execution-trace-design.md)
- [实施计划](../plans/2026-09-01-console-chat-execution-trace.md)
- [进度账本](../progress/2026-09-01-console-chat-execution-trace-ledger.md)

## 当前状态

实现与定向验证均已完成。开发全程位于 `codex/chat-trace-ui` 分支对应的独立 worktree，
文档基线提交 `4859027` 早于代码开发。

2026-09-02 收到真实工具调用在聊天页不显示、且需展示调用入参和返回值的反馈。经复核，原实现错误地
假设 AgentScope 最终消息始终带有工具内容块；本次先更新设计，再以工具桥接器实际调用为数据来源修复。

## 最终实现

### Core 合同

- `MessageContentType` 新增 `THINKING`，`MessageContentBlock` 增加对应工厂并限制字段组合；
  用户消息不能伪造思考块，思考文本不计入面向模型的普通文本内容。
- 新增 `AgentProgressEventType` 与 `AgentProgressEvent`，通过构造期校验保证工具事件的输入、输出与耗时
  只出现在允许的生命周期阶段，并与思考文本使用不同字段。
- `AgentRuntime` 增加带进度消费者的默认重载，旧实现继续回退到原结构化执行入口。

### AgentScope 事件映射

- `AgentScopeReActExecutor` 监听 AgentScope Java 2.0.2 的 `ThinkingBlock*Event`，在适配器内部按块聚合，
  仅于块结束后转发完整内容；最终 assistant 快照同时保留 `ThinkingBlock`。
- 工具调用改为由 `AgentScopeToolBridge` 在进入受治理网关前、后直接发送，因此不受最终 AgentScope 消息
  是否保留工具块影响；输入 JSON、输出/错误、耗时与结果状态映射到稳定的 `RunStatus`。
- `AgentScopeExecutor` 和 `AgentScopeRuntimeAdapter` 增加向后兼容重载，执行进度与最终文本沿不同通道传递。

### 服务端调用链与持久化

`ConversationController` → `ConversationService` → `RunExecutionService` → `AgentRuntime` 共同增加进度消费者。
`RunExecutionService` 在可信租户、授权工具均已解析后对完整思考内容、工具调用入参及返回值执行纵深脱敏
和限长，再由 Controller 发送新增的 `progress` SSE 事件。运行完成后，脱敏的 `THINKING`、`TOOL_USE`、`TOOL_RESULT` 与 `TEXT`
仍作为有序内容块保存到既有 `content_blocks_json`，没有新增表、字段或 Flyway 迁移。

`ConversationPromptComposer` 会显式跳过 `THINKING`，因此思考仅供观察和历史回看，不会在续聊时
重新进入模型提示词。工具最终摘要仍沿用原有受治理、脱敏后的内容块语义。

### 控制台交互

- 会话聊天页在流式 assistant 气泡内展开实时“执行过程”，展示思考开始/完成、工具参数准备、
  工具执行开始和成功、失败或拒绝终态，以及已脱敏、限长的调用入参、返回值、错误与耗时。
- 历史 assistant 消息把连续的思考和工具内容块组合为可折叠轨迹，工具使用与结果按
  `toolCallId` 合并，并展示服务端保存的脱敏输入/结果摘要。
- 最终回答仍独立使用 Markdown 呈现；窄屏下工具摘要改为单列，静态资源版本更新为 `2.0.14`。
- 工具调用的未预期异常日志仅保留异常类型和不含异常消息的调用栈位置，避免工具入参或上游响应被
  拼接进异常消息后写入日志。

## 验证证据

- JDK 21 / Maven 3.9.4 环境下，`cm-agent-core` 测试 71 项通过；
  `AgentScopeRuntimeAdapterTest` 与 `AgentScopeToolBridgeTest` 测试 30 项通过，覆盖桥接器事件顺序、
  最终消息缺少工具块时的历史快照以及异常日志不泄露消息。
- `ConsoleResourceTest` 12 项通过；`RunControllerTest` 与 `SensitiveDataRedactorTest` 32 项通过，覆盖
  SSE 与持久化内容块中的工具入参、返回值、耗时和 JSON 风格 Token/API Key 脱敏。
- `node --check` 通过；`node --test cm-agent-console/src/test/js/console-core.test.cjs` 共 39 项通过，
  覆盖聊天页消费 `progress.input`、`progress.output` 和脱敏展示标签。
- `git diff --check` 通过，仅输出仓库现有换行符转换提示。

## 与设计的差异

原实现与设计假设存在偏差：AgentScope 的最终消息可能不包含已经实际执行的工具块，导致前端没有可展示
记录。本次修复将工具事件源切换为受治理的桥接器，并把展示值严格限定为脱敏、限长快照；不会展示未经
处理的原始参数或响应。实时思考仍采用“完整块结束后一次发送”，没有逐 token 展示。
