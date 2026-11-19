# 桌面项目聊天实施设计

日期 2026-11-18 · 主题 `desktop-project-chat-delivery` · 修订 `R0` · `mode=default`

## 决定来源

产品决定见 [R2 设计](../specs/2026-11-18-desktop-project-chat-design.md)。实施使用现有同机项目票据和项目会话持久绑定。项目选择在前端进行，SSE 请求按会话绑定识别项目，授权模式仅作用于当前 Run。

## 组件

- 前端在现有 V2 聊天页整合同机项目和专用审批预览。普通聊天保留原用法。
- 服务端在建立项目 Run 前检查认证主体、项目状态与会话归属。工具执行使用现有项目白名单、精确批准和审计。
- 模型边界复核实际项目片段、在线目标和冻结授权。试装版使用已有 JDBC/Flyway，应用进程只监听本机回环。
- 免数据库配置的正式发行由后续独立需求承接。

[清单](../checklists/2026-11-18-desktop-project-chat-delivery-checklist.md) · [计划](../plans/2026-11-18-desktop-project-chat-delivery.md) · [提示词](../prompts/2026-11-18-desktop-project-chat-delivery-prompt.md) · [实现说明](../implementation/2026-11-18-desktop-project-chat-delivery-implementation-design.md) · [账本](../progress/2026-11-18-desktop-project-chat-delivery-ledger.md)
