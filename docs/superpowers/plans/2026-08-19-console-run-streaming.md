# 控制台运行流式输出实施计划

## 任务拆分

1. 扩展核心 Runtime 契约，为增量文本提供兼容回调。
2. 在 AgentScope 适配器中桥接最终回答文本块事件。
3. 在运行服务和 Controller 中实现脱敏 SSE 流、异步错误事件和任务执行。
4. 更新 v2 控制台流解析、实时输出区域和资源缓存版本。
5. 补充 Adapter、Web、控制台资源测试与中文文档。

## 涉及文件

- `cm-agent-core`：`AgentRuntime`、`FakeAgentRuntime`。
- `cm-agent-agentscope-adapter`：执行器、适配器与 Runtime 合同测试。
- `cm-agent-server`：运行服务、运行 Controller 与 MockMvc 测试。
- `cm-agent-console`：核心请求脚本、页面脚本、样式、v2 资源和资源测试。
- `README.md`、`docs/release-notes.md`、本组设计与过程文档。

## 实现顺序

核心契约 → AgentScope 增量事件 → 服务端 SSE → 控制台解析和渲染 → 测试与文档。

## 验证方式

- 使用 JDK 21 执行 `mvn -pl cm-agent-console test`。
- 使用 JDK 21 执行 `mvn -pl cm-agent-agentscope-adapter -am test`。
- 使用 JDK 21 执行 `mvn -pl cm-agent-server -am -Dtest=RunControllerTest test`。
- 使用 JDK 21 执行 `mvn -pl cm-agent-server -am -DskipTests compile`。

设计依据见 [2026-08-19-console-run-streaming-design.md](../specs/2026-08-19-console-run-streaming-design.md)。
