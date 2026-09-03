# AgentScope Permission System 接入设计

## 关联文档

- [实施计划](../plans/2026-09-02-agentscope-permission-system-integration.md)
- [实现说明](../implementation/2026-09-02-agentscope-permission-system-integration-implementation-design.md)
- [进度账本](../progress/2026-09-02-agentscope-permission-system-integration-ledger.md)

## 背景

本主题已从需求调研进入实现阶段，2026-09-03 已完成第一版前后端代码纵切。下文保留产品目标与验收要求；实际支持范围、验证证据及未完成项分别以[实现说明](../implementation/2026-09-02-agentscope-permission-system-integration-implementation-design.md)和[进度账本](../progress/2026-09-02-agentscope-permission-system-integration-ledger.md)为准，不将设计目标等同于已验收能力。

CM Agent 已具备 API RBAC、租户隔离、Agent 与工具授权、工具执行前复核和安全审计，但现有链路只能回答“当前主体和 Agent 是否有权使用该工具”，不能表达“这一次包含具体参数的高风险调用是否需要用户确认”。

AgentScope Java 2.0.2 的 Permission System 位于 ReAct 工具执行路径上，通过规则、运行模式和工具自身检查生成 `ALLOW`、`DENY` 或 `ASK` 决策；`ASK` 会以 `GenerateReason.PERMISSION_ASKING` 暂停 Agent，并在后续调用收到 `ConfirmResult` 后继续执行。官方资料：

- <https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.html>
- <https://github.com/agentscope-ai/agentscope-java/blob/v2.0.2/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java>

该能力应作为 CM Agent 现有授权体系之后、实际工具执行之前的“运行时审批层”，不能替代 `PermissionEvaluator`、`ToolAuthorizationPolicy`、`ToolInvocationGateway` 或审计边界。

## 目标

- 使 AgentScope 能对 CM Agent 暴露的每个工具调用执行真实权限判定，避免现有桥接工具绕过 Permission Engine。
- 为高风险工具提供按具体调用确认的 `ASK` 流程，并支持跨请求、重启和多实例恢复。
- 保持现有租户、Agent 工具授权和执行前复核不变；审批通过不等于获得业务授权。
- 为在线交互和无人值守运行提供不同的安全默认行为。
- 保证待审批工具输入、AgentState、错误和审计记录符合现有脱敏、加密和关联编号规范。

## 非目标

- 第一阶段不提供长期“记住允许”、项目级永久规则或用户自助规则管理页面。
- 第一阶段不允许审批人修改模型生成的工具参数。
- 第一阶段不根据 `ToolRiskLevel` 推断工具是否只读，也不开放生产环境 `BYPASS`、`EXPLORE` 或 `ACCEPT_EDITS`。
- 不用 AgentScope Permission 替换现有 RBAC、ToolGrant、租户过滤、工具状态检查和安全审计。
- 第一版不提供进程崩溃后的自动接管、审批恢复租约或主动过期扫描。

## 现状与差距

本节描述接入前的代码基线，所列工具桥接和运行状态差距已由本次实现补齐。

### 工具桥接未进入 Permission Engine

`AgentScopeToolBridge` 当前直接实现 `AgentTool`。AgentScope 2.0.2 在权限评估时仅对 `ToolBase` 实例调用 `PermissionEngine`，普通 `AgentTool` 会直接按 `ALLOW` 处理。因此，仅在 `ReActAgent.builder()` 增加 `permissionContext(...)` 不会保护现有 CM Agent 工具。

### 运行结果不能表达等待审批

现有 `RunStatus` 只有 `RUNNING`、`SUCCEEDED`、`FAILED` 和 `DENIED`，`AgentScopeExecutionResult` 只允许终态。当前结果映射没有识别 `GenerateReason.PERMISSION_ASKING`，如果直接开启 ASK，暂停消息可能被误判为成功。

### 当前会话模型不能恢复原生待调用状态

每次运行都会创建新的 `Model`、`Toolkit`、`RuntimeContext` 和 `ReActAgent`，结束后关闭 Agent。会话历史由 `ConversationPromptComposer` 渲染为受控文本重新提交，而不是恢复 AgentScope 原生 `AgentState`。ASK 所需的 `ToolUseBlock`、确认关联标识和待执行上下文必须使用独立运行检查点保存。

### 风险等级不等于副作用分类

现有 `ToolRiskLevel.LOW/MEDIUM/HIGH` 主要服务授权、审计和调试确认，无法可靠判断工具是否只读。低风险工具仍可能写数据，因此初期所有桥接工具按 `readOnly=false` 注册，不能依赖 `EXPLORE` 或 `ACCEPT_EDITS` 自动放行。

## 权限分层

一次工具执行必须依次通过以下边界：

1. Web/API RBAC 校验主体是否拥有 `agent:run`。
2. 运行准备阶段筛选当前租户、当前 Agent 已获授权且启用的工具。
3. AgentScope Permission System 根据本次运行模式、规则和具体工具输入给出 `ALLOW`、`DENY` 或 `ASK`。
4. ASK 经授权审批人确认后恢复 Agent；确认只针对本次具体调用。
5. `ToolInvocationGateway` 在真正执行前重新读取工具和 ToolGrant，复核租户、工具名称、启用状态和 Agent 授权。
6. 工具执行服务落实端点、输入、出站访问和输出治理，并记录技术日志与安全审计。

任何后层放行都不能覆盖前层拒绝。审批期间若 ToolGrant 被撤销、工具被禁用或主体失去权限，恢复后必须拒绝执行。

## 产品决策

### 决策一：HIGH 工具按每次具体调用确认

第一版将审批与 `tenantId`、`agentId`、`runId`、`toolCallId`、`toolId`、策略版本和规范化输入哈希绑定。批准 `refund_order(orderId=O-1001, amount=5000)` 只允许执行该组参数；模型生成新的订单、金额或调用标识时必须重新审批。

第一版默认映射：

| 工具风险 | 默认审批策略 | 行为 |
| --- | --- | --- |
| `LOW` | `NEVER` | AgentScope 显式允许，但仍经过现有治理网关 |
| `MEDIUM` | `NEVER` | 为保持兼容暂不询问，后续可由管理员单独配置 |
| `HIGH` | `EACH_CALL` | 每个具体工具调用均进入 ASK |
| 明确禁止 | `ALWAYS_DENY` | 不允许通过用户确认覆盖 |

领域模型应将风险等级与审批策略分离，预留 `NEVER`、`EACH_CALL`、`ONCE_PER_RUN` 和 `ALWAYS_DENY`，但第一版不开放 `ONCE_PER_RUN` 配置。不能直接接受 AgentScope 默认建议的工具名级永久 ALLOW 规则，避免一次确认扩大为同名工具的长期授权。

### 决策二：允许发起人自批，但审批是独立权限

第一版允许运行发起人处理自己的待审批请求，但必须同时拥有 `agent:run` 和新增的 `agent:approve`。审批记录分别保存：

- `requestedBy`：发起 Agent Run 的主体；
- `decidedBy`：批准或拒绝的主体；
- `executionPrincipal`：工具实际执行时使用的原发起主体。

审批人的权限不能借给发起人。即使未来支持其他人审批，工具仍以原发起主体执行，并在执行前重新校验原发起主体和 Agent 的工具授权。数据模型和 API 不应假设 `requestedBy` 必须等于 `decidedBy`，为独立审批人、财务审批角色和四眼原则预留空间。

### 决策三：无人值守运行对 ASK 默认拒绝

计划任务、服务账号和其他无人值守入口使用 `DONT_ASK` 语义：显式 ALLOW 规则可执行，所有 ASK 转为 DENY。第一版不让后台线程、数据库事务或 SSE 连接等待人工操作。

在线交互运行使用 `DEFAULT` 模式并返回待审批状态。后续若建设异步审批，无人值守 Run 可进入 `WAITING_APPROVAL`、保存加密检查点、发送通知并在审批后由任意服务实例恢复；这属于后续独立能力，不改变第一版 fail-closed 默认值。

## 分阶段方案

### 阶段 A：权限引擎基础接入

- 将 `AgentScopeToolBridge` 改为继承 `ToolBase`，保留现有调用治理、记录和取消语义。
- 为每次 Run 从可信 `ToolDefinition` 构造 `PermissionContextState`。
- 使用 `DEFAULT` 模式，并为现有获授权 LOW/MEDIUM 工具生成显式 ALLOW 规则；完整功能开关在阶段 B 完成前保持关闭，若提供仅供测试的引擎模式则 HIGH 工具必须显式拒绝，不能放行或产生无 UI 的悬挂状态。
- 所有桥接工具暂设 `readOnly=false`，不启用基于文件编辑语义的模式。
- 生产环境拒绝 `BYPASS`；无人值守入口固定为 `DONT_ASK`。

### 阶段 B：在线 ASK 与恢复

- 监听 `RequireUserConfirmEvent`，提取待审批工具列表和确认关联标识。
- 检查最终 `Msg.getGenerateReason()`，将 `PERMISSION_ASKING` 映射为等待审批结果，而不是成功终态。
- 使用 `runId` 作为 AgentScope `sessionId`；同一个 Run 的首次执行和审批恢复使用相同状态槽，Run 终态后删除检查点。
- 通过持久化 `AgentStateStore` 或受控适配器保存加密检查点，避免把 CM Agent 长期会话历史和 AgentScope 运行状态混用。
- 审批恢复时由服务端加载原始待调用块并创建 `ConfirmResult`；客户端只提交审批请求标识和 `APPROVE`/`DENY`。

### 阶段 C：异步审批与策略治理

- 支持无人值守 Run 暂停、通知、过期、恢复和多实例接管。
- 增加管理员预授权规则、规则作用域、参数约束、有效期、撤销和策略版本失效。
- 按业务域扩展审批权限，例如 `agent:approve:self`、`tool:approve:production`，但不能以这些权限替代工具本身的执行授权。

## 领域模型建议

### Run 状态

新增非终态 `WAITING_APPROVAL`。`RunRecord` 对完成时间的约束需调整为：`RUNNING` 和 `WAITING_APPROVAL` 不得有 `finishedAt`，其余终态必须有 `finishedAt`。等待审批期间不能写入虚假的 assistant 完成消息。

### 审批请求

建议新增 `ToolApprovalRequest`：

- `id`、`tenantId`、`agentId`、`conversationId`、`runId`；
- `requestedBy`、`status`、`expiresAt`、`version`；
- `checkpointRef` 或加密状态载荷引用；
- `createdAt`、`updatedAt`、`decidedBy`、`decidedAt`。

建议新增 `ToolApprovalItem` 支持同一 `RequireUserConfirmEvent` 包含多个工具调用：

- `approvalRequestId`、`toolCallId`、`toolId`、工具名称快照；
- 已脱敏的输入展示摘要；
- 原始输入规范化哈希；
- 审批策略和策略版本；
- 单项决定及原因。

状态更新必须使用版本或条件更新保证请求只从 `PENDING` 进入 `APPROVED`（全部允许）、`DENIED`（全部拒绝）、`PARTIALLY_APPROVED`（允许与拒绝并存）、`EXPIRED` 或 `CANCELLED` 之一。第一版对任何非 PENDING 的重复提交统一返回 409，并要求客户端读取权威状态；服务端不能再次创建恢复任务或重复执行工具。

### 运行检查点

AgentState 可能包含原始模型消息和工具输入，必须采用独立受控存储：

- 存储键来自可信的 tenant、Agent、Run 和主体上下文；
- 载荷加密，不进入普通消息表、日志、审计详情或前端响应；
- 设置审批过期时间和清理策略；
- Run 成功、失败、拒绝、取消或过期后删除；
- 多实例读取必须校验 tenant、Agent、Run 和审批请求的一致性。

## 前后端接口与交互合同

2026-09-03 的前端文案、卡片层级、选择汇总、内存草稿与失败恢复交互，以[人工确认 UI 优化设计](2026-09-03-tool-approval-ui-ux-design.md)为增量合同。下文接口与安全边界保持不变，旧“全部允许/拒绝”文案现为“全部选为允许/拒绝”，仍须显式提交。

本节是第一版代码生成的固定合同，不是界面方向建议。后端 DTO、REST/SSE 字段、前端状态机和测试均以此为准；若实现阶段需要变更字段或时序，必须先同步设计说明和实施计划。

### 对外审批视图

后端向前端返回 `ToolApprovalView`，字段固定为：

| 字段 | 类型 | 约束与用途 |
| --- | --- | --- |
| `approvalId` | UUID 字符串 | 审批请求公开标识 |
| `agentId` | UUID 字符串 | 用于前端路由关联，不作为服务端租户判断来源 |
| `conversationId` | UUID 字符串 | 当前会话标识 |
| `runId` | UUID 字符串 | 当前暂停 Run 标识 |
| `status` | 字符串 | `PENDING`、`APPROVED`、`DENIED`、`PARTIALLY_APPROVED`、`EXPIRED` 或 `CANCELLED` |
| `version` | 非负整数 | 审批提交的乐观锁版本 |
| `requestedByDisplayName` | 字符串 | 已脱敏的发起人显示名称，不返回权限集合或令牌信息 |
| `decidedByDisplayName` | 可空字符串 | 终态审批人显示名称；PENDING 时为 `null` |
| `decidedAt` | 可空 ISO-8601 时间 | 决定生效时间；PENDING 时为 `null` |
| `canDecide` | boolean | 服务端结合发起人身份、`agent:run`、`agent:approve`、状态及有效期计算，前端只用于展示 |
| `expiresAt` | ISO-8601 时间 | 服务端权威过期时间 |
| `items` | 数组 | 本轮所有待确认工具调用，不能为空 |

`items` 中每个 `ToolApprovalItemView` 固定包含：

| 字段 | 类型 | 约束与用途 |
| --- | --- | --- |
| `itemId` | UUID 字符串 | 审批明细公开标识 |
| `toolCallId` | 字符串 | 与执行轨迹关联的 AgentScope 调用标识 |
| `toolId` | UUID 字符串 | CM Agent 工具标识 |
| `toolName` | 字符串 | 展示用工具名称快照 |
| `riskLevel` | 字符串 | 第一版 ASK 项固定为 `HIGH` |
| `inputSummary` | 字符串 | 已脱敏、限长、只按纯文本展示的参数摘要 |
| `decision` | 可空字符串 | 未决定为 `null`，终态为 `APPROVE` 或 `DENY` |

响应不得包含 tenant、原始 `ToolUseBlock`、原始输入 Map、输入哈希、检查点引用、AgentState、凭据或内部异常。前端不得根据 `canDecide=true` 假设服务端必然允许提交，最终权限仍以后端决定接口为准。

示例：

```json
{
  "approvalId": "8d21ab94-7747-4502-a3b8-3a797718fef1",
  "agentId": "73076ff1-2195-41bb-a671-73f44493db49",
  "conversationId": "3f35a454-793e-437e-941f-e1baa9e80c32",
  "runId": "ab10d5db-72dd-437b-b690-43fd5ac5eaed",
  "status": "PENDING",
  "version": 0,
  "requestedByDisplayName": "张三",
  "decidedByDisplayName": null,
  "decidedAt": null,
  "canDecide": true,
  "expiresAt": "2026-09-02T10:30:00Z",
  "items": [
    {
      "itemId": "0e1d1bc1-3160-4118-a3cb-7759284be2af",
      "toolCallId": "call_refund_001",
      "toolId": "4b025bb0-b19d-45c4-8fa6-61ac1337c56b",
      "toolName": "refund_order",
      "riskLevel": "HIGH",
      "inputSummary": "{\"orderId\":\"O-1001\",\"amount\":5000}",
      "decision": null
    }
  ]
}
```

### 在线消息 SSE

现有消息流接口保持：

```text
POST /api/agents/{agentId}/conversations/{conversationId}/messages/stream
```

当运行遇到 ASK 时发送一次 `approval-required`，其 `data` 为完整 `ToolApprovalView`。事件时序固定为：

```text
started
message-started? / delta? / progress*
approval-required
连接正常结束
```

`approval-required` 后不得再发送本轮 `completed`，也不得把等待审批当作 `error`。服务端保存审批请求和加密检查点后才能发送事件；发送后结束当前 SSE，不保留工作线程、模型调用、数据库事务或响应连接等待用户。

### 待审批查询

页面首次进入、刷新或切换会话时调用：

```text
GET /api/agents/{agentId}/conversations/{conversationId}/approvals?status=PENDING
```

响应固定为：

```json
{
  "items": []
}
```

`items` 为 `ToolApprovalView` 数组，按创建时间正序返回。第一版仅查询当前会话未过期 PENDING 请求，不做分页，最多返回 50 条；超限诊断与分页尚未实现，不能把该接口当作完整历史审批列表。

冲突、恢复错误或倒计时归零后查询单个服务端权威状态：

```text
GET /api/agents/{agentId}/conversations/{conversationId}/approvals/{approvalId}
```

响应为一个 `ToolApprovalView`，可返回终态及每个 item 的决定。跨租户、跨 Agent、跨会话和不存在仍统一返回 404。

### 审批提交与恢复 SSE

聊天页统一使用流式决定接口：

```text
POST /api/agents/{agentId}/conversations/{conversationId}/approvals/{approvalId}/decision/stream
```

一个 AgentScope ASK 事件可能包含多个并行工具调用。第一版要求用户对每个 item 分别选择决定，并一次性原子提交全部明细：

```json
{
  "expectedVersion": 0,
  "decisions": [
    {
      "itemId": "0e1d1bc1-3160-4118-a3cb-7759284be2af",
      "decision": "APPROVE"
    }
  ]
}
```

请求不得包含工具名称、参数、输入哈希、tenant、发起人、审批人或执行主体。`decisions` 必须覆盖当前审批请求的全部 PENDING item，item 不得重复；缺失、多余、跨审批请求或非法枚举均返回 400。`expectedVersion` 不匹配、请求已被其他人处理或存在竞争决定时返回 409；已过期返回 410；跨租户、跨 Agent、跨会话和不存在统一返回 404；缺少 `agent:approve` 或 `agent:run` 返回 403。上述校验在打开 SSE 前执行，原子决定时还会复核；并发竞争可能在 SSE 建立后发生，此时通过 `error` 事件返回状态对应的业务码。

决定被原子接受后，SSE 先发送：

```text
approval-decision
```

其数据固定包含 `approvalId`、最新 `status`、最新 `version` 和 `decidedAt`。随后恢复原 Run，并复用现有事件：

```text
approval-decision
progress*
message-started? / delta?
completed | error
```

全部 item 均被拒绝时，不再进行无意义模型推理，Run 以 `DENIED` 收口并发送 `completed`；部分批准时，服务端使用每项决定构造完整 `ConfirmResult` 列表，执行获批工具，拒绝项作为受控拒绝结果供 Agent 继续推理。恢复阶段发生基础设施异常时发送现有安全 `error` 事件。为兼容现有控制台，HTTP 和 SSE 错误码字段均为 `code`，同时包含 `errorId` 与可行动中文 `message`；本文其余位置的 `errorCode` 表示错误码语义，不是新增协议字段。

### 前端页面范围

第一版只修改现有 `/console/v2/chat.html`，不新增前端框架、构建工具或独立审批中心。需要修改：

- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`：增加审批状态说明和无障碍 live region；
- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`：增加审批状态、查询、卡片渲染、决定提交和恢复 SSE 处理；
- `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`：增加审批卡片、风险标识、决定控件和响应式样式；
- `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`：原则上复用现有 `request` 与 `stream`；只有通用 SSE 错误合同确有缺口时才修改。

`chat.html` 在消息列表之后、消息输入表单之前增加固定容器，后续代码生成使用以下 ID，避免 HTML 与 JavaScript 各自猜测选择器：

```html
<section id="chatApprovalRegion" class="chat-approval-region" hidden aria-labelledby="chatApprovalHeading">
    <h3 id="chatApprovalHeading">待审批操作</h3>
    <div id="chatApprovalList" class="chat-approval-list"></div>
    <p id="chatApprovalStatus" class="chat-approval-status" role="status" aria-live="polite"></p>
</section>
```

动态卡片统一使用 `.chat-approval-card`、`.chat-approval-item`、`.chat-approval-risk`、`.chat-approval-decisions` 和 `.chat-approval-actions`；错误提示使用卡片内的状态节点，不单独约定 `.chat-approval-error`。用 `data-approval-id`、`data-item-id` 和 `data-status` 关联服务端状态。动态元素 ID 必须由 approvalId/itemId 的安全规范化值生成，不把工具名称或模型输入拼入 ID、class 或选择器。

前端状态建议固定为：

```javascript
state.pendingApprovals = new Map();    // 保存当前会话审批视图及交互状态
state.approvalSubmissions = new Set(); // 正在提交或恢复的 approvalId
```

`app.js` 至少拆分以下职责，函数名称可以按既有风格微调，但职责不能合并为不可测试的大函数：

- `loadPendingApprovals(agentId, conversationId)`：页面刷新和会话切换时加载服务端状态；
- `handleApprovalRequired(view, streamMessage)`：消费 SSE 并写入本地 Map；
- `renderApprovalCard(view)`：渲染请求级卡片和 item 级决定控件；
- `submitApprovalDecisions(view, decisions)`：校验全量决定并调用恢复流；
- `handleApprovalStreamEvent(event)`：处理 `approval-decision`、`progress`、`delta`、`completed` 和 `error`；
- `setConversationApprovalLock(conversationId)`：根据 PENDING 请求控制当前会话发送能力。

### 审批卡片结构与文案

每个 `ToolApprovalView` 渲染为一张请求级卡片，按以下顺序展示：

1. 标题“需要确认高风险操作”和 HIGH 风险标识；
2. 发起人、Run 简短标识和服务器过期时间；
3. 每个工具 item 的名称、调用标识和“调用参数（已脱敏）”纯文本区域；
4. 每个 item 的“允许”和“拒绝”二选一控件；
5. “全部允许”“全部拒绝”快捷操作；
6. 主按钮“提交决定”；
7. 状态或错误提示区域。

输入摘要必须使用 `textContent` 或现有 `element(..., {text})` 渲染到 `<pre>`，禁止当作 Markdown 或 HTML。风险、状态和按钮不能只依赖颜色表达；HIGH 标签、审批状态和错误提示必须有文字。

当 `canDecide=false` 时，卡片保持只读，隐藏或禁用决定控件，展示“当前账号没有审批权限，请联系审批人”。当请求进入终态后，控件不可再次操作，并显示决定和决定时间。

### 前端状态机

单个审批请求在浏览器中的状态转换为：

```text
LOADING
  -> PENDING_READONLY | PENDING_DECIDABLE
PENDING_DECIDABLE
  -> SUBMITTING
  -> RESUMING
  -> COMPLETED
  -> CONFLICT | EXPIRED | ERROR
```

- `SUBMITTING`：决定请求尚未得到 `approval-decision`，禁用所有控件；
- `RESUMING`：服务端已接受决定，显示“正在恢复运行”，继续渲染原有 progress、delta 和工具轨迹；
- `CONFLICT`：收到 409，重新查询服务端状态并展示“审批已被处理”；
- `EXPIRED`：收到 410 或服务端查询返回 EXPIRED，展示“审批已过期”，不允许重新提交；
- `ERROR`：不自动重复提交；先查询权威状态，仅确认仍为可决定 PENDING 后允许用户重新操作。查询失败保持只读；已收到 `approval-decision` 则不得重新提交。

当前会话存在 PENDING 审批时，前后端都拒绝继续发送新的用户消息，前端禁用发送按钮并显示“请先处理待审批操作”；切换其他会话不受影响。后端必须返回 409 作为最终保障，不能只依赖前端禁用。

### 刷新、切换与倒计时

- 加载会话消息后立即查询 PENDING 审批，再渲染输入区域状态，避免短暂开放发送按钮。
- 收到 `approval-required` 时写入 Map 并渲染；不能等历史消息落库后才显示。
- 切换 Agent 或会话时清除不属于新上下文的本地审批状态，并以 GET 结果重建。
- 倒计时只用于提示，过期判断始终以服务端为准；浏览器倒计时归零后重新查询状态，不在本地擅自写 EXPIRED。
- 网络重连或页面刷新不能重复创建审批请求；审批请求由 runId 和服务端幂等约束保证唯一。

### 可访问性与响应式要求

- 审批区域使用语义化 `section`/`fieldset`/`legend`，item 选择可通过键盘操作。
- 提交后将焦点移动到状态提示；错误提示使用 `role="alert"`，运行恢复状态使用 `aria-live="polite"`。
- 所有按钮提供明确中文文本和 disabled 状态，不使用只有图标的决定按钮。
- 桌面端 item 参数与决定区域可双列展示；窄屏下改为单列，按钮保持至少可读可点，不产生横向页面滚动。
- 长工具名、调用标识和参数摘要允许换行；参数区域内部可纵向滚动，不能撑破聊天布局。

### 前端错误处理

| 场景 | 前端行为 |
| --- | --- |
| 400 决定不完整 | 保留用户选择，显示服务端脱敏原因，不自动重试 |
| 401 登录失效 | 复用现有统一退出逻辑 |
| 403 无审批权限 | 切换为只读卡片并刷新审批列表 |
| 404 资源不可见 | 移除本地卡片并提示审批不存在或不可访问 |
| 409 已被处理/版本冲突 | 重新 GET，展示服务端终态，不重复提交 |
| 410 已过期 | 标记过期并锁定控件 |
| SSE 恢复阶段 `error` | 显示 `errorCode`、中文消息和 `errorId`，再查询 Run 与审批状态 |
| 浏览器网络中断 | 不自动重复决定请求；先重新查询审批版本和状态 |

### 前端测试要求

- `console-core.test.cjs` 覆盖 `approval-required`、`approval-decision` 以及恢复阶段多事件分片解析。
- console Java 资源测试确认 chat 页面包含审批容器、无障碍区域和新接口路径。
- JavaScript 测试至少覆盖：单 item、多 item、全部允许、混合决定、只读审批、重复点击、409、410、刷新恢复、会话切换、网络中断后重新查询。
- DOM 测试确认参数只用纯文本渲染，包含 `<script>`、Markdown 链接或超长内容时不会执行或破坏页面。
- 响应式样式测试或静态断言覆盖审批卡片窄屏单列和长文本换行。

## 实际配置合同

- `cm-agent.agentscope.permission-enabled`：功能开关，默认 `false`；未开启时保持原有已治理工具可执行的兼容行为。
- `cm-agent.agentscope.approval-ttl`：审批和检查点有效期，默认 `15m`，必须大于零且不超过 `24h`。
- 在线会话固定 `DEFAULT`，无会话 Run 固定 `DONT_ASK`；HIGH 固定逐调用审批。模式和策略不开放外部覆盖，不暴露 BYPASS、EXPLORE、ACCEPT_EDITS 或未实现的清理宽限配置。

以上属性由 `AgentScopeRuntimeProperties` 承载并校验；不新增嵌套的 `permission.*` 配置。生产继续遵守现有 profile 安全校验；开启前完成 V11 迁移并配置共享受控 AES 主密钥，详见 `docs/configuration.md`。

## 审计、日志和错误处理

- 创建审批、批准、拒绝、过期、恢复失败和最终工具执行分别记录审计事件。
- 审计至少包含 tenant、Agent、Run、approvalId、toolCallId、requestedBy、decidedBy、决定和结果，不包含原始输入或 AgentState。
- 恢复失败建立稳定 `errorId`，前端返回脱敏中文原因、稳定 `errorCode` 和同一 `errorId`；后台日志保留受控堆栈和可信上下文。
- 普通拒绝使用 WARN 或审计，不滥用 ERROR；检查点损坏、解密失败、状态不一致和重复执行风险使用 ERROR。
- 审批页面和 API 不返回 JWT、Secret、完整内部 URL、异常堆栈或数据库细节。

## 数据库与迁移约束

进入实现阶段后应新增 Flyway 版本，不能修改既有迁移。PostgreSQL 16 和 MySQL 8.4 的表、字段、索引和中文数据库原生注释必须语义一致；复杂方言差异使用相同版本号的方言迁移。

建议索引覆盖：

- tenant + approval status + expiresAt，用于待办和过期扫描；
- tenant + runId，保证 Run 与审批关联查询；
- tenant + agent + conversation，用于会话恢复；
- tenant + approvalRequestId + toolCallId，保证审批明细唯一性。

## 安全不变量

- 客户端不能指定 tenant、requestedBy、decidedBy、executionPrincipal 或原始 ToolUseBlock。
- 审批决定不能改变工具参数；输入哈希不一致必须重新审批。
- 审批还必须绑定原始 `toolId`；即使名称相同，新建替换工具也不能继承旧工具批准。
- 审批不能覆盖现有 ToolGrant、主体权限、工具启用状态和租户边界。
- AgentScope 建议规则默认不持久化，也不自动扩大为工具名级长期 ALLOW。
- HIGH 工具在审批过期、检查点缺失、状态冲突或恢复失败时一律不执行。
- 无人值守运行未命中显式 ALLOW 时 fail-closed。

## 验收标准

- 所有 CM Agent 桥接工具均实际进入 AgentScope Permission Engine；存在防止普通 `AgentTool` 绕过的回归测试。
- LOW/MEDIUM 工具在兼容策略下正常执行，HIGH 工具在执行前产生 ASK 且不会触发治理网关。
- ASK 被映射为 `WAITING_APPROVAL`，不会误报成功、失败或完成消息。
- 批准后使用原工具调用和原发起主体恢复；拒绝、过期和冲突均不会执行工具。
- 审批等待期间撤销 ToolGrant 或禁用工具，恢复后由治理网关拒绝。
- 重启或切换服务实例后可从加密检查点恢复；终态后检查点被清理。
- 页面只展示脱敏摘要，前端提交中不包含原始工具参数或 AgentState。
- 单 item 和多 item 审批均按固定 DTO、REST 与 SSE 合同工作；每个 item 必须有明确决定，不能用请求级模糊批准替代具体调用确认。
- 聊天页可从 `approval-required` 实时展示卡片，也可在刷新和切换后通过 PENDING 查询恢复；同一会话待审批期间前后端均阻止新消息。
- 前端覆盖可决定、只读、提交中、恢复中、冲突、过期和恢复错误状态，参数始终按纯文本安全渲染并满足键盘与窄屏使用要求。
- 重复批准、批准与拒绝竞争以及审批超时均有测试，原子决定最多接受一次；外部工具副作用的恰好一次语义仍需工具自身的幂等机制，不能由审批数据库事务保证。
- PostgreSQL 16 与 MySQL 8.4 的迁移、Repository、中文数据库注释和租户隔离通过远程容器验证。
- 配置、部署、运维、安全、API 和发布说明在实现交付时同步更新。

### 第一版验收边界

已验证 Core/Adapter/Console 回归、审批 Service/Web 目标测试、前端纯函数与 SSE 合同，以及 Rocky 上 PostgreSQL/MySQL 迁移与现有持久化套件。尚未完成新审批 JDBC Repository 的专门并发/回滚集成测试、真实进程重启与多实例恢复演练、浏览器 DOM/键盘端到端验收；这些仍是上线前验收项，不能用编译通过或静态资源断言替代。准确命令、数量和环境见进度账本。
