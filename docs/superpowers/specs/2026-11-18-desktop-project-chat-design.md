# 桌面版项目聊天设计

日期 2026-11-18 · 主题 `desktop-project-chat` · 修订 `R2` · `mode=interactive`

## 目标与范围

桌面版先交付 Windows x64 试装包。安装包自带 Java，在本机启动后端并打开聊天页，运行环境提供已配置的 JDBC。使用者可以从聊天页选择文件夹，在项目下开启多条独立会话。消息使用现有 `/api/agents/{agentId}/conversations/{conversationId}/messages/stream` 接口流式运行。首版包括查看和修改文件、运行命令。文件修改、命令和项目片段发往在线模型共用「操作前询问」「高风险时询问」「完全访问」三个选择。普通聊天仍为独立使用场景。

本工作包覆盖设计和验收定义。业务开发、安装分发和发布由后续实施任务承接。局域网实例用于观察现有页面，桌面版部署的权限规则单独设计。

## 现有实现与本轮改造

- **聊天页。** 当前聊天主要使用普通会话，项目会话组件位于项目页。桌面聊天页提供「选择文件夹」「项目列表」「当前项目的会话」和普通聊天入口。选择窗口取消时保留当前会话和草稿。发送时如果尚无项目会话，先完成创建，再将 `conversationId` 交给流式发送。 [聊天页](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html) [项目会话界面](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/project-development.js)
- **选择和绑定。** 安装版在当前 OS 用户的交互环境打开原生选择窗口。服务端使用可信选择结果登记目录身份，生成项目 ID。会话创建事务写入项目、当前主体、Agent 和目录身份绑定，同一项目可以开多条会话。普通用户使用预置项目 Agent 及受控工具授权。现有创建过程会准备固定目录工具并要求 `tool:grant`，这部分应移到受控初始化。 [原生选择器](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/WindowsLocalProjectDirectoryPicker.java) [项目服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectService.java)
- **流式发送与审批。** SSE 当前已经能从持久会话绑定识别项目 Run，项目页初次发送仍走同步接口。拟让聊天页复用 SSE 发送、进度、停止与 `.../approvals/{approvalId}/decision/stream` 审批恢复。若接口接收 `projectId`，该字段只用于与固定绑定比较。绑定不一致在开启 SSE 前拒绝，且本次不产生消息与 Run。执行前再次校验主体、租户、项目状态和目录身份，处理选择后移动、替换或撤销的情况。 [SSE 入口](../../../cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java) [会话执行](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java)
- **文件修改与命令。** 复用同机项目的变更提案、审批和工具链约束。变更应用与命令启动都需要在实际执行边界复核项目与有效授权，拒绝或撤销后不得提交操作。命令的工作目录、参数和进程生命周期需要单独控制。固定项目工作目录与 Windows Job Object 可用于定位执行位置和管理进程，文件系统和网络权限仍由执行环境决定。 [命令服务](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectCommandService.java) [Windows 进程实现](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/WindowsLocalProjectProcess.java)
- **安装版。** Windows x64 安装器自带 Java，启动并托管本机后端，接口通过本机受限入口访问。令牌及项目绝对路径按 OS 用户保护，安装进程、聊天进程和工具执行进程分别遵循最小权限。试装前须由环境提供已配置的 JDBC。沿用现有 JDBC/Flyway，面向普通用户的免数据库配置安装随持久化改造另行验收。数据库不可用时明确报告启动条件和诊断编号。 [本地项目配置](../../configuration.md#本地项目开发工作区) [Flyway 方言选择](../../../cm-agent-persistence/src/main/java/com/cmagent/persistence/CmAgentFlyway.java)

## 三档授权

同机项目的 `projectAuthorizationMode` 已接入三档。桌面版聊天页默认「高风险时询问」，发送时锁定当前 Run 的授权模式。用户切换模式影响后续消息。审批恢复沿用原 Run 的模式，Run 结束后授权失效。与本地项目无关的授权和平台强制审批仍由服务端策略裁定。

| 模式 | 根内只读和生成提案 | 应用文件变更、启动命令 | 向在线模型发送项目片段 |
| --- | --- | --- | --- |
| 操作前询问 `ASK_ON_EFFECT` | 直接执行 | 按当前调用精确审批 | 展示模型和拟发送片段，确认后发送 |
| 高风险时询问 `ASK_ON_RISK` | 直接执行低风险操作，文件和命令提案可直接生成 | 现有工具固定为高风险，按当前调用精确审批 | 项目片段统一按高风险，确认后发送 |
| 完全访问 `FULL_ACCESS` | 在已授权范围执行 | 自动放行符合策略的操作，校验项目绑定、提案版本和摘要，记录自动放行审计 | 自动向本轮已授权的模型发送合规片段，记录脱敏审计 |

命令执行沿用同机项目的受信工具链白名单、受控提案和执行前核验。三档均验证程序、允许参数、项目相对工作目录、计划摘要和有效授权。部署强制审批仍优先，所有档位都应产生相应审计。 [授权策略](../../../cm-agent-core/src/main/java/com/cmagent/core/security/OperationApprovalPolicy.java) [同机项目执行工具](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/LocalProjectTools.java)

当前工具审批与向在线模型发送请求是两条独立链路。桌面版新增模型请求提交前的权限检查，通过数据来源识别文件内容、能识别项目的引用和包含项目数据的工具结果。预览与拟发送内容采用同一份脱敏片段。批准固定本轮、项目、片段内容及模型目标。内容或模型变化时重新判断授权，用户拒绝时阻止有关片段发出。首个请求、后续工具结果、历史和审批恢复均遵守同一边界，在 ReAct 与 Harness 的模型发送路径验证。 [模型数据决定](../../adr/0003-desktop-project-model-disclosure.md) [模型工具桥接](../../../cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeToolBridge.java) [模型发送路径](../../../cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java)

## 接口顺序

1. `POST /api/projects/selections` 启动原生文件夹选择。`GET /api/projects/selections/{requestId}` 查询结果。用户取消时停止流程。
2. `POST /api/projects` 消费选择票据登记项目。后端根据同一主体的已登记目录身份判断是否复用项目，并返回 `projectId`。
3. `POST /api/projects/{projectId}/conversations` 创建带固定绑定的会话。为首次发送前的创建步骤提供幂等标识，页面重试取得同一 `conversationId`。已有项目会话直接使用自己的 ID。
4. `POST /api/agents/{agentId}/conversations/{conversationId}/messages/stream` 发送输入并开始当前 Run。流式连接中断时查询已有 Run 状态，只有能确认原消息未被接收时才重新发送。 [项目接口](../../../cm-agent-server/src/main/java/com/cmagent/server/web/LocalProjectController.java) [会话接口](../../../cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java)

## 设计决定

| 编号 | 问题 | 候选 | 结论及影响 |
| --- | --- | --- | --- |
| D1 | 安装后 Java 服务在哪里运行 | 本机一体版或中心服务加伴随客户端 | 已确认本机一体版。原生选择与项目操作发生在用户电脑，应用启动时托管后端。 |
| D2 | 项目与会话如何归属 | 创建时固定项目或在原会话中换目录 | 已确认固定项目。切换时打开对应的会话或新建会话。见[会话归属决定](../../adr/0002-desktop-project-conversation.md)。 |
| D3 | 首版操作范围 | 只读、含修改、含修改及命令 | 已确认首版包含文件修改和命令执行，需要验证真实副作用及其审批结果。 |
| D4 | 首发系统 | Windows x64、加 macOS、加 macOS 与 Linux | 已确认 Windows x64，安装包自带 Java。 |
| D5 | 命令审批 | 固定逐次确认、参考设备管理三档 | 已确认参考三档。命令能力范围见 D9。 |
| D6 | 文件写入审批 | 固定逐批确认、会话持续授权、参考设备管理三档 | 已确认参考三档。由档位决定是否展示与确认精确变更，仍核验变更版本。 |
| D7 | 项目和历史存储 | 本机持久化随安装管理、多设备同步、改造后移 | 已确认先沿用现有持久化，专用本地存储改造后移。现有数据库交付见 D12。 |
| D8 | 在线模型使用项目文件 | 首次确认、逐次确认、本地模型、参考设备管理三档 | 已确认参考三档。模型请求提交前新增权限检查，风险分类见 D11。 |
| D9 | 三档下能运行哪些命令 | 沿用项目工具链白名单、提供通用终端能力 | 已确认沿用白名单，三档适用同一工具链和参数边界。 |
| D10 | 默认档和生效时机 | 高风险时询问且每轮锁定、操作前询问且每轮锁定 | 已确认默认高风险询问，每轮锁定。 |
| D11 | 项目片段发往在线模型如何定级 | 所有项目片段按高风险、普通与敏感片段分级 | 已确认所有项目片段按高风险，两档询问均确认「模型与实际片段」，完全访问在本轮授权范围自动发送。 |
| D12 | 保持现有持久化的首版如何交付 | 使用已配置 JDBC 的试装包、由首版安装器准备现有数据库 | 已确认先交付已配置 JDBC 的 Windows 试装包，免数据库配置安装后续验收。 |

`R0` 采用 D1–D3。`R1` 采用 D4–D8。`R2` 采用 D9–D12，确定命令范围、默认模式和时效、项目片段的风险分类及 Windows 试装条件。

## 主要验收场景

1. 从聊天页选取目录，创建两个项目会话。使用现有 JDBC 的环境中重开应用后能重新进入正确会话。目录失效时给出可行动的提示，原历史仍可查阅。
2. 项目首条消息通过指定 SSE 路由获得增量输出。普通历史仍可聊，错误项目引用无副作用，工具进度、停止与审批恢复能正确显示。
3. 真实模型能读取选中根目录内的测试文件，按三档授权修改内容、执行任务并处理发送给在线模型的文件片段。验证拒绝、越界、授权失效和结果未知，使用受控模型端点确认拒绝的片段未发出。
4. 在 Windows x64 的已配置 JDBC 环境验证安装包自带 Java、普通账号首次启动和重启恢复。普通用户创建项目会话无需全局工具授权或手填绝对路径。

配套产物包括[清单](../checklists/2026-11-18-desktop-project-chat-checklist.md)、[计划](../plans/2026-11-18-desktop-project-chat.md)、[提示词](../prompts/2026-11-18-desktop-project-chat-prompt.md)、[实现说明](../implementation/2026-11-18-desktop-project-chat-implementation-design.md)和[账本](../progress/2026-11-18-desktop-project-chat-ledger.md)。
