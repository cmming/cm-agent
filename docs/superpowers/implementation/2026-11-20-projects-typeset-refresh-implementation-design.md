# 本地项目页字阶与会话态收敛实现说明

日期：2026-11-20；主题：`projects-typeset-refresh`；修订：R2；模式：`mode=default`。

## 实际改动

- `DESIGN.md`
  - 扩展 compact product scale，记录 30px 工作区标题、24px 对象标题、21px 窄屏对象标题、16px 分区标题、14px 紧凑正文、13px 控件标签和 12px 元数据。
  - 记录中文标题字距、平衡换行、等宽数字以及本地项目会话的阅读顺序。
- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/projects.html`
  - 增加 `.project-chat-header`、`.project-run-state`、`.project-approval-region` 和 `.project-known-operations`。
  - 将发送表单移动到消息记录和“加载后续消息”之后；会话顺序改为状态、审批、消息、输入、运行操作。
- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/local-projects.css`
  - 增加本地项目页固定字阶与标题换行策略。
  - 重做会话消息、最近会话、运行状态、审批区、输入表单和授权说明的排版。
  - 缩放输入区高度，授权说明仅在 1200px 以上使用两列，1024px 与窄屏使用单列。
  - 删除无效的窄屏授权值重复展示规则。
- 全部 v2 入口 HTML 与两个 JS 测试
  - 共享样式版本更新为 `local-projects.css?v=1.10.0`。
  - 增加会话头、审批区、运行操作区及“消息位于表单之前”的 HTML 结构断言。

## 调用链与行为

`project-development.js` 引用的元素 ID、按钮、表单与消息节点保持不变；自动滚动仍指向 `projectChatMessages`，减少与增加消息及轮询逻辑未变。会话 DOM 只调整视觉顺序和分组，不改变接口调用、运行状态、审批提交或错误提示。

## R1 文件面板结构修复

- `projects.html`：移除会话区结束后多余的 `</div>`。修复前浏览器显示 `projectFilesPane` 的父节点为 `main.workspace`；修复后父节点为 `projectWorkingGrid`。
- `local-projects.test.cjs`：增加 `main/aside/article/section/div` 容器栈校验，出现提前闭合或标签错位时测试失败。
- 文件接口、文件名渲染和文本预览代码未修改；实时消息与文件接口未出现替换字符，问题限定在 HTML 结构。

## R2 左侧项目与会话导航

- `projects.html`：在项目列表下增加 `projectConversationRail`；移动会话状态、会话列表和新建会话控件；新建控件折叠在 `project-conversation-create` 中；右栏增加未选择会话空态。
- `project-development.js`：会话列表按钮记录 `data-conversation-id` 和 `aria-pressed`；选择会话时更新选中态、隐藏右侧空态，并通过 `showConversation` 切回会话视图。
- `local-projects.js`：项目切换时先隐藏会话导航和旧会话，项目加载后再按会话能力显示；创建项目开发组件时注入 `showConversation` 回调。
- `local-projects.css`：左栏改为项目段与会话段，桌面宽约 320px，会话列表独立滚动，当前会话使用选中面和状态点，移动端按单列排列。
- 共享脚本和样式版本：`local-projects.css?v=1.11.0`、`local-projects.js?v=1.3.2`、`project-development.js?v=1.2.2`。

## 与设计的差异

- 实现中将授权模式的重复文案隐藏，因为原生选择框在当前 Chromium/Windows 环境下可完整显示，继续重复会制造噪声。
- 额外的 chat 项目选择标题从 18px 收敛到 16px，以复用同一分区标题角色。
- R1 是部署后回归修复，不改变 R0 的视觉方向或接口范围。
- R2 保留会话消息与审批 API；只调整导航位置和派生选中态。

[清单](../checklists/2026-11-20-projects-typeset-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-typeset-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-typeset-refresh-design.md) · [计划](../plans/2026-11-20-projects-typeset-refresh.md) · [实现说明](../implementation/2026-11-20-projects-typeset-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-typeset-refresh-ledger.md)
