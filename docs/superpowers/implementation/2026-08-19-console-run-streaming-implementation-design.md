# 控制台运行流式输出实现说明

## 实际实现

核心 `AgentRuntime` 增加带 `Consumer<String>` 的默认运行方法，保持旧 Runtime 二进制和源码调用方式。`FakeAgentRuntime` 发送一段回显，便于本地与 Web 测试覆盖流协议。

`AgentScopeReActExecutor` 覆盖流式执行方法，订阅 `TextBlockDeltaEvent` 后转交文本消费者。它不会转发 `ThinkingBlockDeltaEvent`、工具调用参数或 `ToolResultTextDeltaEvent`。`RunExecutionService` 在发出增量前执行 `SensitiveDataRedactor`，并继续使用原有终态持久化和工具调用保存流程。

`RunController` 新增 `POST /api/agents/{agentId}/runs/stream`。认证与授权在异步前完成，应用任务执行器运行长任务，`SseEmitter` 发送 `started`、`delta`、`completed`、`error`。错误事件包含 `ApiErrorCode`、中文脱敏消息和请求关联 `errorId`，对应诊断日志记录 `RUN_STREAM` 边界；已开始运行不会因浏览器关闭而取消。

安全过滤链显式放行同一请求的 `DispatcherType.ASYNC` 收尾分派。初始 `REQUEST` 分派仍先经过 JWT 认证和 Controller 权限校验，因此该规则不会放行新的 API 请求。

控制台 `createApiClient` 增加 SSE `stream` 读取器，处理分片边界和非 2xx JSON 错误。运行页以 `textContent` 追加实时文本，完成时以持久化结果校正。全部 v2 页面更新脚本查询版本，保证从任意入口进入再导航到运行页时都会载入新逻辑。

## 与原方案的差异

无范围差异。为维持既有 `AgentScopeExecutor` 测试 Lambda 兼容性，执行器保留三参数函数式方法，并新增带输出消费者的默认重载，而不是改变原函数式接口签名。

相关设计见 [2026-08-19-console-run-streaming-design.md](../specs/2026-08-19-console-run-streaming-design.md)，任务清单见 [2026-08-19-console-run-streaming.md](../plans/2026-08-19-console-run-streaming.md)。
