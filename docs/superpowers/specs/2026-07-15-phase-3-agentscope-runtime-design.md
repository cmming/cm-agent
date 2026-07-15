# 阶段 3：真实 AgentScope Runtime 设计

## 目标

在不改变 CM Agent 核心治理边界的前提下，将 `AgentRuntime` 接入 AgentScope Java RC3，支持可配置的 DashScope/兼容 OpenAI 模型、授权工具执行、同步运行、超时取消和结果映射。

## 架构边界

- AgentScope 专属类型只存在于 `cm-agent-agentscope-adapter`。
- `cm-agent-core` 扩展运行请求，使 runtime 能获得完整 `AgentDefinition`、认证主体和已授权工具快照；不引入 Spring 或数据库依赖。
- `cm-agent-server` 仍由 `RunExecutionService` 负责按 tenant 查询 Agent、权限检查、创建和完成 Run、ToolCall 与 Audit。
- Starter 负责真实 runtime 条件装配；真实 runtime 与 fake runtime 互斥。生产缺少真实配置时启动失败，不自动回退。
- 首版只支持同步运行，不增加流式 API、会话持久化、消息表或 Flyway 迁移。

## 配置与供应商

新增 `cm-agent.agentscope` 配置：启用开关、供应商、API Key、Base URL、默认模型和超时。API Key 使用环境变量或外部配置注入，禁止写入仓库、日志、审计和错误响应。Agent 的 `modelName` 优先于全局默认模型；`modelProviderId` 仅作为运行上下文标识，首版不新增模型配置表或 Repository。

## 运行数据流

1. `RunExecutionService` 从认证主体取得 tenant，按 tenant 查询 Agent 和授权工具，创建 `RUNNING` Run 及启动审计。
2. runtime 请求携带 Agent 定义、输入、主体和授权工具快照。
3. adapter 校验工具 tenant、启用状态和请求 tenant 一致，再构造 AgentScope Agent、模型和工具集合。
4. 工具执行器只能使用已授权工具 ID，并保留 tenant/principal 上下文，不能接受模型覆盖的租户或主体。
5. adapter 映射最终文本、工具调用、状态和脱敏错误；持久化与审计继续由现有服务收口。

## 异常与安全

模型、工具、超时、取消和映射异常均转为受控 runtime 失败，由现有失败收口和审计链路处理。审计或持久化异常优先保留。日志只记录 runId、agentId、tenantId 和脱敏原因，不记录 API Key、完整 prompt、完整模型响应或 JDBC 凭据。任何跨租户工具或未授权工具都必须在 adapter 和 server 现有授权链路中被拒绝。

## 测试与验证

- adapter 单元测试覆盖请求映射、成功/失败、超时取消、工具 tenant 隔离和敏感信息边界。
- AgentScope 契约测试使用可控 fake model/tool，不依赖真实凭据，但调用真实 RC3 API 类型。
- Starter 测试覆盖真实 runtime 条件装配、fake/real 互斥和缺失配置失败。
- Server 回归测试覆盖认证、跨租户、权限拒绝审计和两段式持久化。
- 首先确认 Java 21/Maven JDK 21；执行受影响模块测试和全量测试。无数据库迁移变更，不新增 Testcontainers 验证范围。

## 兼容性与回滚

默认行为保持 fake runtime，只有显式启用真实 AgentScope 配置才切换。回滚只需关闭真实 runtime 并恢复 fake 或应用自定义 `AgentRuntime` Bean；不涉及数据库迁移回滚。
