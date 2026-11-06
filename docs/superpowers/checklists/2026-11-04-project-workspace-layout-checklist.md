# 项目页面工作区与授权体验清单

日期：2026-11-04；修订：R3；模式：mode=default；历史 Git 基线：`60d0fd2`。本工作包由原工作区布局六份文档及同日的授权手机显示、授权文案两组文档合并。R0 为合并前工作区布局记录；R1 是用户要求的文档整理，不重新实施业务。

范围：项目页面会话与文件布局、手机授权选项可读性、三种授权方式文案及对应验证；R3 另归档设计配套文件刷新和输入框预览修复的独立历史。非目标：改变服务端授权策略、权限、默认值、接口、数据库或部署状态。当前工作树包含已有改动；已完成的历史验证不能替代真实环境验收。

| 编号 | 状态 | 内容、依赖与验收证据 |
|---|---|---|
| T1 | 完成 | 核对项目页面、样式、测试和已有工作树；为 T2 提供依据 |
| T2 | 完成 | 会话与文件全尺寸切换，输入位于历史之前，面板取消嵌套纵向滚动；保留文件预览独立滚动 |
| T3 | 进行中 | 页面验收：39 项 Node 测试及桌面、手机模拟页检查通过；真实设备、真实服务、真实发送与审批仍未验收 |
| T4 | 完成 | 原布局工作包文档生成；历史事实，已纳入本组六份文档 |
| T5 | 完成 | 手机端完整显示选中授权选项，切换与运行恢复同步；320×740、390×844 模拟检查及 Node 测试有历史证据 |
| T6 | 完成 | 三种模式文案和共同约束按服务端策略说明；测试与 390×844 模拟页切换有历史证据 |
| T7 | 完成 | 三种模式切换与模拟活动运行恢复的名称、说明和锁定状态核对；实际服务恢复仍属 T3 未验收范围 |
| T8 | 完成 | 本次按用户要求合并三组工作包、保留历史并检查引用；不修改业务代码或其他任务文档 |
| T9 | 完成 | 复核三组内容的核心需求、实现、验证边界及六份文档链接；补记测试命令和逐字比对限制 |

T5、T6 对应原独立工作包各自的 T1–T4，编号在原文档中分别从 T1 重用；合并后为避免与 R0 冲突使用新编号，映射与历史见账本。T8 仅标记文档整理完成，不表示 T3 全部验收完成。

关联文档：[清单](../checklists/2026-11-04-project-workspace-layout-checklist.md) · [提示词](../prompts/2026-11-04-project-workspace-layout-prompt.md) · [设计](../specs/2026-11-04-project-workspace-layout-design.md) · [计划](../plans/2026-11-04-project-workspace-layout.md) · [实现](../implementation/2026-11-04-project-workspace-layout-implementation-design.md) · [账本](../progress/2026-11-04-project-workspace-layout-ledger.md)


## R3 合并说明（2026-11-04）

根据用户要求，将同日另外两组工作包合并到本组；两组分别是设计配套文件刷新和输入框预览修复，与项目页面任务独立。以下为对应类型的历史记录，原 T 编号只在各自历史工作包内有效；旧工作包文件链接已改写为指向本组六份文档。原提示词仅作为历史记录，不得因阅读而执行。

| 编号 | 状态 | 归并范围 |
|---|---|---|
| T10 | 完成 | 保留设计配套文件刷新历史；原工作包 T1–T2 独立编号 |
| T11 | 完成 | 保留输入框预览修复历史；原工作包 T1–T3 独立编号 |
| T12 | 完成 | 合并三组文档并检查链接；不修改业务代码 |

### 历史归档：2026-11-04-impeccable-sidecar-refresh（仅供查阅）

# 设计配套文件刷新清单

需求：刷新 `.impeccable/design.json`，保留 `DESIGN.md`。范围限设计配套文件及本工作包，不改变界面。

| 编号 | 状态 | 目标与验收 | 依赖 | 验证 |
|---|---|---|---|---|
| T1 | 完成 | 对照设计规范与 v2 样式刷新配套文件 | 无 | 解析 JSON，检查面板、导航、悬停及断点 |
| T2 | 完成 | 保留 DESIGN.md，生成六份记录 | T1 | 比较 SHA-256，检查链接 |

事实依据：`DESIGN.md` 中 2026-10-15 规则及 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/workbench.css`。假设：旧版色阶和未受影响的示例继续适用。未验证：实时浏览器预览。

工作模式：mode=default。基线 HEAD：`60d0fd2`；工作区包含已有未提交改动，予以保留。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)


### 历史归档：2026-11-04-impeccable-input-preview-fix（仅供查阅）

# 输入框预览修复清单

需求：修复设计配套组件预览输入框溢出并在浏览器验证。不修改产品界面或 DESIGN.md。

| 编号 | 状态 | 目标与验收 | 依赖 | 验证 |
|---|---|---|---|---|
| T1 | 完成 | 输入框宽度包含边框和内边距 | 无 | 检查 JSON 样式 |
| T2 | 完成 | 浏览器中输入框位于预览区域内且可获得焦点 | T1 | Chrome 预览及边界测量 |
| T3 | 完成 | 创建六份工作包，保留 DESIGN.md | T2 | 链接与文件哈希检查 |

证据：`.impeccable/design.json`。本次未测试官方设计面板或移动端。

工作模式：mode=default。Git 基线：`60d0fd2`；保护现有未提交修改。关联此前工作包：`2026-11-04-impeccable-sidecar-refresh`。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)

