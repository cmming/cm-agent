# 项目页面工作区与授权体验设计

修订：R3；日期：2026-11-04；模式：mode=default。

## 背景与目标

R0 工作区会话与文件并列、面板内滚动且输入位于历史之后。用户随后要求手机端看全授权选择，并理解三种模式的区别和后果。目标是聚焦当前任务、使选项完整可读并准确传达确认边界；不改变接口、授权策略、默认模式或权限。

## 方案与约束

- 所有尺寸复用会话/文件切换，页面承担工作区纵向滚动，输入位于消息与操作历史之前；文件正文预览保留自身滚动。最近会话与工具说明按需展开。
- 760px 以下显示当前原生 select 选项的完整文字；切换和从服务端恢复运行时同步，重复视觉文字对辅助技术隐藏。
- 授权名称为“副作用前确认”“高风险前确认”“自动执行已授权操作”。`OperationApprovalPolicy` 对非只读或高风险操作按副作用模式询问，对高风险、文件变更或命令按风险模式询问；自动执行仍受授权、可信分类及强制审批约束。当前固定项目工具的文件变更和命令在前两种模式均需确认。命令可能访问项目外资源，停止不会撤销已发生的操作。
- 描述仅限当前项目、Agent 和本轮运行；未授权、分类未知或无可用审批入口等情形可能拒绝，不承诺所有操作都能自动执行。

## 修订与合并历史

R0：项目布局六份记录。2026-11-04 后续分别新增 `project-authorization-mobile` 和 `project-authorization-copy` 六份记录。R1 按用户要求将两组内容合并到本工作包：原有 T1–T4 不变，追加 T5–T8；原两组各自 T1–T4 分别映射到 T5、T6，后续验证为 T7。原长选项手机截断属于历史问题，已由 T5 的完整展示和 T6 的短名称处理；真实手机与真实服务仍未验收。合并只整理文档，未回滚或更改业务实现。

关联文档：[清单](../checklists/2026-11-04-project-workspace-layout-checklist.md) · [提示词](../prompts/2026-11-04-project-workspace-layout-prompt.md) · [设计](../specs/2026-11-04-project-workspace-layout-design.md) · [计划](../plans/2026-11-04-project-workspace-layout.md) · [实现](../implementation/2026-11-04-project-workspace-layout-implementation-design.md) · [账本](../progress/2026-11-04-project-workspace-layout-ledger.md)

## R2 文档复核（2026-11-04）

对照本次对话中可见的原三组摘要、原文片段与当前源码，确认工作区布局、手机授权显示、授权文案和安全约束均已纳入。原两组的 12 份文件此前未跟踪且已移除，现无法逐字比对完整原文；该复核不能证明所有措辞和细节均逐字保留。


## R3 合并说明（2026-11-04）

根据用户要求，将同日另外两组工作包合并到本组；两组分别是设计配套文件刷新和输入框预览修复，与项目页面任务独立。以下为对应类型的历史记录，原 T 编号只在各自历史工作包内有效；旧工作包文件链接已改写为指向本组六份文档。原提示词仅作为历史记录，不得因阅读而执行。

### 历史归档：2026-11-04-impeccable-sidecar-refresh（仅供查阅）

# 设计配套文件刷新设计

背景：设计文件后来加入 v2 统一样式规则，配套预览仍反映旧阴影及导航渐变。目标是同步配套预览；非目标是重写 DESIGN.md 或修改界面。以 DESIGN.md 较新规则和现有 workbench.css 为依据；保留未发现冲突的色阶、组件与叙述。假设旧色阶可继续使用，不将其视为新提取的数据。验收：JSON 可解析，相关预览和断点体现现状，DESIGN.md 内容不变。

工作模式：mode=default。基线 HEAD：`60d0fd2`；工作区包含已有未提交改动，予以保留。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)


### 历史归档：2026-11-04-impeccable-input-preview-fix（仅供查阅）

# 输入框预览修复设计

背景：Shadow DOM 独立渲染时，输入框宽度 100% 外加边框和内边距造成溢出。目标：在配套组件 CSS 设置 `box-sizing:border-box`，让总宽度包含边框和内边距。非目标：修改 DESIGN.md、实际应用页面或其他组件。验收：预览中边界包含输入框，输入框可获得焦点。假设：预览采用标准浏览器盒模型；官方面板尚未验证。

工作模式：mode=default。Git 基线：`60d0fd2`；保护现有未提交修改。关联此前工作包：`2026-11-04-impeccable-sidecar-refresh`。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)

