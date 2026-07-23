# 配置说明

本文档说明阶段3服务端的 profile、真实 AgentScope Runtime、持久化、安全和敏感信息配置。`local`/`test` 可显式使用 fake runtime；生产 profile 必须关闭 fake runtime、启用 AgentScope Runtime，并使用外部签发、受控验证的 JWT。

## Profile 选择

公共 `application.yml` 通过 `spring.profiles.active` 选择环境，也兼容使用 `CM_AGENT_PROFILE` 作为 profile 选择器。部署时优先使用 `spring.profiles.active`，例如：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local"
```

`application.yml` 没有把 `local` 设为默认 profile。未显式设置 `spring.profiles.active` 且未设置 `CM_AGENT_PROFILE` 时，不会自动加载 `application-local.yml`；服务将使用公共配置中的默认值。

| Profile | 用途 | 持久化与安全边界 |
| --- | --- | --- |
| `local` | 本地开发和演示 | 可以使用 memory、fake runtime 和 bootstrap admin；凭据仅限本地 |
| `test` | 自动化测试 | 可以使用 memory 和测试 bootstrap admin；测试凭据由代码/CI 注入 |
| `postgres`、`mysql` | Rocky VM 集成验证 | 使用 JDBC/Flyway；仅限联调，不是生产凭据来源 |
| `prod`、`production` | 生产或类生产 | 必须使用 JDBC，关闭 fake runtime，禁用 bootstrap admin 和开发 JWT fallback |
| `supabase` | Supabase PostgreSQL | 必须使用 JDBC，关闭 fake runtime，禁用 bootstrap admin 和开发 JWT fallback |

`prod` profile 通过 Spring profile group 复用 `production` 配置。生产 profile 启动时，如果缺少安全 JWT secret、误用 memory、启用 bootstrap admin 或混入不允许的 profile，服务应启动失败。

## 基础配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | 服务端监听端口 |
| `spring.profiles.active` | 空 | 运行环境选择器；推荐显式设置 |
| `CM_AGENT_PROFILE` | 空 | profile 兼容选择器；仅在未通过 Spring 配置选择时使用 |
| `cm-agent.fake-runtime-enabled` | `false` | fake runtime 开关；仅 `local`/`test` 可按需设为 `true`，strict profile 必须为 `false` |
| `cm-agent.agentscope.enabled` | `false` | AgentScope 真实 Runtime 开关；生产 profile 必须为 `true`，并与 fake runtime 互斥 |
| `cm-agent.agentscope.model-timeout` | `60s` | 单次模型阶段超时时间，必须为正数 |
| `cm-agent.agentscope.tool-timeout` | `30s` | AgentScope 工具执行超时时间，必须为正数 |
| `cm-agent.agentscope.model-max-attempts` | `2` | 模型最大尝试次数，范围为 1 到 5 |
| `cm-agent.persistence.mode` | `memory` | `memory` 只允许本地开发和测试；生产 profile 必须为 `jdbc` |
| `cm-agent.default-tenant-code` | `default` | 默认租户标识 |
| `cm-agent.mcp.enabled` | `false` | 是否注册无状态 MCP Streamable HTTP 端点；生产启用前必须同时配置来源和主机白名单 |
| `cm-agent.mcp.endpoint` | `/mcp` | MCP 端点的单一路径，不能包含查询串、片段、通配符或结尾斜杠 |

## MCP Streamable HTTP

MCP 端点默认关闭。启用时，除 `cm-agent.mcp.enabled=true` 外，必须显式配置非空的 `cm-agent.mcp.allowed-origins` 和 `cm-agent.mcp.allowed-hosts`；任一白名单缺失都会使服务启动失败。可使用部署环境的等价环境变量或受控外部 YAML 提供列表，示例中的值仅用于说明：

```yaml
cm-agent:
  mcp:
    enabled: true
    endpoint: /mcp
    allowed-origins:
      - https://mcp-client.example.test
    allowed-hosts:
      - mcp.example.test
```

`POST /mcp` 使用 MCP Java SDK 2.0 的无状态 Streamable HTTP transport。`GET` 固定返回 `405`；关闭开关时端点不存在并返回 `404`。端点仍受 JWT 认证保护，不会因为启用 MCP 而变为公开接口；调用方还需要 `tool:mcp:invoke` 权限。每个 HTTP 请求都依据认证主体重新构建当前租户的工具目录，每次调用还会重新读取发布记录和工具定义，因此取消发布、禁用或配置漂移会即时生效。

只会公开已发布且启用的 HTTP/LOCAL 工具：HTTP 工具的端点必须与受治理配置一致，LOCAL 工具必须与当前注册快照一致。调用使用 `MCP` 来源进入统一受治理执行入口，不创建 Agent 或 Run。输入按 MCP Schema 校验；调用失败仅返回受控 MCP 错误文本，不返回密钥、URL、Cookie、异常栈或底层错误。

通过 `cm-agent.config.*` 可以覆盖公共 YAML 中对应的 `cm-agent.*` 属性。生产配置应放在受控外部 YAML 或由 secret manager 生成并挂载的配置文件中：

```yaml
cm-agent:
  config:
    jwt-secret: <externally-controlled-jwt-verification-key>
    persistence-mode: jdbc
    jdbc-url: <controlled-jdbc-url>
    jdbc-username: <least-privilege-db-user>
    jdbc-password: <secret-manager-db-password>
    jdbc-driver-class-name: org.postgresql.Driver
    fake-runtime-enabled: false
    agentscope-enabled: true
```

不要把上面的占位符替换后的值提交到 Git、镜像层、日志、审计消息或 API 响应。敏感配置不应通过文档中的固定值传播。

## AgentScope 真实 Runtime

阶段3使用 AgentScope Java `2.0.0`，支持 `OPENAI_COMPATIBLE` 和 `DASHSCOPE_NATIVE` 两种 Provider，分别由 AgentScope OpenAI 与 DashScope 扩展提供。启用真实 Runtime 必须满足：

```yaml
cm-agent:
  fake-runtime-enabled: false
  agentscope:
    enabled: true
    model-timeout: 60s
    tool-timeout: 30s
    model-max-attempts: 2
    credentials:
      - tenant-id: <tenant-id>
        model-config-id: <model-config-id>
        api-key: ${MODEL_API_KEY}
```

默认 `ExternalModelCredentialProvider` 以 `tenantId + modelConfigId` 复合键查找外部凭据。启用真实 Runtime 且未配置任何凭据时会 fail-fast，错误信息不会包含 API Key；若部署平台使用 secret manager，可提供自定义 `ModelCredentialProvider` Bean，此时不需要把凭据列表写入应用配置。

`model_configs` 表保存模型 Provider、`baseUrl`、`modelName` 和启用状态。历史数据库兼容字段不能作为明文密钥使用，API Key 只能经外部 Secret 或自定义 `ModelCredentialProvider` 注入，也不得进入 DTO、日志、审计或异常。

真实运行目前只提供同步单轮调用。多轮会话持久化、流式 REST、HITL 和手动取消均未在阶段3交付。

## JDBC 与 Flyway

| 覆盖项 | 实际绑定项 | 说明 |
| --- | --- | --- |
| `cm-agent.config.persistence-mode` | `cm-agent.persistence.mode` | `memory` 或 `jdbc` |
| `cm-agent.config.jdbc-url` | `cm-agent.persistence.jdbc.url` | 启用 JDBC 时必须配置 |
| `cm-agent.config.jdbc-username` | `cm-agent.persistence.jdbc.username` | 使用最小权限账号 |
| `cm-agent.config.jdbc-password` | `cm-agent.persistence.jdbc.password` | 仅从受控外部 YAML 或 secret manager 注入 |
| `cm-agent.config.jdbc-driver-class-name` | `cm-agent.persistence.jdbc.driver-class-name` | PostgreSQL 或 MySQL 驱动 |

JDBC 模式创建 DataSource，并在启动时由 Flyway 执行 `classpath:db/migration`。已发布的 `V1__init_schema.sql` 不修改；阶段2新增 `V2__add_runtime_query_indexes.sql` 和 `V3__add_tool_calls_created_at_index.sql`，为 `runs`、`tool_calls` 和 `audit_events` 增加租户范围的查询索引。生产环境需先核对 Flyway 历史，再按发布流程应用新迁移。

## 安全配置

| 配置项 | 说明 |
| --- | --- |
| `cm-agent.config.jwt-secret` | 生产和类生产必须使用由外部身份系统/secret manager 或受控外部 YAML 管理的 JWT 验证密钥 |
| `cm-agent.config.bootstrap-admin-enabled` | `local`/`test` 可按需启用；`prod`、`production`、`supabase` 必须为 `false` |
| `cm-agent.config.bootstrap-admin-password` | 不写入生产配置仓库、镜像、文档或日志；测试凭据由代码/CI 注入 |
| `cm-agent.config.public-api-docs-enabled` | 生产 profile 默认关闭公开 API 文档 |

生产 profile 不提供开发 JWT fallback。缺少 JWT secret 或违反 profile 安全约束时，服务应拒绝启动，而不是生成或回退到开发密钥。

### 生产认证边界

生产和类生产服务使用外部身份系统或受控认证服务签发的 Bearer JWT。服务只从受控外部配置取得验证密钥，用于校验外部签发的令牌；不得在 Git、镜像、命令行、日志或审计消息中保存密钥或令牌。

`/api/auth/login` 是 bootstrap admin 登录入口，仅供显式启用 bootstrap admin 的 `local` profile 使用。`production`、`prod` 和 `supabase` 必须关闭 bootstrap admin，因此生产调用该入口应被拒绝；生产调用方应携带外部签发的 Bearer JWT。`test` 的 bootstrap 凭据也必须由测试代码、CI 或受控外部配置注入。

## 审计与错误语义

阶段2审计写入是严格路径。登录、权限拒绝、Agent 变更、工具治理和运行生命周期等关键动作写入失败时，不吞掉异常，不把请求伪装成成功；API 返回 HTTP `503 Service Unavailable`，日志只记录脱敏后的上下文。生产告警应区分审计失败、数据库连接失败和普通业务失败。

Run 与 ToolCall 使用当前认证主体的 tenant 条件读写；Run 列表和 Audit 列表采用有界 cursor 分页，避免跨租户或无界扫描。返回内容和日志中的输入、输出、错误消息经过敏感信息脱敏。

## 运行边界

- `memory` 仅限开发和测试，进程重启会丢失运行、工具调用和审计状态。
- JDBC 模式已接通 Run、ToolCall、Audit 的持久化与查询；`local`/`test` 的运行执行器可以由 fake runtime 提供结果。
- 真实 Runtime 的每次工具调用都通过治理网关重新读取定义并授权；工具定义中的 endpoint 只是元数据，不会被 Adapter 自动联网执行。
- 模型 timeout 或 Provider 故障会把运行收口为失败；授权拒绝优先映射为拒绝；审计失败保持严格语义并传播。AgentScope 2.0.0 的工具层只暴露通用取消信号，系统仅根据其明确生成的超时结果判定工具 timeout，不能把该信号视为通用手动取消能力。
- 工具可能产生外部副作用。超时、中断或 Provider 重试不能证明外部系统已经回滚，工具实现与下游接口必须使用 `runId`、`toolCallId` 或业务幂等键实现去重。
- metrics、集中式日志/追踪、备份治理和 CI/CD 不应在当前配置文档中被视为已交付能力，对应工作列入[中文路线图](roadmap.md)的阶段4-5。
