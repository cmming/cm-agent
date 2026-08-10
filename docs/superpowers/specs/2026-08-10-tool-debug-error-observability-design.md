# 工具调试错误可观测性设计

## 背景

工具调试执行器已经能够区分超时、Secret 不可用、目标地址拒绝、上游非成功状态等失败原因，但 `ToolDebugService` 会把所有失败统一转换成“工具调试失败”。控制台因此无法告诉使用者真实原因，后台也缺少可与前端失败一一对应的错误日志。

## 目标

- 控制台展示经过脱敏的具体失败原因，而不是统一文案。
- 为失败响应提供稳定错误码和唯一错误编号。
- 服务端日志使用同一个错误编号，记录足够的可信上下文和脱敏诊断信息。
- 保持 JWT、Secret、输入、内部 URL 和原始异常细节不泄露。

## 范围

- `POST /api/tools/{id}/debug` 的失败响应。
- HTTP/LOCAL 工具调试失败的控制台展示。
- `ToolDebugService` 的结果失败日志和异常日志。
- 相关服务单元测试、控制台脚本测试和开发文档。

## 非目标

- 不改变 Agent 运行、MCP 调用或通用 API 错误响应结构。
- 不新增数据库表、日志持久化、链路追踪平台或日志采集依赖。
- 不把原始调试输入、Secret 或未经脱敏的异常消息写入日志。

## 方案

1. 扩展 `ToolDebugResponse`，增加 `errorCode` 和 `errorId`。
2. 复用本次调试请求生成的 `toolCallId` 作为 `errorId`，避免生成无法关联执行上下文的第二个标识。
3. 执行器返回失败结果时，对 `errorMessage` 使用现有 `ToolOutputSanitizer` 脱敏，并按稳定原因映射错误码。
4. 执行器抛出异常时，前端只返回 `TOOL_EXECUTION_EXCEPTION` 和可检索错误编号；后台记录异常类型、脱敏说明和脱敏诊断堆栈。
5. 控制台状态区显示“具体原因（错误编号）”，结果区分别展示原因、错误码和错误编号。

## 约束

- 已知 HTTP 执行失败原因必须保持可读，未知失败使用 `TOOL_EXECUTION_FAILED`。
- 成功响应中的 `errorCode` 和 `errorId` 为空字符串。
- HTTP 状态继续通过 `statusCode` 独立返回。
- 审计消息仍保持“工具调试失败”，避免把详细错误写入审计链路。

## 验收标准

- HTTP 上游返回 503 时，前端显示“HTTP 服务返回非成功状态”、`HTTP_UPSTREAM_ERROR`、503 和错误编号。
- 后台日志可以用同一错误编号检索到工具、租户、主体、状态码和脱敏原因。
- 超时、Secret、网络、响应格式等已知原因具有不同错误码。
- 未分类异常不向前端暴露异常类型或堆栈，后台日志不包含 Secret 或完整内部 URL。
- 服务端和控制台相关测试通过。

## 关联文档

- [实现计划](../plans/2026-08-10-tool-debug-error-observability.md)
- [实现说明](../implementation/2026-08-10-tool-debug-error-observability-implementation-design.md)
- [进度账本](../progress/2026-08-10-tool-debug-error-observability-ledger.md)
