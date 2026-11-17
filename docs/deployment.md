# 部署指南

本文档说明当前版本构建、AgentScope 2.0.2 真实 Runtime、JDBC/Flyway 迁移、运行持久化和 Rocky VM 容器验证方式。能力边界最后核验于 2026-11-13；生产环境不使用本机 Docker Desktop 作为容器验证环境。

## 前置条件

- Java 21。
- Maven 3.9 或更高版本。
- 生产数据库、受控网络和最小权限账号。
- 生产 JWT secret、数据库 URL、用户名和密码只能由 secret manager 或受控外部 YAML 注入。
- OpenAI Compatible 或 DashScope 模型服务，以及按租户和模型配置隔离的外部模型凭据。
- 涉及 Docker Compose、Testcontainers、Flyway 或 JDBC 集成验证时，必须使用 Rocky Linux VM 的容器环境。
- 按需准备 HTTP/MCP、Skill/脚本沙箱、文件产物、参考工作区、本机客户端和本地项目所需的外部依赖；是否启用以实际合并配置为准，不能仅因代码已交付就在生产启用；部署前参照[配置说明](configuration.md#基础配置)核对配置来源。

## 构建

代码可以在开发机上完成静态文档检查和 Maven 构建：

```powershell
java -version
mvn -v
mvn -q "-DskipTests" package
```

上述命令不代表已经完成容器或数据库验证。容器验证必须在 Rocky VM 执行。

## Rocky VM 容器验证

远程验证前确认 Rocky VM 上的 Docker、Compose 可用，并确认远程工作区提交与待验证提交一致。Maven 与 JDK 必须在 `maven:3.9.9-eclipse-temurin-21` 验证容器内核对，不能用 Rocky 宿主机的 Maven/JDK 代替：

```bash
ssh rocky
cd <remote-workspace>
git rev-parse --short HEAD
docker version
docker compose version
docker run --rm maven:3.9.9-eclipse-temurin-21 java -version
docker run --rm maven:3.9.9-eclipse-temurin-21 mvn -v
```

确认 `<remote-workspace>` 的提交 SHA 与待验证的 `<commit-sha>` 一致后，只启动当前项目需要的 Compose 服务，并在 Maven 容器内执行测试。若包含未提交修改，还须核对实际验证文件清单与摘要，不能仅凭相同 HEAD 判定源码一致：

```bash
docker compose up -d postgres mysql
docker run --rm --network host -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 -v "$PWD:/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -pl cm-agent-persistence -am test
docker run --rm --network host -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 -v "$PWD:/workspace" -v /var/run/docker.sock:/var/run/docker.sock -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -pl cm-agent-server -am test
```

以上是 Rocky Linux 上的验证命令模板，不代表已经执行。宿主网络让验证容器可连接 Docker 发布的测试端口，Docker socket 用于 Testcontainers 创建同一 daemon 上的测试容器；该 socket 具有宿主级控制能力，仅用于受信的项目验证代码，不向 Skill 沙箱开放。

验证结束后仅停止本项目服务：

```bash
docker compose stop postgres mysql
```

需要验证 AgentScope Redis 状态存储示例时，可额外启动 Redis：

```bash
docker compose up -d redis
docker compose ps redis
```

该服务使用 `redis:7.0-alpine` 并监听宿主机 `6379` 端口。它只提供本项目本地联调用途；如通过 `REDIS_URL` 提供包含密码的连接串，禁止将完整连接串写入命令历史、日志或文档。

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
  model-credentials:
    encryption-key: ${CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY}
```

真实 Runtime 必须同时满足 `fake-runtime-enabled=false` 与 `agentscope-enabled=true`。`CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY` 是 Base64 编码的 256 位 AES 主密钥，除模型 API Key 外，也用于运行检查点、参考工作区快照以及本地项目和设备项目等受保护数据；它必须由部署平台或密钥管理系统注入。备份和恢复时须保留与数据库及相应私有状态卷匹配的原密钥；轮换前先核对待审批 Run 和各功能的恢复条件，不能直接替换密钥，具体见[运行数据与生命周期](operations.md#运行数据与生命周期)和[本地项目运维](operations.md#本地项目开发工作区运维)。技能沙箱凭据、技能文件产物和导入存储各有独立密钥，不得混用。模型 API Key 通过受权限保护的模型配置 API 写入数据库密文，不从 YAML 凭据列表读取；也可以用自定义 `ModelCredentialProvider` Bean 直接对接 secret manager。

`model_configs` 保存 Provider、`baseUrl`、`modelName` 等模型元数据和 API Key 密文，不保存明文 API Key，也不会通过 API 回显。AgentScope Java 2.0.2 当前支持 OpenAI Compatible 与 DashScope Provider；升级 AgentScope 或 Provider 扩展时必须重新核对依赖树和运行合同。

## 动态 HTTP 工具与 MCP 部署

动态 HTTP 工具和 MCP 在提交基线中默认关闭；工作树或部署配置可能覆盖该值，启动前应核对实际生效配置。生产仅在已完成出口网络评估后启用，并使用最小集合白名单。以下是无真实凭据的结构示例：

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
```

MCP 启用时必须同时配置非空的 Origin 和 Host 白名单，生产应使用最小精确集合；完整的开关、端点和白名单配置示例统一见 [MCP 配置说明](configuration.md#mcp-streamable-http)，缺少白名单时启动会失败。

`secret/...` Header 引用必须由部署平台注册的 `SecretProvider` 解析，真实值不能出现在 YAML、数据库迁移、镜像、日志或 API 响应中。为 DNS TOCTOU 提供纵深防御，应在 egress 防火墙、受控 DNS 或代理中再次约束 `allowed-hosts` 对应的实际目标地址。

Flyway `V6__add_http_parameter_definitions.sql` 为 HTTP 工具增加扁平参数定义列，`V7__remove_legacy_http_parameter_mapping.sql` 删除 HTTP 配置表中的 `input_schema` 和 `parameter_mappings`。HTTP 工具从 V7 起只使用 `parameters`，历史映射数据不会转换或继续执行。部署前必须备份并确认不再需要旧配置，再完成迁移和应用升级。

发布 MCP 前需为调用主体授予 `tool:mcp:invoke`，为控制台发布/取消发布授予 `tool:grant`，为单工具调试授予 `tool:debug`。MCP 端点沿用 JWT 认证，不得设置为匿名路径；`GET` 返回 `405`，关闭开关时返回 `404`。反向代理不得将认证头、Cookie 或下游密钥写入访问日志。发布后无需重启：每个 MCP 请求都会重新加载租户目录，取消发布、禁用和配置漂移即时拒绝调用。

外部配置目录示例为 `/etc/cm-agent/`，实际路径由部署平台控制。生产启动必须显式选择 `production`、`prod` 或 `supabase` profile：

```bash
java -jar cm-agent-server/target/cm-agent-server-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=production \
  --spring.config.additional-location=file:/etc/cm-agent/
```

生产认证使用外部身份系统或受控认证服务签发的 Bearer JWT；服务从受控外部配置取得 JWT 验证密钥，不在应用内生成生产登录凭据。`/api/auth/login` 的 bootstrap admin 入口仅允许在明确启用并配置凭据的 `local`/`test` 环境使用；`prod`、`production`、`supabase` profile 必须拒绝该能力，生产调用方必须携带外部签发的 Bearer JWT。

JWT 验证密钥、数据库凭据和模型 API Key 不得写入 Git、镜像层、命令历史、日志、审计消息或 API 响应。`postgres`、`mysql` profile 只用于 Rocky VM 联调，不能作为生产凭据来源；这些 profile 的敏感值也必须由外部配置提供。

## Flyway 与数据库迁移

截至 2026-11-13，仓库迁移头为 V31。公共迁移位于 `cm-agent-persistence/src/main/resources/db/migration` 根部，方言迁移位于 `postgresql/` 和 `mysql/`；`CmAgentFlyway` 同时加载公共脚本与当前数据库方言，不递归加载另一数据库目录。部署前必须直接核对迁移目录和 `flyway_schema_history`，本指南不复制会快速变化的逐版本脚本正文。

当前迁移按职责分为：

- V1–V3：租户、身份、模型、Agent、工具、运行、ToolCall、审计基表及运行查询索引。
- V4–V7：工具名称唯一性、HTTP 工具、MCP 发布、软删除墓碑和参数定义。
- V8–V9：PostgreSQL/MySQL 原生表和字段注释及模型凭据注释。
- V10–V11、V14、V20：持久化消息、在线审批、过期扫描索引和检查点载荷容量。
- V12–V13、V15–V19：Skill、发布验证、沙箱、租户端点、描述容量和加密文件产物。
- V21：ReAct/Harness 全数据集执行模式门禁。
- V22–V25：只读参考工作区、受控导入、请求关联和协议见证。
- V26–V31：本地项目、本机客户端、设备项目任务、Run 归属和任务栅栏。

Flyway 在 JDBC 应用启动时按版本执行。发布前应备份数据库并记录当前版本，确认 `flyway_schema_history`，避免绕过迁移工具手工改表。生产可将 DDL 迁移账号与运行账号分离，由发布流程先应用迁移，再使用运行账号启动服务。

V4 仍会为 `tool_definitions` 增加同一租户内的名称唯一索引。新环境或从很早的基线升级时，应用 V4 前必须先执行以下只读检查并处理结果中的重复记录，否则唯一索引会使迁移失败：

```sql
SELECT tenant_id, name, COUNT(*) AS duplicate_count
FROM tool_definitions
GROUP BY tenant_id, name
HAVING COUNT(*) > 1;
```

V4 的复合外键引用 V1 已存在的 `tool_definitions (id, tenant_id)` 唯一键，兼容 PostgreSQL 16 与 MySQL 8.4。HTTP 请求头配置只保存 Secret 引用，实际值必须由受控 Secret Provider 在运行时解析，不得写入数据库。

V5 为 `tool_definitions` 增加 `deleted_at`、`deleted_name` 和 `(tenant_id, deleted_at)` 索引，用于工具安全软删除：

- 管理面只读取 `deleted_at IS NULL` 的活动工具。删除时不物理移除定义，而是把原名称保存到 `deleted_name`，将活动 `name` 替换为内部唯一墓碑名并禁用工具。
- 原名称在删除事务提交后即可由同租户的新工具复用；运维查询活动工具数量时必须显式过滤 `deleted_at IS NULL`，不能把墓碑计为可调用工具。
- 墓碑继续保留原工具 ID 和租户复合键，作为 `tool_calls` 等历史记录的外键锚点。不得手工删除墓碑、重写历史引用，也不得通过修改 V5 迁移绕过该约束。
- 非生产 MySQL 固定目录中的内置 LOCAL 示例可在删除后重新安装。该流程仅对目录审核过的固定租户、固定 ID、原名称和 `LOCAL` 类型执行原位恢复，并与安装审计共享事务；普通工具创建仍使用 INSERT，不能复活任意墓碑。

受控恢复复用同一物理行，因此不会替换主键或破坏既有历史外键。若原名称已被其他活动工具复用，恢复会按租户名称唯一约束返回冲突，运维人员不应手工改名或删除历史记录来强制恢复。

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

当前提供 ReAct/Harness、多轮会话、会话 SSE、显式停止本轮、在线 HIGH 工具逐调用审批和只读审批历史，不提供消息编辑/删除、会话归档、附件与多模态、自动摘要、写请求幂等重放、独立审批中心、长期预授权规则或跨实例自动接管。显式停止会等待运行时确认工具退出，但不能证明外部副作用已回滚。工具恢复时会重新授权，endpoint 元数据不会被自动执行；具有外部副作用的工具仍须由下游支持幂等键并自行保障一致性。

HTTP/MCP、Skill 与脚本沙箱、文件产物、参考工作区、受控导入、本机客户端和本地项目分别受功能开关及发布边界约束；部分能力处于未公开发布状态，属性初始值或提交基线不保证实际部署配置关闭。启用前应逐项核对[配置说明](configuration.md)和[运维说明](operations.md)，不能用单个 capability 接口或单元测试替代生产验收。
