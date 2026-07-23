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
- 控制台：`http://localhost:8080/`
- OpenAPI：`http://localhost:8080/swagger-ui/index.html`

控制台面向平台使用者提供独立登录、能力总览、Agent 列表/详情/创建、Tool 列表/创建/授权、Agent 运行调试、运行历史与工具调用详情，以及审计日志游标分页。页面只展示当前已经交付的后端能力，不提供编辑、删除、手动取消或流式运行。JWT 仅保存在当前页面内存中，刷新页面或关闭标签页后需要重新登录。

## 动态 HTTP 工具与 MCP

控制台可创建 `HTTP` 工具：填写 `GET` 或 `POST`、URL 模板、输入 JSON Schema、参数映射、`secret/...` 请求头引用及超时。输入 JSON Schema 支持嵌套对象和数组；映射通过 JSON Pointer 读取输入并写入 PATH、QUERY、HEADER 或 BODY。缺失值与显式 `null` 都会按映射默认值处理，PATH 占位符必须与必填 PATH 映射一一对应。

工具不会保存或返回请求头密钥。Header 只能配置例如 `secret/integration/service-token` 的引用，运行时由受控 `SecretProvider` 解析。HTTP 执行默认关闭，启用前必须配置可访问主机白名单；协议、SSRF、重定向、超时和响应大小均受服务端限制。完整配置和运维边界见[配置说明](docs/configuration.md)与[运维说明](docs/operations.md)。

控制台支持对单个 HTTP 或 LOCAL 工具调试，需要 `tool:debug` 权限；HIGH 风险工具还必须输入与工具名称完全一致的二次确认。调试结果与失败信息只显示受控、脱敏后的摘要。已发布的 HTTP/LOCAL 工具可选择通过默认关闭的 MCP Streamable HTTP 端点提供；MCP 调用除 JWT 外还需要 `tool:mcp:invoke`，取消发布、禁用或运行配置漂移会立即使其不可调用。

临时覆盖本地配置时，请使用占位符并确保只在本地调试范围内生效：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.config.jwt-secret=<local-dev-only-jwt-secret> --cm-agent.config.bootstrap-admin-enabled=true --cm-agent.config.bootstrap-admin-password=<local-dev-only-password>"
```

真实 Runtime 支持 AgentScope 2.0.0 的 OpenAI Compatible 与 DashScope Provider。启用时必须同时关闭 fake runtime，并按 `tenantId + modelConfigId` 从外部 Secret 映射模型凭据：

```yaml
cm-agent:
  fake-runtime-enabled: false
  agentscope:
    enabled: true
    credentials:
      - tenant-id: <tenant-id>
        model-config-id: <model-config-id>
        api-key: ${MODEL_API_KEY}
```

默认外部凭据列表为空时，真实 Runtime 会启动失败；生产也可以提供自定义 `ModelCredentialProvider` 对接 secret manager。`model_configs` 只保存 Provider、`baseUrl`、`modelName` 等非敏感元数据，不保存明文 API Key。

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
- [配置说明](docs/configuration.md)
- [部署指南](docs/deployment.md)
- [运维说明](docs/operations.md)
- [发布说明](docs/release-notes.md)

## 文档语言

生产文档默认使用中文。英文文档可以作为翻译补充，但不能替代中文文档。
