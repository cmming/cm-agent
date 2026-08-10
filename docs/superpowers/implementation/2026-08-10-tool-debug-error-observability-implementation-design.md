# 工具调试错误可观测性实现说明

## 最终实现

`ToolDebugResponse` 在原有成功状态、HTTP 状态、输出、错误说明和耗时基础上，新增 `errorCode` 与 `errorId`。`ToolDebugService` 直接使用本次请求的 `toolCallId` 作为错误编号，使前端显示值和后台执行上下文天然一致。

执行器返回 `ToolExecutionResult.failed` 时，服务先用 `ToolOutputSanitizer` 处理失败原因，再按稳定原因生成错误码。HTTP 超时、Secret、输入、请求头、地址、重定向、上游状态、响应、TLS 和连接失败均可区分；未识别原因归入 `TOOL_EXECUTION_FAILED`，但仍返回脱敏后的具体文本。

执行器抛出未分类运行时异常时，前端收到 `TOOL_EXECUTION_EXCEPTION` 和错误编号，不接收内部异常类型或堆栈。后台日志记录同一错误编号、tenant、主体、工具、工具类型、异常类型以及使用脱敏消息重建的诊断堆栈。

控制台使用 `formatToolDebugFailure` 组合具体原因和错误编号。调试结果详情增加“错误原因”“错误码”“错误编号”三项，HTTP 状态和耗时继续独立展示。

## 关键代码位置

- `ToolDebugService.debug`：失败响应、错误编号和结构化日志入口。
- `ToolDebugService.failureCode`：稳定失败原因到错误码的映射。
- `ToolDebugService.logExecutionException`：异常类型和脱敏诊断堆栈日志。
- `console-core.js#formatToolDebugFailure`：前端失败提示格式化。
- `app.js#debugTool`、`app.js#renderDebugResult`：状态区和详情区展示。

## 数据与调用链变化

```text
ToolExecutionResult.failed
  -> ToolDebugService 脱敏并分类
  -> errorMessage + errorCode + toolCallId/errorId
  -> 控制台显示具体原因与错误编号
  -> 后台日志按同一 errorId 检索
```

成功链路、权限校验、tenant 隔离、严格审计和 HTTP 执行安全策略均未改变。不新增数据库字段，也不改变工具参数定义。

## 与原方案的差异

原方案设想可以单独生成错误编号；实际实现复用了已有 `toolCallId`。该调整减少了标识数量，并让一次调试执行、前端错误与后台日志可以直接关联。

## 关联文档

- [需求设计](../specs/2026-08-10-tool-debug-error-observability-design.md)
- [实现计划](../plans/2026-08-10-tool-debug-error-observability.md)
- [进度账本](../progress/2026-08-10-tool-debug-error-observability-ledger.md)
