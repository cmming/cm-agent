# 聊天页人工审批界面优化计划

本计划对应 [设计说明](../specs/2026-09-30-chat-approval-ui-design.md)，实现结果见 [实现记录](../implementation/2026-09-30-chat-approval-ui-implementation-design.md)，执行状态见 [进度账本](../progress/2026-09-30-chat-approval-ui-ledger.md)。

| 顺序 | 任务 | 文件 | 验证 |
|---|---|---|---|
| 1 | 检查聊天页审批渲染、状态查询、权限提示和布局约束 | `console/v2/chat.html`、`assets/app.js`、`assets/console-core.js`、`assets/styles.css` | 对照现有 Node 契约和服务端只读状态语义 |
| 2 | 让待办进入视口并明确选择、提交状态 | `assets/app.js`、`console/v2/chat.html` | Node 测试覆盖待办定位和提示文本 |
| 3 | 强化逐项选择与滚动操作区 | `assets/styles.css` | 静态样式合同及人工布局检查 |
| 4 | 更新只读审批说明，不放宽服务端权限 | `assets/console-core.js` | 只读提示 Node 回归 |
| 5 | 完成回归和交付记录 | `src/test/js/console-core.test.cjs`、本组四份文档 | 定向 Node 测试、`git diff --check`、变更范围检查 |

不重启用户的 8080 服务。浏览器若无已认证会话，只记录认证阻塞，不探查凭据或绕过授权。
