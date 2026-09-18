# 发布说明

## 0.1.0-SNAPSHOT：阶段3真实 AgentScope Runtime

本快照在阶段2生产持久化与安全收口基础上，接入 AgentScope Java 2.0.2 真实 Runtime。第一阶段底座和阶段2的 JDBC/Flyway、安全、多租户、权限与严格审计边界继续保持。

- 新增消息一等公民的持久化会话：Core 提供 Conversation、ConversationMessage 和有序内容块合同，文本、工具调用与工具结果以脱敏快照保存。新增 `/api/agents/{agentId}/conversations` 会话/消息分页、同步发送和 SSE 发送接口；续聊使用最近 40 条、最多 60,000 字符的完整消息窗口，原 Run API 与 `AgentRunResult` 保持兼容。
- 新增 PostgreSQL/MySQL 方言 V10，扩展 `conversations.updated_at` 与消息 sequence、发送方、内容块 JSON、关联 runId，补齐中文数据库注释、租户索引、会话内 sequence 唯一约束和 Run 外键。memory 模式同步提供仅用于本地和测试的会话仓储。
- AgentScope Adapter 从最终 `Msg` 映射安全的 TEXT/TOOL_USE/TOOL_RESULT 块，会话使用 conversationId 作为 sessionId；会话 SSE 使用 `started`、`message-started`、`delta`、`completed/error` 生命周期事件。控制台运行页新增会话选择、新建会话、历史回放和连续发送。

### 本次变更

- 修复 OpenCode Go 的真实运行兼容性：当 `OPENAI_COMPATIBLE` 基础地址主机精确为 `opencode.ai` 时，模型传输层自动携带供应商必需的 `x-opencode-session`，值绑定服务端已创建的 `runId`，同 Run 重试保持一致；不新增可由客户端提交的自定义 Header 配置，其他兼容网关不受影响。同时将 AgentScope 2.0.0 在可信 `RuntimeContext` 绑定前的无用户默认状态槽读取视为未命中，继续拒绝所有无可信身份的检查点写入、删除和非空非法身份访问，消除误导性运行告警而不放宽租户边界。

- 模型配置 v2 页面新增显式“获取模型列表”：服务端新增草稿和已保存配置两条发现接口，统一返回去重、排序且受上限约束的模型 ID，页面支持候选选择和手工输入回退。发现请求仅由服务端发起，已保存密钥只可用于原配置地址；请求禁止重定向，默认仅放行 OpenAI 与 DashScope 官方主机，并受 5 秒、128 KiB、200 项限制。新增 `MODEL_DISCOVERY_*` 错误码、审计事件和同一 `errorId` 的诊断日志，不新增数据库迁移或目录缓存。

- 补齐会话审批历史回显：新增 `GET /api/agents/{agentId}/conversations/{conversationId}/approvals/history`，按决定时间/审批 ID 游标分页，要求 `agent:read` 并隔离 tenant、Agent、会话。前端把待审批和只读历史分开；同 Run 多轮决定按消息关联，未匹配当前消息窗口的记录在历史区展示，支持重新进入回显、加载更早和失败重试。历史不提供批准按钮，也不触发工具执行或自动过期处理。复用既有 V11，无新增迁移或配置；跨重启保存要求 JDBC。Node 历史回归及 Rocky PostgreSQL/MySQL 专项测试通过，真实浏览器及进程重启验收仍待完成。

- 优化聊天页人工确认：增加仅本次授权和参数核对说明、逐项决定汇总及结果明确的提交按钮；批量按钮明确只改变选择，单项不显示批量操作。提交、接受、恢复和未知结果分别提示，终态详情折叠；内存草稿绑定审批版本/调用快照，退出或快照变化失效。新增只查询的“刷新审批状态”入口，保留错误编号、防重复提交及纯文本参数展示。仅前端变化，不调整权限、API 或数据库；真实浏览器视觉和焦点验收待完成。

- 新增 AgentScope Permission System 在线审批：桥接工具统一继承 `ToolBase`，`cm-agent.agentscope.permission-enabled=true` 时 HIGH 工具按具体 `toolId + toolCallId + 输入哈希` 在治理网关前进入 ASK，Run 状态变为 `WAITING_APPROVAL`；聊天 SSE 发送 `approval-required`，审批接口按全部明细原子接收允许/拒绝决定，并使用原发起主体从加密 AgentState 恢复。审批者必须是运行发起人且同时拥有 `agent:run` 和新增 `agent:approve`；恢复执行仍重新校验 tenant、ToolGrant 和工具启用状态，同名替换工具不能继承旧批准。每轮 ASK 前已执行的工具记录会保存，恢复预检失败尝试终结 Run、清理检查点并审计；接口与日志使用相同错误编号，受控拒绝和内部故障分别记录脱敏 WARN/ERROR。控制台支持刷新恢复、纯文本参数、多工具决定、旧会话响应隔离和异常后的权威状态查询。新增 PostgreSQL/MySQL V11；功能默认关闭，有效期默认 15 分钟、最大 24 小时。主动过期扫描、崩溃自动接管和完整浏览器端到端验收尚未交付，详见运维说明。

- 会话聊天页新增类似 AgentScope Studio 的可折叠“执行过程”：实时展示模型实际产生的完整思考块以及工具调用准备、执行和终态；工具桥接器以实际进入治理网关的调用为唯一来源，即使 AgentScope 最终消息未保留工具块，历史消息和 SSE `progress` 仍会关联展示入参、返回值（或受控错误）及服务端耗时。所有展示载荷均由服务端脱敏、限长后才会持久化或发送到浏览器，绝不传递原始参数、原始结果或凭据；思考块只用于可观察性展示，不会作为后续轮次提示词重新注入模型。原有 Runtime 实现通过默认重载保持兼容，不新增数据库迁移；已写入 `THINKING` 内容块后，回滚到不认识该枚举值的旧版本前应先确认历史消息兼容策略。

- v2 控制台新增独立“会话聊天”页：可按 Agent 创建、切换和继续持久化会话，历史消息、受控工具摘要和模型回答以聊天形式呈现；发送通过既有会话 SSE 实时追加文本，完成后以服务端持久化消息为准刷新。该页面复用现有认证、租户隔离、`agent:read`/`agent:run` 权限、审计和错误编号，不新增 API、数据库迁移或消息编辑/删除能力；原运行记录页继续用于运行调试、历史和工具调用回看。

- 新增可选的 AgentScope Studio 开发期调试集成：`cm-agent.agentscope.studio.enabled=true` 时，服务通过 AgentScope 2.0.2 的进程级系统 Hook 将 Agent 消息转发至 Studio。Studio 地址、项目和服务实例 Run 名称均可配置；严格生产 profile 会拒绝启用，且不改变 CM Agent 的 Run、审计、权限或租户隔离语义。

- v2 运行记录页改为流式输出：新增 `POST /api/agents/{agentId}/runs/stream` 同源 SSE 接口，依次发送 `started`、已脱敏的 `delta`、`completed` 或包含稳定错误码和 `errorId` 的 `error` 事件。AgentScope Runtime 转发最终回答的文本块增量，不发送思考过程、工具参数或工具原始输出；连接断开不取消后端运行，运行记录、工具调用和审计仍按既有终态流程落库。原 `POST /api/agents/{agentId}/runs` 保持不变，以兼容现有 API 调用方。
- Agent 管理新增 `PUT /api/agents/{id}` 与 `DELETE /api/agents/{id}`：创建和编辑请求使用当前租户的 `modelConfigId`，服务端仅接受已启用配置并从配置读取实际模型名称；删除需要新增的 `agent:delete` 权限，会自动移除无历史 Agent 的工具授权并写入严格审计。已有会话或运行历史的 Agent 返回明确 `409 Conflict`，不可级联删除历史，应通过编辑接口停用。v2 控制台新增模型配置下拉选择、编辑、删除确认和冲突提示；v1 旧请求的 `modelName` 仅在当前租户唯一匹配一个已启用配置时暂时兼容。
- 新增租户级模型配置管理 API 与 v2 控制台页面：`GET/POST /api/model-configs` 和 `GET/PUT/DELETE /api/model-configs/{id}` 分别使用 `model:read`、`model:write`、`model:delete` 权限，创建、更新、删除写入严格审计；删除仍被 Agent 引用的配置返回明确 `409 Conflict`，启动初始化器维护的系统默认配置不可删除但可停用或更新。创建请求必须写入 API Key，更新可轮换 API Key；服务以 AES/GCM 加密后写入数据库，所有响应均不回显密钥。新增 PostgreSQL/MySQL 方言 Flyway V9，仅更新密文字段的中文数据库注释。
- 新增 Flyway V8 数据库注释迁移，为现有 18 张业务表和 135 个字段补齐中文原生注释；PostgreSQL 与 MySQL 使用同版本方言脚本，由统一 Flyway 配置按 JDBC 元数据选择，迁移测试逐表、逐字段阻止后续注释遗漏。
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
- 控制台新增版本化多页面入口：根路径默认跳转到 `/console/v2/login.html`，v2 将登录、总览、Agent、模型配置、Tool、运行和审计拆为七个独立 HTML 页面；原始 `index.html` 不删除，并继续通过 `/console/v1/` 提供。v2 使用仅作用于 `/api` 的 `HttpOnly`、`SameSite=Strict` 会话 Cookie 恢复刷新认证，并在当前文档内加载独立 HTML 以兼容嵌入式浏览器；JWT 只短暂保留在当前页面内存，不写入浏览器存储或 URL。修复了 `body[data-page]` 被误绑定为导航按钮、点击登录时未认证概览请求与登录请求竞争并回跳的问题；退出接口会立即清除 Cookie，用户名和密码不落地。
- 控制台覆盖当前用户、Agent 列表/详情/创建、Tool 列表/创建/编辑/删除/授权与解除关联、Agent 执行、运行历史/详情/工具调用和审计游标分页；健康检查与 OpenAPI 作为辅助入口。
- HTTP Tool 注册与编辑表单改为树形参数编辑器，支持在 OBJECT/ARRAY 节点内直接添加子参数并按层级缩进展示；页面根据 `parentId` 还原树，提交时自动转为扁平参数数组。表单同时提供类型、请求位置、默认值、示例值及包含 PATH、QUERY、BODY_ROOT 根数组的完整示例，并已移除旧版 Schema 与映射入口。
- v1 控制台继续使用页面内存令牌；v2 使用前端不可读取的会话 Cookie 恢复刷新认证，并只在当前文档内存中保留登录令牌。两个版本复用统一 `401` 失效处理和纯文本 DOM 渲染，不使用 `localStorage` 或 `sessionStorage` 持久化 JWT、用户名或密码；补充窄屏响应式布局和键盘焦点样式。
- 控制台仍不提供手动取消、消息编辑/删除、会话归档、自动摘要、无人值守暂停、长期审批规则或独立审批中心。
- `agentscope.version` 升级到 `2.0.0`，接入 OpenAI Compatible 与 DashScope Provider，提供同步单轮 ReAct 运行。
- 通过 `tenantId + modelConfigId` 调用 `ModelCredentialProvider` 获取模型凭据；默认实现从数据库读取 AES/GCM 密文并在运行时解密，`model_configs` 不保存明文 API Key。
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
- 新增 PostgreSQL/MySQL 方言版 `V8__add_schema_comments.sql`，只写入表和字段注释，不增加或删除业务列；MySQL 受语法限制会以 `MODIFY COLUMN` 完整重述既有字段定义，部署前必须备份并通过迁移测试核对结构约束。
- V2、V3 迁移只增加 `runs`、`tool_calls`、`audit_events` 的查询索引；V1 已建立对应表和基础租户约束。
- 新增 `V5__soft_delete_tool_definitions.sql`，为 `tool_definitions` 增加 `deleted_at`、`deleted_name` 和租户删除状态索引。旧迁移不变；墓碑行保留原名称副本，活动名称改为内部唯一值以释放租户内名称约束。
- 新增 V6 扁平 HTTP 参数定义列，并通过 `V7__remove_legacy_http_parameter_mapping.sql` 删除 HTTP 配置表中的旧 Schema 与 JSON Pointer 映射列；历史 HTTP 映射数据不再兼容。
- 新增 PostgreSQL/MySQL 方言 V11，创建 `tool_approval_requests`、`tool_approval_items` 和 `runtime_checkpoints`，包含租户复合外键、乐观锁版本、审批查询索引、AES/GCM 密文状态槽及完整中文表/字段注释。回滚到旧版本前必须先收口所有 `WAITING_APPROVAL` Run。
- JDBC 应用启动时由 Flyway 执行迁移。升级前应备份数据库、核对 `flyway_schema_history`，并准备迁移失败处理与恢复预案。
- 生产可将迁移账号与运行账号分离；连接信息、密码和 JWT secret 只从受控外部 YAML 或 secret manager 注入。

### 兼容性与安全注意事项

- `memory` 仍只用于开发和测试，重启会丢失 Run、ToolCall 和 Audit，不适用于生产。
- 现有 API 的认证、权限、租户过滤和审计约束继续生效；新增的 cursor 由服务端生成，调用方不应自行构造。
- 审计写入失败不再被忽略，会导致请求返回 `503`；部署和告警系统应将其视为依赖不可用。
- 生产 profile 不允许 bootstrap admin、开发 JWT fallback 或可用的固定凭据。文档和配置示例仅使用占位符。
- 真实 Runtime 当前支持兼容单轮 SSE、持久化会话 SSE 与在线 HIGH 工具审批；不承诺手动取消、会话归档、自动摘要、写请求幂等重放、无人值守暂停或长期审批规则。
- AgentScope 2.0.0 工具层的通用取消信号不能证明外部副作用已停止；有副作用的工具必须使用 `runId`、`toolCallId` 或业务键保证幂等。
- 模型 API Key 通过受权限保护的模型配置接口加密写入数据库；加密主密钥只能使用受控环境变量或 Secret Manager 注入，密钥不得进入 Git、日志、审计或 API 响应。

### 未包含范围

以下内容不属于本次阶段3发布：

- 消息编辑/删除、会话归档、自动摘要、写请求幂等重放、手动取消、无人值守暂停、长期审批规则和独立审批中心。
- 阶段4 metrics、集中式日志与追踪、备份恢复自动化、容量治理和应用自动归档。
- 阶段5 CI/CD 交付流水线、发布自动化、稳定性工程和正式版本承诺。

详细边界见[中文路线图](roadmap.md)。
