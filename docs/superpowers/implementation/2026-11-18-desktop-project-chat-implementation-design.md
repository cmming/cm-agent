# 桌面版项目聊天实现说明

工作包 `2026-11-18-desktop-project-chat` · R2（2026-11-18） · `mode=interactive`

## 本轮产物

- T0 根据聊天控制台、项目接口和流式运行的现有代码整理设计取舍。新增本工作包、补充[术语表](../../../GLOSSARY.md)并记录[桌面项目会话的架构决定](../../adr/0002-desktop-project-conversation.md)。业务实现对应 T1–T6。
- 当前[聊天页](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html)从普通 Agent 会话进入。本机项目已有独立项目入口和项目会话。普通聊天发送前准备普通会话，因此需要将原生目录选择、项目登记及会话创建接进聊天页。
- 现有[会话控制器](../../../cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java)验证请求后开启 SSE，[会话服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java)从已绑定会话解析项目。后续流式设计从这条链路演进，具体校验和事件约定见设计。
- [本机项目服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectService.java)当前创建项目会话会准备 Agent 工具授权。桌面版需要有独立初始化方式，供普通用户在既有授权范围创建会话。现有文件和命令运行链为三种模式提供基础。项目内容发给在线模型的调用链目前缺少对应的授权判断，需要在外发前接入。
- 第二轮将 Windows x64 首发、三种授权模式的适用范围及继续使用 JDBC 写入工作包。桌面专用存储方案见设计的后续范围。
- 第三轮补充命令沿用现有工具链白名单、默认「高风险时询问」并按 Run 固定授权模式、项目片段外发均视为高风险的规则。首个安装包沿用已配置的 JDBC，存储重新设计后再交付免配置数据库的安装体验。

本轮形成需求与设计文档。设计中描述的桌面安装、聊天入口、接口调整和权限收口属于后续业务改动。

关联文档：[清单](../checklists/2026-11-18-desktop-project-chat-checklist.md) · [设计](../specs/2026-11-18-desktop-project-chat-design.md) · [计划](../plans/2026-11-18-desktop-project-chat.md) · [提示词](../prompts/2026-11-18-desktop-project-chat-prompt.md) · [账本](../progress/2026-11-18-desktop-project-chat-ledger.md)
