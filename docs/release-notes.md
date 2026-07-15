# 发布说明

## 0.1.0-SNAPSHOT：阶段2生产运行时收口

本快照在第一阶段底座之上完成“生产持久化与安全收口”。第一阶段仍提供 Maven 多模块工程、核心领域接口、Spring Boot Starter、独立服务端、轻量控制台、工具治理、多租户/RBAC 基线和 fake runtime。

### 本次变更

- Run、ToolCall、Audit 接入 JDBC Repository，并保持每次读写的 tenant 隔离。
- 通过 Flyway 新增 `V2__add_runtime_query_indexes.sql` 和 `V3__add_tool_calls_created_at_index.sql`，为运行、工具调用和审计查询增加租户范围索引。
- Run 启动与完成采用两段式持久化：启动阶段记录 `RUNNING` 与启动审计，完成阶段更新结果、写入 ToolCall 并记录完成/失败审计。
- Run 和 Audit 查询支持有界 cursor 分页；Run 详情返回同 tenant 的 ToolCall。
- 审计写入失败保持严格语义，API 返回 `503 Service Unavailable`；错误、输入、输出和日志经过敏感信息脱敏。
- 收口 JWT secret、profile、bootstrap admin、生产 JDBC 和错误响应边界；`production`、`prod`、`supabase` 必须使用 JDBC，禁用 bootstrap admin 和开发 JWT fallback。
- 公共 `application.yml` 不再默认选择 `local`；部署应通过 `spring.profiles.active`，`CM_AGENT_PROFILE` 仅作为兼容选择器。

### 数据库迁移影响

- 不修改已经发布的 `V1__init_schema.sql`。
- 新增 V2、V3 迁移只增加 `runs`、`tool_calls`、`audit_events` 的查询索引；V1 已建立对应表和基础租户约束。
- JDBC 应用启动时由 Flyway 执行迁移。升级前应备份数据库、核对 `flyway_schema_history`，并准备迁移失败处理与恢复预案。
- 生产可将迁移账号与运行账号分离；连接信息、密码和 JWT secret 只从受控外部 YAML 或 secret manager 注入。

### 兼容性与安全注意事项

- `memory` 仍只用于开发和测试，重启会丢失 Run、ToolCall 和 Audit，不适用于生产。
- 现有 API 的认证、权限、租户过滤和审计约束继续生效；新增的 cursor 由服务端生成，调用方不应自行构造。
- 审计写入失败不再被忽略，会导致请求返回 `503`；部署和告警系统应将其视为依赖不可用。
- 生产 profile 不允许 bootstrap admin、开发 JWT fallback 或可用的固定凭据。文档和配置示例仅使用占位符。
- 真实 AgentScope runtime 尚未接入；当前运行结果仍来自 fake runtime，不能据此承诺真实模型、工具执行或流式能力。

### 未包含范围

以下内容不属于本次阶段2发布：

- 阶段3真实 AgentScope runtime 及其模型/工具适配。
- 阶段4 metrics、集中式日志与追踪、备份恢复自动化、容量治理和应用自动归档。
- 阶段5 CI/CD 交付流水线、发布自动化、稳定性工程和正式版本承诺。

详细边界见[中文路线图](roadmap.md)。
## 阶段 3：真实 AgentScope Runtime

- 接入 AgentScope Java 2.0.0-RC3 的真实同步 `ReActAgent` 运行桥接，支持 OpenAI 兼容模型配置、Agent 参数映射、超时和受控失败结果。
- Starter 增加真实 runtime 条件装配；默认仍关闭，生产凭据必须通过外部配置提供。
- 本阶段不新增 Flyway 迁移，不提供流式输出、会话持久化或工具执行器持久化。
