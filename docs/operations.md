# 运维说明

本文档说明阶段3服务的健康检查、真实 AgentScope Runtime、审计严格语义、Run 查询、Flyway 和敏感日志边界。备份恢复、容量治理和自动归档属于阶段4，当前不由应用自动完成。

## 健康检查

服务端暴露 Spring Boot Actuator 健康端点：

```http
GET /actuator/health
```

返回 `UP` 只表示应用进程和基础 Spring 容器就绪。生产环境还应独立检查数据库连接、Flyway 迁移状态、审计写入和下游依赖，再决定是否接收流量。

## 生产认证与 bootstrap login

生产和类生产 profile 使用外部身份系统或受控认证服务签发的 Bearer JWT。服务从受控外部配置读取 JWT 验证密钥并校验令牌；密钥和令牌不得进入 Git、镜像、命令历史、日志、审计消息或 API 响应。

`/api/auth/login` 仅用于本地 `bootstrap admin` 登录。只有 `local` profile 可以显式启用它，生产的 `production`、`prod`、`supabase` profile 必须保持 `bootstrap-admin-enabled=false`，因此生产调用该入口应被拒绝。生产排障应使用受控身份系统签发的临时权限和 Bearer JWT，不应临时打开 bootstrap admin。

生产 profile 还必须设置 `fake-runtime-enabled=false`。本地和测试 profile 可按需启用 fake runtime，但不得把该开关带入生产配置或通过外部覆盖重新打开。

生产还必须设置 `agentscope-enabled=true`，并提供 AgentScope Java 2.0.0 的 OpenAI Compatible 或 DashScope 模型配置。默认凭据按 `tenantId + modelConfigId` 从外部配置解析，也可由自定义 `ModelCredentialProvider` 对接 secret manager；默认凭据为空时启动会失败。`model_configs` 不保存明文 API Key。

## 审计严格失败语义

审计是关键动作的严格记录：登录、权限拒绝、Agent 变更、工具创建/授权和 Run 生命周期都会尝试写入审计。审计 repository 写入失败时：

- 应用保留异常，不吞掉或伪装成成功。
- HTTP API 返回 `503 Service Unavailable`，调用方应按不可用处理并重试或告警。
- 日志只允许包含脱敏后的事件类型、资源标识和错误摘要，不输出 secret、密码、模型 API Key、完整 JDBC URL 或原始输入输出。
- 运行失败收口和失败审计也需要纳入告警，避免只监控普通接口 5xx。

## Run 与 ToolCall 查询

Run API 提供列表和详情：

```http
GET /api/agents/<agent-id>/runs?limit=20
GET /api/agents/<agent-id>/runs?limit=20&cursor=<opaque-cursor>
GET /api/agents/<agent-id>/runs/<run-id>
```

`limit` 范围为 1 到 100；cursor 由服务端生成，按 `started_at` 与 `id` 的稳定顺序继续查询，不应由客户端自行拼接。详情返回 Run 及其同 tenant 的 ToolCall。审计列表也使用同样受限的 cursor 语义。

所有 Run、ToolCall、Audit 查询都使用认证主体的 tenant 条件。运维排查跨租户数据时必须通过受控授权和审计流程，不能直接移除 tenant 条件查询。

## 运行数据与生命周期

JDBC 模式下，运行启动阶段写入 `RUNNING` Run 和启动审计；运行完成阶段在同一完成事务边界内更新 Run、写入 ToolCall 和完成审计。运行异常会尝试写入 `FAILED` 收口；审计失败仍按严格错误语义返回 `503`。

当前没有应用自动删除、归档或 TTL 清理 Run、ToolCall、Audit。备份恢复演练、保留期、容量阈值、分区/归档策略属于阶段4的可观测性与运维工作，生产团队需要在交付前另行建立数据库级治理方案。

## 真实运行故障与工具副作用

- 每次工具调用都会重新读取租户工具定义并执行授权；运行中撤销授权或禁用工具后，后续调用必须被拒绝并审计。endpoint 字段只作为治理元数据，Adapter 不会据此自动访问网络。
- 模型 timeout、Provider HTTP/传输故障会使运行失败；工具拒绝按拒绝状态收口；审计持久化失败保持严格语义，不能被普通 Provider 错误覆盖。
- AgentScope 2.0.0 的工具 API 只提供通用取消信号，无法可靠区分所有取消来源。系统只把 AgentScope 明确生成且与当前工具超时配置匹配的结果识别为 timeout，不承诺手动取消。
- timeout 或线程中断不等于外部副作用已停止。工具与下游系统必须以 `runId`、`toolCallId` 或业务键实现幂等；重试前先查询下游结果，避免重复扣款、通知或写入。
- 当前仅支持同步单轮调用，不支持多轮会话持久化、流式 REST 或 HITL。调用方超时应保留运行 ID，并通过 Run 详情查询最终收口状态。

## 动态 HTTP 工具与 MCP 运维

动态 HTTP 工具是受治理的出站能力，不是通用代理。生产启用前应将 `cm-agent.http-tools.enabled` 设为 `true`，配置精确的 `cm-agent.http-tools.allowed-hosts`，并保持 `allow-http=false`。空白名单、私有地址、回环地址、不安全协议、跨源重定向和超过总超时的请求会被拒绝。响应大小受 `max-response-bytes` 限制；运维人员不应通过提高上限来传输文件或大对象。

请求头只保存 `secret/...` 引用。`SecretProvider` 必须由部署平台接入 secret manager，且需具有可取消的短 I/O 超时；不得把解析出的值写入日志、审计、异常、控制台或抓包导出。HTTP 客户端的域名解析与连接之间仍可能出现 DNS TOCTOU，因此生产网络必须额外限制应用出站目的地址，例如通过 egress 防火墙、受控 DNS 或受控代理。

为需要外部副作用的 HTTP 工具配置业务幂等键。网络超时、线程中断或调用端重试不表示下游操作已经停止或回滚；应在下游查询业务结果后再重试，避免重复扣款、通知或写入。调试同样可能触发真实下游调用，只授予受信人员 `tool:debug`，并对 HIGH 风险工具执行工具名称完全匹配的二次确认。

MCP 默认关闭。启用 `cm-agent.mcp.enabled=true` 时，同时设置允许的 Origin/Host 白名单，并在反向代理层只公开配置的端点。`POST /mcp` 需要有效 Bearer JWT 和 `tool:mcp:invoke`；`GET /mcp` 固定为 `405`，关闭时为 `404`。每次 MCP 请求都会重新加载当前租户发布目录，取消发布、禁用或 HTTP/LOCAL 配置漂移无需等待缓存失效即可生效。MCP 调用、HTTP 工具和调试的错误与审计文字只应保留受控摘要，排障时不得要求输出 Authorization、Cookie、API Key、完整 URL 或堆栈。

## Flyway 运维

- 迁移文件位于 `cm-agent-persistence/src/main/resources/db/migration`。
- 不修改已经发布的 `V1__init_schema.sql`；阶段2通过新增 `V2__add_runtime_query_indexes.sql` 和 `V3__add_tool_calls_created_at_index.sql` 支持 Run、ToolCall、Audit 查询。
- 发布前备份数据库，记录 `flyway_schema_history` 当前版本，并在变更窗口观察迁移日志。
- 迁移失败时停止应用切流，不执行全局清理；按数据库备份和发布回滚预案处理。
- 生产可由发布账号执行 DDL，再使用最小权限运行账号提供服务。

## 日志与告警

至少监控以下信号：

- `/actuator/health` 失败、应用启动失败和数据库连接池耗尽。
- Flyway 迁移失败、JDBC 写入失败和 Run 完成失败。
- 审计写入失败及其返回的 `503` 数量。
- JWT 缺失、认证失败激增、权限拒绝激增和运行失败激增。
- 模型 Provider 不可用、模型/工具 timeout、模型凭据解析失败和工具授权拒绝激增。

日志、审计响应和运维导出不得包含 JWT secret、数据库密码、模型 API Key、完整 JDBC URL、原始 prompt、工具输入输出或堆栈中的敏感配置。敏感字段只保留脱敏摘要和可关联的资源 ID。

`memory` 仅限开发和测试；其运行、工具调用和审计数据会在进程重启后丢失，不得作为生产故障恢复方案。metrics、集中式追踪和自动化容量告警尚未在阶段3交付。
