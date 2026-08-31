# 消息一等公民会话模型实施计划

> 本计划以复核后的[设计说明](../specs/2026-08-31-message-first-conversations-design.md)为准；用户确认前不得开始业务代码编写。

用户已确认全部技术边界；任务 1 至任务 6 均已完成，远程验证使用已核验摘要的未提交工作区临时副本。

## 任务 1：领域合同与兼容层

- 在 `cm-agent-core` 新增 Conversation、ConversationMessage、MessageRole、MessageContentBlock、MessageContentType、AgentMessageSnapshot 及分页请求对象。
- 新增 ConversationRepository 与 ConversationMessageRepository，明确 tenant 边界、复合游标和 sequence 不变量。
- 为 AgentRunRequest 增加可空 conversationId，并保留原七参数构造器。
- 为 AgentRuntime 增加带默认实现的结构化消息执行方法；不修改 AgentRunResult record。
- 增加领域不变量、内容块角色约束和旧 Runtime 兼容测试。

## 任务 2：数据库与 Repository

- 分别新增 PostgreSQL/MySQL 方言 V10；同一数据库只加载其中一个脚本，不创建公共 V10。
- 扩展 conversations/messages，完成存量数据回填、中文原生注释、唯一索引和 Run 外键。
- 实现 JdbcConversationRepository 与 JdbcConversationMessageRepository；会话行锁内完成 sequence 分配与 updatedAt 更新。
- 扩展 InMemoryPlatformStore 和两种 Repository 配置，保持内存实现仅用于本地与测试。
- 更新 MigrationTest，并为 JDBC Repository 增加租户隔离、跨 Agent 拒绝、并发 sequence 与分页测试。

## 任务 3：AgentScope 结构化消息映射

- 扩展 AgentScopeExecutionResult，保留最终 Msg 的安全领域快照。
- 从 AgentResultEvent 的最终 Msg 映射 TEXT 内容；AgentScopeToolBridge 额外保留内部 `toolCallId -> ToolCallRecord` 映射，用安全摘要替换原始 TOOL_USE/TOOL_RESULT 内容并保持块顺序。
- conversationId 存在时用其作为 RuntimeContext.sessionId；传统单轮运行仍使用 runId。
- 扩展安全流式事件回调，向上层传递 replyId、blockId 与文本 delta，不暴露 ThinkingBlock 或原始工具内容。
- 更新 Adapter 合同测试，覆盖最终消息、工具块、事件关联和旧文本结果一致性。

## 任务 4：会话运行编排

- 新增 ConversationPromptComposer，按最近 40 条/60,000 字符窗口渲染历史，覆盖截断、失败 Run 标记和提示注入边界测试。
- 新增 ConversationService，编排会话校验、RUNNING Run 与 USER 消息短事务、Runtime 调用、Run 终态以及 ASSISTANT 消息追加。
- 复用 RunPersistenceService 的现有审计和失败收口；增加接收预生成 runId 的兼容重载。
- 对无最终 Msg 的失败不创建 assistant 消息；对带最终 Msg 的终态保存安全内容块。

## 任务 5：REST、SSE 与控制台

- 新增 ConversationController：会话创建/详情/分页、消息分页、同步发送与流式发送。
- 定义 ConversationRunResult 和会话 SSE started/message-started/delta/completed/error DTO；replyId 只在 AgentStartEvent 到达后发送，保持 RunController 与原 SSE 合同不变。
- 控制台运行页增加会话列表、新建会话、历史加载、连续发送、工具块摘要与失败状态；发送期间禁用重复提交且不自动重试。
- 增加 MockMvc 测试，覆盖认证、权限、租户/Agent 隔离、校验、错误码/errorId 和脱敏。

## 任务 6：生产文档、验证与交付记录

- 更新 README、docs/roadmap.md、docs/configuration.md 和 docs/release-notes.md，说明会话能力、历史窗口、数据敏感性与兼容接口。
- 补充本主题 implementation 与 progress 文档，并记录与本设计的实际差异。
- 本地执行 JDK/Maven 环境检查、core/adapter/server 非容器测试和跳过测试打包。
- 在 `ssh rocky` 的 `maven:3.9.9-eclipse-temurin-21` 容器环境执行 PostgreSQL 16/MySQL 8.4 Testcontainers、Flyway 与 JDBC 测试；未提交时同步当前 `HEAD` 加工作区改动构造临时副本，并以 SHA-256 核验传输完整性。

## 主要影响文件

- `cm-agent-core/src/main/java/com/cmagent/core/domain/*`
- `cm-agent-core/src/main/java/com/cmagent/core/repository/*`
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/AgentRuntime.java`
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/*`
- `cm-agent-persistence/src/main/java/com/cmagent/persistence/*`
- `cm-agent-persistence/src/main/resources/db/migration/{postgresql,mysql}/V10__make_messages_first_class.sql`
- `cm-agent-server/src/main/java/com/cmagent/server/{config,runtime,web,store}/*`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- 对应测试、生产文档与本主题四份过程记录。

## 已确认的技术边界

- 消息遵循 AgentScope 的“角色 + 有序内容块”语义，但 Core 不依赖 AgentScope 类型。
- 当前 ReActAgent 每个 Run 重建；会话历史由持久化消息窗口注入，conversationId 同时作为 AgentScope sessionId。
- 工具调用和结果是 assistant 消息的内容块；只保存受治理摘要。
- 原 Run API 与 AgentRunResult 不变；新会话 API 复用 `agent:read`、`agent:run` 权限。
- 本次不实现编辑、删除、归档、附件、多模态持久化、HITL、摘要压缩和请求幂等重放。

## 确认项

请在编码前确认：

1. 接受以持久化历史窗口（最近 40 条、最多 60,000 字符）构造当前 AgentScope 用户消息；
2. 接受工具调用/结果作为 assistant 内容块，并且只保存/展示脱敏摘要；
3. 接受原 Run API 保持单轮不变，全部多轮能力通过 `/api/agents/{agentId}/conversations` 提供；
4. 接受本次范围排除项，以及写请求不自动重试、不承诺幂等重放。
