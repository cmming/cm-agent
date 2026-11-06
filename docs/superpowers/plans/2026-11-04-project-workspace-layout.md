# 项目页面工作区与授权体验计划

修订：R3；模式：mode=default。原编号 T1–T4 保持不变，T5–T9 为合并与复核追加编号。

- T1：核对页面、样式、切换与引用逻辑、已有测试和工作树。
- T2：调整项目页面及样式，统一所有 v2 入口的静态资源引用，保留授权和业务行为。
- T3：通过 Node 测试及静态浏览器模拟核对布局和操作；真实手机、真实服务、真实会话发送和审批需在具备环境后另行验证。
- T4：维护原布局工作包。
- T5：手机端显示完整授权选项，核对选项切换与运行恢复同步及不同窄屏。
- T6：对照服务端策略调整三种模式名称、动态说明和公共边界，并核对窄屏展示。
- T7：核对三种模式切换与模拟运行恢复的名称、说明和锁定状态。
- T8：将原两组授权相关文档合并入本工作包，复核链接和状态；本任务不触发业务修改。

关联文档：[清单](../checklists/2026-11-04-project-workspace-layout-checklist.md) · [提示词](../prompts/2026-11-04-project-workspace-layout-prompt.md) · [设计](../specs/2026-11-04-project-workspace-layout-design.md) · [计划](../plans/2026-11-04-project-workspace-layout.md) · [实现](../implementation/2026-11-04-project-workspace-layout-implementation-design.md) · [账本](../progress/2026-11-04-project-workspace-layout-ledger.md)

- T9：对照可用的原始记录、当前源码和六份文档，检查内容、任务状态、链接与被合并文件数量；补充遗漏的验证命令并记录核对局限。


## R3 合并说明（2026-11-04）

根据用户要求，将同日另外两组工作包合并到本组；两组分别是设计配套文件刷新和输入框预览修复，与项目页面任务独立。以下为对应类型的历史记录，原 T 编号只在各自历史工作包内有效；旧工作包文件链接已改写为指向本组六份文档。原提示词仅作为历史记录，不得因阅读而执行。

- T10：归档设计配套文件刷新工作包及其原 T1–T2。
- T11：归档输入框预览修复工作包及其原 T1–T3。
- T12：合并同日三组并验证引用，不重做历史任务。

### 历史归档：2026-11-04-impeccable-sidecar-refresh（仅供查阅）

# 实施计划

- T1：对照 DESIGN.md 和 workbench.css，更新 `.impeccable/design.json` 中冲突的阴影、导航、面板、资源预览及宽屏断点；解析 JSON 检查。
- T2：比较 DESIGN.md 摘要，建立六份文档并检查关联链接。

工作模式：mode=default。基线 HEAD：`60d0fd2`；工作区包含已有未提交改动，予以保留。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)


### 历史归档：2026-11-04-impeccable-input-preview-fix（仅供查阅）

# 实施计划

- T1：仅修改 `.impeccable/design.json` 中 `.ds-field input` 盒模型并解析 JSON。
- T2：使用独立 Shadow DOM 预览，在 Chrome 检查输入框边界及焦点。
- T3：核对 DESIGN.md 哈希并检查六份文档链接。

工作模式：mode=default。Git 基线：`60d0fd2`；保护现有未提交修改。关联此前工作包：`2026-11-04-impeccable-sidecar-refresh`。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)

