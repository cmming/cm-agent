# 部署指南

本文档说明阶段3的构建、AgentScope 2.0.0 真实 Runtime、JDBC/Flyway 部署、运行持久化和 Rocky VM 容器验证方式。生产环境不使用本机 Docker Desktop 作为容器验证环境。

## 前置条件

- Java 21。
- Maven 3.9 或更高版本。
- 生产数据库、受控网络和最小权限账号。
- 生产 JWT secret、数据库 URL、用户名和密码只能由 secret manager 或受控外部 YAML 注入。
- OpenAI Compatible 或 DashScope 模型服务，以及按租户和模型配置隔离的外部模型凭据。
- 涉及 Docker Compose、Testcontainers、Flyway 或 JDBC 集成验证时，必须使用 Rocky Linux VM 的容器环境。

## 构建

代码可以在开发机上完成静态文档检查和 Maven 构建：

```powershell
java -version
mvn -v
mvn -q "-DskipTests" package
```

上述命令不代表已经完成容器或数据库验证。容器验证必须在 Rocky VM 执行。

## Rocky VM 容器验证

远程验证前确认 Rocky VM 上的 Docker、Compose、Java 21、Maven 3.9+ 可用，并确认远程工作区提交与待验证提交一致：

```bash
ssh rocky
cd <remote-workspace>
git rev-parse --short HEAD
java -version
mvn -v
docker version
docker compose version
```

确认 `<remote-workspace>` 的提交 SHA 与待验证的 `<commit-sha>` 一致后，只启动当前项目需要的 Compose 服务：

```bash
docker compose up -d postgres mysql
mvn -pl cm-agent-persistence -am test
mvn -pl cm-agent-server -am test
```

验证结束后仅停止本项目服务：

```bash
docker compose stop postgres mysql
```

不得在 Rocky VM 上执行全局容器、卷或镜像清理；也不得用本机 Docker Desktop 代替上述数据库和 Testcontainers 验证。

## 生产配置注入

生产配置应通过部署平台挂载的外部 YAML 或 secret manager 生成文件加载，例如：

```yaml
cm-agent:
  config:
    jwt-secret: <secret-manager-jwt-secret>
    persistence-mode: jdbc
    jdbc-url: jdbc:postgresql://<db-host>:5432/<database-name>
    jdbc-username: <least-privilege-db-user>
    jdbc-password: <secret-manager-db-password>
    jdbc-driver-class-name: org.postgresql.Driver
    bootstrap-admin-enabled: false
    fake-runtime-enabled: false
    agentscope-enabled: true
  agentscope:
    credentials:
      - tenant-id: <tenant-id>
        model-config-id: <model-config-id>
        api-key: ${MODEL_API_KEY}
```

真实 Runtime 必须同时满足 `fake-runtime-enabled=false` 与 `agentscope-enabled=true`。上例的 `${MODEL_API_KEY}` 只能由部署平台从 Secret 注入；也可以用自定义 `ModelCredentialProvider` Bean 直接对接 secret manager。默认外部凭据列表为空时应用会 fail-fast，避免生产在无模型凭据的情况下接收流量。

`model_configs` 只部署 Provider、`baseUrl`、`modelName` 等模型元数据，不保存明文 API Key。AgentScope Java 2.0.0 当前支持 OpenAI Compatible 与 DashScope Provider；升级 AgentScope 或 Provider 扩展时必须重新核对依赖树和运行合同。

## 动态 HTTP 工具与 MCP 部署

动态 HTTP 工具和 MCP 都默认关闭。生产仅在已完成出口网络评估后启用，并使用最小集合白名单。以下是无真实凭据的结构示例：

```yaml
cm-agent:
  http-tools:
    enabled: true
    allow-http: false
    allowed-hosts:
      - api.example.test
    min-timeout: 100ms
    max-timeout: 30s
    max-response-bytes: 262144
    max-redirects: 3
  mcp:
    enabled: true
    endpoint: /mcp
    allowed-origins:
      - https://mcp-client.example.test
    allowed-hosts:
      - mcp.example.test
```

`secret/...` Header 引用必须由部署平台注册的 `SecretProvider` 解析，真实值不能出现在 YAML、数据库迁移、镜像、日志或 API 响应中。为 DNS TOCTOU 提供纵深防御，应在 egress 防火墙、受控 DNS 或代理中再次约束 `allowed-hosts` 对应的实际目标地址。

发布 MCP 前需为调用主体授予 `tool:mcp:invoke`，为控制台发布/取消发布授予 `tool:grant`，为单工具调试授予 `tool:debug`。MCP 端点沿用 JWT 认证，不得设置为匿名路径；`GET` 返回 `405`，关闭开关时返回 `404`。反向代理不得将认证头、Cookie 或下游密钥写入访问日志。发布后无需重启：每个 MCP 请求都会重新加载租户目录，取消发布、禁用和配置漂移即时拒绝调用。

外部配置目录示例为 `/etc/cm-agent/`，实际路径由部署平台控制。生产启动必须显式选择 `production`、`prod` 或 `supabase` profile：

```bash
java -jar cm-agent-server/target/cm-agent-server-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=production \
  --spring.config.additional-location=file:/etc/cm-agent/
```

生产认证使用外部身份系统或受控认证服务签发的 Bearer JWT；服务从受控外部配置取得 JWT 验证密钥，不在应用内生成生产登录凭据。`/api/auth/login` 的 bootstrap admin 入口仅供 `local` profile 的本地调试使用，生产 profile 已禁用该入口，调用方必须携带外部签发的 Bearer JWT。

JWT 验证密钥、数据库凭据和模型 API Key 不得写入 Git、镜像层、命令历史、日志、审计消息或 API 响应。`postgres`、`mysql` profile 只用于 Rocky VM 联调，不能作为生产凭据来源；这些 profile 的敏感值也必须由外部配置提供。

## Flyway 与数据库迁移

阶段2的 JDBC 运行数据范围包括 `runs`、`tool_calls` 和 `audit_events`。V1 已建立这些表及基础租户约束；阶段2新增 V2、V3：

- 不修改已经发布的 `V1__init_schema.sql`。
- 新增 `V2__add_runtime_query_indexes.sql`，为 Run、ToolCall、Audit 的租户范围查询增加索引。
- 新增 `V3__add_tool_calls_created_at_index.sql`，为 ToolCall 的租户、运行、创建时间和 ID 排序增加联合索引。
- Flyway 在 JDBC 应用启动时按版本执行；发布前应确认 `flyway_schema_history`，避免绕过迁移工具手工改表。
- 生产可将 DDL 迁移账号与运行账号分离，由发布流程先应用迁移，再使用运行账号启动服务。

升级前先备份数据库并记录当前迁移版本；迁移失败时停止发布、保留错误上下文并按回滚预案处理，不修改历史 V1 文件“修复”问题。

V4 为 `tool_definitions` 增加同一租户内的名称唯一索引，并新增 HTTP 工具配置和 MCP 发布配置表。应用 V4 前必须先执行以下只读检查并处理结果中的重复记录，否则唯一索引会使迁移失败：

```sql
SELECT tenant_id, name, COUNT(*) AS duplicate_count
FROM tool_definitions
GROUP BY tenant_id, name
HAVING COUNT(*) > 1;
```

V4 的复合外键引用 V1 已存在的 `tool_definitions (id, tenant_id)` 唯一键，兼容 PostgreSQL 16 与 MySQL 8.4。HTTP 请求头配置只保存 Secret 引用，实际值必须由受控 Secret Provider 在运行时解析，不得写入数据库。

## 两段式运行持久化

每次 Agent 运行分为两个持久化阶段：

1. 启动阶段：在 JDBC 事务中写入 `RUNNING` Run 记录和“运行已启动”审计事件。
2. 完成阶段：在 JDBC 事务中更新 Run 结果，批量写入 ToolCall，并写入完成、拒绝或失败审计事件。

运行异常会尝试把 Run 收口为 `FAILED`；审计写入失败保持严格语义并向 API 返回 `503`。请求主体的 tenant 会同时约束 Run、ToolCall、Audit 的读写，不能由客户端覆盖。

## 启动与健康检查

生产服务应使用受控进程管理器启动，并先检查：

```text
GET http://<service-host>:8080/actuator/health
```

启动失败、Flyway 失败、JWT 验证密钥缺失、模型凭据缺失、数据库连接失败和审计写入失败都应阻止流量切入。`local`/`test` 才允许显式启用 fake runtime；生产 profile 固定为 `fake-runtime-enabled=false`、`agentscope-enabled=true`。

阶段3只提供同步单轮运行，不应把部署就绪解释为已经支持多轮会话持久化、流式 REST、HITL 或手动取消。工具每次调用都会重新授权，endpoint 元数据不会被自动执行。对具有外部副作用的工具，部署前必须确认下游支持幂等键；模型或工具 timeout、中断以及 AgentScope 2.0.0 的通用取消信号均不能证明外部副作用已回滚。
