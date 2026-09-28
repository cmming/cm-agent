# Skill 试运行结果预览实现说明

对应设计：[2026-09-28-skill-trial-result-preview-design.md](../specs/2026-09-28-skill-trial-result-preview-design.md)；执行步骤见[计划](../plans/2026-09-28-skill-trial-result-preview.md)。

## 实际实现

- `SkillTrialService.runDetail(...)` 先复用已有试运行读取和所有权检查，再按可信 Principal 的 tenant/agent 范围读取 Run 详情，并确认 Run 类型为 `TEST`。
- `SkillResponses.Trial` 新增可选 `preview`。预览包括 `RunStatus`、已脱敏最终输出/错误、开始/结束时间以及有限工具调用摘要；保留旧构造器以兼容现有 Java 调用方。
- `SkillTrialController` 在创建试运行、审批恢复和 GET 详情的响应中附加预览。读取详情除 `skill:read` 外要求 `agent:read`，服务端继续检查技能/Run 与当前主体的租户和 Agent 归属。
- 工具摘要仅映射既有 `RunToolCall` 的工具名、脱敏输入/输出摘要、状态、耗时、授权状态及错误说明；不包含 tenant、principal、tool-call ID 或未脱敏原始参数。
- 技能管理页新增结果区域，显示门禁状态与 Run 状态、输出、错误和可折叠工具摘要；依赖现有 `text()` 文本节点构造，避免把模型/工具文本解析为 HTML。发起新试运行时清除旧预览，响应返回后刷新区域。
- 未引入表结构或配置变化；TEST Run 仍使用原真实治理执行链路，Fake Runtime 浏览器验证只证明界面和接口串通。

## 与设计的差异

- 无实质差异。真实浏览器使用隔离测试配置中的 Fake Runtime，因此本次输出用于确认预览工作流，不代表外部模型或工具行为验收。
