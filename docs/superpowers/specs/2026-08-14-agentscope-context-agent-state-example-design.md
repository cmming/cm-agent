# AgentScope 上下文与 AgentState 示例设计

## 背景

现有 `cm-agent-examples/dashscope-mcp-agent` 已有 `McpStreamableHttpExample`，其展示了使用 DashScope `qwen3.7-plus` 模型进行交互式 MCP 工具调用。该模块尚未单独展示 AgentScope 2.0 的 `RuntimeContext`、`AgentState` 与状态存储机制。

## 目标

新增与 `McpStreamableHttpExample` 相同包路径的独立可运行示例，复用其 DashScope 模型构建方式，演示：

- 单个 `ReActAgent` 实例以 `(userId, sessionId)` 为索引维护不同会话；
- 同一会话的第二次调用能读取第一次调用留下的对话上下文；
- 不同 `sessionId` 的上下文彼此隔离；
- `AgentState` 的读取、JSON 序列化与恢复，以及 `clearContext` 的清空效果；
- `RuntimeContext` 的 `requestId` 为单次调用元数据，不用作持久化状态寻址。

## 范围与非目标

本次仅新增示例类和本组过程文档，不修改既有 MCP 示例、Maven 依赖、生产模块、数据库或服务端配置。示例使用 `InMemoryAgentStateStore`，不承担跨进程持久化与生产多副本恢复的职责。

## 方案

`ContextAndAgentStateExample` 创建一个 `InMemoryAgentStateStore` 并注入 `ReActAgent`。每次调用新建携带固定 `userId`、目标 `sessionId` 与不同 `requestId` 的 `RuntimeContext`：先在 `preference-session` 连续调用两次，再在 `isolated-session` 调用一次；每段调用后使用 `agent.getAgentState(userId, sessionId)` 输出上下文数量，并经 `AgentState.toJson()` / `fromJsonString(...)` 验证快照可恢复。最后调用 `clearContext` 观察第一段会话的对话上下文被清除。

模型配置沿用 `McpStreamableHttpExample` 的 `DashScopeChatModel.builder()`、`qwen3.7-plus`、流式开关和 `DashScopeChatFormatter`；API Key 改为仅从 `DASHSCOPE_API_KEY` 环境变量读取，避免新增任何硬编码凭据。

## 约束

- 所有新增代码注释与文档使用中文。
- `InMemoryAgentStateStore` 仅用于单进程演示，进程退出后状态会丢失；生产多实例场景应使用 Redis 等分布式实现。
- 真实模型调用需要可用的 DashScope API Key 与网络，不纳入无网络自动化测试。

## 验收标准

- Maven 可在 JDK 21 下完成本示例模块及其依赖模块的跳过测试打包。
- 未设置 `DASHSCOPE_API_KEY` 时示例给出中文提示并安全退出。
- 配置有效 API Key 后，示例控制台可展示同会话延续、不同会话隔离、状态序列化恢复和清空上下文结果。
