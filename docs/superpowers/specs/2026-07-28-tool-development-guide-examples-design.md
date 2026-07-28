# LOCAL 与 HTTP 工具开发指南及示例工程设计

## 1. 背景与目标

CM Agent 当前的 `ToolType` 包含 `LOCAL`、`HTTP`、`MCP` 和 `A2A`，但受治理执行链只完整支持 `LOCAL` 与 `HTTP`。本次工作面向开发者补充一份工具开发指南，并提供两个可编译、可运行、可测试的示例工程，帮助开发者沿项目真实接入路径创建和验证工具。

本次只讲解以下两类工具：

- `LOCAL`：由应用实现 `ToolExecutor`，并通过 `ToolRegistry` 注册的进程内工具。
- `HTTP`：由平台保存 HTTP 配置，经安全策略校验后动态调用外部 HTTP 服务的工具。

MCP 仅作为已创建 `LOCAL` 或 `HTTP` 工具的可选发布通道简要说明，不作为第三种工具类型展开。`MCP`、`A2A` 类型的独立执行器不在本次范围内。

## 2. 交付内容

### 2.1 开发指南

新增 `docs/tool-development-guide.md`，作为统一的中文开发者指南，包含：

1. 支持范围与类型选择。
2. `ToolDefinition`、输入 JSON Schema、风险等级、租户、权限和审计等公共概念。
3. LOCAL 工具的依赖、定义、执行器、注册、调用与 Server 接入说明。
4. HTTP 工具的 Server 配置、创建请求、参数映射、Secret 引用与调试调用。
5. PATH、QUERY、HEADER、BODY 四种参数映射示例。
6. Agent 授权与可选 MCP 发布步骤。
7. SSRF 防护、Host 白名单、超时、响应限制、敏感信息和租户一致性等安全边界。
8. 常见错误与排查表。
9. 两个示例工程的构建和运行命令。

根 `README.md` 增加开发指南入口，`docs/release-notes.md` 记录新增文档和示例工程，但不声明生产工具行为发生变化。

### 2.2 LOCAL 示例

扩充已有 `cm-agent-examples/starter-local-tool`，不新增职责重复的 LOCAL 模块。示例按职责拆分为：

- Spring Boot 示例应用：负责启动和装配。
- LOCAL 工具定义：提供固定的示例 tenant、tool ID、名称、JSON Schema、风险等级和启用状态。
- `EchoToolExecutor`：解析并校验输入 JSON，执行业务逻辑并返回 `ToolExecutionResult`。
- 注册组件：通过 `ToolRegistry.register` 绑定工具定义与执行器。
- 演示调用组件：启动后发起一次示例调用并输出受控结果。

LOCAL 示例使用固定、明确标注为示例的数据，不包含生产凭据。业务执行逻辑不内联在注册表达式中，以便开发者理解定义、执行和注册三个独立职责。

开发指南明确区分两种场景：

- Starter 独立接入：应用直接使用 `ToolRegistry` 注册并调用工具。
- 正式 Server 接入：持久化工具定义的 ID、tenant 和名称必须与运行时注册信息一致，工具还需要经过 Agent 授权和运行时二次治理。

### 2.3 HTTP 示例

新增 `cm-agent-examples/http-tool-client`，并加入 `cm-agent-examples/pom.xml` 的模块列表。该工程不依赖服务端内部 Service 或 Repository，而是使用公开 REST API 完成真实接入：

1. 从配置或环境变量读取 CM Agent 服务地址、JWT、目标 URL、工具名称，以及可选的 Secret Header 名称和 Secret 引用。
2. 构造 `POST /api/tools` 请求，创建 `ToolType.HTTP` 工具。
3. 工具输入 Schema 声明必填字符串字段 `message`。
4. 使用 BODY 参数映射，将 `/message` 写入目标请求的 `/message`。
5. 创建成功后读取工具 ID。
6. 调用 `POST /api/tools/{id}/debug`，提交示例输入并输出脱敏后的调试结果。

示例默认设置 `mcpPublished=false`，不自动授权 Agent，也不自动修改 Server 配置。开发者必须显式提供目标 URL，并在 Server 中启用动态 HTTP 工具、配置目标 Host 白名单和所需权限。

HTTP 示例不固化公网目标、JWT、Secret 实际值或生产 URL。Secret Header 只有在名称和 `secret/...` 引用同时提供时才写入创建请求，示例及日志不读取或输出 Secret 实际值。

## 3. 数据流

### 3.1 LOCAL 工具

```text
Spring Boot 启动
  → 构造 ToolDefinition
  → 构造 EchoToolExecutor
  → ToolRegistry.register
  → ToolExecutionRequest
  → EchoToolExecutor.execute
  → ToolExecutionResult
```

正式 Server 运行时还会在执行前校验工具启用状态、tenant、注册快照、Agent 授权和调用上下文。示例不得暗示直接调用 `ToolRegistry` 可以替代生产治理链。

### 3.2 HTTP 工具

```text
示例客户端
  → POST /api/tools
  → Server 校验权限、Schema、参数映射与 HTTP 配置
  → 保存 ToolDefinition 与 HttpToolConfig
  → 返回工具 ID
  → POST /api/tools/{id}/debug
  → Server 校验调试权限和风险确认
  → Host/协议/DNS/重定向安全校验
  → 解析参数与 Secret 引用
  → 调用目标 HTTP 服务
  → 返回有界、脱敏的调试结果
```

## 4. 配置设计

HTTP 示例使用独立前缀的 Spring Boot 配置属性，命令行参数和环境变量均可覆盖。至少包含：

- 示例是否执行。
- CM Agent 服务基础地址。
- Bearer JWT。
- HTTP 工具名称。
- 目标 URL。
- 可选 Secret Header 名称。
- 可选 `secret/...` 引用。
- 调试输入中的 `message`。

默认配置允许应用在未提供凭据时启动并显示运行提示，但只有显式启用示例后才发送网络请求。启用后缺少服务地址、JWT 或目标 URL时立即以清晰中文错误终止。

Server 侧前置条件在文档中明确列出：

- `cm-agent.http-tools.enabled=true`。
- 目标 Host 已加入 `cm-agent.http-tools.allowed-hosts`。
- JWT 主体至少具备创建工具和调试工具所需权限。
- 如使用 Secret Header，Server 侧 Secret Provider 能解析对应引用。

## 5. 错误处理与安全约束

### 5.1 LOCAL 示例

- 输入必须是合法 JSON 对象。
- 缺少 `message`、类型错误或空字符串时返回失败结果，不抛出包含输入原文的异常。
- 输出只包含示例业务结果，不记录完整执行上下文。

### 5.2 HTTP 示例

- 创建或调试响应为非 2xx 时，以 HTTP 状态和受控响应摘要报告失败。
- 不在异常、日志或测试断言中输出 JWT、Secret 实际值或完整生产 URL。
- 工具重名时给出修改示例工具名称后重试的提示，不自动删除或覆盖已有工具。
- 创建成功但调试失败时保留已创建记录，并明确告知开发者检查 HTTP 开关、Host 白名单、目标可达性、Schema 映射和 Secret 引用。
- 示例不关闭或绕过 SSRF、协议、DNS、重定向、超时和响应大小限制。

## 6. 测试设计

### 6.1 LOCAL 示例测试

- `EchoToolExecutor` 单元测试：成功输入、非法 JSON、字段缺失和字段类型错误。
- 注册执行测试：启动示例上下文，通过 `ToolRegistry` 找到固定工具定义并执行成功。

### 6.2 HTTP 示例测试

使用进程内本地模拟 HTTP 服务充当 CM Agent API，只验证示例客户端本身，不模拟动态 HTTP 目标执行：

- 创建请求使用 `POST /api/tools`。
- 请求携带正确的 Bearer 认证头。
- 创建请求包含 HTTP 类型、Schema、BODY 映射和 `mcpPublished=false`。
- 创建成功后使用返回 ID调用 `/api/tools/{id}/debug`。
- 调试请求包含预期输入。
- 非 2xx 响应转换为不泄露 JWT 的明确错误。
- 未启用示例时不发送网络请求。
- 缺少必填配置时在请求前失败。

测试不访问公网，不要求真实 JWT、Secret、数据库或 CM Agent Server。

## 7. 验证与验收标准

实施完成后执行：

1. `java -version` 和 `mvn -v`，确认 Maven 使用 JDK 21。
2. 示例模块及其依赖测试。
3. 示例聚合模块打包检查。
4. 文档链接和命令人工复核。
5. `git diff --check`。

本次不修改 JDBC Repository、Flyway 迁移或数据库配置，不涉及 Docker、Docker Compose 或 Testcontainers，因此不需要 Rocky Linux 容器验证。如实施中意外引入相关变化，必须按仓库规则改用 `ssh rocky` 完成容器验证。

验收结果必须满足：

- 开发者能根据文档判断应选择 LOCAL 还是 HTTP。
- 两个示例在 JDK 21 环境下编译并通过测试。
- LOCAL 示例能完成注册与调用。
- HTTP 示例在提供合法运行参数和可用 Server 后能完成创建与调试。
- 所有示例、文档和输出均不包含真实凭据。
- 不改变现有生产 API、数据库 Schema 或工具治理语义。

## 8. 影响范围

预计只影响：

- `docs/tool-development-guide.md`
- `README.md`
- `docs/release-notes.md`
- `cm-agent-examples/pom.xml`
- `cm-agent-examples/starter-local-tool`
- `cm-agent-examples/http-tool-client`

不修改 `cm-agent-core`、`cm-agent-server`、`cm-agent-persistence`、数据库迁移或生产配置。工作区中与本任务无关的现有修改保持不动。
