# CM Agent 设计说明

日期：2026-06-18
状态：已批准进入实施计划
开源协议：Apache-2.0

## 项目目标

CM Agent 是一个基于 AgentScope Java 的企业级智能体开源底座。项目面向 Java 后端开发者和企业平台团队，既提供可复用的 Java SDK / Spring Boot Starter，也提供可独立部署的服务端和轻量管理控制台。

第一版重点不是做某个垂直业务智能体，而是把生产可用的基础能力打稳：SDK、Starter、`AgentRuntime` 抽象、工具治理、轻量多租户、RBAC、审计日志、模型配置、AgentScope Java 适配，以及最小控制台工作流。RAG、客服助手、数据分析助手、工作流自动化等业务产品形态都作为后续里程碑构建在这个底座之上。

## 当前上下文

当前工作区是空仓库：`F:\java\cm-agent`。

已确认的外部信息：

- AgentScope Java 是 JVM 智能体框架，提供 ReAct 推理、Harness 基础设施、多智能体编排、MCP/A2A 协议支持等能力。
- AgentScope Java GitHub 仓库说明其支持 ReAct 推理、工具调用、记忆管理和多智能体协作。
- Maven Central 当前可以看到 `io.agentscope` 下的 `agentscope-extensions` 等构件，版本包含 `2.0.0-RC3`。

参考资料：

- [AgentScope Java 文档](https://java.agentscope.io/v2/en/intro.html)
- [AgentScope Java GitHub](https://github.com/agentscope-ai/agentscope-java)
- [Maven Central: agentscope-extensions](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions)

## 核心目标

- 使用 Maven 多模块的模块化单体架构，既能单体部署，也能清晰拆分 SDK、Starter、runtime、persistence、adapter 和 console 边界。
- 提供稳定的 CM Agent 核心接口，避免业务代码直接依赖 AgentScope Java 具体类。
- 默认提供 AgentScope Java v2 适配器，同时保留未来支持 AgentScope 1.x 或其他 runtime 的空间。
- 支持轻量多租户，所有租户数据携带 `tenantId`，模型配置、工具授权、会话和审计都按租户隔离。
- 提供 JWT 登录、RBAC、服务间 API Key，并为 OIDC/SSO 预留扩展点。
- 默认支持 DashScope 原生模型配置和 OpenAI-compatible 模型配置。
- 支持本地工具治理和外部 MCP/A2A 端点配置。
- 同时支持 MySQL 和 PostgreSQL，通过兼容 schema 和自动化迁移测试保证可用性。
- 提供薄管理控制台，包含登录、Agent 管理、工具治理、聊天调试和审计查询。
- 提供生产最低线能力：结构化日志、审计日志、健康检查、Docker Compose、OpenAPI 文档、迁移脚本、中文 README 和示例。

## 文档语言约束

面向生产使用者的文档默认使用中文。README、快速开始、部署指南、运维说明、配置示例、发布说明和项目生成文档都必须优先提供中文版本。后续可以增加英文翻译，但英文文档不能替代或阻塞中文生产文档基线。

## 第一版不做的范围

- 完整工作流编排。
- 独立插件包动态加载。
- 计费、配额、商业套餐。
- 面向终端用户的正式聊天产品。
- 知识库导入、RAG、Embedding、向量检索。
- 复杂 SSO 管理界面。
- 多服务部署拓扑。
- 完整插件市场。

## 推荐路线

第一版采用 Maven 多模块的模块化单体。

这个路线能兼顾生产形态和开源可用性：开发者可以引入 SDK/Starter，平台团队可以部署独立服务，贡献者也能比较容易理解模块边界。后续如果某些模块需要拆成独立服务，可以基于清晰接口逐步演进。

已拒绝的替代路线：

- SDK/Starter 优先：更简单，但企业平台感和演示能力较弱。
- 多服务平台优先：看起来更“生产”，但第一版运行、调试和贡献成本过高。

## 模块结构

### `cm-agent-api`

公共 API 契约模块，供 SDK、server、console 和 examples 共享。

职责：

- 公共 DTO。
- 错误码。
- 分页请求和分页响应。
- 租户上下文模型。
- 当前用户/调用方模型。
- 审计事件模型。
- 如项目决定统一响应格式，则放置统一响应封装。

约束：

- 不依赖 Spring Web。
- 不依赖持久化实现。
- 不依赖 AgentScope Java。

### `cm-agent-core`

稳定领域接口和核心服务模块。

职责：

- `AgentRuntime` 抽象。
- `AgentRunRequest`、`AgentRunResult` 和运行事件模型。
- `ToolRegistry`。
- `ToolExecutor`。
- `ToolAuthorizationPolicy`。
- `PermissionEvaluator`。
- `AuditPublisher`。
- `ModelRegistry`。
- 租户上下文传递抽象。
- 供测试和本地示例使用的默认内存实现。

约束：

- 不直接访问数据库。
- 不直接依赖 AgentScope Java 实现类。
- 尽量保持核心逻辑不依赖 Spring 容器启动即可测试。

### `cm-agent-agentscope-adapter`

默认 Agent runtime 适配模块，底层接 AgentScope Java v2。

职责：

- 实现 `AgentRuntime`。
- 将 `AgentDefinition` 和 `AgentRunRequest` 转换为 AgentScope 运行对象。
- 将 CM Agent 工具元数据桥接到 AgentScope 工具调用机制。
- 将 AgentScope 的事件、工具调用、错误和最终响应映射回 CM Agent 运行事件。
- 隔离所有 AgentScope 专属类型，避免泄漏到 `core`、`server` 或业务项目。

版本策略：

- 在 dependency management 中锁定 AgentScope Java 依赖版本。
- 以 `2.0.0-RC3` 作为初始候选版本，因为它在 2026-06-18 已出现在 Maven Central。
- 在正式实施前和首次公开发布前再次检查 AgentScope Java 最新 release notes。
- 通过 adapter 契约测试确保 AgentScope API 变化不会影响 `core` 或 `server`。

### `cm-agent-persistence`

数据库持久化和迁移模块。

职责：

- Flyway 迁移脚本。
- 租户、用户、角色、权限、API Key、模型配置、Agent 定义、工具定义、工具授权、会话、消息、运行记录、工具调用、审计事件的 repository 实现。
- MySQL 和 PostgreSQL 兼容性测试。

数据库策略：

- 优先使用可移植字段类型。
- 结构化元数据第一版优先以文本 JSON 存储，由应用层序列化和反序列化。
- 数据库特定 SQL 必须隔离在 repository 或迁移变体中。
- 使用 Testcontainers 同时验证 MySQL 和 PostgreSQL。

### `cm-agent-spring-boot-starter`

供业务系统嵌入使用的 Spring Boot Starter。

职责：

- 配置属性。
- 核心服务自动装配。
- 默认安全集成钩子。
- 基于 Spring Bean 或注解的本地工具注册。
- 默认审计发布器接入。
- 可选接入 AgentScope adapter 和 persistence。

目标用法：

Java 后端团队只需引入 Starter，就能在自己的 Spring Boot 应用中嵌入 CM Agent 能力，而不一定要部署独立服务端。

### `cm-agent-server`

可独立部署的 Spring Boot 服务端。

职责：

- REST API。
- 登录认证和 token 签发。
- 服务间 API Key 认证。
- 租户感知请求过滤器。
- RBAC 权限控制。
- Agent 管理。
- 工具管理。
- 聊天调试 / Agent run 接口。
- 审计查询接口。
- OpenAPI 文档。
- Actuator 健康检查。

### `cm-agent-console`

轻量管理控制台。

职责：

- 登录页。
- Agent 列表和编辑页。
- 工具治理页。
- 聊天调试页。
- 审计日志页。

约束：

- 控制台只调用公开 REST API。
- 控制台不能绕过 RBAC 或工具授权。
- 第一版控制台不是面向终端用户的正式聊天产品。

### `cm-agent-examples`

可运行示例模块，面向用户和贡献者。

职责：

- Starter 嵌入示例。
- 独立 server 示例。
- 本地工具示例。
- MCP/A2A 端点配置示例。
- DashScope 模型配置示例。
- OpenAI-compatible 模型配置示例。

## 核心领域模型

### `Tenant`

轻量多租户边界。租户归属数据都必须包含 `tenantId`。租户级配置包括模型供应商、启用工具和安全默认值。

### `User`、`Role`、`Permission`

默认身份和授权模型，服务于开源自部署场景。JWT 登录为控制台和 API 调用签发 token，RBAC 权限保护服务端接口和敏感操作。

权限示例：

- `tenant:read`
- `tenant:update`
- `agent:read`
- `agent:write`
- `agent:run`
- `tool:read`
- `tool:grant`
- `audit:read`
- `apikey:write`

第一版不完整实现 OIDC/SSO，但身份层必须允许未来将外部身份源映射为本地用户和角色。

### `ApiKey`

服务间调用凭证，绑定租户和权限集合。API Key 支持创建、禁用、轮换和审计。

### `AgentDefinition`

Agent 配置对象。

字段：

- `id`
- `tenantId`
- `name`
- `description`
- `systemPrompt`
- `modelProviderId`
- `modelName`
- `temperature`
- `maxIterations`
- `enabled`
- `toolIds`
- `createdBy`
- `updatedBy`

### `ModelProvider` 和 `ModelConfig`

租户级模型供应商配置。

第一版支持：

- DashScope 原生模式。
- OpenAI-compatible 模式。

API Key 等密钥必须加密存储，或委托给可插拔 secret provider。任何 API 响应都不能返回明文密钥。

### `ToolDefinition`

本地工具和外部工具的元数据。

字段：

- `id`
- `tenantId`
- `name`
- `description`
- `type`：`LOCAL`、`MCP` 或 `A2A`
- `inputSchema`
- `riskLevel`：`LOW`、`MEDIUM` 或 `HIGH`
- `enabled`
- `endpoint`
- `createdBy`
- `updatedBy`

### `ToolGrant`

工具授权绑定，用于控制租户、Agent、角色或调用方是否可以调用某个工具。

第一版必须支持租户级授权和 Agent 级授权。角色级授权可以先体现在模型中，在 RBAC 数据可用时实现。

### `Conversation`、`Message`、`Run`、`ToolCall`

聊天调试和运行追踪记录。

`Run` 记录：

- 请求租户和调用方。
- 使用的 Agent。
- 运行状态。
- 开始和结束时间。
- 错误码和错误消息。
- 模型返回 token 用量时记录 token 字段。
- 存在价格配置时记录费用估算字段。

`ToolCall` 记录：

- 工具 ID。
- 工具名称。
- 输入摘要。
- 输出摘要。
- 状态。
- 耗时。
- 授权决策。
- 错误详情。

### `AuditEvent`

追加写入的审计记录，用于生产追踪。

审计事件包括：

- 登录成功和失败。
- API Key 创建、禁用和轮换。
- 租户配置变更。
- 模型配置变更。
- Agent 创建、更新、启用、禁用和运行。
- 工具注册、启用、禁用和授权变更。
- 工具调用。
- 权限拒绝。
- runtime 错误。

## 主数据流

1. 控制台用户或 API 客户端携带 JWT/API Key 发起请求。
2. 服务端解析 `TenantContext` 和 `Principal`。
3. RBAC 校验当前操作权限。
4. 对 Agent run 请求，服务端加载 `AgentDefinition`、租户级 `ModelConfig` 和已授权的 `ToolDefinition`。
5. 服务端创建 `Run` 记录。
6. `AgentRuntime.run()` 接收完整的租户作用域请求。
7. AgentScope adapter 将请求映射为 AgentScope Java v2 运行对象。
8. 每次工具执行前，`ToolAuthorizationPolicy` 检查租户、Agent、调用方和授权状态。
9. 已授权的本地工具在进程内执行；MCP/A2A 工具调用已配置的外部端点。
10. 工具调用、运行事件、错误和最终输出写入持久化存储。
11. `AuditPublisher` 为安全敏感操作和运行过程发布审计事件。
12. 控制台聊天调试页通过同一套 REST API 展示消息、工具调用和错误。

设计原则：

AgentScope Java 负责运行 Agent。CM Agent 负责企业治理边界：租户隔离、权限检查、工具授权、审计日志、API 契约和部署体验。

## REST API 范围

### 认证

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/auth/me`
- `POST /api/api-keys`
- `GET /api/api-keys`
- `POST /api/api-keys/{id}/disable`
- `POST /api/api-keys/{id}/rotate`

### 租户

- `GET /api/tenants`
- `GET /api/tenants/{id}`
- `PATCH /api/tenants/{id}`
- `GET /api/tenants/{id}/model-configs`
- `POST /api/tenants/{id}/model-configs`
- `PATCH /api/tenants/{id}/model-configs/{configId}`
- `POST /api/tenants/{id}/model-configs/{configId}/disable`

### Agent 管理

- `GET /api/agents`
- `POST /api/agents`
- `GET /api/agents/{id}`
- `PATCH /api/agents/{id}`
- `POST /api/agents/{id}/enable`
- `POST /api/agents/{id}/disable`

### 工具

- `GET /api/tools`
- `GET /api/tools/{id}`
- `POST /api/tools`
- `PATCH /api/tools/{id}`
- `POST /api/tools/{id}/enable`
- `POST /api/tools/{id}/disable`
- `GET /api/tools/{id}/grants`
- `POST /api/tools/{id}/grants`
- `DELETE /api/tools/{id}/grants/{grantId}`

### 运行和聊天调试

- `POST /api/agents/{id}/runs`
- `GET /api/runs/{id}`
- `GET /api/runs/{id}/messages`
- `GET /api/runs/{id}/tool-calls`
- `GET /api/conversations`
- `GET /api/conversations/{id}`

运行事件可以通过 Server-Sent Events 引入流式输出。如果第一版实施切片无法完成流式能力，同步 run 加持久化事件也可以作为第一阶段验收口径。

### 审计

- `GET /api/audit-events`

支持过滤条件：

- 时间范围。
- 租户。
- 调用方。
- Agent。
- 工具。
- 事件类型。
- 状态。

## 控制台范围

控制台必须保持轻量。

页面：

- 登录。
- Agent 列表。
- Agent 编辑。
- 工具治理。
- 聊天调试。
- 审计日志。

要求：

- 所有页面调用 REST API。
- RBAC 控制可见页面和可执行操作。
- 聊天调试展示最终回答、消息历史、工具调用、授权失败和 runtime 错误。
- 审计日志过滤能力与审计 API 保持一致。

## 安全和治理

认证：

- 用户使用 JWT。
- 服务间调用使用 API Key。
- 身份层预留 OIDC/SSO 扩展点。

授权：

- RBAC 保护 API 操作。
- Tool grant 保护工具调用。
- 高风险工具必须显式授权后才能被 Agent 使用。

密钥处理：

- 模型 API Key 和外部端点凭证不能通过 API 明文返回。
- Secret 存储必须抽象化，后续可替换为 Vault、KMS 或云厂商 Secret Manager。

审计：

- 所有管理配置变更和 Agent/工具运行行为都要产生审计事件。
- 权限拒绝和工具授权拒绝必须可审计。

租户隔离：

- 所有租户归属查询都必须按 `tenantId` 过滤。
- 服务端请求上下文从登录用户或 API Key 中解析 `tenantId`。
- 测试必须覆盖跨租户访问拒绝场景。

## 持久化和迁移

支持数据库：

- MySQL。
- PostgreSQL。

迁移方式：

- 使用 Flyway 定义 schema。
- Repository 测试使用 Testcontainers 同时跑 MySQL 和 PostgreSQL。
- Schema 命名和索引设计避免依赖数据库特性；确实需要时必须通过明确的迁移变体隔离。

第一版表：

- `tenants`
- `users`
- `roles`
- `permissions`
- `user_roles`
- `role_permissions`
- `api_keys`
- `model_configs`
- `agent_definitions`
- `tool_definitions`
- `tool_grants`
- `conversations`
- `messages`
- `runs`
- `tool_calls`
- `audit_events`

## 测试策略

### 单元测试

覆盖：

- 权限判断。
- 租户上下文传递。
- 工具授权。
- 工具注册表行为。
- Agent run 请求组装。
- 审计事件生成。
- 模型供应商选择。

### 持久化测试

覆盖：

- MySQL Flyway 迁移成功。
- PostgreSQL Flyway 迁移成功。
- Repository CRUD。
- 租户隔离。
- 审计事件追加写入和查询。

### 服务端集成测试

覆盖：

- 登录和 token 刷新。
- API Key 认证。
- RBAC 成功和拒绝路径。
- Agent CRUD。
- 工具授权。
- 使用 fake runtime 的 Agent run。
- 管理操作和运行操作后的审计事件生成。

### 适配器契约测试

覆盖：

- CM Agent 请求到 AgentScope adapter 输入的映射。
- 工具调用桥接。
- 错误映射。
- 运行事件映射。

这些测试优先使用 fake model/runtime，避免 CI 依赖真实大模型凭证。

### 控制台冒烟测试

覆盖：

- 登录。
- 创建或编辑 Agent。
- 授权工具。
- 发起聊天调试请求。
- 查看审计日志。

## 生产最低线

第一版必须包含：

- 结构化应用日志。
- 持久化审计事件。
- Spring Boot Actuator 健康检查。
- OpenAPI 文档。
- server、MySQL、PostgreSQL 开发 profile 的 Docker Compose。
- 本地开发配置。
- DashScope 原生模型配置示例。
- OpenAI-compatible 模型配置示例。
- 中文 README，包含快速开始、架构概览和生产说明。
- 中文生产文档，覆盖部署、运维、配置和发布说明。
- Apache-2.0 license 文件。

## 第一阶段实施切片

实施计划应产出一个可工作的薄纵切：

1. Maven 多模块骨架。
2. 核心接口和领域模型。
3. Spring Boot Starter 自动装配。
4. MySQL 和 PostgreSQL 持久化迁移。
5. 服务端认证、租户上下文和 RBAC 基线。
6. Agent definition CRUD。
7. Tool definition 和 grant 管理。
8. 基于 fake runtime 的 Agent run 接口。
9. AgentScope adapter 模块，包含契约测试和受控集成路径。
10. 审计事件发布和查询。
11. 最小控制台页面：登录、Agent 编辑、工具治理、聊天调试和审计查看。
12. 示例和中文文档。

## 验收标准

- 开发者可以通过中文文档中的命令在本地运行独立 server。
- 开发者可以在 Spring Boot 示例中引入 Starter 并注册本地工具。
- MySQL 和 PostgreSQL 迁移在自动化测试中通过。
- 租户管理员可以登录、配置 Agent、授权工具、发起聊天调试请求，并看到审计记录。
- 服务客户端可以使用绑定租户的 API Key 调用 run API。
- 测试覆盖跨租户读写拒绝。
- Agent 缺少工具授权时，工具调用被拒绝。
- AgentScope Java 专属类型不出现在 `cm-agent-core` 公共接口中。
- 公开中文文档清楚说明第一版范围和不做范围。

## 风险和缓解措施

风险：AgentScope Java v2 仍处于 RC 阶段，API 可能变化。

缓解：所有直接 AgentScope 使用都隔离在 `cm-agent-agentscope-adapter`，锁定依赖版本，并为 adapter 编写契约测试。

风险：双数据库支持会增加第一版复杂度。

缓解：schema 保持可移植，第一版避免数据库专属 JSON 能力，要求 MySQL 和 PostgreSQL 都有 Testcontainers 覆盖。

风险：控制台范围容易膨胀。

缓解：控制台只做管理和调试页面，用来验证后端能力，不做终端用户聊天产品。

风险：安全范围容易过大。

缓解：第一版实现 JWT、RBAC、API Key 和 OIDC 扩展点，完整 SSO 管理界面后置。

风险：工具执行在企业环境中可能带来安全隐患。

缓解：工具必须有元数据、风险等级、显式授权、执行前鉴权和可审计的工具调用记录。
