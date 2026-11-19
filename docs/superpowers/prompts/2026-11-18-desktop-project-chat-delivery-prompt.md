# 桌面项目聊天实施提示词

日期 2026-11-18 · 主题 `desktop-project-chat-delivery` · 修订 `R0` · `mode=default`

本提示词保存实施要求和验收背景，实施操作仍以用户指令为准。

```text
在 F:/java/cm-agent 实施用户确认的 R2 桌面项目聊天。先读 AGENTS.md、docs/superpowers/specs/2026-11-18-desktop-project-chat-design.md 和 docs/superpowers/checklists/2026-11-18-desktop-project-chat-delivery-checklist.md，再按本工作包计划推进。当前模式 mode=default，已确认需求以 R2 为准，常规实现细节自主判断并在账本记录。

T1 前端从 V2 聊天页选择本机文件夹并进入固定项目会话，首条及后续消息用 SSE。T2 服务端复用项目归属和 JDBC，验证会话、项目与授权。T3 接入三档授权，默认高风险询问，每轮冻结，白名单与项目范围保持生效。在线模型发送项目片段按高风险判断，批准前不能发出受保护片段。
T4 打出随包 Java 的 Windows x64 试装包，沿用已配置的 JDBC。T5 验证聊天、风险模式、真实操作及试装重启。涉及容器、JDBC Repository、Flyway 或数据库集成测试时，按 AGENTS.md 在 SSH Rocky 的 Maven 21 容器中验证。

保留成立的安全边界和技术限制，保护既有工作树改动，日志、命令输出和产物内不得携带真实凭据。将实际改动记入实现说明，验证证据和提交、发布状态记入账本。
```

[清单](../checklists/2026-11-18-desktop-project-chat-delivery-checklist.md) · [设计](../specs/2026-11-18-desktop-project-chat-delivery-design.md) · [计划](../plans/2026-11-18-desktop-project-chat-delivery.md) · [实现说明](../implementation/2026-11-18-desktop-project-chat-delivery-implementation-design.md) · [账本](../progress/2026-11-18-desktop-project-chat-delivery-ledger.md)
