# 消息一等公民会话模型实现说明

## 关联文档

- [设计说明](../specs/2026-08-31-message-first-conversations-design.md)
- [实施计划](../plans/2026-08-31-message-first-conversations.md)
- [进度账本](../progress/2026-08-31-message-first-conversations-ledger.md)

## 最终实现

### Core 合同

`cm-agent-core` 新增 `Conversation`、`ConversationMessage`、`ConversationMessageDraft`、消息角色、类型化内容块、分页请求、结构化 Runtime 结果与两个 Repository 接口。消息内容块在构造阶段校验角色和字段组合，并使用不可变列表防止写入后变更。

`AgentRunRequest` 新增可空 `conversationId`，原七参数构造器继续可用。`AgentRuntime` 新增带默认实现的 `runStructured`：旧 Runtime 仍以 `AgentRunResult` 工作，兼容层会过滤旧实现可能产生的空增量，并把安全文本和 ToolCall 摘要转换为 assistant 消息快照。原 `AgentRunResult` 未修改。

### 持久化

PostgreSQL 与 MySQL 各新增一个方言 V10，不存在公共 V10。迁移为会话增加 `updated_at`，为消息增加 `sequence_no`、`sender_name`、`content_blocks_json` 与 `run_id`，完成存量回填、中文数据库注释、查询索引、会话内序号唯一约束和 Run 复合外键。

`JdbcConversationMessageRepository` 在短事务内锁定会话行，再计算并写入下一个 sequence；消息追加后同步推进会话更新时间。memory 模式使用单会话锁提供相同顺序语义。所有查询显式携带 tenant，Web 层读取消息前还会验证 tenant + agent + conversation 归属。

### 运行与 AgentScope

`ConversationService` 先在短事务内创建 RUNNING Run 和 USER 消息，再读取此前历史并调用 Runtime；模型调用不占用数据库事务。最终结构化消息存在时追加一条 ASSISTANT 消息，没有最终消息的异常不伪造回复。

历史窗口固定为最近 40 条、最多 60,000 字符，按完整消息边界截断。历史以服务端生成的安全边界包装，工具块只渲染受治理摘要；关联 FAILED Run 的 USER 消息标记为“上一轮执行失败”。

AgentScope Adapter 在会话运行时使用 conversationId 作为 `RuntimeContext.sessionId`，并把最终 `Msg` 的文本块及 ToolUse/ToolResult 顺序转换为 Core 内容块。工具原始参数和原始结果不越过适配器边界，使用内部 ToolCallRecord 的脱敏摘要替换。

### Web、SSE 与控制台

新增 `/api/agents/{agentId}/conversations` 下的创建、列表、详情、消息分页、同步发送和流式发送接口。新接口复用 `agent:read`/`agent:run`，不接受客户端 tenant、role、sequence 或 runId。跨租户或跨 Agent 读取返回 `CONVERSATION_NOT_FOUND`。

会话 SSE 返回 `started`、`message-started`、`delta`、`completed` 或 `error`。`message-started` 在首个带 AgentScope `replyId` 的文本增量到达时发出，随后 delta 保留 `replyId` 与 `blockId`；错误事件按审计、持久化、运行时、参数和内部失败分类，并携带可检索 `errorId`。

v1/v2 控制台运行页新增会话选择、新建会话、历史回放和连续发送。页面只通过安全 DOM/Markdown 渲染脱敏文本和工具摘要，不自动重试写请求。

## 调用链变化

1. 浏览器创建或选择 Conversation。
2. 发送消息后，ConversationService 创建 Run 与 USER 消息并提交。
3. 服务读取此前消息窗口和失败 Run 状态，构造本轮 Runtime 输入。
4. RunExecutionService 复用既有模型、工具治理、审计与失败收口。
5. AgentScope 返回最终 Msg；适配器转换安全内容块。
6. Run/ToolCall 完成持久化，随后追加 ASSISTANT 消息；SSE 返回 completed。

## 验证确认

远程 Rocky Linux 的 `maven:3.9.9-eclipse-temurin-21` 容器已在 PostgreSQL 16.14 与 MySQL 8.4 上完成 V1--V10 Flyway 迁移和持久化测试。验证期间发现并修正 `MigrationTest` 对迁移总数的旧断言（9 改为 10）；复跑的 `MigrationTest` 两项均通过。完整结果见[进度账本](../progress/2026-08-31-message-first-conversations-ledger.md)。

## 与确认方案的差异

- 确认方案把 `message-started` 描述为直接转发 `AgentStartEvent`。最终实现为保持 Core 回调只承载安全文本事件，在首个 `TextBlockDeltaEvent` 到达时用其中的 AgentScope replyId 发出一次 `message-started`。因此没有文本增量的运行只有 `started` 与终态事件，不会伪造消息开始事件。
- 计划提出 JDBC 并发 sequence 专项测试；最终 Repository 通过会话行锁实现并发串行化，现有测试覆盖顺序、分页、类型化块和租户隔离，但并发压力验证留到远程容器验证阶段补充。

其余 API、历史窗口、工具摘要、V10 方言、兼容性和范围排除项与确认方案一致。
