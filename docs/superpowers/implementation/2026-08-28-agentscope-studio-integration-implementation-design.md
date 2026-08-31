# AgentScope Studio 本地调试集成实现说明

## 关联文档

- [设计说明](../specs/2026-08-28-agentscope-studio-integration-design.md)
- [实施计划](../plans/2026-08-28-agentscope-studio-integration.md)
- [进度账本](../progress/2026-08-28-agentscope-studio-integration-ledger.md)

## 最终实现

`cm-agent-server` 新增 `agentscope-extensions-studio` 依赖以及 `AgentScopeStudioConfiguration`。启用 `cm-agent.agentscope.studio.enabled` 后，配置类在非严格 profile 中调用 `StudioManager.init()` 初始化 Studio。

`AgentScopeStudioManagerRuntime` 依据 AgentScope 2.0.2 实际字节码行为实现：Studio 初始化本身会注册全局 `StudioMessageHook`，所以没有改动 `AgentScopeReActExecutor`，也没有在每个 Agent 构造时重复注册 Hook。`url`、`project` 与 `run-name` 被定义为 JVM 级配置；现存不同 Studio 配置会导致启动失败。

新增 `AgentScopeStudioProperties` 验证地址和名称。`ProfileSafetyValidator` 会在 `production`、`prod`、`supabase` 拒绝此调试集成，配置类也不会在这些 profile 创建。配置与 README 明确提示 Studio 消息转发的开发期数据边界。

## 调用链变化

启动链新增：`AgentScopeStudioConfiguration` → `AgentScopeStudioRuntime` → `StudioManager.init().initialize()` → AgentScope 系统 `StudioMessageHook`。之后由 AgentScope 自动拦截其创建的 Agent 消息并推送 Studio；CM Agent 的 `AgentRuntime`、工具网关、Run 持久化和审计调用链保持不变。

## 与原方案的差异

最初设想是在每次 `ReActAgent` 构造时设置 `runName` 并挂载 `StudioMessageHook`。核对 AgentScope 2.0.2 后确认该方案会与 SDK 的静态全局管理器冲突，并可能在并发运行下串联消息。因此改为单 JVM 单 Studio Run 的官方初始化方式。

## 发布说明

已更新 `docs/release-notes.md`，说明新增的可选调试能力及严格 profile 保护；不涉及数据库迁移或 API 变更。
