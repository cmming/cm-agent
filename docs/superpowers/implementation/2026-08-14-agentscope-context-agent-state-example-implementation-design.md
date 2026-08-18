# AgentScope 上下文与 AgentState 示例实现说明

对应[设计](../specs/2026-08-14-agentscope-context-agent-state-example-design.md)与[实施计划](../plans/2026-08-14-agentscope-context-agent-state-example.md)。

## 实现位置

- 示例类：`cm-agent-examples/dashscope-mcp-agent/src/main/java/com/cmagent/examples/mcpagent/ContextAndAgentStateExample.java`

## 调用链

```text
main
  → 从 DASHSCOPE_API_KEY 读取凭据
  → 创建 InMemoryAgentStateStore
  → 构建 DashScopeChatModel 与 ReActAgent
  → agent.call(messages, RuntimeContext(userId, sessionId, requestId))
  → AgentScope 自动加载、更新并保存该 (userId, sessionId) 的 AgentState
  → getAgentState → toJson / fromJsonString
  → clearContext → 输出已清空的上下文数量
```

示例中 `preference-session` 连续调用两次，证明同一状态槽位的上下文会被后续调用读取；`isolated-session` 使用同一用户但不同会话标识，展示其上下文独立。`requestId` 通过 `RuntimeContext.put` 传递，仅是本次调用的元数据，不参与状态槽位寻址。

选择 `InMemoryAgentStateStore` 是为了让示例不在本机创建状态文件、且便于进程内观察。该实现不持久化到进程外；生产环境需要跨节点恢复时应替换为 Redis 等分布式状态存储。模型 API Key 不写入源码，必须由环境变量提供。

与原方案没有差异。
