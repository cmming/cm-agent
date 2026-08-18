# CM Agent

CM Agent 是基于 AgentScope Java 的企业级智能体开源底座。第一阶段完成 Java SDK、Spring Boot Starter、独立服务端、轻量控制台、工具治理、多租户和 RBAC 基线；阶段2完成生产持久化与安全收口；阶段3已接入 AgentScope Java 2.0.0 真实运行时。

## 快速开始

本地开发调试必须显式选择 `local` profile。该 profile 加载 `application-local.yml`，启用 memory 持久化、fake runtime、本地 bootstrap admin 和本地专用 JWT 配置。无 profile 启动时不会自动加载 `local`。

```powershell
mvn -q "-DskipTests" package
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local"
```

测试启动同样需要显式选择 `test` profile：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=test"
```

## 导出指定提交文件

需要将某个提交的文件以覆盖方式应用到其他工作区时，可执行：

```powershell
.\scripts\export-commit-files.ps1 -Commit <提交哈希> -OutputDirectory <导出目录>
```

脚本会导出该提交相对首个父提交新增、修改和重命名后的文件，并保留仓库目录结构。导出内容取自该提交版本，可复制到目标工作区覆盖。`exported-files.txt` 会记录已导出的文件；已删除文件不会复制，而会列在 `deleted-files.txt` 中，覆盖前必须人工复核并处理这些删除项。导出目录必须不存在或为空，以避免混入旧的导出结果。

本地和测试 profile 的 bootstrap admin 凭据只由代码/CI 注入或使用本地专用占位配置，不能复制到生产。生产和类生产环境必须使用受控外部 YAML 或 secret manager 提供 JWT secret 和 JDBC 凭据。

服务启动后访问：

- 健康检查：`http://localhost:8080/actuator/health`
- 控制台（默认 v2）：`http://localhost:8080/`，会重定向到 `/console/v2/login.html`
- 旧版控制台（v1）：`http://localhost:8080/console/v1/`
- OpenAPI：`http://localhost:8080/swagger-ui/index.html`

v2 控制台按版本化 URL 拆分为登录、能力总览、Agent 管理、模型配置、工具治理、运行记录和审计日志七个独立 HTML 页面。页面之间使用真实链接和独立 URL，并在当前文档内加载目标 HTML，以兼容会隔离跨文档状态的嵌入式浏览器；直接刷新或访问页面时由仅作用于 `/api` 的 `HttpOnly`、`SameSite=Strict` 会话 Cookie 恢复认证。登录响应中的 JWT 只在当前页面内存中短暂使用，不会写入浏览器存储或 URL；退出时会立即清除 Cookie。用户名和密码不会保存。原始单页 `index.html` 继续作为 v1 从 `/console/v1/` 提供，v1 的 JWT 仍只保存在页面内存中，刷新后需要重新登录。

两个版本复用相同的服务端 API、权限、多租户和审计边界。v2 面向平台使用者提供能力总览、Agent 列表/详情/创建、模型配置列表/详情/创建/编辑/删除、Tool 列表/创建/编辑/删除/授权、Agent 详情解除工具关联、Agent 运行调试、运行历史与工具调用详情，以及审计日志游标分页。页面不提供手动取消或流式运行。

模型配置 API 位于 `/api/model-configs`：读取需要 `model:read`，创建和更新需要 `model:write`，删除需要 `model:delete`。所有操作从认证主体取得 tenant，并记录权限拒绝或成功写操作审计；仍被 Agent 引用的配置删除时返回 `409 Conflict`。启动初始化器维护的系统默认配置不可删除，可通过更新接口停用或调整。接口和页面只管理 Provider、`baseUrl`、`modelName`、显示名称与启用状态，不接收、不保存、不返回 API Key。

## 动态 HTTP 工具与 MCP

控制台可创建 `HTTP` 工具：填写 `GET` 或 `POST`、URL 模板、输入 JSON Schema、参数映射、`secret/...` 请求头引用及超时。输入 JSON Schema 支持嵌套对象和数组；映射通过 JSON Pointer 读取输入并写入 PATH、QUERY、HEADER 或 BODY。缺失值与显式 `null` 都会按映射默认值处理，PATH 占位符必须与必填 PATH 映射一一对应。

工具不会保存或返回请求头密钥。Header 只能配置例如 `secret/integration/service-token` 的引用，运行时由受控 `SecretProvider` 解析。HTTP 执行默认关闭，启用前必须配置可访问主机白名单；协议、SSRF、重定向、超时和响应大小均受服务端限制。完整配置和运维边界见[配置说明](docs/configuration.md)与[运维说明](docs/operations.md)。

控制台支持对单个 HTTP 或 LOCAL 工具调试，需要 `tool:debug` 权限；HIGH 风险工具还必须输入与工具名称完全一致的二次确认。调试结果与失败信息只显示受控、脱敏后的摘要。已发布的 HTTP/LOCAL 工具可选择通过默认关闭的 MCP Streamable HTTP 端点提供；MCP 调用除 JWT 外还需要 `tool:mcp:invoke`，取消发布、禁用或运行配置漂移会立即使其不可调用。

工具编辑需要 `tool:grant` 权限，编辑时工具类型保持锁定，LOCAL 工具名称也不可修改；HTTP 工具必须提交完整且有效的 HTTP 配置，其他类型不能携带 HTTP 配置。编辑已发布的 LOCAL 工具会保持原 MCP 发布状态，也可在本次编辑中取消发布；未发布的 LOCAL 工具仍须使用独立的 MCP 发布操作。页面只回填 Secret 引用，不展示真实 Secret。

删除需要 `tool:delete` 权限并经过确认。只要工具仍被同租户任一 Agent 引用，删除就会返回明确的 `409 Conflict` 且不产生副作用；需先在 Agent 详情中确认解除关联（需要 `tool:grant`），随后才能删除。工具一旦产生调用历史，也会返回另一条明确的 `409 Conflict`，并保留工具定义、运行历史、调用记录和审计链路；这种冲突不能通过解除 Agent 关联来消除，控制台不会将其误提示为关联冲突。成功删除会立即从管理、授权、调试和 MCP 查询中隐藏工具，但 JDBC 会保留不可见的工具墓碑，确保删除前已经开始、删除后才落库的 ToolCall 仍能通过外键校验并进入运行历史；墓碑不会占用原工具名称。

在非生产 `mysql` profile 下，工具治理页只会向固定 bootstrap 示例租户 `00000000-0000-0000-0000-000000000001` 中具有 `tool:read` 权限的认证主体展示固定的 `echo`、`add` 内置 LOCAL 示例目录。这是 MySQL 调试的隔离演示边界，不是面向所有 tenant 的工具安装能力：其他 tenant 的目录为空，安装请求返回 `404`。该示例租户中具有 `tool:grant` 权限的主体点击“添加示例工具”后，定义才会写入 MySQL；安装成功后页面会自动填入示例输入，并可由具有 `tool:debug` 权限的主体通过现有调试入口调用。服务启动只注册固定 Java 执行器，不会自动写入数据库。

该入口不支持上传或编写执行代码。正式业务 LOCAL 工具仍需在同一 Server JVM 中实现并注册 `ToolExecutor`；页面中的“运行时已就绪”只表示当前注册快照，实际调用仍会重新执行治理校验。

临时覆盖本地配置时，请使用占位符并确保只在本地调试范围内生效：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.config.jwt-secret=<local-dev-only-jwt-secret> --cm-agent.config.bootstrap-admin-enabled=true --cm-agent.config.bootstrap-admin-password=<local-dev-only-password>"
```

真实 Runtime 支持 AgentScope 2.0.0 的 OpenAI Compatible 与 DashScope Provider。启用时必须同时关闭 fake runtime；模型 API Key 由模型配置管理接口加密写入数据库，运行时按 `tenantId + modelConfigId` 读取：

```yaml
cm-agent:
  fake-runtime-enabled: false
  agentscope:
    enabled: true
  model-credentials:
    encryption-key: ${CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY}
```

`CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY` 是 Base64 编码的 256 位 AES 主密钥，不是模型 API Key，必须由部署环境或密钥管理系统提供。模型 API Key 只以 AES/GCM 密文写入 `model_configs`，创建时必填、更新时可轮换，所有读取接口均不会回显。生产也可以提供自定义 `ModelCredentialProvider` 对接外部密钥管理系统。

## 当前状态

- 第一阶段：已交付工程骨架、核心领域接口、Starter、控制台、工具治理、多租户/RBAC 基线和 fake runtime。
- 阶段2：已交付 Run、ToolCall、Audit 的 JDBC Repository 与 Flyway V2/V3 查询索引，租户隔离、严格审计、JWT/profile/bootstrap/error/redaction 安全收口，以及运行启动/完成两段事务和 cursor 查询。
- 阶段3：已交付 AgentScope Java 2.0.0 真实同步单轮运行、OpenAI Compatible/DashScope 模型适配、外部模型凭据、受治理工具调用、超时中止与结果映射；并交付动态 HTTP 工具、控制台调试与可选 MCP 发布。工具每次调用都会重新授权，endpoint 元数据不会被自动执行。
- 阶段4：可观测性与运维增强尚未交付。
- 阶段5：交付与稳定性工程尚未交付。

阶段3不承诺多轮会话持久化、流式 REST、HITL 或手动取消。模型与工具调用失败、审计严格失败以及外部副作用的重试/幂等边界见[配置说明](docs/configuration.md)和[运维说明](docs/operations.md)。

完整范围和后续依赖见[中文路线图](docs/roadmap.md)。

## 生产文档

- [中文路线图](docs/roadmap.md)
- [工具开发指南](docs/tool-development-guide.md)
- [工具编辑、删除与 Agent 解除关联实现原理设计](docs/superpowers/implementation/2026-08-03-tool-management-edit-delete-implementation-design.md)
- [配置说明](docs/configuration.md)
- [部署指南](docs/deployment.md)
- [运维说明](docs/operations.md)
- [发布说明](docs/release-notes.md)

## 文档语言

生产文档默认使用中文。英文文档可以作为翻译补充，但不能替代中文文档。
