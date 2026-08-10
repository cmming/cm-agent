# 全局错误诊断实现说明

关联设计：[设计说明](../specs/2026-08-10-global-error-diagnostics-design.md)；关联计划：[实施计划](../plans/2026-08-10-global-error-diagnostics.md)。

## 实际实现

- `ApiErrorResponse` 增加 `errorId`，`RequestCorrelationFilter` 优先复用合法的 `X-Request-Id`，否则生成 UUID，并写入响应头和日志 MDC。
- `ApiExceptionHandler` 对数据访问、审计持久化和未分类运行时异常记录 `ErrorDiagnosticLogger`；响应中的错误编号与日志一致。安全过滤链产生的 401、403 也写入同一结构。
- `ErrorDiagnosticLogger` 统一输出错误编号、边界、错误码、租户、主体、Agent、运行、工具和调用来源。异常消息经过现有脱敏组件处理，SQL 文本进一步替换为占位符，堆栈只保留位置而不携带原始异常消息。
- 控制台 `formatError` 保持 403、404 的受控文案；结构化 5xx 响应则展示后端消息、错误码和错误编号。
- `RunExecutionService`、`GovernedToolInvocationService` 与 `McpPublishedToolCatalog` 在受控失败和异常失败处写入诊断日志；MCP 协议响应和现有业务错误语义未变化。

## 与原方案的差异

未新增外部日志平台、指标系统或数据库字段。错误编号采用 HTTP 请求编号或已有运行/工具调用标识，避免扩展持久化模型。
