# 项目优先聊天实施计划

日期 `2026-11-18` · 修订 `R0` · 模式 `mode=interactive`

范围为 CM Agent 控制台 v2 的目录项目优先聊天及普通聊天入口，任务编号 `T0` 至 `T4`。

## 实施顺序

1. **T0** 在术语表和设计中写入目录项目、普通会话及用户确认的 Q1 至 Q8。
2. **T1** 在 `assets/app.js` 让聊天路由保留查询参数，并把项目偏好与会话运行状态交给项目聊天组件管理。项目目录与会话详情必须重新请求，检查登录返回、同页导航和浏览器历史。
3. **T2** 把 `projects.html` 的服务端项目会话控件移动到 `chat.html`。在 `local-projects.js` 设置项目聊天链接和文件版本交接，在 `project-development.js` 渲染会话与接收草稿。验证交接不发出写请求。
4. **T3** 分离 `client-projects.js` 的项目管理与聊天挂载，接入设备专用请求头。两类归档项目只读并加载分页历史。验证跨来源、退出与迟到响应隔离，保留既有审批和运行停止回归。
5. **T4** 统一各 v2 HTML 的资源版本，更新使用、配置和发布说明。运行 Console 模块的 Maven 测试及 `cm-agent-console/src/test/js/*.test.cjs`。用模拟 API 的浏览器预览验证桌面、窄屏、登录返回、文件引用和归档历史。

[清单](../checklists/2026-11-18-project-first-chat-checklist.md) · [执行提示词](../prompts/2026-11-18-project-first-chat-prompt.md) · [设计](../specs/2026-11-18-project-first-chat-design.md) · [实现说明](../implementation/2026-11-18-project-first-chat-implementation-design.md) · [进度账本](../progress/2026-11-18-project-first-chat-ledger.md)
