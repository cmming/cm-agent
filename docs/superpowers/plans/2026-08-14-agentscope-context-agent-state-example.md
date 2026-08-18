# AgentScope 上下文与 AgentState 示例实施计划

对应设计：[上下文与 AgentState 示例设计](../specs/2026-08-14-agentscope-context-agent-state-example-design.md)。

## 任务拆分

1. 在 `cm-agent-examples/dashscope-mcp-agent/src/main/java/com/cmagent/examples/mcpagent` 新增 `ContextAndAgentStateExample`。
   - 使用与 `McpStreamableHttpExample` 一致的 DashScope 模型构建器和默认模型名。
   - 使用环境变量读取 API Key，并配置 `InMemoryAgentStateStore`。
2. 在 `main` 中构造两个不同 `sessionId` 的 `RuntimeContext`，验证同会话延续与不同会话隔离。
3. 增加状态读取、JSON 恢复与 `clearContext` 展示逻辑，说明状态存储限制。
4. 在 JDK 21 环境下执行 Maven 跳过测试打包；真实模型调用因需要用户凭据与外部网络，单独记录验证状态。

## 涉及文件

- 新增：`cm-agent-examples/dashscope-mcp-agent/src/main/java/com/cmagent/examples/mcpagent/ContextAndAgentStateExample.java`
- 新增：本组 `specs`、`plans`、`implementation`、`progress` 文档。

## 验证命令

```powershell
$env:JAVA_HOME='F:\java21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl cm-agent-examples/dashscope-mcp-agent -am "-DskipTests" package
```

手动运行（需要有效的 `DASHSCOPE_API_KEY`）：

```powershell
mvn -pl cm-agent-examples/dashscope-mcp-agent exec:java '-Dexec.mainClass=com.cmagent.examples.mcpagent.ContextAndAgentStateExample'
```
