# 动态 HTTP 工具与 MCP 发布设计

## 1. 背景与目标

CM Agent 当前已经具备 ToolDefinition、ToolGrant、ToolRegistry、AgentScopeToolBridge、运行时二次授权和严格审计，但工具定义中的 endpoint 仅为元数据，页面创建工具时 inputSchema 固定为 `{\"type\":\"object\"}`，新建工具也不会自动获得真实执行器。

本次新增一类受治理的动态 HTTP 工具。管理员可通过控制台配置接口 URL、GET/POST 方法、嵌套输入 Schema、参数映射、默认值、Secret 请求头引用和超时；系统在现有工具治理链路内动态调用目标接口。同时提供可选的 MCP Streamable HTTP Server，把管理员显式发布的 HTTP 工具和已注册 LOCAL 工具提供给外部 MCP 客户端，并支持在控制台直接调试单个工具。

核心目标如下：

- 动态 HTTP 工具无需为每个接口编写 Java ToolExecutor。
- 保持租户隔离、RBAC、工具授权、严格审计和敏感信息保护。
- 防止 endpoint 自动执行形成 SSRF、凭据泄露或未审计外连。
- 支持嵌套参数、JSON Pointer 映射和 JSON 类型默认值。
- MCP 发布默认关闭，工具必须显式发布，MCP Server 也必须显式启用。
- 页面支持 HTTP 工具创建、MCP 发布/取消发布和 HTTP/LOCAL 工具独立调试。

## 2. 范围与非目标

### 2.1 本次范围

- 新增 ToolType.HTTP。
- HTTP 方法只支持 GET 和 POST。
- 参数位置支持 PATH、QUERY、HEADER、BODY。
- 输入支持嵌套 object、array 和 JSON 标量。
- 默认值支持 string、integer、number、boolean、object、array；字段缺失或显式为 null 时应用默认值。
- 静态敏感请求头只保存 Secret 引用，值由可替换的 HttpToolSecretProvider 解析。
- 每个工具可配置请求超时，服务端设置最小值和最大值。
- 响应支持 JSON 和纯文本，并执行大小限制与脱敏。
- 提供无状态 MCP Streamable HTTP Server，只发布 HTTP 和已注册 LOCAL 工具。
- MCP 复用现有 Bearer JWT 身份和 tenant，新增 tool:mcp:invoke 权限。
- 控制台单工具调试支持 HTTP 和已注册 LOCAL 工具，新增 tool:debug 权限。

### 2.2 非目标

- PUT、PATCH、DELETE 等其他 HTTP 方法。
- HTTP 自动重试或 OAuth Token 自动刷新。
- MCP Resources、Prompts、旧 SSE 传输、STDIO 或有状态 MCP Session。
- 把现有 MCP/A2A 类型自动转换为可执行或可发布工具。
- 在数据库、DTO、日志、审计或 ToolCall 中保存 Secret 实际值。
- 仅靠应用层 SSRF 校验代替生产网络出口策略。

## 3. 总体架构

现有 Agent 调用继续从 AgentScopeToolBridge 进入治理网关。新增统一的 GovernedToolExecutionService，承接工具可用性检查、执行路由、脱敏和统一结果语义。不同入口分别完成自身授权后调用它：

```text
内部 Agent 调用 ─┐
页面 Tool 调试 ──┼→ GovernedToolExecutionService → HTTP/LOCAL 执行器
MCP tools/call ──┘
```

- Agent 入口继续检查 Agent ToolGrant，并在模型运行前和实际调用时双重授权。
- 页面调试入口检查 tool:debug，不伪造 Agent 或 Run。
- MCP 入口检查发布状态和 tool:mcp:invoke，不绑定虚假的 Agent。
- HTTP 工具由 DynamicHttpToolExecutor 每次按 tenantId + toolId 重新读取配置。
- LOCAL 工具继续从 ToolRegistry 查找并执行，且核对 ID、tenant 和名称。
- MCP tools/list 每次按当前 JWT tenant 动态查询已发布、已启用且当前可执行的工具。

## 4. 模块边界

### 4.1 cm-agent-core

- ToolType 增加 HTTP。
- 新增 HttpToolConfig、HttpMethod、HttpParameterLocation、HttpParameterMapping、McpToolPublication 等领域类型。
- 新增 HttpToolConfigRepository 和 McpToolPublicationRepository 接口。
- 领域对象维护方法范围、Schema、Pointer、默认值、集合防御性复制等不变量。
- 不依赖 Spring MVC、Spring Security、JDBC 或具体 HTTP 客户端。

### 4.2 cm-agent-persistence

- 新增 Flyway V4，不修改 V1/V2/V3。
- 实现 JdbcHttpToolConfigRepository 和 JdbcMcpToolPublicationRepository。
- 所有查询与写入显式携带 tenantId，跨租户读取必须返回空。
- JSON Schema、参数映射和 Secret 引用使用 TEXT 保存 JSON，以兼容 PostgreSQL 16 和 MySQL 8.4。

### 4.3 cm-agent-server

- 扩展 Tool 创建 DTO 和 ManagementCommandService。
- 在同一事务中保存 ToolDefinition、HttpToolConfig、可选 MCP 发布配置和创建审计。
- 实现 DynamicHttpToolExecutor、HttpToolUrlPolicy、HttpToolSecretProvider、默认外部配置 Secret Provider 和统一执行服务。
- 提供 MCP Streamable HTTP 端点、发布管理接口和工具调试接口。
- Controller 只负责路由、校验、主体解析、权限入口和响应状态，不直接访问 DataSource。

### 4.4 cm-agent-console

- HTTP 创建表单支持 URL、方法、嵌套 inputSchema、参数映射、默认值、Secret Header 引用和超时。
- 工具列表展示 HTTP 摘要与 MCP 发布状态。
- 支持发布、取消发布和单工具调试。
- HIGH 风险工具调试必须二次确认并输入工具名称。

## 5. 数据模型

### 5.1 tool_http_configs

使用 tenant_id + tool_id 作为唯一业务键，字段如下：

| 字段 | 说明 |
| --- | --- |
| tenant_id | 租户 ID，参与所有查询与唯一约束 |
| tool_id | ToolDefinition ID，关联 tools |
| method | GET 或 POST |
| url_template | 目标 URL 模板 |
| input_schema | 完整嵌套 JSON Schema |
| parameter_mappings | JSON Pointer 参数映射 JSON |
| secret_headers | 请求头名称到 Secret 引用的 JSON |
| timeout_ms | 单工具请求超时 |
| created_at / updated_at | 创建和更新时间 |

ToolDefinition.endpoint 同步保存 URL 以兼容现有工具列表；HTTP 运行时以 tool_http_configs 为执行配置来源，并校验二者一致，配置漂移时拒绝执行。

### 5.2 tool_mcp_publications

使用 tenant_id + tool_id 作为唯一业务键，字段如下：

| 字段 | 说明 |
| --- | --- |
| tenant_id | 租户 ID |
| tool_id | ToolDefinition ID |
| enabled | 是否发布 |
| published_by | 发布主体 ID |
| created_at / updated_at | 创建和更新时间 |

工具默认不发布。只允许发布 HTTP 或当前已注册的 LOCAL 工具。LOCAL 发布前必须确认 ToolRegistry 中的 ID、tenant 和名称一致。

### 5.3 权限

V4 增加：

- tool:debug：页面直接调试真实工具。
- tool:mcp:invoke：通过 MCP 调用已发布工具。

权限默认授予管理员角色，不扩大普通用户现有权限。

## 6. 嵌套输入与参数映射

HttpToolConfig 保存完整 inputSchema，并保存从模型输入到目标请求的映射。映射结构如下：

```json
{
  "sourcePointer": "/order/number",
  "location": "PATH",
  "targetName": "orderNo",
  "targetPointer": null,
  "required": true,
  "defaultValue": null
}
```

- sourcePointer 使用 RFC 6901 JSON Pointer，从模型输入读取嵌套值。
- PATH、QUERY、HEADER 使用 targetName。
- BODY 使用 targetPointer 构造嵌套 JSON。
- PATH 参数必须为必填，并能在 URL 模板中找到同名占位符。
- GET 禁止 BODY 映射。
- object 和 array 可映射到 BODY；PATH、QUERY、HEADER 只接受标量或标量数组。
- 同一请求位置不允许重复目标；BODY 父子路径冲突或类型冲突在创建时拒绝。
- 模型显式值优先；字段缺失或显式为 null 时使用 defaultValue。
- 默认值必须通过 inputSchema 对应节点的类型约束。
- 没有默认值的必填参数缺失时，调用失败；非必填且无默认值时忽略。

## 7. HTTP 请求执行

执行顺序如下：

1. 按 tenantId + toolId 读取 ToolDefinition 和 HttpToolConfig。
2. 校验工具启用、类型为 HTTP、定义和配置一致。
3. 校验模型输入符合 inputSchema。
4. 按 sourcePointer 读取值并应用默认值。
5. URL 编码并组装 PATH、QUERY；按 targetPointer 组装嵌套 JSON BODY。
6. 解析 Secret Header 引用并加入受控请求头。
7. 执行 URL 安全策略。
8. 使用 JDK HttpClient 发起 GET 或 POST，不自动重试。
9. 校验重定向、响应状态、Content-Type 和响应大小。
10. 返回脱敏、裁剪后的统一结果。

模型参数禁止覆盖 Host、Content-Length、Connection、Transfer-Encoding、Authorization、Cookie 等敏感或逐跳请求头。Authorization 和 API Key 等只允许通过 Secret 引用注入。

## 8. SSRF 与网络安全

- 生产默认只允许 HTTPS；local/test 可通过显式配置允许 HTTP 测试目标。
- Host 必须命中 cm-agent.http-tools.allowed-hosts。
- 白名单支持精确域名和受控子域模式，不接受任意正则。
- DNS 解析出的全部地址都必须是允许的公网地址；拒绝回环、私网、链路本地、组播、保留地址和云元数据地址。
- JDK HttpClient 禁止自动重定向；最多手动跟随 3 次，每次重新校验协议、Host、端口和 DNS 解析结果。
- 生产文档要求同时配置基础设施网络出口策略，限制应用只能访问批准目标。
- 审计和日志不记录完整 Query、请求头、请求体、响应体或 Secret。

## 9. Secret 解析

定义可替换接口：

```java
HttpToolSecretProvider.resolve(UUID tenantId, String secretRef)
```

默认实现从受控外部 YAML/环境变量映射解析 tenantId + secretRef。生产可提供自定义 Bean 对接 Secret Manager。Secret 实际值不进入数据库、管理 API、日志、审计、ToolCall 或异常消息。

Secret 缺失时返回固定“工具凭据不可用”错误，不发起网络请求。

## 10. 超时、响应与错误语义

- 单工具 timeout_ms 必须位于服务端配置的最小值和最大值之间。
- GET 和 POST 均不自动重试。
- 只接受 JSON 与纯文本响应。
- 响应采用有界读取，默认上限 256 KiB，可由服务端配置统一调整。
- 2xx 返回 HTTP 状态码和脱敏后的响应体。
- 非 2xx 标记工具调用失败，只返回受控错误和状态码；响应体只允许形成脱敏审计摘要，不交给模型。
- DNS、连接、TLS、超时、Schema、配置或 Secret 错误使用固定中文语义，不暴露底层异常。
- 审计持久化异常保持严格语义并向上传播。

## 11. MCP Streamable HTTP Server

- 使用官方 MCP Java SDK 的 Servlet/Streamable HTTP 服务端能力，保持 Spring MVC 技术栈。
- 使用与项目 Jackson 2 兼容的 SDK JSON 模块；版本在实施时通过 dependency:tree 确认并锁定。
- 暴露单一 /mcp 路径；POST 处理无状态 JSON-RPC 请求，GET 由官方无状态传输明确返回 405，不建立 SSE 流。
- 使用无状态模式，不签发或保存 MCP-Session-Id。
- cm-agent.mcp.server.enabled 默认 false。
- 所有请求校验 Bearer JWT、Origin 白名单和现有安全链路。
- tools/list 只返回当前 tenant 下已发布、已启用且当前可执行的 HTTP/LOCAL 工具。
- tools/call 要求 tool:mcp:invoke，每次重新读取发布状态、定义和执行器状态。
- MCP 调用不绑定 Agent、不创建 Run，使用独立调用 ID 记录 MCP_TOOL_CALL_STARTED、COMPLETED、FAILED、DENIED 审计。
- MCP 返回协议级错误或受控工具错误，不暴露 Java 堆栈、Secret、完整目标 URL Query 或目标错误正文。
- Origin 校验遵循 MCP Streamable HTTP 安全要求，允许来源由服务端显式配置。

## 12. 页面创建、发布与调试

### 12.1 创建 HTTP 工具

POST /api/tools 在 type=HTTP 时接收 httpConfig，并允许 mcpPublished=true。HTTP 配置包含 method、urlTemplate、inputSchema、parameterMappings、secretHeaders 和 timeoutMillis。其他类型携带 httpConfig 时返回参数错误。

ToolDefinition、HTTP 配置、可选 MCP 发布记录和创建审计在一个事务中完成。

### 12.2 MCP 发布管理

```text
PUT    /api/tools/{toolId}/mcp-publication
DELETE /api/tools/{toolId}/mcp-publication
```

发布和取消发布都检查租户与权限并记录严格审计。取消发布后，后续 tools/list 和 tools/call 立即不可用。

### 12.3 单工具调试

```text
POST /api/tools/{toolId}/debug
```

- 只支持 HTTP 和已注册 LOCAL 工具。
- 要求 tool:debug 权限，不要求绑定 Agent 或 ToolGrant。
- 请求体包含符合工具 inputSchema 的 JSON input。
- 返回成功/失败、耗时、HTTP 状态码和脱敏结果。
- 不创建 Run，记录 TOOL_DEBUG_STARTED、COMPLETED、FAILED、DENIED 审计。
- HIGH 风险工具前端必须二次确认并要求输入工具名称；后端仍独立鉴权，不能信任前端确认状态替代安全检查。

### 12.4 控制台

- ToolType 选择 HTTP 后显示 HTTP 专用字段。
- 提供 JSON Schema 和参数映射编辑区域，支持嵌套 Pointer、默认值和前端基础校验。
- 页面只提交 Secret 引用，不接收或回显 Secret 实际值。
- 列表显示类型、风险等级、HTTP 配置摘要和 MCP 发布状态。
- 提供发布、取消发布和调试操作。
- 调试输入使用 JSON 编辑区，结果展示状态、耗时、HTTP 状态和脱敏响应。
- JWT 继续只保存在页面内存中。

## 13. 测试策略

### 13.1 Core

- HTTP 配置领域不变量。
- 嵌套 Schema、JSON Pointer、默认值和参数映射。
- GET/BODY 禁止、PATH 占位符、重复目标、BODY 父子路径冲突。
- HTTP/LOCAL 执行路由结果语义。

### 13.2 Persistence

- PostgreSQL 16 和 MySQL 8.4 的 V4 迁移。
- 两个新 Repository 的保存、查询、唯一约束与跨租户隔离。
- tool:debug、tool:mcp:invoke 权限和管理员角色基线。
- 旧数据升级与现有工具类型兼容。

### 13.3 Server

- 创建事务、回滚和严格审计。
- URL 白名单、协议、端口、DNS/IP、重定向和响应大小。
- 嵌套 PATH/QUERY/HEADER/BODY、默认值、Secret Header、超时和非 2xx。
- Secret 缺失、不可达、TLS、超时和固定错误语义。
- Agent、页面调试、MCP 三种入口的权限、租户隔离、审计和执行路由。
- MCP initialize、tools/list、tools/call、未认证、错误 Origin、未发布和取消发布。
- 环境可用时运行官方 MCP conformance server suite。

### 13.4 Console

- HTTP 表单、类型显隐、嵌套 Schema/映射提交。
- MCP 发布与取消发布。
- JSON 调试输入、结果展示和错误状态。
- HIGH 风险工具名称确认。
- 页面不渲染 Secret 实际值。

## 14. 验证与交付

- 本机确认 Java 21 和 Maven 3.9+。
- 本机运行非容器单元测试、Server/Console 回归、全量可执行测试和 git diff --check。
- JDBC、Flyway、PostgreSQL/MySQL 和 Testcontainers 测试通过 ssh rocky 在 maven:3.9.9-eclipse-temurin-21 容器环境运行。
- 远程验证前确认 Docker 可用、Maven 使用 JDK 21，并确认远程提交与待验证本地提交一致。
- 如环境具备 npx，运行 MCP 官方 conformance server suite；否则明确记录未执行原因。
- 同步更新 README、configuration、deployment、operations、technical-architecture 和 release-notes。
- 实施过程维护 docs/superpowers/progress 下的 progress ledger。

## 15. 兼容性与部署默认值

- 新功能默认关闭，不改变现有部署的网络出口行为。
- 不修改已经发布的 V1/V2/V3，只新增 V4。
- 现有 Tool 创建请求保持兼容；只有 HTTP 类型要求 httpConfig。
- 现有 LOCAL/MCP/A2A 工具行为保持不变。
- MCP Server 默认关闭，启用时必须提供允许 Origin、JWT 验证和权限配置。
- HTTP 工具必须配置允许 Host；未配置白名单时不允许外连。
- memory 模式继续仅用于本地和测试；生产持久化使用 JDBC/Flyway。

## 16. 已确认的设计决策

- HTTP 方法：GET、POST。
- 认证：Secret 引用 + 可替换 HttpToolSecretProvider。
- 网络：服务端 Host 白名单、地址检查和重定向复核。
- 参数：PATH、QUERY、HEADER、BODY，支持嵌套和默认值。
- 响应：状态码 + 有界脱敏 JSON/文本；非 2xx 为失败。
- 重试：默认不重试。
- 类型：新增 HTTP。
- 存储：独立 tool_http_configs 和 tool_mcp_publications。
- 创建：HTTP 定义和配置单表单、单事务，Agent 授权保持独立。
- MCP：同一 PR，Streamable HTTP、无状态、Bearer JWT、显式发布。
- MCP 范围：HTTP 和已注册 LOCAL。
- MCP 授权：不绑定 Agent，使用 tool:mcp:invoke。
- 页面：支持发布/取消发布和单工具调试。
- 调试：HTTP 和已注册 LOCAL，使用 tool:debug；HIGH 风险需要名称确认。
