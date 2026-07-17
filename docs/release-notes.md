# 发布说明

## 0.1.0-SNAPSHOT：阶段3真实 AgentScope Runtime

本快照在阶段2生产持久化与安全收口基础上，接入 AgentScope Java 2.0.0 真实 Runtime。第一阶段底座和阶段2的 JDBC/Flyway、安全、多租户、权限与严格审计边界继续保持。

### 本次变更

- 轻量控制台升级为面向使用者的可操作管理控制台，采用独立登录页、左侧导航、能力总览和分模块管理布局。
- 控制台覆盖当前用户、Agent 列表/详情/创建、Tool 列表/创建/授权、Agent 执行、运行历史/详情/工具调用和审计游标分页；健康检查与 OpenAPI 作为辅助入口。
- 控制台使用内存令牌、统一 `401` 失效处理和纯文本 DOM 渲染，不持久化 JWT、用户名或密码；补充窄屏响应式布局和键盘焦点样式。
- 本次控制台改造未新增后端业务接口，不提供编辑、删除、手动取消、流式输出、多轮会话或 HITL。
- `agentscope.version` 升级到 `2.0.0`，接入 OpenAI Compatible 与 DashScope Provider，提供同步单轮 ReAct 运行。
- 通过 `tenantId + modelConfigId` 调用外部 `ModelCredentialProvider` 获取模型凭据；默认凭据为空时启动 fail-fast，`model_configs` 不保存明文 API Key。
- 生产 profile 使用 `fake-runtime-enabled=false` 与 `agentscope-enabled=true`；fake runtime 继续仅服务本地和测试。
- 工具每次调用重新授权并记录严格审计，endpoint 元数据不自动执行；模型、工具 timeout 和 Provider 故障按固定结果语义收口。

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
- 真实 Runtime 当前只支持同步单轮；不承诺多轮会话持久化、流式 REST、HITL 或手动取消。
- AgentScope 2.0.0 工具层的通用取消信号不能证明外部副作用已停止；有副作用的工具必须使用 `runId`、`toolCallId` 或业务键保证幂等。
- 模型凭据只能使用 `${MODEL_API_KEY}` 一类 Secret 占位符或自定义 `ModelCredentialProvider` 注入，不得进入数据库兼容字段、Git、日志、审计或 API。

### 未包含范围

以下内容不属于本次阶段3发布：

- 多轮会话持久化、流式 REST、HITL 和手动取消。
- 阶段4 metrics、集中式日志与追踪、备份恢复自动化、容量治理和应用自动归档。
- 阶段5 CI/CD 交付流水线、发布自动化、稳定性工程和正式版本承诺。

详细边界见[中文路线图](roadmap.md)。
