# 全局错误诊断设计

## 背景

当前控制台把大多数 5xx 响应统一显示为服务不可用；全局异常处理器将数据库、审计和未分类运行时异常转换为受控响应时不记录异常；Agent、Tool 与 MCP 的执行失败日志覆盖不一致。前端错误、API 响应和后台日志无法稳定关联，导致定位依赖人工猜测。

## 目标

- 所有 REST 失败响应携带可检索 `errorId`，并优先显示后端脱敏错误原因。
- 全局异常边界对数据库、审计和未分类异常记录一次带 `errorId` 的脱敏诊断日志。
- Agent、Tool、MCP 执行失败在编排边界记录统一关联上下文，复用 `runId` 或 `toolCallId`。
- 不记录原始输入、模型输出、Secret、JWT、Cookie、完整内部 URL、SQL 或未经脱敏的异常消息。

## 范围

### P0：REST 失败链路

1. 扩展 `ApiErrorResponse`，增加 `errorId`。
2. 新增请求关联过滤器，在每个 HTTP 请求建立或继承安全的 `requestId`，写入响应头和 MDC。
3. 改造 `ApiExceptionHandler`：数据库、审计、未分类运行时异常记录一次 `ERROR` 脱敏堆栈；校验、权限、资源不存在等受控失败不打印堆栈。
4. 控制台无论状态码是否为 5xx，都优先显示后端的脱敏 `message`、`code`、`errorId`。

### P1：执行编排链路

1. 复用 `ToolDebugService` 的结构化日志模式，抽取无状态的错误诊断协作组件。
2. `RunExecutionService` 在运行时根异常处记录 `runId`、tenant、Agent、错误码、异常类型和脱敏堆栈。
3. `GovernedToolInvocationService` 与 `McpPublishedToolCatalog` 在工具结果失败和异常失败时记录 `toolCallId`、来源、工具及 HTTP 状态。
4. `DynamicHttpToolExecutor` 保持返回稳定失败结果，不在每个底层 catch 重复输出堆栈。

### P2：回归与文档

1. 覆盖 API 错误编号、响应头、日志脱敏和控制台格式化。
2. 覆盖运行、Agent 工具调用与 MCP 工具调用的失败关联日志。
3. 更新工具开发指南、发布说明和本任务四份过程文档。

## 非目标

- 不引入外部日志平台、OpenTelemetry、数据库表或分布式追踪服务。
- 不改变既有安全审计语义。
- 不把所有业务失败升级为异常或 `ERROR` 日志。
- 不修改用户当前未提交的 YAML 配置。

## 核心设计

### 错误关联

REST 请求使用 `requestId` 作为默认 `errorId`；过滤器接受格式合法、长度受限的 `X-Request-Id`，否则生成随机 UUID。响应头始终回传该 ID。执行编排优先复用 `runId`、`toolCallId`；执行上下文缺少该 ID 时才生成新的 `errorId`。

### 响应与日志

`ApiErrorResponse` 增加 `errorId`，保持原 `code`、`message`、`timestamp` 字段不变。全局异常处理器只在未知、持久化、审计等系统失败时调用诊断组件；组件用 `SensitiveDataRedactor` 处理异常消息，并创建仅包含脱敏消息与原堆栈的诊断异常写入日志。

统一日志字段为：`errorId`、`operation`、`errorCode`、`tenantId`、`resourceType`、`resourceId`、`runId`、`toolCallId`、`source`、`statusCode`、`durationMillis`、`failureType`。字段按边界上下文可用性取值，不伪造或信任客户端租户信息。

### 前端展示

控制台 `formatError` 继续保证 401、403、404 的受控提示；对于其他失败优先显示结构化后端消息，再在括号内追加错误码和错误编号。前端不显示堆栈或内部异常类型。

## 验收标准

- 数据库或未分类 API 异常返回响应中包含 `errorId`，日志含相同编号和脱敏堆栈。
- 控制台对 503 响应显示后端消息、错误码和错误编号，而非统一文案。
- Agent 运行和 Tool/MCP 调用失败日志能按 `runId` 或 `toolCallId` 关联。
- 敏感令牌、URL、Secret 不出现在响应或测试捕获的日志中。
- 相关单元、Web、控制台测试及差异检查通过。

## 关联文档

- [实现计划](../plans/2026-08-10-global-error-diagnostics.md)
- [实现说明](../implementation/2026-08-10-global-error-diagnostics-implementation-design.md)
- [进度账本](../progress/2026-08-10-global-error-diagnostics-ledger.md)
