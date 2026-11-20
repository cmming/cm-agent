# 本地项目页字阶与会话态收敛进度账本

日期：2026-11-20；主题：`projects-typeset-refresh`；修订：R2；模式：`mode=default`。

## 基线与范围

- 分支：`master`
- Git 基线：`a61558ccf28d8792e103b82fbb46a1590dc8d97d`
- 需求：改善 `/console/v2/projects.html` 的字阶，并修正真实会话状态的排版混乱。
- 开始时工作树已有上一轮布局修改及其他大量未提交内容；本轮保留这些修改。

## 任务状态与证据

| 编号 | 状态 | 实际证据 |
|---|---|---|
| T0 | 完成 | 测量页面标题 30px、详情标题 24px、列表名称 14px、按钮 16px 等计算值；打开真实项目会话检查长标题、最近会话、空状态与表单 |
| T1 | 完成 | `DESIGN.md` 已记录 compact product scale；`local-projects.css` 已实现对应字重、行高、字距、平衡换行和等宽数字 |
| T2 | 完成 | `projects.html` 会话顺序已改为状态、审批、消息、输入、运行操作；现有 ID 与脚本挂载点保留 |
| T3 | 完成 | 消息左右分列并独立滚动；最近会话单行省略；授权说明 1200px 以上两列、1024px 与窄屏单列；390px 下 `scrollWidth=390`、`innerWidth=390` |
| T4 | 完成 | 49 项 Node 测试通过；JDK 21 Maven 控制台测试退出码 0；type 与 layout 检测均为 `[]` |
| T5 | 完成 | 修复前实时 DOM：`projectFilesPane` 父节点为 `main.workspace`；修复后浏览器解析本地 HTML：父节点为 `projectWorkingGrid`，文件区位于项目详情面板内 |
| T6 | 完成 | 左栏包含项目段和会话段；会话列表记录当前 `aria-pressed`；点击会话通过 `showConversation` 切回聊天；桌面 1440px 与移动端 390px 无水平溢出 |

## 验证命令与结果

- 只读遍历当前账号可见的 5 个项目、19 个会话和 52 条消息：
  - `application/json` 响应正常；消息中 `U+FFFD` 替换字符为 0，常见 UTF-8 误解码特征为 0。
  - 因此用户看到的“乱码”不是已加载消息的字符编码损坏，而是会话表单、消息和状态缺少稳定层级造成的排版混乱。
- R1 部署后复核：
  - 文件列表文件名和接口响应使用正常字符，无 `U+FFFD`。
  - 修复前 `projectFilesPane.parentElement` 为 `main.workspace`，说明 HTML 提前闭合导致文件区脱离详情面板。
  - 修复本地 HTML 后，静态服务器真实浏览器解析结果：`projectWorkingGrid` 的父节点为 `.project-workspace`，`projectFilesPane` 与 `projectDevelopment` 的父节点均为 `projectWorkingGrid`。
  - 前后截图：`output/playwright/projects-redesign/projects-files-before-fix.png`、`projects-files-after-fix.png`。
- `node --test cm-agent-console/src/test/js/local-projects.test.cjs cm-agent-console/src/test/js/console-navigation.test.cjs cm-agent-console/src/test/js/project-development.test.cjs`
- 结果：50 项通过，0 失败。
- `$env:JAVA_HOME='F:\java21'; $env:PATH='F:\java21\bin;' + $env:PATH; mvn -q -pl cm-agent-console -am test`
  - 结果：退出码 0。
- Impeccable `detect --scope type` 与 `detect --scope layout`
  - 结果：两项均为 `[]`。
- Playwright 真实登录页面 + 最终 CSS/DOM 注入：
  - 1440×960：会话消息、输入表单和已知运行操作已检查；页面无水平溢出。
  - 1024×900：项目列与详情布局保持可用；授权说明为 324px 单列，表单高度约 531px，页面无水平溢出。
  - 390×844：单列项目、消息气泡和授权表单已检查；页面无水平溢出。
  - 截图：`output/playwright/projects-redesign/projects-typeset-final-desktop-chat.png`、`projects-typeset-final-1024-chat.png`、`projects-typeset-final-mobile-chat.png`、`projects-typeset-final-mobile-compose.png`。

## 验证限制与未执行项

- 当前运行页尚未部署本轮工作树资源，视觉检查使用最终 CSS 和等价 DOM 结构注入。
- 未验证真实发送新消息、审批允许/拒绝、命令执行和远端部署后的完整端到端流程。
- 未修改后端、数据库、权限、认证、审计或运行接口。
- 提交状态：已纳入本次提交；未推送、未部署。
- 未更新 `docs/release-notes.md`，因为本轮未形成版本化发布，也未完成远端发布验收。
- 用户已经部署 R0；R1 结构修复仍需再次重新部署后才能在当前远端地址生效。
- R2 浏览器验证：左栏面板宽 320px，会话列表位于项目列表下方；新建会话入口默认收起；移动端面板宽 362px，`scrollWidth=390`、`innerWidth=390`。
- R2 截图：`output/playwright/projects-redesign/projects-conversation-rail-desktop.png`、`projects-conversation-rail-mobile.png`。

[清单](../checklists/2026-11-20-projects-typeset-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-typeset-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-typeset-refresh-design.md) · [计划](../plans/2026-11-20-projects-typeset-refresh.md) · [实现说明](../implementation/2026-11-20-projects-typeset-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-typeset-refresh-ledger.md)
