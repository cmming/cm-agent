# 控制台运行流式输出设计

## 背景

`/console/v2/runs.html` 原先等待 Agent 完整执行结束后才显示结果。AgentScope Runtime 已向模型使用流式协议，但文本增量没有穿过服务端运行编排和浏览器请求层，长回答会让使用者误以为页面无响应。

## 目标

- v2 运行页逐段显示真实模型的最终回答。
- 新增受认证、受授权的同源 SSE 运行接口，同时保留既有非流式接口。
- 保持多租户、权限、运行持久化、工具治理、审计和输出脱敏边界。
- 在 SSE 已提交后仍向前端提供稳定错误码、中文原因和可检索 `errorId`。

## 范围

- Runtime 契约、AgentScope 适配器和服务端运行编排的文本增量通道。
- `/api/agents/{agentId}/runs/stream` SSE 端点。
- 控制台 SSE 解析、实时输出区域及缓存版本更新。

## 非目标

- 不增加手动取消、多轮会话、思考过程展示或工具原始输出展示。
- 不改变运行、工具调用的数据库结构或既有 `POST /runs` 响应协议。

## 方案

`AgentRuntime` 增加带文本消费者的兼容默认方法；不能流式输出的实现保留原行为。AgentScope 适配器只转发 `TextBlockDeltaEvent`，不转发思考块、工具参数和工具结果文本。`RunExecutionService` 在向消费者发送前复用脱敏器，最终结果仍走现有持久化路径。

Controller 在请求线程完成认证和 `agent:run` 授权后，使用应用任务执行器运行 Agent 并通过 `SseEmitter` 发送 `started`、`delta`、`completed` 和 `error`。浏览器断开只停止发送，不取消后端运行。异步错误由流事件返回稳定错误码和与诊断日志一致的 `errorId`。

前端通过 `fetch` 读取 SSE 字节流，按空行重组事件帧，使用纯 DOM 的 `textContent` 追加输出；`completed` 的持久化结果会校正即时输出，兼容非流式 Runtime。

## 约束

- SSE 仅限同源认证请求，沿用 Cookie 和内存 JWT 兼容链路。
- 输出片段、终态输出和错误文案均不得暴露密钥、内部 URL、堆栈或工具输入。
- 连接断开不能跳过运行记录终态和审计收口。

## 验收标准

1. 真实 AgentScope 测试可收到并拼接最终回答的文本增量。
2. SSE 接口返回启动、文本增量和完成事件，完成事件携带持久化终态。
3. v2 页面实时渲染输出且不使用 `innerHTML` 或浏览器存储。
4. 原非流式运行接口、租户隔离和权限行为保持兼容。

相关计划见 [2026-08-19-console-run-streaming.md](../plans/2026-08-19-console-run-streaming.md)。
