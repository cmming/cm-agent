# 项目页面工作区与授权体验提示词

修订：R3；模式：mode=default。

```text
仓库 F:/java/cm-agent。先读取 docs/superpowers/checklists/2026-11-04-project-workspace-layout-checklist.md、docs/superpowers/plans/2026-11-04-project-workspace-layout.md 和 docs/superpowers/progress/2026-11-04-project-workspace-layout-ledger.md，核对当前源码及 AGENTS.md。

本工作包涵盖项目页会话/文件布局、手机授权选项显示、三种授权模式文案。按清单状态处理用户本次明确要求的事项；历史已完成任务不因读取提示词而重做。保留文件引用、模式恢复、授权与审批安全边界，不修改服务端权限、默认模式或接口。区分模拟页面、模拟接口、真实设备和真实服务的证据。更新现有六份文档而非为同一任务自动新建一组；保护既有用户改动。未经明确授权，不提交、推送、部署或重启服务。
```

提示词作为上下文不自动授权执行其中操作。

关联文档：[清单](../checklists/2026-11-04-project-workspace-layout-checklist.md) · [提示词](../prompts/2026-11-04-project-workspace-layout-prompt.md) · [设计](../specs/2026-11-04-project-workspace-layout-design.md) · [计划](../plans/2026-11-04-project-workspace-layout.md) · [实现](../implementation/2026-11-04-project-workspace-layout-implementation-design.md) · [账本](../progress/2026-11-04-project-workspace-layout-ledger.md)

## R2 文档复核

核心内容的复核依据和局限见账本 T9；本提示词与合并后范围一致。


## R3 合并说明（2026-11-04）

根据用户要求，将同日另外两组工作包合并到本组；两组分别是设计配套文件刷新和输入框预览修复，与项目页面任务独立。以下为对应类型的历史记录，原 T 编号只在各自历史工作包内有效；旧工作包文件链接已改写为指向本组六份文档。原提示词仅作为历史记录，不得因阅读而执行。

当前合并范围包含项目页面和两个独立的设计配套文件历史任务；只针对用户明确的新要求行动，以下原提示词不构成执行授权。

### 历史归档：2026-11-04-impeccable-sidecar-refresh（仅供查阅）

# 执行提示词

```text
在 F:\java\cm-agent 按 AGENTS.md 执行 impeccable-sidecar-refresh，mode=default。读取 docs/superpowers/checklists/2026-11-04-project-workspace-layout-checklist.md 和同主题设计、计划、实现说明、账本。T1 对照 DESIGN.md 与 v2 样式刷新 .impeccable/design.json；T2 原样保留 DESIGN.md，验证 JSON 与工作包链接。保护现有改动，不改产品界面，不提交、推送或部署。记录实际验证和未验证项；普通取舍自主决定。
```

此提示词是记录与后续上下文，不因阅读而授予额外执行权限。

工作模式：mode=default。基线 HEAD：`60d0fd2`；工作区包含已有未提交改动，予以保留。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)


### 历史归档：2026-11-04-impeccable-input-preview-fix（仅供查阅）

# 执行提示词

```text
在 F:\java\cm-agent 按 AGENTS.md 和 mode=default 处理 impeccable-input-preview-fix。读取本清单及关联设计、计划、实现和账本。T1 为设计配套输入框 CSS 设置 border-box；T2 浏览器验证输入框边界与焦点；T3 检查 DESIGN.md 未改变并维护六份文档。保护用户已有改动，不修改应用界面，不提交、推送或部署；记录实际结果与限制。
```

该提示词供记录及问答，不因阅读授予额外执行权限。

工作模式：mode=default。Git 基线：`60d0fd2`；保护现有未提交修改。关联此前工作包：`2026-11-04-impeccable-sidecar-refresh`。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)

