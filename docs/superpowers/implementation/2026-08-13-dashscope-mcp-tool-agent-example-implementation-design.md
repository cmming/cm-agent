# AgentScope + DashScope + MCP 时间工具示例实现说明

## 1. 对应任务

本文对应 [AgentScope + DashScope + MCP 时间工具示例设计](../specs/2026-08-13-dashscope-mcp-tool-agent-example-design.md) 与 [实施计划](../plans/2026-08-13-dashscope-mcp-tool-agent-example.md)。交付内容为新增示例模块 `cm-agent-examples/dashscope-mcp-agent`，包含一个可直接运行的 `main` 方法，演示 AgentScope Java 智能体经 MCP Streamable HTTP 客户端调用外部时间查询工具，并由阿里云百炼 DashScope `qwen3.7-plus` 模型驱动完成一次问答。

## 2. 代码定位

- 模块：`cm-agent-examples/dashscope-mcp-agent`
- 模块 POM：`cm-agent-examples/dashscope-mcp-agent/pom.xml`
- 示例主类：`cm-agent-examples/dashscope-mcp-agent/src/main/java/com/cmagent/examples/mcpagent/DashScopeMcpToolExample.java`
- 聚合模块声明：`cm-agent-examples/pom.xml`

## 3. 调用链与关键代码位置

```text
DashScopeMcpToolExample.main
  → buildMcpClient()
      McpClientBuilder.create("time-mcp-server")
          .streamableHttpTransport("http://localhost:8088/api/mcp")
          .timeout(...).initializationTimeout(...)
          .buildSync()
  → buildAgent(McpClientWrapper)
      Toolkit toolkit = new Toolkit();
      toolkit.registerMcpClient(mcpClient).block();
      DashScopeChatModel.builder().apiKey(...).modelName("qwen3.7-plus")...build();
      ReActAgent.builder().name(...).sysPrompt(...).model(...).toolkit(...).maxIters(5).build();
  → agent.call("现在几点了？...", RuntimeContext).block()
  → 打印 Msg.getTextContent()
```

`Toolkit#registerMcpClient(McpClientWrapper)` 内部会先调用 `wrapper.initialize()`，再拉取 `listTools()` 并逐个注册为 AgentScope 工具，因此示例代码无需单独调用 `mcpClient.initialize()`。该行为已通过反编译 `agentscope-core-2.0.0.jar` 中 `McpClientManager.registerMcpClient` 的字节码确认。

`McpClientBuilder.streamableHttpTransport(String url)` 接受完整 URL（含路径），内部通过 `HttpTransportConfig` 自动拆分 base 地址与 endpoint 路径，因此可以直接传入 `http://localhost:8088/api/mcp`，无需手动拆分 base URL 与 endpoint。

`DashScopeChatModel.builder()` 未显式调用 `.baseUrl(...)` 时，底层 `DashScopeHttpClient` 会回退到官方默认地址 `https://dashscope.aliyuncs.com`，已通过反编译确认，因此示例只设置 `apiKey`、`modelName`、`stream`、`defaultOptions`。

## 4. 资源管理与异常处理

`McpClientWrapper` 与 `ReActAgent` 均实现 `AutoCloseable`，示例使用 try-with-resources 在同一语句中声明两个资源，保证 MCP 连接与智能体状态在示例结束后正常释放。捕获顶层异常后仅打印可读中文提示与堆栈，便于本地排查 MCP 连接失败、鉴权失败或模型调用异常；示例不做任何审计、脱敏或统一错误码封装，因为它不经过 CM Agent Server 的受治理执行链路，相关规范只适用于生产 Controller/Service，不适用于本纯本地演示程序。

## 5. 与既有示例的差异

`starter-local-tool` 演示 Spring Boot Starter 场景下的 LOCAL 工具注册与调用；`http-tool-client` 演示通过 CM Agent Server 公开 REST API 管理和调试 HTTP 工具。`dashscope-mcp-agent` 与二者均不同：它不依赖 CM Agent Server、不使用 Spring Boot，而是直接使用 AgentScope Java SDK 原生 API，演示"智能体本身作为 MCP 客户端"这一独立集成路径，用于验证模型 + MCP 工具联调的最小闭环。

## 6. 安全提示

示例中的 DashScope API Key 为用户在任务中提供并要求硬编码演示的一次性本地联调凭据；代码注释已明确提示生产场景应改为受控配置或密钥管理服务读取，不应照搬硬编码方式。示例本身不写入任何仓库外持久化存储，也不在日志之外的任何地方回显该 Key。

## 7. 运行期依赖冲突排查与修复

使用者按文档在本机使用真实 MCP 服务实测时，MCP `initialize` 阶段成功（日志可见 `Server response with Protocol: 2025-06-18...`），但注册工具阶段抛出：

```text
java.lang.NoSuchMethodError: 'io.modelcontextprotocol.spec.McpSchema$JsonSchema io.modelcontextprotocol.spec.McpSchema$Tool.inputSchema()'
	at io.agentscope.core.tool.McpClientManager.lambda$registerMcpClient$1(McpClientManager.java:182)
```

### 7.1 根因排查

逐层核对本地 Maven 仓库中的真实构件（而非只看依赖树的 GAV 坐标）定位根因：

1. `mvn -pl cm-agent-examples/dashscope-mcp-agent dependency:tree "-Dincludes=io.modelcontextprotocol.sdk"` 显示 `agentscope-core:2.0.0` 直接依赖 `io.modelcontextprotocol.sdk:mcp:0.17.0`；该 `mcp-0.17.0.jar` 反解压后仅 1850 字节、无任何 `.class`，是纯重定位/聚合 POM。
2. 读取 `mcp-0.17.0.pom` 发现其真正声明的实现依赖是 `mcp-core:0.17.0` 与 `mcp-json-jackson2:0.17.0`（并非 `2.0.0`）。
3. 但父 POM `dependencyManagement` 中 `mcp.version=2.0.0`，把这两个构件在整个 Reactor 内统一管理为 `2.0.0`（该设定是为了配合 `cm-agent-server` 自身 `com.cmagent.server.mcp` 包所使用的官方 MCP Java SDK 2.0.0 API），因此本示例模块最终解析到的实际是 `mcp-core:2.0.0`。
4. 反编译对比两个版本的 `io.modelcontextprotocol.spec.McpSchema$Tool`：
   - `mcp-core:0.17.0`：`public io.modelcontextprotocol.spec.McpSchema$JsonSchema inputSchema()`。
   - `mcp-core:2.0.0`：`public java.util.Map<java.lang.String, java.lang.Object> inputSchema()`。
5. `agentscope-core:2.0.0` 中 `McpClientManager` 的字节码按 `0.17.0` 的返回类型 `JsonSchema` 调用该方法；运行期加载到的却是 `2.0.0` 的 `Tool` 类（返回 `Map`），JVM 方法解析失败，抛出 `NoSuchMethodError`。

结论：`agentscope-core:2.0.0` 与 `mcp-core`/`mcp-json-jackson2` 的 `2.0.0` 版本**二进制不兼容**，必须使用其真正编译所依赖的 `0.17.0`。这是一个仅在真正触发 AgentScope 内置 MCP 客户端能力时才会暴露的潜在项目级依赖冲突，此前 `cm-agent-agentscope-adapter` 未使用该原生 MCP 客户端能力（而是使用自研的 `AgentScopeToolBridge`），因此从未触发。

### 7.2 修复方式与影响范围

只在 `cm-agent-examples/dashscope-mcp-agent/pom.xml` 中显式声明并锁定：

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-core</artifactId>
    <version>0.17.0</version>
</dependency>
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-json-jackson2</artifactId>
    <version>0.17.0</version>
</dependency>
```

模块内直接声明的显式版本会覆盖父 POM `dependencyManagement` 对同一 GAV 的管理版本，因此只影响本模块的依赖解析结果，不改变父 POM 的 `mcp.version` 属性，`cm-agent-server` 自身的 MCP Streamable HTTP Server 实现继续使用 `2.0.0`，两者互不影响。

修复后 `dependency:tree` 确认 `mcp`、`mcp-core`、`mcp-json`、`mcp-json-jackson2` 全部落在 `0.17.0`；`dependency:build-classpath` 确认最终 classpath 中不再出现任何 `mcp-core-2.0.0.jar`/`mcp-json-jackson2-2.0.0.jar`。

### 7.3 遗留排查建议

若未来该模块升级 `agentscope-core` 版本，应重新核对其实际依赖的 MCP SDK 版本（`mvn dependency:tree` 并反编译核心 `McpSchema` 内部类比对字段/方法签名），而不是简单假设与父 POM 当前管理的 `mcp.version` 一致；两者服务于不同代码路径（AgentScope 内置 MCP 客户端 vs. `cm-agent-server` 自建 MCP Server），版本可能长期不同步。

## 8. 验证结果

见 [进度账本](../progress/2026-08-13-dashscope-mcp-tool-agent-example-ledger.md)。

