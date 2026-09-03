# AgentScope Permission System 接入实现说明

## 关联文档

- [设计说明](../specs/2026-09-02-agentscope-permission-system-integration-design.md)
- [实施计划](../plans/2026-09-02-agentscope-permission-system-integration.md)
- [进度账本](../progress/2026-09-02-agentscope-permission-system-integration-ledger.md)

## 实际交付范围

本次已完成第一版在线 HIGH 工具逐调用审批的前后端纵切。AgentScope Permission System 只负责模型已经提出的具体工具调用是否需要人工确认；API RBAC、租户隔离、Agent 与工具关联、ToolGrant、工具启用状态、执行前复核和严格审计仍由 CM Agent 负责，审批不能覆盖这些安全边界。

功能通过 `cm-agent.agentscope.permission-enabled` 显式开启，默认关闭。开启后 LOW/MEDIUM 工具显式 ALLOW；持久化会话中的 HIGH 工具使用 ASK，无会话 Run 固定使用 `DONT_ASK` 将 ASK 转为拒绝，避免产生无人处理的暂停。当前未开放模式覆盖配置、BYPASS、EXPLORE、ACCEPT_EDITS、长期授权规则或独立审批人工作台。

## 核心实现

### 1. 领域状态与运行时合同

- `RunStatus` 新增非终态 `WAITING_APPROVAL`，`RunRecord` 允许 `RUNNING -> WAITING_APPROVAL -> 终态`，两个活动状态都不能包含 `finishedAt`。
- 新增 `ToolApprovalRequest`、`ToolApprovalItem`、审批状态/决定/策略枚举、`RuntimePendingApproval` 和 `RuntimeCheckpoint`。
- `AgentRuntimeResult` 可携带待审批安全快照；`AgentRuntime.resumeStructured` 是显式恢复扩展点，旧 Runtime 默认拒绝恢复，避免静默忽略审批。
- `ToolApprovalRepository` 与 `RuntimeCheckpointRepository` 位于 Core，不依赖 Spring Web、JDBC 或 AgentScope 类型。

### 2. AgentScope ToolBase 与 ASK/恢复

`AgentScopeToolBridge` 已由普通 `AgentTool` 改为 `ToolBase`。工具仍固定声明 `readOnly=false`、`concurrencySafe=false`，因为现有风险等级不能证明无副作用。`AgentScopeReActExecutor` 为每次运行构建权限上下文：开关关闭时全部已治理工具显式 ALLOW；开关开启时 HIGH 为 ASK，LOW/MEDIUM 为 ALLOW；有 conversationId 的在线会话使用 DEFAULT，无会话 Run 使用 DONT_ASK。

执行器识别 `GenerateReason.PERMISSION_ASKING`，从确认事件生成包含规范化输入哈希和限长参数摘要的待审批快照，并保留 AgentScope `agent_state`。Core 不携带原始 Map 或 AgentState；摘要在 Adapter 生成时尚未脱敏，必须由 Server 在保存审批和返回视图前脱敏，不能直接对外返回 Runtime 结果。

恢复时以可信的 `tenantId:principalId` 作为 userId、原 runId 作为 sessionId，加载原状态并把全部 item 决定转换为 `ConfirmResult`。恢复前核对完整 `toolCallId` 集合、原工具 `toolId` 和规范化输入 SHA-256；输入变化或同名工具被不同 ID 替换均拒绝。获批调用仍只能从 `ToolInvocationGateway` 进入现有治理链。

### 3. 加密检查点与数据库

`RepositoryAgentStateStore` 将 AgentScope State 序列化后交给现有 `ModelCredentialCipher` 使用随机 IV 的 AES/GCM 加密，明文只短暂存在于当前 JVM 内存。tenant 只能从执行器构造的 userId 前缀解析；无法解析时拒绝保存或读取。检查点与审批请求共享 `approval-ttl`，运行终态和过期处理会删除对应 session。

Flyway V11 为 PostgreSQL 和 MySQL 分别创建：

- `tool_approval_requests`：资源归属、发起人/审批人快照、状态、过期时间、乐观锁版本和检查点引用；
- `tool_approval_items`：具体工具调用、风险快照、脱敏输入摘要、SHA-256 输入哈希、策略版本和单项决定；
- `runtime_checkpoints`：tenant/user/session/key 唯一槽、状态类型、AES/GCM 密文和过期时间。

三张表及每个字段都包含数据库原生中文注释；复合外键指向现有 `agent_definitions`、`tool_definitions`、`conversations` 和 `runs`。memory 模式提供对应内存 Repository，仅用于本地与测试。

### 4. 审批编排、API 与审计

`ConversationService` 在同一会话存在有效 PENDING 审批时拒绝新消息。Runtime 返回 ASK 后，`RunExecutionService` 把 Run 转为 `WAITING_APPROVAL`，`ToolApprovalService` 保存审批请求并记录 `TOOL_APPROVAL_CREATE` 审计，不追加虚假 assistant 完成消息。

`RunPersistenceService.waitForApproval` 同时保存本轮 ASK 前已经执行的工具调用记录，并沿用脱敏和授权工具快照；连续多轮 ASK 时即使 Run 已处于等待状态，也不能丢弃新一轮记录。

对外接口为：

```text
GET  /api/agents/{agentId}/conversations/{conversationId}/approvals?status=PENDING
GET  /api/agents/{agentId}/conversations/{conversationId}/approvals/{approvalId}
POST /api/agents/{agentId}/conversations/{conversationId}/approvals/{approvalId}/decision/stream
```

消息流遇到 ASK 时发送 `approval-required` 后正常结束，不发送 `completed`。决定接口只接受 `expectedVersion` 和覆盖全部 item 的 `itemId + APPROVE/DENY`；tenant、主体、工具参数和检查点都不能由客户端提供。Controller 在建立 SSE 前完成权限、资源、版本、过期和决定完整性校验；Repository 用 `PENDING + version_no` 条件更新保证竞争决定最多一个成功。

第一版只允许原发起人自批，且必须同时拥有 `agent:run` 与 `agent:approve`。前端 `canDecide` 同时检查这些条件及状态/有效期，恢复入口再次确认执行主体与原 Run 一致。全部拒绝直接将 Run 收口为 `DENIED`；全部允许或混合决定从检查点恢复，依次发送 `approval-decision`、执行进度/文本和 `completed`，再次遇到 HIGH 工具则发送新的 `approval-required`。审批创建、决定和过期写入独立审计，恢复后的工具及 Run 终态继续沿用现有严格审计。

决定接受后，若模型/Agent 预检或 Runtime 恢复抛出异常，审批 Service 尝试终结仍活动的 Run、删除检查点并写恢复失败审计；持久化或审计失败继续向外传播。`ConversationController` 对流内 4xx 受控拒绝使用带 `errorId`、可信租户/主体/会话的 WARN；未预期异常由统一诊断器保留脱敏堆栈。HTTP/SSE 沿用 `code`、`message`、`errorId` 字段，非法决定请求返回 `TOOL_APPROVAL_INVALID_DECISION`，不会把异常原文直接交给浏览器。

### 5. 控制台

2026-09-03 已叠加[人工确认 UI 交互优化](2026-09-03-tool-approval-ui-ux-implementation-design.md)及[审批历史回显](2026-09-03-tool-approval-history-implementation-design.md)。当前已支持终态分页及刷新恢复，下面有关“只能查指定审批 ID、PENDING 不是历史台”的表述记录最初接入的边界，最新链路与测试以独立历史主题为准。

聊天页在消息列表和输入框之间增加固定审批区域。`app.js` 维护当前会话的待审批 Map 和提交中 Set，在页面进入、刷新和会话切换时从服务端重建；当前会话有 PENDING 时锁定发送按钮，但其他会话不受影响。

消息和审批状态查询完成前保持加载锁；加载代次、登录会话和 Agent/会话标识共同阻止旧响应或旧恢复流覆盖新界面。`console-core.buildApprovalDecisionPayload` 负责完整性校验，只生成版本与 item 决定。409/410、网络中断或恢复失败后读取服务端权威详情；决定已被接受不再提交，无法确认状态时保持只读。终态卡片展示决定及时间，但刷新后 PENDING 列表不是历史审批台。

每张审批卡展示 HIGH 标签、发起人、Run、过期时间、工具名、调用标识和脱敏参数摘要。多工具请求必须逐项选择，也提供“全部允许/全部拒绝”；只有全部 item 都选择后才能提交。参数只通过 `textContent` 写入 `<pre>`，不解释 HTML 或 Markdown。样式包含键盘可操作的 radio、文字状态、live region、长文本换行和窄屏单列布局。

## 调用链变化

```text
会话消息 SSE
  -> RunExecutionService
  -> AgentScope ReActAgent + PermissionContext
  -> HIGH 工具 ASK
  -> 加密保存 agent_state + 审批请求
  -> Run WAITING_APPROVAL + approval-required

决定恢复 SSE
  -> agent:run + agent:approve、tenant/资源/版本/过期/全量决定校验
  -> PENDING + version 原子决定
  -> 原 runId、原发起主体加载加密 agent_state
  -> 校验原工具 ID、toolCallId 与输入哈希，ConfirmResult 恢复 ReActAgent
  -> ToolInvocationGateway 再次复核授权与工具状态
  -> 工具/Run 审计、消息落库、删除检查点
```

## 与原设计的差异和保留项

- 配置采用扁平的 `permission-enabled` 与 `approval-ttl`；在线会话 DEFAULT、无会话 Run DONT_ASK 由入口语义固定，没有提前暴露可被误配的模式、检查点宽限或长期策略选项。
- 过期在审批提交时原子标记并收口 Run；PENDING 列表排除已过期项，但当前没有主动定时扫描，无人访问的过期行会延迟转换为 EXPIRED。
- 数据模型保留 requestedBy/decidedBy 分离，但第一版只允许发起人自批；四眼审批和通知属于后续需求。
- 检查点复用现有单主密钥 AES/GCM 组件，未增加独立密钥版本字段；部署轮换主密钥前必须先收口所有 WAITING_APPROVAL Run。
- 当前原子性保证“审批决定最多接受一次”，但决定提交后到 Runtime 恢复完成之间不是一个数据库事务。进程崩溃后重复决定会被拒绝，当前没有自动接管；这不能证明外部工具副作用已经回滚或恰好执行一次，下游仍需幂等键。后台恢复租约/补偿任务属于后续阶段。
- 当前发送拦截针对有效 PENDING 审批，并非跨请求/实例的会话执行互斥锁。决定接受后恢复期间，其他客户端可能提交同一会话消息；跨客户端串行化和崩溃恢复需后续租约设计。
- 检查点保存、Run 等待状态转换、审批创建分属不同阶段。创建/审计失败或进程中断仍可能留下等待 Run，需要运维核查，尚无自动补偿。
- PENDING 查询最多返回 50 条，当前不分页也不提供超限诊断；终态历史通过指定审批 ID 查询。
- 当前前端测试为资源合同、SSE 解析和静态安全断言，没有引入浏览器 DOM 测试框架；完整键盘与焦点流仍建议在端到端测试中覆盖。

## 验证结果

JDK 21 下 Core 75、Adapter 65、Console 12 项测试通过；Server 本次选择的 8 个测试类共 42 项通过；JavaScript 45 项通过且脚本语法检查通过；Server 及依赖模块跳过测试打包成功。Rocky 容器中的持久化套件 51 项通过，包含 PostgreSQL 16/MySQL 8.4 V11 迁移、索引、外键及原生中文注释断言。该结果不替代新 JDBC 审批仓储专门测试或真实浏览器/进程重启验收，详见进度账本。

## 发布和回滚

V11 只新增表和索引，不修改历史迁移。启用功能前先迁移数据库并确保所有服务实例共享同一受控加密主密钥。回滚到不识别 `WAITING_APPROVAL` 的旧版本前，必须先停止新审批并收口所有待审批 Run，否则旧版本无法正确解释运行状态和检查点。
