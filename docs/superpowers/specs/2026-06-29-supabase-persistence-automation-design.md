# Supabase 持久化自动化设计说明

日期：2026-06-29
状态：待用户复核

## 背景

CM Agent 已完成生产持久化第一阶段：`cm-agent-core` 定义 Agent、Tool、ToolGrant repository 契约，`cm-agent-persistence` 提供 JDBC repository，`cm-agent-server` 通过 `cm-agent.persistence.mode=jdbc` 启用 Flyway 和 JDBC 持久化。现有 schema 位于 `cm-agent-persistence/src/main/resources/db/migration/V1__init_schema.sql`，已覆盖 `tenants`、`model_configs`、`agent_definitions`、`tool_definitions` 和 `tool_grants` 等表。

Supabase 的数据库能力本质上是托管 PostgreSQL。当前功能扩展的目标不是引入第二套存储实现，而是把现有 JDBC/Flyway 持久化链路安全地适配到 Supabase 项目，并使用 Supabase development branch 完成数据库侧自动化验证。

已发现的 Supabase 项目：

- Project ID: `hfgdsvsvuosdkqeodked`
- Project name: `cmming's Project`
- Region: `us-west-2`
- Postgres: `17`

## 目标

本阶段采用方案 A：Supabase 作为 JDBC/Flyway 的托管 PostgreSQL 目标。

交付目标：

- 保持现有 repository、JDBC 和 Flyway 架构不变。
- 增加 Supabase 运行配置约定和文档。
- 默认使用 Supabase development branch 做 schema 检查和 migration 验证。
- 在 Supabase branch 上确认 Agent、Tool、ToolGrant 所需表结构存在。
- 增加配置级测试，确保 Supabase profile 不会回退到 memory 持久化。
- 维护 progress ledger，记录 Supabase branch、migration、验证命令和外部阻塞。

## 非目标

本阶段不引入 Supabase Java SDK。

本阶段不实现 Supabase Auth、Storage、Realtime 或 Edge Functions。

本阶段不把 AuditEvent、Run、ToolCall 纳入新的持久化接线范围。

本阶段不提交 Supabase 数据库密码、JWT secret、连接字符串或任何真实 secret。

本阶段不直接对 Supabase 主项目执行 DDL。所有数据库 DDL 验证默认只在 development branch 上进行。

## 方案选择

### 方案 A：Supabase 作为 JDBC/Flyway 目标

复用现有 `cm-agent.persistence.mode=jdbc`、Flyway migration 和 JDBC repository。新增 Supabase profile/配置说明、Supabase branch 自动化检查、文档和测试。

优点是贴合现有架构、改动小、生产路径清晰。缺点是 Supabase 的 Auth、Storage 等云特性暂不使用。

这是本阶段采用的方案。

### 方案 B：只做 Supabase schema 只读验证

只用 Supabase 插件读取 migration 和表结构，不改代码配置。

优点是最保守。缺点是项目中不会留下可复用的 Supabase 运行约定，后续部署仍靠手工经验。

### 方案 C：新增 `cm-agent.persistence.mode=supabase`

新增 Supabase 专属持久化模式，内部仍走 JDBC。

优点是产品语义清晰。缺点是会和现有 `jdbc` 模式重复，并增加配置分支和测试成本，当前阶段收益不足。

## 架构设计

Supabase 不新增独立 repository。CM Agent 仍通过 PostgreSQL JDBC 访问数据库：

- `JdbcPersistenceConfiguration` 创建 `DataSource`、Flyway 和 `JdbcClient`。
- `JdbcAgentDefinitionRepository` 负责 Agent 持久化。
- `JdbcToolDefinitionRepository` 负责 Tool 持久化。
- `JdbcToolGrantRepository` 负责 Grant 持久化。

Supabase 插件只用于项目侧自动化：

- 列出并选择 Supabase 项目。
- 创建 development branch。
- 在 branch 上列出 migration 和表结构。
- 必要时在 branch 上应用 CM Agent schema migration。
- 验证目标表存在。

业务代码不依赖 Supabase 插件。插件操作只用于开发和验证阶段，不进入应用运行时。

## 配置设计

推荐通过环境变量配置 Supabase JDBC：

```text
CM_AGENT_PROFILE=supabase
CM_AGENT_PERSISTENCE_MODE=jdbc
CM_AGENT_JDBC_URL=Supabase branch JDBC URL from secret manager
CM_AGENT_JDBC_USERNAME=Supabase database user from secret manager
CM_AGENT_JDBC_PASSWORD=Supabase database password from secret manager
CM_AGENT_JDBC_DRIVER_CLASS_NAME=org.postgresql.Driver
CM_AGENT_JWT_SECRET=external secret with safe length
```

`supabase` profile 的语义是“类生产托管 PostgreSQL 环境”。它必须满足：

- 默认或文档约定使用 JDBC 持久化。
- 缺少 JDBC URL 时启动失败。
- 不允许静默回退到 memory 存储。
- 不启用 bootstrap admin。
- 不启用开发 JWT fallback。

如果实现中不新增 `application-supabase.yml`，也必须在文档中明确使用 `CM_AGENT_PROFILE=prod` 或 `production` 加 JDBC 环境变量的等价方式。推荐实现新增 `application-supabase.yml`，减少部署时遗漏 `CM_AGENT_PERSISTENCE_MODE=jdbc` 的风险。

## Supabase 自动化流程

默认流程：

1. 读取 Supabase 项目 `hfgdsvsvuosdkqeodked`。
2. 获取创建 development branch 的成本信息，并在需要时由用户确认。
3. 创建 branch，名称建议为 `cm-agent-supabase-persistence`。
4. 等待 branch 变为可用状态。
5. 在 branch project ref 上调用 `list_migrations` 和 `list_tables`。
6. 如果缺少 CM Agent 表，将 `V1__init_schema.sql` 作为 migration 应用到 branch。
7. 再次读取 `public` schema 表结构。
8. 确认至少存在以下表：
   - `tenants`
   - `model_configs`
   - `agent_definitions`
   - `tool_definitions`
   - `tool_grants`

如果 Supabase branch 创建需要成本确认或权限不足，必须停止并报告真实阻塞。不得改为直接操作主项目数据库。

## 数据范围

本阶段只覆盖现有 JDBC 已接入的数据：

- AgentDefinition
- ToolDefinition
- ToolGrant
- 默认租户和默认模型配置初始化所需的 `tenants`、`model_configs`

AuditEvent、Run、ToolCall 表可以由 existing migration 创建，但服务端接线不在本阶段完成。

## 错误处理

应用启动错误：

- `CM_AGENT_PROFILE=supabase` 且 JDBC URL 为空时启动失败。
- Supabase profile 下 persistence mode 不是 `jdbc` 时启动失败。
- Supabase profile 下启用开发 JWT fallback 或 bootstrap admin 时启动失败。

Supabase 自动化错误：

- 项目不存在或不健康时，记录 ledger 并停止。
- branch 创建失败时，记录 Supabase 返回状态和错误。
- migration 应用失败时，记录 migration 名称和错误，不重试破坏性 reset。
- 表结构验证失败时，列出缺失表。

## 测试策略

每个实现任务使用 TDD：

1. 先写失败测试。
2. 验证测试因缺少配置或行为失败。
3. 实现最小代码或文档自动化。
4. 重跑目标测试。
5. 记录 progress ledger。

配置测试覆盖：

- `supabase` profile 启动时默认使用 JDBC mode。
- `supabase` profile 缺少 JDBC URL 时失败。
- `supabase` profile 禁止 memory mode。
- `supabase` profile 禁止 bootstrap admin。
- `supabase` profile 禁止开发 JWT fallback。

Supabase branch 验证覆盖：

- 能读取目标 Supabase 项目。
- 能创建或定位 development branch。
- 能列出 branch 表结构。
- 目标表存在。

本地 Maven 验证命令：

```powershell
$env:JAVA_HOME='F:\java21'
$env:PATH='F:\java21\bin;' + $env:PATH
mvn -q -DskipTests compile
mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest,RunControllerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

如果获得 Supabase branch JDBC URL 和安全凭据，再增加 Supabase profile smoke test。凭据不可自动获取时，PR Test Plan 必须写明需要用户注入 secret 后执行。

## 验收标准

- 设计、计划和 progress ledger 均提交到仓库。
- Supabase profile 或等价配置约定已文档化。
- 配置测试证明 Supabase 环境不会回退到 memory。
- Supabase development branch 上完成 schema 检查。
- branch 上缺表时，migration 只应用在 branch，不应用在主项目。
- Maven compile 和非容器 server 测试通过，或记录真实外部阻塞。
- PR Test Plan 明确区分已验证项目和需要用户 secret/Docker/Supabase 权限的项目。

## 后续里程碑

完成 Supabase Agent/Tool/ToolGrant 持久化自动化后，后续可独立设计：

1. AuditEvent repository 接入 server 审计链路。
2. Run 和 ToolCall 持久化，支持运行追踪。
3. Supabase Auth 与 CM Agent 用户体系映射。
4. Supabase Storage 用于文件型工具输入输出。
5. Supabase Realtime 用于运行状态推送。
