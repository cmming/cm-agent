# 会话聊天页面实施计划

本计划以[设计说明](../specs/2026-08-31-console-session-chat-page-design.md)为准。

## 任务 1：页面与导航

- 新增 `cm-agent-console/.../console/v2/chat.html`，提供 Agent 选择、会话列表、消息区和输入区。
- 在全部 v2 已登录页面左侧导航加入聊天入口，并在能力总览加入快捷入口。

## 任务 2：前端会话交互

- 扩展 `assets/app.js` 的多页面路由、页面初始化和聊天状态。
- 复用现有 API 客户端、会话 SSE、Markdown 渲染与错误展示；为聊天页增加历史加载、流式占位和发送期禁用控制。
- 保持运行页原有会话调试行为不变。

## 任务 3：样式和测试

- 在 `assets/styles.css` 增加聊天布局、消息气泡、会话选择、流式状态和窄屏响应式样式。
- 在现有 JavaScript 静态测试与服务端控制台资源冒烟测试中加入聊天页可访问及关键页面行为断言。

## 验证方式

- 执行 `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`。
- 执行 `mvn -pl cm-agent-console test`。
- 执行 `mvn -pl cm-agent-server -am -Dtest=ConsoleSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 执行 `git diff --check`。

## 关联文档

- [设计说明](../specs/2026-08-31-console-session-chat-page-design.md)
- [实现说明](../implementation/2026-08-31-console-session-chat-page-implementation-design.md)
- [进度账本](../progress/2026-08-31-console-session-chat-page-ledger.md)
