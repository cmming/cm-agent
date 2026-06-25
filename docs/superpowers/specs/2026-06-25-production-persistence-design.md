# 生产化持久化设计说明

日期：2026-06-25
状态：待用户复核

## 背景

当前 CM Agent 已完成第一阶段薄纵切：Maven 多模块、核心领域模型、认证、Agent/Tool REST API、fake runtime、审计、控制台、Starter 和 AgentScope adapter 契约都已经具备。项目的 Flyway 迁移脚本已经包含 `agent_definitions`、`tool_definitions`、`tool_grants`、`runs`、`tool_calls` 和 `audit_events` 等生产表结构。

现阶段主要缺口是运行时管理数据仍集中在 `cm-agent-server` 的 `InMemoryPlatformStore` 中。Agent、Tool 和 ToolGrant 在服务重启后会丢失，这会阻塞生产可用性，也会让后续 API Key、真实 runtime、MCP/A2A 和 Run 追踪缺少稳定基础。

## 目标

下一阶段采用生产可用性优先路线，把管理链路从内存存储推进到可持久化、可测试、可按租户隔离的后端基础能力。

本设计的首个交付目标是：

- 抽象 Agent、Tool、ToolGrant 的 repository 接口。
- 提供 JDBC 实现，复用现有 Flyway schema。
- 保持现有 REST API 路径和主要响应结构不变。
- 保留 local/test 的内存实现，避免破坏本地快速启动。
- 在 production/prod profile 下要求使用持久化配置，避免误用内存存储。
- 先以 `AgentDefinition` JDBC repository 作为第一实施切片，再扩展 Tool 和 Grant。

## 非目标

本阶段不接真实大模型运行。

本阶段不实现 RAG、Embedding、向量检索或业务智能体模板。

本阶段不重做控制台 UI，只保证现有控制台调用的 API 继续可用。

本阶段不一次性完成 API Key、Run、ToolCall 的全量生产化。它们会排在 Agent/Tool/Grant 持久化之后。

## 方案选择

### 方案一：增量持久化 Agent/Tool/Grant

先把 Agent、Tool、ToolGrant 从 `InMemoryPlatformStore` 拆出 repository 接口，并逐个提供 JDBC 实现。

优点是范围小、TDD 容易落地、每个提交都能保持系统可运行。缺点是短期内 Run 和 ToolCall 仍不是完整生产记录。

这是推荐方案。

### 方案二：一次性全平台持久化

一次性完成 Agent、Tool、Grant、Run、ToolCall、Audit 和 API Key。

优点是目标完整。缺点是范围过大，容易把 schema、repository、controller、认证、审计和控制台改动混在一起，验证成本高。

### 方案三：先做 API Key 和 RBAC

优先补服务间调用、权限和跨租户拒绝。

优点是安全治理进展明显。缺点是底层管理数据仍在内存中，生产基础不稳。

## 推荐路线

采用方案一，并按以下顺序迭代：

1. `AgentDefinitionRepository` 接口和 JDBC 实现。
2. `ToolDefinitionRepository` 接口和 JDBC 实现。
3. `ToolGrantRepository` 接口和 JDBC 实现。
4. server controller 从 `InMemoryPlatformStore` 切换到 repository 接口。
5. production/prod profile 下增加持久化配置校验。
6. 增加跨租户读取和写入拒绝测试。
7. 后续进入 API Key 创建、禁用、轮换，再做 Run/ToolCall 持久化。

## 架构设计

### Repository 接口

在 `cm-agent-core` 中新增稳定 repository 接口，避免 `cm-agent-server` 直接绑定 JDBC 实现：

- `AgentDefinitionRepository`
- `ToolDefinitionRepository`
- `ToolGrantRepository`

这些接口只使用 `cm-agent-core` 领域模型和 JDK 类型，不依赖 Spring Web、Spring JDBC 或数据库实现。

### JDBC 实现

在 `cm-agent-persistence` 中新增 JDBC repository：

- `JdbcAgentDefinitionRepository`
- `JdbcToolDefinitionRepository`
- `JdbcToolGrantRepository`

实现使用现有 `JdbcClient` 风格，保持和 `JdbcAuditEventRepository` 一致。SQL 必须显式带 `tenant_id` 条件，所有按 ID 查询都使用 `(tenant_id, id)` 范围，避免跨租户读取。

### 内存实现

`InMemoryPlatformStore` 的 Agent/Tool/Grant 能力逐步拆成内存 repository 或适配到 repository 接口。它只用于 local/test 或无 DataSource 的开发场景。

production/prod profile 不允许使用 Agent/Tool/Grant 的内存 repository。缺少 DataSource 或 JDBC repository 时，应用应启动失败并给出明确错误。

### 服务端集成

`AgentController`、`ToolController` 和 `RunController` 不再直接依赖 `InMemoryPlatformStore` 管理 Agent/Tool/Grant。它们改为注入 repository 接口。

审计链路暂不强制迁移。已有 `JdbcAuditEventRepository` 可以继续作为后续增强点，当前设计重点是先移除管理数据的内存风险。

## 数据设计

本阶段复用 `V1__init_schema.sql` 中已有表：

- `agent_definitions`
- `tool_definitions`
- `tool_grants`

第一切片实现 `agent_definitions` 时，需要处理 `model_provider_id` 外键。测试和本地持久化环境必须插入默认 `tenants` 和 `model_configs` 记录后再创建 Agent。

默认数据初始化要满足两个原则：

- 只在缺失默认租户或默认模型配置时补齐，避免覆盖用户数据。
- 生产环境不创建默认管理员密码，不绕过现有 bootstrap admin 安全约束。

## API 行为

现有 API 路径保持不变：

- `GET /api/agents`
- `POST /api/agents`
- `GET /api/agents/{id}`
- `GET /api/tools`
- `POST /api/tools`
- `POST /api/tools/{id}/grants`
- `POST /api/agents/{agentId}/runs`

第一切片只改变 Agent 数据来源，不改变外部契约。

Agent 创建成功后必须写入数据库。随后同一租户查询能读到该 Agent，其他租户不能通过列表或详情接口读到它。

## 错误处理

Repository 层不把缺失数据当作异常抛出，按接口返回 `Optional.empty()` 或空列表。

写入违反数据库约束时允许抛出 Spring 数据访问异常，由 server 层在后续切片统一映射为 API 错误响应。

controller 层现有 `404 Agent 不存在`、`401 未登录或令牌无效`、`403 权限不足` 语义保持不变。

## 测试策略

每个 repository 按 TDD 实施：

1. 先写失败测试。
2. 验证测试因 repository 缺失或行为缺失失败。
3. 写最小 JDBC 实现。
4. 运行单个 repository 测试。
5. 再运行受影响 controller 测试。

第一切片测试覆盖：

- 保存 Agent 后可以按租户和 ID 读取。
- 列表只返回当前租户 Agent。
- 其他租户读取同一 ID 返回空。
- Agent 的 `toolIds` JSON 能正确写入和读回。
- `POST /api/agents` 和 `GET /api/agents` 行为保持现有测试通过。

后续 Tool 和 Grant 切片测试覆盖：

- Tool CRUD 的租户隔离。
- Grant 去重。
- Agent 授权工具后 run 链路只加载已授权工具。
- 未授权工具不会进入 runtime request。

## 验收标准

- `mvn -q -pl cm-agent-persistence -Dtest=JdbcAgentDefinitionRepositoryTest test` 通过。
- `mvn -q -pl cm-agent-server -Dtest=AgentControllerTest test` 通过。
- `mvn -q test` 通过，或明确记录因本地 Docker/Testcontainers 环境导致的外部依赖失败。
- production/prod profile 下没有数据库配置时不能静默使用内存 repository。
- local/test 仍能维持快速开发体验。
- REST API 外部路径不变。
- 跨租户读取 Agent 被测试覆盖。

## 后续里程碑

完成 Agent/Tool/Grant 持久化后，下一阶段进入：

1. API Key 创建、禁用、轮换和哈希存储。
2. Run 与 ToolCall 持久化，支持运行追踪。
3. 审计查询过滤增强。
4. 真实 AgentScope runtime 和模型配置密钥保护。
5. MCP/A2A 工具接入。
