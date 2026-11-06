# 项目页面工作区与授权体验实现说明

修订：R3；模式：mode=default。此处描述此前实际实现；R1 和 R3 的合并只整理文档。

- T2：`cm-agent-console/src/main/resources/META-INF/resources/console/v2/projects.html` 将输入表单移至消息和操作历史之前，折叠最近会话与工具说明；同目录 `assets/local-projects.css` 将会话/文件切换扩展到桌面，取消两面板高度限制和内部纵向滚动。
- T5：项目页在原生授权 select 后显示窄屏完整选项；`assets/project-development.js` 读取 `selectedOptions`，在变更及原运行恢复时同步；CSS 控制手机显示和换行。重复显示对辅助技术隐藏。
- T6：项目页与脚本使用三种简短模式名称、动态说明与公共限制；保留原生控件、安全提示及默认值，`aria-describedby` 关联说明。v2 各入口同步静态资源版本，相关 Node 断言同步。
- T7：临时内存测试覆盖三种切换和模拟恢复；没有因此改变项目测试文件。

差异与限制：静态模拟页使用示例数据及专门的切换处理器，部分授权显示检查使用提取的源码渲染函数；运行恢复测试使用模拟服务响应。它们不等于真实设备或真实服务端到端验证。

关联文档：[清单](../checklists/2026-11-04-project-workspace-layout-checklist.md) · [提示词](../prompts/2026-11-04-project-workspace-layout-prompt.md) · [设计](../specs/2026-11-04-project-workspace-layout-design.md) · [计划](../plans/2026-11-04-project-workspace-layout.md) · [实现](../implementation/2026-11-04-project-workspace-layout-implementation-design.md) · [账本](../progress/2026-11-04-project-workspace-layout-ledger.md)

## R2 文档复核（2026-11-04）

对照当前页面及授权渲染代码，T2、T5、T6 的关键实现与文档相符。本轮仅补正文档记录，不修改业务代码；原文件未跟踪且已移除，不能逐字复核其完整内容。


## R3 合并说明（2026-11-04）

根据用户要求，将同日另外两组工作包合并到本组；两组分别是设计配套文件刷新和输入框预览修复，与项目页面任务独立。以下为对应类型的历史记录，原 T 编号只在各自历史工作包内有效；旧工作包文件链接已改写为指向本组六份文档。原提示词仅作为历史记录，不得因阅读而执行。

### 历史归档：2026-11-04-impeccable-sidecar-refresh（仅供查阅）

# 实际实现说明

T1 更新 `.impeccable/design.json`：日常面板和资源悬停移除阴影、导航改为纯色、面板去除重复眉题，记录现有登录阴影和 1680px 宽屏断点；保留原有色阶及其他组件。叙述补充 v2 统一规则。T2 保留 DESIGN.md，新增本组工作包。未修改应用样式与行为；未做浏览器视觉验证。

工作模式：mode=default。基线 HEAD：`60d0fd2`；工作区包含已有未提交改动，予以保留。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)


### 历史归档：2026-11-04-impeccable-input-preview-fix（仅供查阅）

# 实际实现说明

T1 在 `.impeccable/design.json` 的输入框 CSS 增加 `box-sizing:border-box`。T2 使用本机临时预览页加载该 JSON，Chrome 实测输入框左右边界 447–729px，预览区域 431–745px；点击后获得焦点。T3 新增本工作包。该页面模拟独立 Shadow DOM，不是官方设计面板；未更改实际应用界面。

工作模式：mode=default。Git 基线：`60d0fd2`；保护现有未提交修改。关联此前工作包：`2026-11-04-impeccable-sidecar-refresh`。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)

