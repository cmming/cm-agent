# AgentScope + DashScope + MCP 时间工具示例设计

## 1. 背景与目标

`cm-agent-examples` 已经提供 `starter-local-tool`（LOCAL 工具接入）与 `http-tool-client`（HTTP 工具管理 REST 客户端）两个示例，但都没有展示 AgentScope Java 智能体直接以 MCP 客户端身份连接外部 MCP 服务并调用其工具的最小链路。

本次新增一个独立示例模块，演示：

- AgentScope Java 2.0.0 内置的 `McpClientBuilder` 以 Streamable HTTP 协议连接指定 MCP 服务；
- 把该 MCP 服务发布的工具（其中含时间查询工具）注册进 AgentScope `Toolkit`；
- 使用阿里云百炼（DashScope）`qwen3.7-plus` 模型驱动 `ReActAgent`，由模型自主决策调用该时间查询工具并给出自然语言回复；
- 通过一个可直接运行的 `main` 方法完成端到端演示，不依赖 Spring 容器或 CM Agent Server。

## 2. 范围与非目标

### 2.1 本次范围

- 新增 Maven 模块 `cm-agent-examples/dashscope-mcp-agent`，并加入 `cm-agent-examples/pom.xml` 模块列表。
- 提供单个可直接运行的示例类 `DashScopeMcpToolExample`，包含 `main` 方法。
- MCP 服务地址、协议类型、DashScope API Key、模型名称均按需求直接硬编码在示例代码中，不读取外部配置文件或环境变量。
- 示例演示对象为使用方本地已经启动、地址为 `http://localhost:8088/api/mcp`、协议为 Streamable HTTP 的既有 MCP 服务，本次不新增或修改该 MCP 服务本身。

### 2.2 非目标

- 不修改 `cm-agent-core`、`cm-agent-server`、`cm-agent-persistence`、`cm-agent-agentscope-adapter` 或任何生产模块的行为。
- 不新增数据库 Schema、Flyway 迁移或生产配置项。
- 不提供自动化单元测试（示例强依赖本地真实运行的 MCP 服务与真实 DashScope 网络调用，不适合作为无网络隔离的自动化测试）。
- 不在示例之外的任何位置复用或保存本次演示用的 API Key；该 Key 仅用于本地一次性联调，不作为长期生产凭据管理。

## 3. 总体链路

```text
main()
  → 构建 McpClientBuilder（Streamable HTTP，指向 http://localhost:8088/api/mcp）
  → Toolkit.registerMcpClient(...)：内部完成 MCP 初始化、拉取工具列表并逐个注册
  → 构建 DashScopeChatModel（apiKey + modelName=qwen3.7-plus）
  → 构建 ReActAgent（绑定 model + toolkit + 系统提示词）
  → agent.call("现在几点了？", RuntimeContext)
  → 模型按系统提示自动决策调用 MCP 时间查询工具
  → 打印模型最终文本回复
```

## 4. 关键 API 依据

以下 API 均已通过反编译 `agentscope-core-2.0.0.jar` 与 `agentscope-extensions-model-dashscope-2.0.0.jar` 确认存在且签名匹配：

- `io.agentscope.core.tool.mcp.McpClientBuilder`：`create(String)`、`streamableHttpTransport(String)`（接受完整 URL，内部会拆分 base 与 endpoint 路径）、`timeout(Duration)`、`initializationTimeout(Duration)`、`buildSync()`。
- `io.agentscope.core.tool.Toolkit#registerMcpClient(McpClientWrapper)`：反编译字节码确认其内部会先调用 `wrapper.initialize()`，再 `listTools()` 并逐个注册，调用方无需手动初始化。
- `io.agentscope.core.ReActAgent#builder()`：`name`、`sysPrompt`、`model`、`toolkit`、`maxIters` 均为公开方法。
- `io.agentscope.core.ReActAgent#call(String, RuntimeContext)`：返回 `Mono<Msg>`，可通过 `block()` 同步获取结果；`Msg#getTextContent()` 取纯文本回复。
- `io.agentscope.extensions.model.dashscope.DashScopeChatModel.builder()`：`apiKey`、`modelName`、`stream`、`defaultOptions` 均为公开方法；未显式设置 `baseUrl` 时反编译确认默认回退到 `https://dashscope.aliyuncs.com`，无需示例代码显式指定。

## 5. 依赖与构建

- 模块依赖 `io.agentscope:agentscope-core` 与 `io.agentscope:agentscope-extensions-model-dashscope`，版本沿用父 POM `dependencyManagement` 中已声明的 `2.0.0`，不新增或修改父 POM 依赖管理。
- 追加 `org.slf4j:slf4j-simple` 便于本地运行时直接在控制台看到 AgentScope 内部日志，避免出现“未找到 SLF4J Provider”的告警。
- 追加 `org.codehaus.mojo:exec-maven-plugin`，使开发者可以直接使用 `mvn -pl cm-agent-examples/dashscope-mcp-agent exec:java` 运行示例，无需手动拼接 classpath。

## 6. 安全与合规说明

- 示例中的 DashScope API Key 为用户在本次任务中直接提供并要求硬编码演示，属于一次性本地联调场景；文档需要明确提示：真实项目中不应把模型 API Key 硬编码进源码，应改为受控配置或密钥管理服务读取。
- 示例不落库、不写入审计、不经过 CM Agent Server 的租户隔离或权限校验，仅用于验证 AgentScope 客户端到 MCP 服务的最小连通性，不能作为生产工具调用链路的替代。

## 7. 依赖版本冲突与修正（实施中发现）

实施并交由使用者本地实测后发现：`agentscope-core:2.0.0` 实际编译、字节码引用的是 `io.modelcontextprotocol.sdk:mcp:0.17.0`（其自身 POM 声明 `mcp-core`/`mcp-json-jackson2` 均固定为 `0.17.0`）。但父 POM 的 `dependencyManagement` 为配合 `cm-agent-server` 自身的 MCP Streamable HTTP Server 实现，把 `mcp-core`/`mcp-json-jackson2` 统一管理到 `2.0.0`；而 `2.0.0` 版本的 `McpSchema.Tool#inputSchema()` 返回类型已从 `McpSchema.JsonSchema` 变更为 `Map<String,Object>`，与 `agentscope-core` 内部 `McpClientManager` 编译期引用的方法签名不兼容，运行期表现为 `NoSuchMethodError`。

修正方案：只在 `dashscope-mcp-agent` 模块内显式声明 `mcp-core`、`mcp-json-jackson2` 依赖并锁定版本为 `0.17.0`，覆盖父 POM 的全局管理版本；不修改父 POM 的 `mcp.version`（`2.0.0`），因此不影响 `cm-agent-server` 自身 MCP 端点继续使用 `2.0.0`。该修正的详细依据与验证见[实现说明](../implementation/2026-08-13-dashscope-mcp-tool-agent-example-implementation-design.md)与[进度账本](../progress/2026-08-13-dashscope-mcp-tool-agent-example-ledger.md)。

## 8. 验收标准

- `mvn -q -pl cm-agent-examples/dashscope-mcp-agent -am "-DskipTests" package` 编译打包成功。
- 在本地已启动对应 MCP 服务（`http://localhost:8088/api/mcp`，Streamable HTTP）且网络可达 DashScope 的前提下，执行 `mvn -pl cm-agent-examples/dashscope-mcp-agent exec:java` 能够：
  - 打印从 MCP 服务注册到的工具名称列表；
  - 触发模型调用时间查询工具并打印最终文本回复。
- 不影响仓库其余模块的编译与既有测试。

