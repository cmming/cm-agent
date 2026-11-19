# 桌面项目聊天实施计划

日期 2026-11-18 · 主题 `desktop-project-chat-delivery` · 修订 `R0` · `mode=default`

## 顺序

1. 对照 [R2 设计](../specs/2026-11-18-desktop-project-chat-design.md)、现有代码和测试明确接口分工。预先准备 Java 21、试装工具并核对 Rocky 的 Docker 与 Maven 镜像。
2. T1–T3 并行接入聊天界面、项目接口、授权和在线传输边界，统合联调。补充范围、拒绝、诊断编号及泄露边界的回归验证。
3. T4 构建 Java 21 Server 及随包运行时，打出 Windows x64 试装包。连接隔离的既有 JDBC 环境。
4. T5 验证原生选目录、真实加载和使用项目、流式聊天、三档模式、重启恢复及拒绝路径。Maven 测试按子系统分组，数据库类验证在 Rocky。

[清单](../checklists/2026-11-18-desktop-project-chat-delivery-checklist.md) · [设计](../specs/2026-11-18-desktop-project-chat-delivery-design.md) · [提示词](../prompts/2026-11-18-desktop-project-chat-delivery-prompt.md) · [实现说明](../implementation/2026-11-18-desktop-project-chat-delivery-implementation-design.md) · [账本](../progress/2026-11-18-desktop-project-chat-delivery-ledger.md)
