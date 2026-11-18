# 项目优先聊天实现说明

日期 `2026-11-18` · 修订 `R0` · 模式 `mode=interactive`

范围为 CM Agent 控制台 v2 的目录项目优先聊天及普通聊天入口，任务编号 `T0` 至 `T4`。

## 代码与文档

- `chat.html` 提供项目与普通聊天入口、目录选择、归档筛选及两种项目的会话面板。 `projects.html` 提供目录登记、搜索与预览，按已选项目生成聊天链接。
- `assets/app.js` 保留聊天链接的查询参数，包括控制台内导航、历史和登录返回。文件预览把版本元数据交给同一份文档中重新挂载的项目聊天组件，按认证账号、项目来源与 ID 校验归属。
- `project-development.js` 在读取来源能力及项目详情后恢复浏览器中的账号偏好，并加载服务端电脑会话。相对路径、SHA-256 与字节范围在用户选择会话后拼入草稿。项目消息、审批和运行操作继续走原有接口。
- `client-projects.js` 分别挂载目录管理与会话界面。已配对电脑项目仍经设备请求头执行；归档会话使用消息与审批历史读取接口。页面切换使原组件失效并取消在途读取。
- 更新共享样式、前端回归断言、术语表、README、配置与发布说明。工作包记录 `T0` 至 `T4` 的页面改造。

[清单](../checklists/2026-11-18-project-first-chat-checklist.md) · [执行提示词](../prompts/2026-11-18-project-first-chat-prompt.md) · [设计](../specs/2026-11-18-project-first-chat-design.md) · [计划](../plans/2026-11-18-project-first-chat.md) · [进度账本](../progress/2026-11-18-project-first-chat-ledger.md)
