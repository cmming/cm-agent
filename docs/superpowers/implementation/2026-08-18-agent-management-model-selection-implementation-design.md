# Agent 管理编辑、删除与模型选择实现说明

关联设计：[设计说明](../specs/2026-08-18-agent-management-model-selection-design.md)，关联进度：[进度账本](../progress/2026-08-18-agent-management-model-selection-ledger.md)。

## 实现结果

新增 `AgentDefinitionCommandService`，负责将模型配置选择、Agent 写入和审计组织在同一事务边界。创建和编辑会读取同 tenant 的模型配置，拒绝停用配置，并将配置的 `id` 与 `modelName` 保存到 `AgentDefinition`。这消除了旧实现固定默认模型配置的问题。

`AgentDefinitionRepository` 增加更新、历史依赖检查和删除契约；JDBC 通过 `conversations` 与 `runs` 检查历史，内存模式通过运行记录检查。删除前由 `ToolGrantRepository` 清理该 Agent 的授权；运行或会话存在时返回 `409`，不破坏历史外键和审计链路。

`AgentController` 新增 `PUT /api/agents/{id}`、`DELETE /api/agents/{id}`，删除权限为 `agent:delete`。v2 `agents.html` 和共享脚本改用 `modelConfigId` 下拉框，并提供编辑、取消和删除操作。旧 v1 与旧 API 的 `modelName` 仅在当前租户存在唯一已启用匹配配置时兼容。

## 与计划的差异

无数据库结构变更，因此未新增 Flyway 迁移。为保持 v1 和已有 API 客户端可渐进升级，额外保留了受限的名称匹配兼容路径；v2 不使用该路径。
