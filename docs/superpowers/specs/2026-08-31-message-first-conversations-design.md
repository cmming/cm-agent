# 消息一等公民会话模型设计

## 复核结论

本设计已按当前仓库实现、Flyway 加载规则和 AgentScope Java 2.0.0 的消息/事件合同重新复核。修订重点如下：

- 消息内容改为有序、类型化内容块，不再只保存单个文本字段。
- 工具调用与工具结果作为 assistant 消息的内容块持久化，不另造与 AgentScope 语义不一致的独立 TOOL 消息。
- 原有 Run API 和 `AgentRunResult` 保持不变；会话运行使用独立响应类型，避免破坏现有调用方。
- 数据库结构与中文注释全部放进 PostgreSQL/MySQL 各自的 V10 方言迁移，避免公共 V10 与方言 V10 发生 Flyway 版本冲突。
- 明确历史窗口、并发追加、失败消息、事件关联和敏感内容边界。

## 背景

当前接口由单个 `input` 文本驱动：服务端创建 Run、调用 AgentScope Runtime，并保存最终文本和 ToolCall。V1 数据库虽然已经创建 `conversations` 与 `messages` 表，但项目没有对应领域对象、Repository、运行编排或 Web API，因而不能可靠地续聊、分页回放或从事件重建完整消息。

AgentScope 官方[消息与事件](https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.html)合同指出：`Msg` 是通信和持久化单元，包含角色、元数据和有序 `ContentBlock`；一次 `call` 的事件序列最终汇聚为一条 assistant `Msg`。本项目需要在不让 Core 依赖 AgentScope 类型的前提下保留这一语义。

## 目标

- 在 Core 中建立租户隔离、可查询、可回放的 Conversation 与 ConversationMessage 领域模型。
- 消息以有序内容块保存文本、工具调用和工具结果的受控快照。
- 支持创建会话、游标分页查询会话和消息、同步续聊及 SSE 续聊。
- 同一会话的新运行使用既有 USER/ASSISTANT 历史作为 AgentScope 输入上下文。
- AgentScope 最终 `Msg` 与安全事件被映射为项目领域消息和 SSE 事件；消息、Run、ToolCall 可通过稳定标识关联。
- 保持现有 `/api/agents/{agentId}/runs`、`/runs/stream`、`AgentRunRequest` 旧构造方式和 `AgentRunResult` JSON 合同兼容。

## 非目标

- 不引入 JPA、Spring Data、MyBatis 或新的数据库框架。
- 不提供消息编辑、消息删除、会话归档、跨 Agent 会话、附件上传、HITL 或会话摘要生成。
- 本次只持久化文本和受控工具摘要；不持久化 ThinkingBlock、原始工具参数、原始工具输出、二进制 DataBlock、模型任意 metadata 或思维链。
- 不改变现有工具治理、严格审计、超时、取消和外部副作用边界。

## 领域模型

### Conversation

`Conversation` 包含：

- `id`、`tenantId`、`agentId`；
- `title`、`createdBy`；
- `createdAt`、`updatedAt`。

会话固定归属一个 Agent。所有读写必须同时使用 tenant、agent 和 conversation 三个条件。创建时标题默认为“新会话”；首条 USER 消息成功追加后，如仍为默认标题，则以规范化后的前 40 个 Unicode code point 更新标题。

### ConversationMessage

`ConversationMessage` 包含：

- `id`、`tenantId`、`conversationId`；
- `sequence`：会话内从 1 开始的连续递增序号；
- `role`：`USER`、`ASSISTANT`、`SYSTEM`、`TOOL`；
- `senderName`：可空的发送方名称；
- `contentBlocks`：不可变、有序的内容块列表；
- `runId`：可空的关联 Run；
- `createdAt`。

本次使用以下受控内容块：

- `TEXT`：USER、ASSISTANT、SYSTEM 可使用，保存脱敏后的文本。
- `TOOL_USE`：ASSISTANT 可使用，保存 `toolCallId`、`toolName` 与脱敏后的输入摘要。
- `TOOL_RESULT`：ASSISTANT 可使用，保存 `toolCallId`、状态、脱敏后的输出或错误摘要。

AgentScope 内部 ReAct 工具循环产生的 ToolUseBlock/ToolResultBlock 属于同一条 assistant 消息，不额外创建 TOOL 角色消息。`TOOL` 角色为以后接收显式 `ToolResultMessage` 保留，本次 Web API 不允许客户端直接写入。

### 运行结果包装

不修改现有 `AgentRunResult` record。新增 `ConversationRunResult`，包含：

- `conversationId`、`userMessageId`、可空的 `assistantMessageId`；
- 原有 `AgentRunResult run`；
- 可空的最终 `ConversationMessage assistantMessage`。

Core 新增不依赖 AgentScope 的 `AgentMessageSnapshot`，供支持结构化消息的 Runtime 返回最终 assistant 内容块；`AgentRuntime` 通过带默认实现的新方法保持旧实现兼容。默认实现可由既有 `AgentRunResult.output/toolCalls` 构造安全快照，并为没有原生调用 ID 的旧 Runtime 生成仅在当前 Run 内稳定的块关联 ID；AgentScope Adapter 覆盖该方法，从最终 `Msg` 与桥接器内部保留的 `toolCallId -> ToolCallRecord` 映射构造结构化快照。原始 ToolUseBlock 输入和 ToolResultBlock 输出不越过适配器安全边界。

`AgentRunRequest` 增加可空 `conversationId`，同时保留原七参数构造器。会话运行把 conversationId 用作 AgentScope `RuntimeContext.sessionId`；传统单轮运行继续使用 runId，保持原语义。

## 运行编排

1. Controller 从认证主体取得 tenant，校验 `agent:run`，不接受客户端传入 tenant。
2. ConversationService 按 tenant + agent + conversation 校验会话，并拒绝跨租户或跨 Agent UUID。
3. 服务端预生成 runId，在一次短事务中创建 RUNNING Run 并追加关联 USER 消息；事务提交失败时不执行模型。
4. 读取当前 USER 消息之前的历史，以最近 40 条、最多 60,000 个字符为窗口。截断按完整消息边界进行，并在渲染上下文时加入服务端生成的历史边界说明。
5. `ConversationPromptComposer` 将允许的历史内容块渲染为带明确角色和工具状态标签的文本，再把当前用户文本作为最后一段。历史文本只作为数据引用，不能覆盖 Agent 的 system prompt。
6. RunExecutionService 使用预生成 runId 执行既有运行流程。AgentScope 会话运行以 conversationId 作为 sessionId，但 Agent 实例仍按 Run 创建；连续语义以持久化历史为权威，不依赖进程内 Agent 状态。
7. 收到最终 AgentScope `Msg` 时，映射 TEXT、受控 TOOL_USE 和 TOOL_RESULT 块，并在一次短事务内追加一条 ASSISTANT 消息。Run 与 ToolCall 仍按既有流程持久化。
8. `SUCCEEDED`、带最终消息的 `DENIED` 等终态都可产生 ASSISTANT 消息；没有最终 `Msg` 的异常或失败不伪造 assistant 消息。

模型调用不能位于数据库长事务中，因此 Conversation、USER 消息、Run 和 ASSISTANT 消息采用可恢复的分段提交。读取历史时只选取已有完整消息；失败 USER 消息仍作为会话事实保留，但其关联失败 Run 会使 Composer 在后续历史中标记为“上一轮执行失败”，避免误认为已有 assistant 回复。

## API 设计

在 `/api/agents/{agentId}/conversations` 下新增：

- `POST`：创建空会话，需 `agent:run`。
- `GET`：按 `updatedAt + id` 复合游标倒序分页，需 `agent:read`。
- `GET /{conversationId}`：读取会话元数据，需 `agent:read`。
- `GET /{conversationId}/messages`：按 `sequence` 正序、使用 `afterSequence` 游标分页，需 `agent:read`。
- `POST /{conversationId}/messages`：追加 USER 消息并同步执行，需 `agent:run`。
- `POST /{conversationId}/messages/stream`：同一操作的 SSE 版本，需 `agent:run`。

消息写入请求仅包含 `input`。消息 ID、runId、sequence、role 与 tenant 均由服务端产生，避免客户端覆盖可信关联。

原有 Run Controller 不增加 conversationId，也不改变响应结构；控制台会话界面改用新 API，现有 Run API 继续作为单轮兼容入口。

### SSE 事件

新会话流式接口发送：

- `started`：USER 消息和 RUNNING Run 提交后发送，包含 `conversationId`、`runId`、`userMessageId`，不伪造尚未由 AgentScope 分配的 replyId。
- `message-started`：收到 AgentStartEvent 后发送，包含 AgentScope 的 `replyId`。
- `delta`：`replyId`、`blockId`、脱敏后的文本 `delta`；只在对应 AgentScope 文本事件产生后发送。
- `completed`：完整 `ConversationRunResult`。
- `error`：稳定 `errorCode`、安全中文原因与可检索 `errorId`，并保留已有会话/消息关联 ID。

不向浏览器发送 ThinkingBlock、原始工具参数或原始工具输出。工具块在 completed 的最终消息中以受控摘要返回。

## 数据库迁移

新增两个互斥的方言迁移，不创建公共 V10：

- `db/migration/postgresql/V10__make_messages_first_class.sql`
- `db/migration/mysql/V10__make_messages_first_class.sql`

两个脚本语义一致，并各自完成结构、存量回填、索引和原生中文注释：

- `conversations` 新增非空 `updated_at`，存量值从 `created_at` 回填；增加 `(tenant_id, agent_id, updated_at, id)` 索引。
- `messages` 保留 V1 的 `role/content` 兼容列，新增 `sequence_no`、`sender_name`、`content_blocks_json`、`run_id`。
- 存量消息按 `created_at, id` 在各 conversation 内回填 sequence，并把原 `content` 转换为单个 TEXT 块 JSON；新代码同时写 `content` 文本投影和 `content_blocks_json`。
- 新增 `(tenant_id, conversation_id, sequence_no)` 唯一索引与 `(tenant_id, run_id)` 查询索引。
- `messages.run_id + tenant_id` 外键指向 `runs(id, tenant_id)`；ConversationService 在同一短事务中先创建 RUNNING Run、再追加 USER 消息，模型调用在事务提交后开始。

JDBC Repository 使用会话行 `SELECT ... FOR UPDATE` 分配 sequence 并更新 `updated_at`；内存实现对单会话锁定。该写法既避免 `MAX(sequence)+1` 并发冲突，也不持有跨模型调用的数据库锁。

## 安全、容量与一致性约束

- 所有 Repository 方法显式带 tenant；ConversationMessage 查询还必须先验证 conversation 的 agent 归属。
- 持久化前对文本、工具输入摘要、输出摘要和错误摘要执行现有脱敏；数据库仍属于敏感业务数据，生产访问与备份须按现有 JDBC 安全边界管理。
- 历史窗口限制为最近 40 条且最多 60,000 字符，防止无限上下文导致模型成本、延迟和内存失控；本次不做自动摘要。
- 消息 sequence 是会话内顺序，时间戳只用于展示，不作为唯一排序依据。
- 同一会话允许并发追加，但每条 USER 消息形成独立 Run；sequence 由 Repository 串行分配，assistant 消息按实际完成顺序追加。控制台默认在当前 Run 完成前禁用再次发送，以减少回答交错。
- HTTP/SSE 客户端主动重试可能产生新 USER 消息和新 Run；本次不承诺幂等重放，控制台不得自动重试写操作。幂等键作为后续独立需求处理。

## 验收标准

- 第二轮 Runtime 请求能观察到第一轮 USER/ASSISTANT 文本及受控工具块历史，且当前输入只出现一次。
- AgentScope 最终消息被映射为一条 ASSISTANT 领域消息；工具调用与工具结果保持块顺序和 toolCallId 关联。
- 会话和消息 API 只返回认证 tenant 且匹配 Agent 的数据；跨租户、跨 Agent 访问均返回 404 并按既有权限规则审计。
- 消息 sequence 唯一、连续、稳定分页；失败且没有最终 Msg 的运行不产生伪造 assistant 消息。
- 新 SSE 在 AgentScope 分配标识后通过 `message-started/delta` 提供 replyId/blockId；前端只收到安全文本和受控工具摘要。
- PostgreSQL 16 与 MySQL 8.4 的 V10 均可迁移，新增字段和所有表/字段注释非空，索引与外键满足设计。
- 该验收以[进度账本](../progress/2026-08-31-message-first-conversations-ledger.md)记录的远程临时代码副本验证为准；验证不依赖先行提交或推送 Git。
- 原 Run 同步/流式接口、`AgentRunResult` JSON 和旧 Runtime 实现继续通过现有测试。

## 关联文档

实施顺序见[实施计划](../plans/2026-08-31-message-first-conversations.md)，实际落地与方案差异见[实现说明](../implementation/2026-08-31-message-first-conversations-implementation-design.md)，验证和提交状态见[进度账本](../progress/2026-08-31-message-first-conversations-ledger.md)。
