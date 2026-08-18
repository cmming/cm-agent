# 发布说明

## 0.1.0-SNAPSHOT：阶段3真实 AgentScope Runtime

本快照在阶段2生产持久化与安全收口基础上，接入 AgentScope Java 2.0.0 真实 Runtime。第一阶段底座和阶段2的 JDBC/Flyway、安全、多租户、权限与严格审计边界继续保持。

### 本次变更

- 新增示例模块 `cm-agent-examples/dashscope-mcp-agent`：演示 AgentScope Java 智能体使用内置 `McpClientBuilder` 以 Streamable HTTP 协议连接外部 MCP 服务（示例地址 `http://localhost:8088/api/mcp`）、注册其时间查询等工具，并由阿里云百炼 DashScope `qwen3.7-plus` 模型驱动 `ReActAgent` 自动决策调用。示例通过独立 `main` 方法运行,不依赖 CM Agent Server，也不经过其租户隔离、权限与审计链路；示例中的模型 API Key 为一次性本地联调值，生产场景应改为受控配置或密钥管理服务读取，本项不改变生产 API、数据库 Schema 或现有工具治理语义。该模块单独锁定 `io.modelcontextprotocol.sdk:mcp-core`/`mcp-json-jackson2` 为 `0.17.0`（与 `agentscope-core:2.0.0` 实际编译依赖的版本一致），避免与父 POM 为 `cm-agent-server` 自身 MCP Streamable HTTP Server 管理的 `2.0.0` 版本发生二进制不兼容（`McpSchema.Tool#inputSchema()` 返回类型不同导致的 `NoSuchMethodError`）；不修改父 POM 的 `mcp.version`，不影响 `cm-agent-server` 现有 MCP 端点。
- 新增面向开发者的 LOCAL 与 HTTP 工具开发指南；完善可运行的 LOCAL `echo`/`add` 多工具示例，并新增通过公开 REST API 创建和调试 HTTP 工具的客户端示例。本项不改变生产 API、数据库 Schema 或现有工具治理语义。
- 新增动态 HTTP 工具：支持 GET/POST、嵌套 JSON Schema、本地引用、JSON Pointer 参数映射、缺失/null 默认值、PATH/QUERY/HEADER/BODY 目标及 `secret/...` Header 引用；创建与配置保存保持原子性和租户内工具名称唯一。
- 动态 HTTP 工具统一使用扁平 `parameters` 定义：由 `id + parentId` 表达对象和数组关系，顶层 `requestLocation` 直接声明 PATH、QUERY、HEADER、BODY 或 BODY_ROOT，服务端自动生成输入 Schema；不再接收或执行 `inputSchema + parameterMappings`、`sourcePointer`、`targetPointer` 或 `nodeRole`，并通过 `BODY_ROOT` 支持 `[{"p1":"v1"}]` 一类根数组请求。
- HTTP 工具允许提交空 `parameters: []`，用于没有 PATH、QUERY、HEADER 或 BODY 输入的接口；服务端为其生成禁止额外字段的空对象 Schema，调用输入固定为 `{}`。
- 新增受治理 HTTP 执行器，默认关闭并要求主机白名单；执行时校验 SSRF 风险地址、同源重定向、总超时和响应上限，输出经过结构化脱敏。部署仍需以 egress 防火墙、受控 DNS 或代理防御 DNS TOCTOU。
- 新增默认关闭的 MCP 2.0 Streamable HTTP 服务端点。启用时必须配置 Origin/Host 白名单，端点保持 JWT 认证、`tool:mcp:invoke` 授权、多租户目录隔离和严格 MCP 调用审计；每个请求无状态构建并在完成后关闭 transport/server。
- 新增 MCP 发布/取消发布与 HTTP/LOCAL 单工具调试。调试需要 `tool:debug`，HIGH 风险工具要求完全匹配的名称确认；发布管理需要 `tool:grant`。取消发布、禁用或配置漂移会在下次 MCP 调用立即生效。
- 工具调试失败响应不再统一隐藏为“工具调试失败”：控制台显示经过脱敏的具体原因、稳定错误码和错误编号；服务端使用同一错误编号记录租户、工具、状态及脱敏诊断信息，未分类异常只在后台保留脱敏堆栈。
- REST 失败响应统一新增 `errorId`，并通过 `X-Request-Id` 回传调用关联编号；控制台在 `5xx` 响应含有结构化消息时展示消息、错误码和错误编号。Agent 运行、受治理工具调用及 MCP 工具调用失败也统一写入脱敏诊断日志，便于按运行、工具调用和租户上下文定位。
- 新增工具编辑、删除与 Agent 解除关联：编辑和解除关联需要 `tool:grant`，删除需要 `tool:delete`；仍被 Agent 引用的工具返回明确的 `409 Conflict` 且无副作用，必须先在 Agent 详情确认解除关联。已有调用历史的工具返回另一条明确的 `409 Conflict`，保留工具定义、运行历史、调用记录和审计链路，控制台不会将其误判为可解除的关联冲突。
- 工具编辑保持工具 ID、租户、类型和创建人不变，LOCAL 工具不可改名。HTTP 工具编辑必须提交有效 HTTP 配置，其他类型拒绝 HTTP 配置；已发布 LOCAL 工具可保持或取消原 MCP 发布状态，未发布 LOCAL 工具仍通过独立发布操作管理。
- 工具更新接口直接返回本次写命令提交的定义、HTTP 配置与发布状态快照，不再在成功审计后重新查询，因此同一工具的并发更新不会互相污染响应，也不会因随后删除而把已成功更新误报为 `500`。
- 成功删除采用安全软删除：管理面立即不可见并释放原名称，但保留工具定义墓碑作为 `tool_calls` 外键锚点。删除前已开始的调用即使在解除关联和删除后才持久化，仍会完整进入运行历史；已有调用历史的删除 `409` 语义保持不变。
- 非生产 MySQL 固定目录的内置 LOCAL 示例删除后可按固定租户、固定 ID、原名称和类型受控原位恢复，安装与审计保持同一事务；普通工具保存不能复活墓碑，PostgreSQL、MySQL 与 memory 的主键语义保持一致。
- Agent 工具关联的内存更新采用原子变更，JDBC 更新在同一事务内锁定 Agent 行；授权、撤销和审计共享事务边界，避免同一 Agent 的不同工具在多实例并发下发生丢失更新。工具更新与删除统一锁定工具行，更新命中零行时返回明确的不存在响应。
- 轻量控制台升级为面向使用者的可操作管理控制台，采用独立登录页、左侧导航、能力总览和分模块管理布局。
- 控制台覆盖当前用户、Agent 列表/详情/创建、Tool 列表/创建/编辑/删除/授权与解除关联、Agent 执行、运行历史/详情/工具调用和审计游标分页；健康检查与 OpenAPI 作为辅助入口。
- HTTP Tool 注册与编辑表单改为树形参数编辑器，支持在 OBJECT/ARRAY 节点内直接添加子参数并按层级缩进展示；页面根据 `parentId` 还原树，提交时自动转为扁平参数数组。表单同时提供类型、请求位置、默认值、示例值及包含 PATH、QUERY、BODY_ROOT 根数组的完整示例，并已移除旧版 Schema 与映射入口。
- 控制台使用内存令牌、统一 `401` 失效处理和纯文本 DOM 渲染，不持久化 JWT、用户名或密码；补充窄屏响应式布局和键盘焦点样式。
- 控制台仍不提供手动取消、流式输出、多轮会话或 HITL。
- `agentscope.version` 升级到 `2.0.0`，接入 OpenAI Compatible 与 DashScope Provider，提供同步单轮 ReAct 运行。
- 通过 `tenantId + modelConfigId` 调用外部 `ModelCredentialProvider` 获取模型凭据；默认凭据为空时启动 fail-fast，`model_configs` 不保存明文 API Key。
- 生产 profile 使用 `fake-runtime-enabled=false` 与 `agentscope-enabled=true`；fake runtime 继续仅服务本地和测试。
- 工具每次调用重新授权并记录严格审计，endpoint 元数据不自动执行；模型、工具 timeout 和 Provider 故障按固定结果语义收口。
- 非生产 `mysql` profile 为固定 bootstrap 示例租户 `00000000-0000-0000-0000-000000000001` 新增固定 `echo`、`add` LOCAL 示例目录与控制台显式安装入口；其他 tenant 的目录为空且安装返回 `404`。启动只在当前 JVM 注册固定 Java 执行器，示例租户中具有 `tool:grant` 权限的主体操作后才写入 MySQL；工具摘要新增仅表示注册快照的 `runtimeReady`，实际调用仍重新执行治理校验。该入口不支持动态代码，正式业务 LOCAL 工具仍须在同一 Server JVM 中注册 `ToolExecutor`；不新增数据库 Schema 或 Flyway 迁移，`prod`、`production`、`supabase` 不启用此能力。

- Run、ToolCall、Audit 接入 JDBC Repository，并保持每次读写的 tenant 隔离。
- 通过 Flyway 新增 `V2__add_runtime_query_indexes.sql` 和 `V3__add_tool_calls_created_at_index.sql`，为运行、工具调用和审计查询增加租户范围索引。
- Run 启动与完成采用两段式持久化：启动阶段记录 `RUNNING` 与启动审计，完成阶段更新结果、写入 ToolCall 并记录完成/失败审计。
- Run 和 Audit 查询支持有界 cursor 分页；Run 详情返回同 tenant 的 ToolCall。
- 审计写入失败保持严格语义，API 返回 `503 Service Unavailable`；错误、输入、输出和日志经过敏感信息脱敏。
- 收口 JWT secret、profile、bootstrap admin、生产 JDBC 和错误响应边界；`production`、`prod`、`supabase` 必须使用 JDBC，禁用 bootstrap admin 和开发 JWT fallback。
- 公共 `application.yml` 不再默认选择 `local`；部署应通过 `spring.profiles.active`，`CM_AGENT_PROFILE` 仅作为兼容选择器。

### 数据库迁移影响

- 不修改已经发布的 `V1__init_schema.sql`。
- V2、V3 迁移只增加 `runs`、`tool_calls`、`audit_events` 的查询索引；V1 已建立对应表和基础租户约束。
- 新增 `V5__soft_delete_tool_definitions.sql`，为 `tool_definitions` 增加 `deleted_at`、`deleted_name` 和租户删除状态索引。旧迁移不变；墓碑行保留原名称副本，活动名称改为内部唯一值以释放租户内名称约束。
- 新增 V6 扁平 HTTP 参数定义列，并通过 `V7__remove_legacy_http_parameter_mapping.sql` 删除 HTTP 配置表中的旧 Schema 与 JSON Pointer 映射列；历史 HTTP 映射数据不再兼容。
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
