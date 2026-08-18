# AgentScope + DashScope + MCP 时间工具示例实施计划

对应设计：[AgentScope + DashScope + MCP 时间工具示例设计](../specs/2026-08-13-dashscope-mcp-tool-agent-example-design.md)

**Goal:** 在 `cm-agent-examples` 下新增一个可直接用 `main` 方法运行的示例模块，演示 AgentScope `ReActAgent` 通过 Streamable HTTP MCP 客户端调用本地时间查询工具，并由 DashScope `qwen3.7-plus` 模型驱动完成一次问答。

**Tech Stack:** Java 21、Maven（父 POM `dependencyManagement`）、`agentscope-core:2.0.0`、`agentscope-extensions-model-dashscope:2.0.0`（内置 MCP Java SDK 2.0.0 客户端能力）。

## Global Constraints

- 只新增 `cm-agent-examples/dashscope-mcp-agent` 模块及 `cm-agent-examples/pom.xml` 的模块声明，不修改其他生产模块。
- 示例地址、模型名称、API Key 按用户要求直接硬编码，注释中需说明生产环境应改用受控配置。
- 不新增自动化测试（该示例强依赖本地真实 MCP 服务与真实外部模型网络调用）。
- 新增/修改的注释、文档使用中文。

---

## 文件结构

### 修改

- `cm-agent-examples/pom.xml`：新增 `dashscope-mcp-agent` 模块声明。
- `README.md`：在生产文档列表补充示例入口说明（若未来纳入正式文档索引，可在发布说明中体现，避免遗漏）。
- `docs/release-notes.md`：记录新增示例，说明不改变生产 API/数据库行为。

### 新增

- `cm-agent-examples/dashscope-mcp-agent/pom.xml`：模块依赖与 `exec-maven-plugin` 配置。
- `cm-agent-examples/dashscope-mcp-agent/src/main/java/com/cmagent/examples/mcpagent/DashScopeMcpToolExample.java`：示例主类，包含 `main` 方法、MCP 客户端构建、Toolkit 注册、DashScope 模型构建、`ReActAgent` 构建与调用。

---

### Task 1：新增示例模块骨架

**Files:**
- Modify: `cm-agent-examples/pom.xml`
- Create: `cm-agent-examples/dashscope-mcp-agent/pom.xml`

**Steps:**
- [x] 在 `cm-agent-examples/pom.xml` 的 `<modules>` 中追加 `dashscope-mcp-agent`。
- [x] 创建模块 POM，`parent` 指向 `cm-agent-examples`，依赖 `io.agentscope:agentscope-core`、`io.agentscope:agentscope-extensions-model-dashscope`（版本沿用父级 `dependencyManagement`），追加 `org.slf4j:slf4j-simple` 与 `org.codehaus.mojo:exec-maven-plugin`（配置 `mainClass`）。

**Verify:**

```powershell
mvn -q -pl cm-agent-examples/dashscope-mcp-agent -am dependency:resolve
```

Expected: 依赖解析成功，无 `agentscope-extensions-model-dashscope` 缺失报错。

---

### Task 2：实现示例主类

**Files:**
- Create: `cm-agent-examples/dashscope-mcp-agent/src/main/java/com/cmagent/examples/mcpagent/DashScopeMcpToolExample.java`

**Interfaces:**
- `buildMcpClient()`：使用 `McpClientBuilder.create(name).streamableHttpTransport(MCP_SERVER_URL).buildSync()` 构建 `McpClientWrapper`。
- `buildAgent(McpClientWrapper)`：构建 `Toolkit` 并调用 `registerMcpClient(...).block()`；构建 `DashScopeChatModel`；构建绑定该 `Toolkit` 与模型的 `ReActAgent`。
- `main(String[])`：使用 try-with-resources 管理 `McpClientWrapper` 与 `ReActAgent` 两个 `AutoCloseable` 资源，调用 `agent.call(String, RuntimeContext)` 并打印回复文本。

**Steps:**
- [x] 硬编码 MCP 地址 `http://localhost:8088/api/mcp`、模型名 `qwen3.7-plus`、DashScope API Key。
- [x] 编写系统提示词，明确要求模型遇到时间类问题必须调用工具而非凭空回答。
- [x] 使用 `RuntimeContext.builder().userId(...).sessionId(...).build()` 构造运行上下文。
- [x] 打印 `toolkit.getToolNames()`（通过 `agent.getToolkit()`）、用户问题与模型最终回复，异常时打印可读中文错误提示与堆栈。

**Verify:**

```powershell
mvn -q -pl cm-agent-examples/dashscope-mcp-agent -am "-DskipTests" package
```

Expected: `BUILD SUCCESS`。

本地已启动对应 MCP 服务时，手动运行确认端到端链路：

```powershell
mvn -pl cm-agent-examples/dashscope-mcp-agent exec:java
```

Expected: 控制台打印已注册工具列表与模型最终文本回复；若本地未启动 MCP 服务，会打印明确的连接失败信息。

---

### Task 3：文档同步

**Files:**
- Modify: `docs/release-notes.md`

**Steps:**
- [x] 在“本次变更”中补充一条：新增 `dashscope-mcp-agent` 示例，演示 AgentScope 通过 MCP Streamable HTTP 客户端调用外部时间查询工具并由 DashScope `qwen3.7-plus` 驱动，明确该示例仅用于本地联调，不代表生产凭据管理方式。

**Verify:**

```powershell
git diff --check -- docs/release-notes.md
```

Expected: 无输出。

---

### Task 4：全量回归确认

**Steps:**
- [x] 确认新增模块不影响既有模块编译：`mvn -q -pl cm-agent-examples/dashscope-mcp-agent -am "-DskipTests" package`。
- [ ]（可选，需人工本地环境）在本地启动时间查询 MCP 服务后运行 `exec:java` 做端到端人工验证；无可用 MCP 服务或无公网访问 DashScope 时，在进度账本中如实记录未执行原因。

