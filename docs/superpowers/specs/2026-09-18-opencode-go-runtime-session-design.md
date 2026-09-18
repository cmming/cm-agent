# OpenCode Go 运行会话路由与状态槽告警修复设计

## 背景

真实 AgentScope 运行请求 OpenCode Go 时，Provider 返回 `400 MissingSessionID`，明确要求请求包含 `x-opencode-session`。同时，AgentScope 2.0.0 在绑定运行上下文前会读取一次默认状态槽，`RepositoryAgentStateStore` 将其 `userId=null` 视为非法而抛出，框架捕获后打印警告；随后真实运行仍使用可信上下文继续执行。

## 目标

- OpenCode Go 能使用 CM Agent 现有 `OPENAI_COMPATIBLE` 模型配置完成请求路由。
- 默认状态槽探测不再产生误导性告警。
- 不允许模型配置、浏览器或提示词设置任意请求头，也不放宽检查点的租户隔离。

## 范围与方案

- 在 Adapter 的 OpenAI 模型工厂中，仅当基础地址主机精确为 `opencode.ai` 时包装 AgentScope 共享 HTTP 传输。
- 使用服务端已经创建的 `runId` 作为 `x-opencode-session`；同一 Run 的重试保持相同会话值。
- 对 AgentScope 默认槽的 `get(null, ...)` 返回未命中；所有保存、删除、存在性检查及非空非法身份继续严格校验 `tenantId:principalId`。
- 增加模型工厂、请求头包装器与状态仓储回归测试，并更新运行配置说明和发布说明。

## 非目标

- 不新增 Provider 类型、数据库字段、模型配置 Header 字段或数据库迁移。
- 不改变会话消息持久化、审批恢复语义或其他 OpenAI Compatible 网关请求。
- 不记录 API Key、Provider 原始响应或请求头内容。

## 验收标准

1. `opencode.ai` 的模型请求携带与 Run ID 相同的 `x-opencode-session`，其他请求字段不变。
2. 非 OpenCode 的 OpenAI Compatible 配置不使用该包装器。
3. 默认槽读取不访问仓储也不产生 `userId` 为空异常；无身份写入仍被拒绝。
4. 定向 Maven 测试在 Java 21 下通过；真实 Provider 连通性以部署环境的测试凭据复核。
