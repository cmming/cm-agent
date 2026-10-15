# Agent 模型名称同步设计

日期：2026-10-15；修订：R0；工作模式：mode=default。

Git 基线：`eac79c26f5d8a856b53c4cdeee0c807ecd191509`。保留任务开始前已存在的控制台、配置与文档修改；初始交付时未提交；本轮提交记录见账本。

## 背景与目标

Agent 通过配置 ID 绑定模型，但保存的名称是创建或编辑时的副本。配置更新后，页面与实际运行可能继续使用旧名称。目标是让已有 Agent 自动跟随其当前绑定配置，无需重新保存。

## 采用方案与假设

默认自主决定：Server 管理的 Agent 采用当前绑定配置的名称；SDK 允许显式模型覆盖的能力保留。此决定依据当前控制台只能选择配置 ID、不能自由提交模型名称的接口合同。

查询链路：认证与 `agent:read` 校验 → `AgentDefinitionQueryService` → 当前租户 Agent 与模型元数据 → 仅替换 `modelName` 的不可变副本 → 原 API 结构 → 既有列表/详情渲染。

运行链路：`RunExecutionService.resolve` → 同租户启用模型校验 → `withModelName` → `AgentRunRequest` → 原适配器。保留原工具授权、审计、技能与停止链路。

列表一次批量读取同租户配置，避免逐 Agent 查询。详情仅对已找到的 Agent 读取同租户配置。停用配置仍展示当前名称，运行仍拒绝。绑定缺失保留 Agent 管理能力，运行仍拒绝，不用其他租户或同名配置补位。

## 非目标与约束

不级联写回 Agent，不改数据库或迁移，不改凭据、API 字段、前端样式、v1、SDK 模型工厂合同；不执行真实模型请求。历史 Agent 记录保留原值，查询和运行分别生成当前副本。

## 验收

沿用 T0～T3；列表、详情与准备运行名称一致；保留其他定义字段；只有 Agent 读取权限仍可正常访问；不同租户不可读取目标；禁用/跨租户模型仍拒绝运行。需重启旧服务后才能验收 8080 实际页面生效。

## 关联文档

- [清单](../checklists/2026-10-15-agent-model-name-sync-checklist.md)
- [提示词](../prompts/2026-10-15-agent-model-name-sync-prompt.md)
- [计划](../plans/2026-10-15-agent-model-name-sync.md)
- [实现说明](../implementation/2026-10-15-agent-model-name-sync-implementation-design.md)
- [账本](../progress/2026-10-15-agent-model-name-sync-ledger.md)
