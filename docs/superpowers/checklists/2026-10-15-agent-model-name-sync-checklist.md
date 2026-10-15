# Agent 模型名称同步清单

日期：2026-10-15；修订：R0；工作模式：mode=default。

Git 基线：`eac79c26f5d8a856b53c4cdeee0c807ecd191509`。保留任务开始前已存在的控制台、配置与文档修改；初始交付时未提交；本轮提交记录见账本。

## 需求与范围

用户报告模型配置修改模型名称后，Agent 页面仍显示修改前名称。范围为 Server 管理的 Agent 查询与运行准备；不涉及样式重做、数据库迁移、凭据与权限规则变更、真实供应商调用或线上数据修正。

## 当前证据

- `AgentDefinitionCommandService` 创建与更新 Agent 时复制 `modelName`；模型配置更新不改变已有副本。
- `assets/app.js` 的列表与详情直接展示 Agent API 的 `modelName`。
- `AgentScopeModelFactory` 允许 Agent 名称覆盖配置默认值，Server 必须在运行入口解析当前绑定。
- 浏览器只读复现：同一 Agent 的模型配置为 `glm-5.3-flash`，模型字段为 `claude-haiku-5-5`。

| 编号 | 状态 | 目标与验收 | 依赖与验证 |
|---|---|---|---|
| T0 | 完成 | 查明列表、详情和运行中旧模型名称的来源 | CodeGraph、当前源码、8080 浏览器只读复现 |
| T1 | 完成 | 同租户列表与详情按绑定配置 ID 读取当前模型名称；下一次准备运行采用同一名称；不回写历史 | T0；查询服务、Controller、运行编排、不可变定义副本 |
| T2 | 完成 | 验证配置改名、只读权限、多租户、配置停用/缺失、实际运行请求及嵌入式兼容 | T1；两组 Java 21 定向测试共 53 项通过；运行请求由测试 Runtime 捕获，未调用真实供应商 |
| T3 | 完成 | 六份文档与生产说明、发布说明一致，记录实际验证和环境边界 | T2；链接、差异、状态自查通过；纳入本轮提交 |

假设：Server 的配置 ID 是模型绑定依据；配置改名应影响后续准备的运行。嵌入式 SDK 的显式覆盖能力不变。当前 8080 为旧进程，未重启后的页面验收仍待进行。

## 关联文档

- [提示词](../prompts/2026-10-15-agent-model-name-sync-prompt.md)
- [设计](../specs/2026-10-15-agent-model-name-sync-design.md)
- [计划](../plans/2026-10-15-agent-model-name-sync.md)
- [实现说明](../implementation/2026-10-15-agent-model-name-sync-implementation-design.md)
- [账本](../progress/2026-10-15-agent-model-name-sync-ledger.md)
