# 本机一体版项目聊天计划

日期 2026-11-18 · 主题 `desktop-project-chat` · 修订 R2 · `mode=interactive`

本机应用在已配置 JDBC 的 Windows x64 试点使用随包 Java 运行后端，项目会话固定绑定。文件修改、白名单命令和在线模型文件传输采用三种授权模式，默认 `ASK_ON_RISK`，每轮 Run 冻结。项目文件片段发往在线模型统一按高风险处理。普通用户免配数据库的安装另行设计。决定详见[设计](../specs/2026-11-18-desktop-project-chat-design.md)。

1. **T0 明确设计。** 核对目录选择、会话归属、SSE 和审批的现有链路。以清单中的可观察行为评审决定，明确试点部署、在线数据传输和三种授权模式的验收边界。
2. **T1 桌面运行时和现有存储。** 以 [Server 模块](../../../cm-agent-server/pom.xml)和[持久化模块](../../../cm-agent-persistence/pom.xml)为基础，为 Windows x64 打包随附 Java 的启动入口，接入试点已配置的 JDBC 数据源。沿用 Flyway 并验证普通用户权限启动、回环监听、凭据存放和重启恢复。
3. **T2 同机目录和会话。** 从[项目控制器](../../../cm-agent-server/src/main/java/com/cmagent/server/web/LocalProjectController.java)接入异步目录选择和项目会话接口，从[项目服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectService.java)复用根身份及会话绑定。以安装初始化或受控配置准备 Agent 的项目工具权限，创建会话时核验已有权限。验证取消、重试、归档、绑定冲突和不同主体访问。
4. **T4 消息流。** 在[会话控制器](../../../cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java)及[会话服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java)落实会话绑定、项目预检、Run 级模式冻结和错误反馈，沿用 SSE、Run 来源和审批链路。用 MockMvc 覆盖入口校验、事件进度、普通聊天兼容和无效请求无新增消息或 Run。定位实际发送给在线模型的边界，两种询问模式均在传输前展示实际片段和接收方，任何内容或接收方变化重新审批，审批通过后接续原 Run。使用测试 Provider 断言批准前及拒绝后没有收到片段，并验证 `FULL_ACCESS` 仍执行项目、Run、片段及接收方约束。
5. **T3 聊天页。** 在 [V2 聊天页](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html)与[共享脚本](../../../cm-agent-console/src/main/resources/META-INF/resources/assets/app.js)接入项目入口、原生选择反馈、项目内历史及流式首发。用前端用例覆盖取消、首次发送、切换和迟到响应，再验证与后端契约一致。
6. **T5 文件和命令。** 按 D5、D6、D9、D10 接入三种模式和现有提议、批准、执行、状态能力。依据[变更服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectChangeService.java)及[命令服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectCommandService.java)核对项目范围、固定工具目录、绝对可执行文件白名单、参数与环境限制。验证相同批准不会重复生效，项目根或计划已变时安全拒绝，结果未知时可查询和人工核对。
7. **T6 端到端验收。** 在干净 Windows x64 系统连接已配置的试点 JDBC，用随包 Java 以普通用户启动并验收重启恢复。使用专用测试目录验证真实变更、受控命令和在线文件传输，覆盖拒绝、停止、模型或应用中断和错误编号关联。Java 构建先核对 `java -version` 和 `mvn -v`。涉及 JDBC、Flyway、Docker 和 Testcontainers 的集成验证在 [AGENTS.md](../../../AGENTS.md) 指定的 Rocky 和 JDK 21 环境执行。

[清单](../checklists/2026-11-18-desktop-project-chat-checklist.md) · [设计](../specs/2026-11-18-desktop-project-chat-design.md) · [提示词](../prompts/2026-11-18-desktop-project-chat-prompt.md) · [实现说明](../implementation/2026-11-18-desktop-project-chat-implementation-design.md) · [账本](../progress/2026-11-18-desktop-project-chat-ledger.md)
