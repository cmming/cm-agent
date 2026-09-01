# 会话聊天页面实现说明

## 关联文档

- [设计说明](../specs/2026-08-31-console-session-chat-page-design.md)
- [实施计划](../plans/2026-08-31-console-session-chat-page.md)
- [进度账本](../progress/2026-08-31-console-session-chat-page-ledger.md)

## 最终实现

### 页面与导航

新增 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`。该页由左侧 Agent/会话选择区、右侧消息列表和底部输入区组成；全部 v2 已登录页面的导航与能力总览快捷操作均可进入该页。

### 会话与流式交互

`assets/app.js` 将 `chatPage` 纳入多页面路由，并复用已有认证状态、API 客户端、Markdown 安全渲染器和会话状态。页面加载时读取 Agent、会话和消息；创建或选择会话后可连续发送。发送时先渲染当前用户输入和流式回答容器，消费既有会话 SSE 的 `delta`、`completed`、`error` 事件；终态后重新读取服务端消息，确保页面与持久化结果一致。

发送期间会禁用 Agent 切换、新建会话和发送按钮，避免同会话消息交错。错误只展示现有接口的脱敏中文消息、错误码和错误编号，不会自动重试写请求。工具消息仅显示后端返回的受控摘要。

### 样式与测试

`assets/styles.css` 新增聊天页布局、会话项、消息气泡、流式状态、屏幕阅读器标签和窄屏响应式规则。JavaScript 静态测试验证页面入口、关键元素和会话流复用；`ConsoleSmokeTest` 增加聊天页静态资源断言。

## 与设计的差异

无。未新增服务端 API、数据库迁移或权限模型。
