# 本地项目页布局优化实现说明

日期：2026-11-20；主题：`projects-layout-refresh`；修订：R0；模式：`mode=default`。

## 实际改动

- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/projects.html`
  - 为页面标题增加本地项目专用样式入口。
  - 将运行能力说明和项目来源切换组合为 `.project-context-band`，并增加可见的“项目来源”标签及 `aria-labelledby`。
  - 将项目身份和“项目管理”组合为 `.project-detail-header`。
  - 将“会话/文件”切换与工作内容组合为 `.project-workspace`。
  - 共享样式版本更新为 `local-projects.css?v=1.9.0`。
- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/local-projects.css`
  - 新增 2026-11-20 布局收敛层，重定标题、环境控制带、来源控件、列表栏、空状态、详情头、标签式工作视图、会话工具栏、文件搜索和文件行的空间关系。
  - 删除会导致文件区伪左边框和桌面双列残留的旧规则。
  - 增加 1100px 和 760px 响应式规则；760px 下强制单列并让来源按钮铺满可用宽度。
- 其余 10 个 v2 入口 HTML
  - 仅将共享 `local-projects.css` 查询版本同步为 `1.9.0`。
- `cm-agent-console/src/test/js/local-projects.test.cjs`
  - 更新样式版本断言，并增加控制带、来源标签、详情头和工作区结构断言。
- `cm-agent-console/src/test/js/console-navigation.test.cjs`
  - 更新所有 v2 入口的共享样式版本断言。

## 调用链与行为

现有 `local-projects.js` 和 `client-projects.js` 引用的元素 ID、按钮、表单与状态节点保持不变，因此项目能力探测、选择项目、切换会话/文件、审批、搜索和预览的调用链没有变化。新增容器只承担布局与可访问关系，不参与网络请求或业务状态。

## 与设计的差异

- 未修改业务文案、接口或脚本行为，符合原设计。
- 为保持所有 v2 页面共享样式版本一致，除了目标页外还机械更新了另外 10 个入口的查询参数。
- 远端页面验收使用最终 CSS 和等价 DOM 结构注入进行视觉检查；没有把工作树文件部署到 `192.168.5.149`。

[清单](../checklists/2026-11-20-projects-layout-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-layout-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-layout-refresh-design.md) · [计划](../plans/2026-11-20-projects-layout-refresh.md) · [实现说明](../implementation/2026-11-20-projects-layout-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-layout-refresh-ledger.md)
