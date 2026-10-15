# Agent 模型名称同步实际实现

日期：2026-10-15；修订：R0；工作模式：mode=default。

Git 基线：`eac79c26f5d8a856b53c4cdeee0c807ecd191509`。保留任务开始前已存在的控制台、配置与文档修改；初始交付时未提交；本轮提交记录见账本。

## 已交付代码

- `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentDefinition.java`：`withModelName` 创建仅名称变化的不可变副本，保留 ID、工具、提示词、参数和创建/更新主体。
- `cm-agent-server/src/main/java/com/cmagent/server/service/AgentDefinitionQueryService.java`：组合两种 Repository，列表批量解析，详情按同租户查找。不访问凭据，不回写历史记录；缺失绑定保留管理展示。
- `cm-agent-server/src/main/java/com/cmagent/server/web/AgentController.java`：沿用原授权、拒绝审计及响应结构，将读取交给查询服务。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`：原模型可用性检查后替换运行定义名称，避免旧副本覆盖当前配置。适配器与 SDK 未修改。
- `AgentControllerTest`：实际 PUT 模型配置后验证列表与详情、不回写、只读权限和跨租户拒绝。
- `RunControllerTest`：验证下一次运行交给 Runtime 的 Agent 与当前模型名称一致。
- `AgentDefinitionQueryServiceTest`：验证批量查询、按 ID 关联、停用配置、缺失绑定和空结果。
- README、发布说明和六份工作包记录实际语义与部署边界。

## 与方案差异

未修改前端：源代码和真实浏览器已证明页面直接使用 API 字段，服务端统一修复可同时覆盖 v1/v2 和已有记录。没有数据库迁移、数据修正或适配器合同变化。

## 验证边界

两组 Java 21 定向测试共 53 项通过，失败、错误及跳过均为 0；详见账本。8080 只读复现属于旧进程；当前未重启服务，不能声称用户页面已经生效。

## 关联文档

- [清单](../checklists/2026-10-15-agent-model-name-sync-checklist.md)
- [提示词](../prompts/2026-10-15-agent-model-name-sync-prompt.md)
- [设计](../specs/2026-10-15-agent-model-name-sync-design.md)
- [计划](../plans/2026-10-15-agent-model-name-sync.md)
- [账本](../progress/2026-10-15-agent-model-name-sync-ledger.md)
