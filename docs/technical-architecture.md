# CM Agent 技术路线与实现逻辑

本文面向参与开发、部署和运维 CM Agent 的人员，说明当前代码已实现的技术路线、模块职责、关键运行链路和生产边界。文中“当前能力”以代码及正式配置/运维文档为准；路线图中标注为后续阶段的项目不应视为已交付能力。

## 1. 项目定位与技术路线

CM Agent 是基于 AgentScope Java 的企业级智能体底座。当前路线不是将模型调用直接暴露为一个简单的 HTTP 代理，而是在模型运行时外侧建立稳定的领域模型、租户隔离、RBAC、工具治理、审计和可替换持久化边界。

技术路线按以下顺序推进：

1. **领域优先**：`cm-agent-core` 定义 Agent、模型配置、工具、运行记录和仓储接口，不依赖 Web、Spring Security 或 JDBC。
2. **框架可插拔**：Starter 提供默认领域能力；Server 负责 Spring MVC、安全、配置和运行编排；AgentScope 通过独立 Adapter 接入，避免核心层绑定某个模型框架。
3. **安全与数据先行**：所有受保护请求先解析认证主体，再以主体 tenant 约束读取、写入和授权；运行、工具调用与审计均可落入 JDBC 数据库。
4. **运行时受治理**：真实模型只能得到经授权的工具描述；工具实际调用时再次鉴权，而不是信任模型输出或配置中的 endpoint。
5. **生产化演进**：阶段 1 的内存实现和 Fake Runtime 用于本地/测试；阶段 2 已形成 JDBC、Flyway、严格审计和 profile 安全边界；阶段 3 已接入 AgentScope Java 真实同步单轮运行。可观测性、正式交付流水线等属于后续路线。

当前不承诺多轮会话持久化、流式 REST、人工介入（HITL）或手动取消；超时和取消也不能证明具有外部副作用的工具已回滚。

## 2. 技术栈与模块边界

项目使用 Java 21、Maven 多模块构建和 Spring Boot 3.5.0。Web 层采用 Spring MVC、Spring Security、Validation、Actuator 与 springdoc OpenAPI；JWT 使用 JJWT；持久化采用 Spring JDBC、Flyway，支持 PostgreSQL 16 与 MySQL 8.4；真实智能体运行时采用 AgentScope Java 2.0.0，并支持 OpenAI Compatible 与 DashScope Provider。

| 模块 | 职责 | 不应承担的职责 |
| --- | --- | --- |
| `cm-agent-api` | 跨模块共享 API 类型，如认证主体、租户上下文、分页与错误码 | 领域编排、数据库访问 |
| `cm-agent-core` | 领域 `record`、运行时/仓储接口、权限与工具授权策略 | Spring MVC、安全会话、JDBC 实现 |
| `cm-agent-persistence` | `Jdbc*Repository`、行映射、Flyway 迁移、数据库集成测试 | Controller 和 Web DTO |
| `cm-agent-spring-boot-starter` | 配置属性和可覆盖的默认 Bean | 生产环境专有编排 |
| `cm-agent-agentscope-adapter` | AgentScope 与核心 `AgentRuntime`、工具网关之间的适配 | 租户身份来源和 HTTP 路由 |
| `cm-agent-server` | HTTP API、安全、审计、运行编排、Repository/Runtime 装配 | 在 Controller 中直接写 SQL |
| `cm-agent-console` | 轻量管理控制台的静态资源与界面 | 绕过服务端授权 |
| `cm-agent-examples` | Starter 接入示例 | 生产运行依赖 |

依赖方向保持由外向内：`server → starter/persistence/console/agentscope-adapter → core → api`。其中 Adapter 对 AgentScope 依赖标记为 optional，Server 在真实运行场景中再显式引入 Provider 扩展。

## 3. 核心领域与治理模型

核心领域对象以 Java `record` 表达，构造器负责非空、范围和集合防御性复制等不变量。关键对象包括：

- **AgentDefinition**：智能体提示词、模型配置引用、最大迭代数、启用状态等。
- **ModelConfig**：Provider、Base URL、模型名和启用状态等非敏感模型元数据。模型 API Key 不应作为业务数据或 API 返回值保存。
- **ToolDefinition / ToolGrant**：工具 Schema、风险级别、启用状态，以及工具到 Agent 的授权关系。
- **RunRecord / RunToolCall**：一次运行及其工具调用的持久化事实；状态包括运行中、成功、失败和拒绝等。
- **AuditEvent**：不可变审计记录，保留主体、资源、结果与时间，用于追溯管理及运行行为。

仓储接口位于 `core.repository`，所有读写都显式带 tenant 条件。服务端从 JWT 认证主体取得 tenant 和权限，客户端输入不能覆盖当前主体的 tenant。权限判断分为普通资源访问的 `PermissionEvaluator` 与工具调用的 `ToolAuthorizationPolicy`；两者均是可替换的核心策略。

## 4. 一次 Agent 运行的实现链路

运行入口为 `RunController`，实际编排位于 `RunExecutionService`。一条同步单轮请求的主要过程如下：

```text
Bearer JWT
  → Security 解析 PrincipalRef（tenant、用户、权限）
  → RunController 校验请求并调用 RunExecutionService
  → 按 tenant 查询已启用 Agent 与模型配置
  → 读取 ToolGrant，筛选并预授权可用工具
  → RunPersistenceService 写入 RUNNING Run 与启动审计
  → AgentRuntime 执行（Fake 或 AgentScope）
  → 完成 Run、批量保存 ToolCall、写入完成/失败/拒绝审计
  → 对响应中的输入、输出和错误信息脱敏后返回
```

`RunPersistenceService` 将持久化拆为启动和完成两个阶段：启动阶段创建 `RUNNING` 记录并写入“运行已启动”审计；完成阶段更新最终状态、写入工具调用并追加最终审计。在 JDBC 模式下每个阶段各自使用事务，从而保证该阶段内的运行事实与审计一致。审计是严格依赖：审计持久化不可用时请求不能被伪装为成功，服务会按受控错误语义失败。

运行时异常会尝试将运行记录收口为 `FAILED`，并对日志、持久化数据和响应中的敏感内容进行脱敏。运行历史和工具调用详情查询同样按 tenant 和 Agent 限定，并通过有界 cursor 分页避免无界扫描。

## 5. 真实运行时与工具调用

`AgentRuntime` 是核心运行时接口。Starter 在显式启用 Fake Runtime 时提供 `FakeAgentRuntime`，用于本地开发和测试；生产/类生产环境使用 `AgentScopeRuntimeAdapter`。真实 Adapter 的职责是：

1. 按 `tenantId + modelConfigId` 向 `ModelCredentialProvider` 请求外部凭据；未找到凭据时返回受控的“模型凭据不可用”结果，不泄露密钥。
2. 将核心 `AgentRunRequest` 转换为 AgentScope 单轮 ReAct 执行规格，并依据模型/工具 timeout 与最大尝试次数运行。
3. 通过 `AgentScopeToolBridge` 将 AgentScope 的工具调用转换为 `ToolInvocationGateway` 请求，再把结果映射回 `ToolCallRecord`。

工具治理有两层：运行前，`RunExecutionService` 只将已授权工具传入模型；运行中，`GovernedToolInvocationService` 会基于当前主体、Agent、工具和授权关系再次检查。工具定义中的 endpoint 是元数据，不会被框架自动发起网络调用；真正的 `ToolExecutor` 必须由应用明确实现和注册。对于写外部系统的工具，下游应支持幂等键，并自行保证重试、超时或取消后的业务一致性。

## 6. 安全、租户与审计边界

服务端使用 JWT 建立 `PrincipalRef`，再将 tenant 贯穿 Controller、Service、Repository 和审计记录。关键约束如下：

- 资源查询、运行记录、工具调用和审计记录均以认证主体 tenant 过滤，禁止跨租户读取。
- Controller 负责请求校验、认证主体解析、权限入口和响应状态；不直接连接数据源或拼接 SQL。
- 拒绝访问和运行状态变化需要审计；审计不可用采用严格失败语义。
- JWT 验证密钥、数据库密码和模型 API Key 只能由受控外部配置或 Secret Manager 注入，禁止写入 Git、镜像、日志、审计或 API 响应。
- `local`/`test` 可以显式启用 bootstrap admin 与 Fake Runtime；`prod`、`production`、`supabase` 必须关闭 bootstrap admin 和开发 JWT fallback，使用 JDBC 与真实 AgentScope Runtime。

生产认证由外部身份系统或受控认证服务签发 Bearer JWT。仅在本地/测试明确启用时才开放 bootstrap 登录入口；生产调用方必须携带外部签发的令牌。

## 7. 数据库、Flyway 与部署

JDBC 模式由 `JdbcPersistenceConfiguration` 创建数据源并在启动时执行 Flyway。迁移位于 `cm-agent-persistence/src/main/resources/db/migration`：

- `V1__init_schema.sql` 建立租户、用户、角色、权限、模型、Agent、工具、运行、工具调用和审计表。
- `V2__add_runtime_query_indexes.sql` 为 Run、ToolCall、Audit 的租户范围查询补充索引。
- `V3__add_tool_calls_created_at_index.sql` 为工具调用明细的时间/ID 排序补充联合索引。

已发布迁移不可修改；结构变化必须新增更高版本的迁移，并同步更新 JDBC 实现、迁移测试和部署文档。PostgreSQL/MySQL 集成测试以及 Docker/Compose/Testcontainers 验证需要在 Rocky Linux 虚拟机的容器环境中执行，不以本机 Docker Desktop 代替。

部署时应显式选择 profile。`local` 和 `test` 用于开发/自动化测试；`postgres`、`mysql` 用于 Rocky VM 集成验证；`production`、`prod` 与 `supabase` 是严格 profile。生产必须使用受控外部 YAML 或 Secret Manager 注入 JDBC、JWT 和模型凭据，并在切流前检查 Flyway 历史、数据库连通性、模型凭据和 `GET /actuator/health`。

## 8. 控制台、API 与当前边界

轻量控制台由 Server 引入，提供登录、能力概览、Agent/Tool 的列表与创建、授权、同步运行调试、运行历史、工具调用详情和审计游标分页。控制台只展示已交付的后端能力；当前不提供编辑、删除、手动取消或流式运行。JWT 只保留在页面内存中，刷新或关闭页面后需要重新登录。

服务还提供 OpenAPI 页面和 Actuator 健康检查。生产 profile 默认不应公开 API 文档，应由外围网关、网络策略或受控运维入口提供访问控制。

下一阶段重点是 metrics、集中日志/追踪、告警、备份恢复、容量与数据保留治理；后续才是可重复 CI/CD、发布回滚、性能/故障演练和稳定性门禁。具体运行配置请参阅[配置说明](configuration.md)、[部署指南](deployment.md)、[运维说明](operations.md)与[路线图](roadmap.md)。
