# 桌面项目聊天实施实现说明

日期 2026-11-18 · 主题 `desktop-project-chat-delivery` · 修订 `R0` · `mode=default`

## 实施范围

本工作包承接 [桌面项目聊天 R2 设计](../specs/2026-11-18-desktop-project-chat-design.md)。

- T1 把同机项目的原生选择、目录登记和会话列表接入 [V2 聊天页](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html)。项目首条消息和审批恢复由 [项目聊天脚本](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/project-development.js) 使用 SSE，复用原有方法读取操作状态。三档授权在本轮消息发送时提交，页面切档影响之后的消息。页面只保存上次活动项目 ID，重启后重新读取服务端项目、最近会话、消息和审批，正文与审批预览不进入浏览器存储。
- T2 [项目服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectService.java) 在可信租户和主体范围内复用同一活动目录。新会话带 `requestId` 时根据服务端已校验的范围派生幂等 ID，只消费管理员预置授权。`POST /api/projects/tools/agents/{agentId}` 负责受控准备固定工具。SSE 的可选 `projectId` 只检查前端选择与现有绑定是否一致。数据库复用现有表与 JDBC/Flyway。
- T3 [模型发送边界](../../../cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/ProjectDisclosureModel.java) 处理本轮项目会话的模型输入，沿用运行审批与加密检查点。实际请求前经宿主再次核对权限和审计，待审批预览与审批记录的摘要对应。试装版的命令只接受部署者配置的参数组合，格式和审批仍使用 [同机命令服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectCommandService.java)。
- T4 [Windows 启动器](../../../cm-agent-server/src/main/java/com/cmagent/server/WindowsPilotLauncher.java) 以当前桌面用户启动回环服务，打开聊天并在退出时关闭服务。[打包脚本](../../../scripts/package-windows-pilot.ps1) 在暂存 Boot JAR 中移除构建机配置，生成随包 Java 的 per-user MSI 及应用镜像。库内默认配置保持审批、外部能力与本机项目关闭，试装启动参数只打开回环、JDBC、审批所需的固定能力。连接信息和私钥由试装管理员放在当前用户外部配置中，本地敏感配置文件不进入版本控制或安装包。

[清单](../checklists/2026-11-18-desktop-project-chat-delivery-checklist.md) · [设计](../specs/2026-11-18-desktop-project-chat-delivery-design.md) · [计划](../plans/2026-11-18-desktop-project-chat-delivery.md) · [提示词](../prompts/2026-11-18-desktop-project-chat-delivery-prompt.md) · [账本](../progress/2026-11-18-desktop-project-chat-delivery-ledger.md)
