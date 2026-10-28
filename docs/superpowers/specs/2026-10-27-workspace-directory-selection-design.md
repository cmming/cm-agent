# 工作区目录选择需求设计

日期：2026-10-27；主题：workspace-directory-selection；修订：R0；模式：mode=default。

## 需求与交付边界

用户需求：“新增一个需求，让系统支持选择工作区目录，同时改造前后端。”本次调用 requirement-workpack，交付需求分析与六份工作包；前后端业务实施均纳入后续范围，目前尚未实施。以下方案是依据当前仓库自主采用的首版假设，不代表用户逐项确认。

目标：使用者在发送消息前明确选择本次会话使用的工作区参考目录；刷新后可恢复选择；运行和审批恢复使用可追溯的目录身份与原运行资料快照。后端选择必须真实影响模型获得的资料，不能只保存一个页面标签。

Git 基线：`369418b3bdc08b096de7854f9d04c982ec8be050`。开始时暂存区为空；已有 `.gitignore`、`application.yml`、`application-mysql.yml`、`docs/release-notes.md` 修改，以及 `.workbuddy/`、`application-ok.yml` 未跟踪内容，本任务保留。

## 当前事实

| 编号 | 当前事实 | 当前证据 |
|---|---|---|
| E1 | 已有默认关闭的只读资料配置；一个 tenant/Agent 只能有一个映射，文件逐项声明 | `cm-agent-server/src/main/java/com/cmagent/server/config/WorkspaceContextProperties.java` 的 `validate`、`Binding`；`docs/configuration.md` |
| E2 | 首次运行读取并加密固定快照；恢复检查身份、映射摘要、守卫和预算，终态清理 | `cm-agent-server/src/main/java/com/cmagent/server/runtime/WorkspaceContextService.java`；`RunExecutionService.java:329` 附近 |
| E3 | 文件入口拒绝应用仓库、用户目录及祖先、网络文件系统、链接与路径别名；Windows 和 Linux 的保护方式不同 | `cm-agent-server/src/main/java/com/cmagent/server/runtime/WorkspaceContextFiles.java`；`docs/operations.md` |
| E4 | ReAct/Harness 支持可信 `workspaceContext`；Harness 原生文件工具、Shell、原生资料加载仍关闭，使用私有临时目录 | `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java`；`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeHarnessExecutor.java:83` 附近；adapter README |
| E5 | Conversation 没有工作区字段；创建会话接口不接收请求体，发送接口只有 input；发送在会话控制锁和短事务内创建 Run 与用户消息 | `cm-agent-core/src/main/java/com/cmagent/core/domain/Conversation.java`；`cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java`；`ConversationService.java:196` 附近 |
| E6 | v2 聊天已有 Agent/历史会话选择、流式回答、审批、停止和状态核对，没有目录选择控件 | `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`；`assets/app.js` 中 `ensureConversation`、`sendChatMessage`、会话切换逻辑 |
| E7 | 目前方言迁移最高编号为 V21；已有会话、检查点 JDBC Repository 与双库迁移测试 | `cm-agent-persistence/src/main/resources/db/migration/{postgresql,mysql}`；`JdbcConversationRepository.java`；`src/test/java/com/cmagent/persistence/MigrationTest.java` |

以上是本次源码检查结论；历史文档中的测试通过记录不算本需求验证。先使用 CodeGraph；部分精确符号查询返回了相邻文件且被预算截断，未覆盖的配置、接口、持久化与页面直接复核。Impeccable 首次 context 的探测目标使用了不存在的 static 路径；随后确认真实页面位于 META-INF/resources，沿用已加载的 PRODUCT.md、DESIGN.md，不据错误路径判断页面不存在。本次未启动浏览器。

## 默认假设与取舍

| 编号 | 自主采用的假设 | 依据与影响 |
|---|---|---|
| A1 | 选择的是 Server 所在机器可见的参考目录 | 与已有资料读取边界一致；不把用户浏览器机器的路径当成服务器路径。远程/容器部署需由部署者先提供受控本地卷 |
| A2 | 首版只读；不改变进程工作目录，不授予文件写入、Shell 或原生 Harness 文件系统能力 | 复用已存在的治理链；“通用项目目录读写工作台”属于另一安全模型，未纳入本版 |
| A3 | 从管理员登记的目录清单选择，可按名称/安全相对位置筛选；不开放任意磁盘浏览、任意绝对路径输入或目录上传 | 满足目录选择且避免文件系统结构泄露；目录和允许文件由部署配置维护，首版不做 Web 管理 CRUD |
| A4 | 选择绑定会话；首条消息后固定，换目录创建新会话 | 防止旧消息混入另一个目录；已有业务不会被静默迁移，跨会话不记忆上一个用户/租户的选择 |
| A5 | 旧调用省略选择时保留现有自动映射；明确不使用工作区必须单独表达 | 采用 DEFAULT、SELECTED、NONE 三种选择模式，区分省略、选目录、主动清空；不以 null 同时代表三种语义 |
| A6 | 正文仍按 Run 冻结，目录选择按会话固定 | 新一轮可读取目录中的更新内容；同一 Run 审批恢复必须使用原快照。目录身份或声明文件集合改变必须使旧绑定失效，不能把同一 ID 悄悄指向另一个目录 |

未验证项：实际部署目录、所有者权限、租户和 Agent 配置、远程卷和多实例路径一致性、真实模型消费、浏览器尺寸与键盘体验。当前没有这些条件已就绪的证据，实施时按 T7 验收。

## 后端方案

### 目录目录项与权限

扩展现有 `cm-agent.workspace-context` 配置，增加多目录 catalog 概念；保留既有 root-directory、bindings 及预算语义。每个目录项声明稳定 workspaceId、中文名称、tenantId、允许的 Agent、根内相对 directory、明确 files、启用状态与配置身份摘要。具体 Java 类型及配置键在 T1 按现有风格确定，不新增未经需要的依赖或自动扫描机制。

根路径仅在服务端受信配置解析；普通接口和日志只出现 ID、名称、安全相对目录、可用状态及预算摘要。安全相对目录属于已授权的展示元数据，错误响应不得夹带文件路径或异常原文。不同租户不得重复授权同一物理目录；父子重叠、别名、重复 ID 和重复物理目录在配置校验时拒绝。同租户多个 Agent 可显式共享同一个目录项。保留现有私有根、禁止来源、文本类型和限额，不能为接受任意项目仓库而削弱原检查。

可见列表同时校验主体认证、`agent:read` 与 `agent:run`，目录项还需匹配可信 tenant/Agent。创建或绑定使用 `agent:run`，读取绑定使用 `agent:read`；未知或跨 tenant/Agent 的目录返回一致 404。新增修改动作限定会话创建者，其他主体即使同租户也不得重绑；现有会话读取权限语义不在本次顺带重构。拒绝必须审计，成功选择和运行冻结记录严格元数据审计；审计或存储失败不得继续进入模型。

### 会话绑定与运行身份

优先增加独立 `ConversationWorkspaceBinding` 与 `RunWorkspaceSelection` 领域对象/Repository，避免强改现有 Conversation/RunRecord 公共构造器。字段至少包含可信 tenant/Agent、会话或 Run ID、模式、可空 workspaceId、配置身份摘要、版本、可信操作者及时间。运行元数据保留可安全展示的名称/相对目录快照，不保存宿主绝对路径或正文。NONE/DEFAULT 也记录模式，终态清理资料正文不能清除历史选择证据。

新增表名建议 `conversation_workspace_bindings`、`run_workspace_selections`；名称为设计建议，尚不存在。会话绑定唯一键为 tenant/conversation；运行选择唯一键为 tenant/run，并有需要的归属索引和明确软引用/外键策略。memory 与 JDBC 实现语义一致；数据库仅保存选择元数据，正文继续使用现有加密检查点，不在普通会话表保存资料。

新增方言迁移，实施时重新核对最大编号，不能把当前 V21 当作永久基线；PostgreSQL/MySQL 同版本、表及每个字段有中文原生注释。使用 CmAgentFlyway 原有选择机制。老会话没有绑定记录时按 DEFAULT 读取，禁止启动时批量扫描/读取资料，也不能从过往文本猜目录。

绑定写入与首条消息/Run 创建使用同一会话并发控制及短事务；JDBC 还需数据库层行锁或等价条件更新，不能只依赖单进程锁。绑定请求携带 expectedVersion，陈旧请求返回 409；已出现用户消息或已有运行的会话禁止改绑。长文件读取及模型调用放在事务外。Run 接受时固定选择身份并持久化，随后文件读取、快照创建、审计完成才能调用模型；失败保留可定位的失败 Run，按现有收口处理。memory 无数据库事务时必须有同等锁与失败补偿测试。

### 拟定接口合同

以下均为待实现合同，路由需与最终实现同步；不表示接口当前存在。

| 接口 | 作用与兼容 |
|---|---|
| `GET /api/agents/{agentId}/workspaces` | 返回仅当前主体可选的登记目录及 capabilities；只做有界目录项查询，不递归读取文件；关闭时明确 enabled=false |
| 原 `POST /api/agents/{agentId}/conversations` | 可选请求体增加 workspaceSelection；原无请求体调用保持 DEFAULT；创建绑定与会话原子保存 |
| `GET /api/agents/{agentId}/conversations/{conversationId}/workspace` | 返回当前模式、选择、安全展示字段、版本、可否修改；失效时显示状态，不静默改绑 |
| `PUT /api/agents/{agentId}/conversations/{conversationId}/workspace` | 仅空会话创建者可以条件更新；NONE 表示清空，DEFAULT 恢复旧规则，SELECTED 必须带合法 workspaceId |
| 原同步/流式单轮 Run 创建接口 | 可选 workspaceSelection；省略沿用 DEFAULT；会话消息发送只用服务端绑定，不能每条消息覆盖 |
| 已有 Run 详情/会话 Run 查询 | 增加独立 workspaceSelection 安全视图，说明该轮的选择；审批/停止请求不能覆盖它 |

HTTP 200/201 的现有约定优先保持；新增错误以接口真实语义为准：参数无效 400、未认证 401、权限拒绝 403、不可见资源 404、版本/会话冲突 409、已登记但当前不可用或撤销 409、读取预算超限 422、依赖/审计/持久化不可用 503、未知内部失败 500。统一异常映射与测试必须覆盖上述实际新增分支，不把业务拒绝写成 200 成功。

建议新增 `WORKSPACE_SELECTION_INVALID`、`WORKSPACE_SELECTION_CONFLICT`、`WORKSPACE_NOT_FOUND`、`WORKSPACE_UNAVAILABLE`；读取正文继续复用现有 `WORKSPACE_CONTEXT_*`。最终枚举按现有 ApiErrorCode 去重复用。错误使用当前接口的 code/errorCode 命名约定，前端统一归一化；必须有中文原因、可行动建议和关联 errorId。Run 前失败生成随机 errorId；已创建 Run 沿用 runId。SSE 建连前走 HTTP 错误，建连后走既有 error 事件，不伪造可修改 HTTP 状态。日志与页面编号一致，保留脱敏堆栈；不记录正文、绝对路径、凭据或客户端伪造 tenant。

### 运行和恢复调用链

选择目录 → 后端按可信主体筛选 catalog → 空会话保存模式/目录身份 → 消息与 Run 创建时固定选择 → WorkspaceContextService 按固定身份解析配置 → 有界读取与原有加密快照/守卫 → AgentRunRequest.workspaceContext → ReAct/Harness 只读参考消息。

WAITING_APPROVAL 恢复：读取原 Run 选择及原检查点 → 校验原身份仍获授权且配置身份未变 → 使用原资料，不重读最新文件。停用/撤销/身份变更即拒绝，不能回退 DEFAULT、NONE 或其他目录。仅文件正文更新不改变声明身份；新 Run 读取新版，原 Run 仍用旧正文。SELECTED 元数据/快照缺失、过期或损坏不能按老 Run 兼容路径忽略；历史无选择元数据的 Run 仅允许原 DEFAULT 逻辑，依原守卫判断。

NONE 永不注入旧绑定；DEFAULT 保留旧映射逻辑且每 Run 冻结实际结果。选择功能总开关关闭后拒绝 SELECTED 新运行/恢复，不能悄悄当 NONE；旧未选择调用保持原行为。清理仍由 Run 生命周期管理，停止、失败、审批超时与严格审计故障均有验收。Harness 临时目录、原生工具禁用、Skill 沙箱无宿主挂载和产物下载边界保持。

## 前端方案

以现有 v2 会话聊天为主入口，沿用 PRODUCT.md/DESIGN.md 的受控工作台和现有 DOM ID。输入区上方紧凑显示“工作区：沿用 Agent 配置 / 不使用工作区 / 已选名称”，提供“选择目录”。弹层列出可用目录名称、安全相对位置、只读标识，可筛选并用键盘选择；不新增顶级导航页，不把静态面包屑“当前工作区”误当实际目录状态。

新会话首发前可选；自动创建会话时，ensureConversation 必须带本次选择。已有空会话保存成功后才更新页面；已有消息显示锁定与“在新会话中选择其他目录”，保留原历史。刷新、切换 Agent/会话从后端重新读取，采用现有 sessionEpoch/请求代次防止慢响应串到新会话；不把 localStorage 当权威，不跨用户保存目录。

覆盖加载、空清单、功能未启用、无权限、目录停用、网络失败、保存中、版本冲突与超长中文名称。SELECTED 不可用时禁用发送并提供联系管理员或新会话操作；不得吞错发送到默认目录。关闭弹层不保存；写请求不自动重试。已创建会话但绑定失败时不能再自动创建第二个会话来掩盖失败。

运行详情显示该轮工作区的只读元数据，审批等待与恢复保持同一选择。桌面不挤占消息和输入区，390px 单列无页面级横向溢出；弹层有标题、焦点管理、Esc 关闭与焦点返回，状态 aria-live，目录名称用 textContent 渲染。共享 app.js 改动必须回归 v1、跨页登录、审批、停止、失败提示和产物下载。

## 非目标与验收门禁

不含用户电脑原生文件夹选择器、上传目录、任意服务端磁盘浏览、文件编辑、Shell、Git 操作、自动读取整个项目、动态管理目录注册、远程主机路径接管和完整项目 IDE。A1/A2/A3 若经后续需求调整，需要修订工作包并重新评估隔离与授权，不能作为普通实现细节偷偷扩大。

验收以清单 T1–T7 为准：两目录选择实际改变模型可见参考；NONE/DEFAULT/SELECTED 兼容；跨租户/主体不可越权；并发首发不能改绑；审批恢复不换资料；重启后 JDBC 选择可恢复；两库注释完整；错误与日志关联且脱敏；桌面/390px/键盘可完成完整流程；ReAct/Harness 均回归。真实模型未运行、浏览器未验收或双库未通过时，不宣布功能验收完成。

## 关联产物

- [清单](../checklists/2026-10-27-workspace-directory-selection-checklist.md)
- [提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)
- [计划](../plans/2026-10-27-workspace-directory-selection.md)
- [实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-design.md)
- [账本](../progress/2026-10-27-workspace-directory-selection-ledger.md)
