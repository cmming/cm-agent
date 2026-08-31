# AgentScope Studio 本地调试集成设计

## 背景

CM Agent 已通过 `cm-agent-agentscope-adapter` 执行 AgentScope Java 2.0.2 的真实 ReAct 运行，但开发者缺少官方 Studio 的消息可视化、链路回放与调试入口。

## 目标

- 在非严格 profile 中按显式开关接入 AgentScope Studio。
- 复用 Studio 官方系统 Hook 转发 Agent 消息，不改变 CM Agent 的运行、审计、权限和租户边界。
- 严格 profile 拒绝开启 Studio，避免生产业务消息进入开发期外部服务。
- 提供可测试的 Spring 初始化边界和中文配置说明。

## 范围

- 在 `cm-agent-server` 添加 Studio 扩展依赖、配置属性和条件化初始化。
- 添加严格 profile 安全校验、单元测试、README、配置说明和发布说明。

## 非目标

- 不在每个 `ReActAgent` 上单独挂载 Hook。
- 不把 Studio Run 与单个 CM Agent `runId` 一一对应。
- 不实现 Studio Human-in-the-Loop、会话持久化或新的 HTTP API。
- 不变更数据库、Flyway、工具治理、审计和模型凭据存储。

## 方案

AgentScope 2.0.2 的 `StudioManager.init().initialize()` 会建立静态连接并注册全局 `StudioMessageHook`。因此在 Spring 启动阶段、`cm-agent.agentscope.studio.enabled=true` 时初始化一次即可；无需修改适配器执行器。

Studio 配置包含 `url`、`project`、`run-name`。三者均为服务 JVM 级别的稳定标识；若 JVM 已被其他代码使用不同值初始化，则快速失败，避免并发请求或重复初始化导致消息串到错误 Run。严格 profile 不创建 Studio 配置，并由 `ProfileSafetyValidator` 拒绝误开开关。

## 约束

- Studio URL 仅允许不含用户信息、查询串或片段的 HTTP(S) 绝对地址。
- 新增代码、测试和文档使用中文；不写入任何密钥或生产地址。
- Studio 可转发业务消息，只能在受控开发环境使用。

## 验收标准

- 非严格 profile 启用时使用绑定后的配置初始化 Studio 边界。
- 无效 Studio URL 在连接前被拒绝。
- `production`、`prod`、`supabase` 启用 Studio 时启动失败。
- server 模块测试与静态差异检查通过。

## 关联文档

- [实施计划](../plans/2026-08-28-agentscope-studio-integration.md)
- [实现说明](../implementation/2026-08-28-agentscope-studio-integration-implementation-design.md)
- [进度账本](../progress/2026-08-28-agentscope-studio-integration-ledger.md)
