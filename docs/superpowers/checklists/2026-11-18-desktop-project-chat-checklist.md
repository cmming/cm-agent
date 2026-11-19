# 本机一体版项目聊天清单

日期 2026-11-18 · 主题 `desktop-project-chat` · 修订 R2 · `mode=interactive` · 分析基线 `c8e1ec3`

来源为本轮对 V2 会话聊天、原生文件夹选择、消息流接口和普通用户桌面安装的需求访谈。首版覆盖 Windows x64 本机运行和 Java 随包交付、固定项目会话、文件修改、受控命令及在线模型文件传输。三类操作采用「操作前询问 / 高风险时询问 / 完全访问」，每轮 Run 默认 `ASK_ON_RISK`，启动时冻结模式。访问始终受已选项目、工具能力和数据范围约束。当前交付面向已配置现有 JDBC 的试点，普通用户免配数据库的安装另行设计。D1–D12 及其影响见[设计](../specs/2026-11-18-desktop-project-chat-design.md)。

| 编号 | 目标与涉及位置 | 依赖 | 可观察验收与验证方式 |
| --- | --- | --- | --- |
| T0 | 明确发布范围和项目授权语义，维护[设计](../specs/2026-11-18-desktop-project-chat-design.md)及工作包 | D1–D12 | 采用的决定有用户确认依据。六份文档能互相跳转，任务编号和验收对应。 |
| T1 | 打包 Windows x64 桌面运行时并复用现有 JDBC/Flyway 数据方案，涉及 [Server](../../../cm-agent-server/pom.xml) 和[持久化层](../../../cm-agent-persistence/pom.xml) | T0、D4、D7、D12 | 在干净 Windows x64 环境中使用随包 Java，连接试点环境已配置的 JDBC 数据源，以普通用户权限启动并监听本机回环。退出或重启后能从既有数据源读取项目及历史。 |
| T2 | 同机原生选目录、登记项目、固定会话关联和初始化授权，复用[项目控制器](../../../cm-agent-server/src/main/java/com/cmagent/server/web/LocalProjectController.java)及[项目服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectService.java) | T0、T1 的运行边界 | 选择成功后获得项目和会话标识。测试取消、重复选择、权限不足及根目录身份变化。普通用户在既有权限内可开会话，无需全局工具授权权限。 |
| T3 | 在 [V2 聊天页](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html)选择项目或文件夹并查看项目会话 | T2、T4 的契约 | 从聊天页选目录并发送首条消息。切换项目显示对应会话。自动化覆盖取消选择和迟到响应场景，普通会话可继续使用。 |
| T4 | 使用既有消息流路由和持久会话绑定，[会话控制器](../../../cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java)在启动 Run 前校验来源与归属，冻结本轮授权模式 | T2、D8、D10、D11 | 合法会话能收到流式回复和审批事件。错误项目引用、授权不足、失效目录及非可信来源在任何新增消息或 Run 前拒绝。项目片段发往在线模型统一按高风险，两种询问模式预览实际内容与接收方，变更后重新审批，通过后可接续原 Run。测试 Provider 核验批准前、拒绝后均无项目片段收到；`FULL_ACCESS` 仅发送当前 Run 有权使用且符合筛选条件的片段。 |
| T5 | 接通项目内文件变更和命令执行，复用[变更服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectChangeService.java)与[命令服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectCommandService.java) | T2、T4、D5、D6、D9、D10 | 三种模式均验证直接执行、询问或拒绝，确认 Run 期间模式保持固定以及项目范围和工具能力限制仍生效。命令只允许显式白名单的绝对可执行文件及受限参数和环境。拒绝、过期授权、对象或命令计划变化、取消和结果未知均按各自语义呈现，未知结果无法自动重放。 |
| T6 | 端到端验收已配置 JDBC 的 Windows x64 试点 | T1–T5、D12 | 用随包 Java 启动，验证 JDBC 凭据和连接。经真实目录选择、首条 SSE、项目文件读写、执行及停止命令、在线传输边界到重启恢复，核对界面、操作结果和日志。 |

数据库与容器类验证遵循 [AGENTS.md](../../../AGENTS.md) 的 Rocky 环境要求。相关功能的安全拒绝、错误脱敏和编号关联为验收门禁。

[设计](../specs/2026-11-18-desktop-project-chat-design.md) · [计划](../plans/2026-11-18-desktop-project-chat.md) · [提示词](../prompts/2026-11-18-desktop-project-chat-prompt.md) · [实现说明](../implementation/2026-11-18-desktop-project-chat-implementation-design.md) · [账本](../progress/2026-11-18-desktop-project-chat-ledger.md)
