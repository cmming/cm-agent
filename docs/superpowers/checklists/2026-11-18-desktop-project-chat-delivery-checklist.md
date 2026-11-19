# 桌面项目聊天实施清单

日期 2026-11-18 · 主题 `desktop-project-chat-delivery` · 修订 `R0` · `mode=default`

## 范围

实施用户确认的 [R2 清单](../checklists/2026-11-18-desktop-project-chat-checklist.md)。首版在已有 JDBC 的 Windows x64 试装环境运行，覆盖本机选目录、固定项目会话、流式发送、三档授权和实际安装验证。

| 编号 | 目标 | 依赖 | 验收 |
| --- | --- | --- | --- |
| T1 | 前端接入项目会话 | T2 的接口契约 | 聊天页选目录、取消、切换、建立并续聊项目，普通会话仍可使用。 |
| T2 | 服务端校验并绑定项目 | 现有项目权限和 JDBC | 一直以认证主体和持久绑定解析项目。失败或过期绑定在新增消息前拒绝。 |
| T3 | 接入三档授权 | T2 | 默认询问高风险，每轮冻结。实际文件、命令和在线项目片段均按授权执行，白名单及项目根隔离保持。 |
| T4 | Windows x64 试装包 | T1–T3、Java 21 和预配 JDBC | 安装包携带 Java，普通用户启动本机回环服务，重启后读取已有项目和会话。 |
| T5 | 验证接口、实际目录和试装 | T1–T4 | 核对原生选择、首条 SSE、拒绝与审批、真实副作用、在线模型测试端点及启动和重启。 |

容器、JDBC 与 Flyway 验证遵循 [AGENTS.md](../../../AGENTS.md) 的 Rocky 规则。

[设计](../specs/2026-11-18-desktop-project-chat-delivery-design.md) · [计划](../plans/2026-11-18-desktop-project-chat-delivery.md) · [提示词](../prompts/2026-11-18-desktop-project-chat-delivery-prompt.md) · [实现说明](../implementation/2026-11-18-desktop-project-chat-delivery-implementation-design.md) · [账本](../progress/2026-11-18-desktop-project-chat-delivery-ledger.md)
