# 聊天页人工审批界面优化实现记录

对应设计：[设计说明](../specs/2026-09-30-chat-approval-ui-design.md)；执行拆分：[计划](../plans/2026-09-30-chat-approval-ui.md)；实际验证：[进度账本](../progress/2026-09-30-chat-approval-ui-ledger.md)。

## 实际实现

- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`：审批卡片渲染和权威状态加载后，存在待办才将 `chatApprovalRegion` 滚入视口；标题显示待办项数；选择未完成时明确提示还需逐项选择，完整时显示允许/拒绝汇总。
- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`：说明核对脱敏参数、逐项选择和提交顺序。
- `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`：调整消息区域最小轨道高度；设置更易点击的 48px 选择行、允许/拒绝区分状态、焦点轮廓和审批区内吸底操作栏。
- `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`：只读提示说明审批可能过期或账号不满足条件，并列出本次运行发起人及 `agent:run`、`agent:approve` 要求和刷新/联系管理员的动作。
- `cm-agent-console/src/test/js/console-core.test.cjs`：覆盖页面提示、自动定位调用、控件样式合同和权限提示。

## 安全与兼容性

没有改变 API、决定载荷、审批提交时序或后端权限。卡片是否可编辑仍由服务端 `canDecide` 与现有客户端状态共同决定；界面文案不是授权来源。刷新仅读取权威状态，不自动重复提交。仅修改 v2 聊天页的展示，不触碰 v1。

## 与原方案差异

无实质差异。实际登录态浏览器验证未完成：本机 `http://localhost:8080/console/v2/chat.html` 返回登录页并跳转 `login.html`，本任务没有使用或读取凭据，也没有重启该服务。
