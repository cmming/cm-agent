# 会话聊天执行过程展示实施计划

## 关联文档

- [设计说明](../specs/2026-09-01-console-chat-execution-trace-design.md)
- [实现说明](../implementation/2026-09-01-console-chat-execution-trace-implementation-design.md)
- [进度账本](../progress/2026-09-01-console-chat-execution-trace-ledger.md)

## 实施顺序

### 任务 1：扩展受控领域合同

- 涉及文件：
  - `cm-agent-core/src/main/java/com/cmagent/core/domain/MessageContentType.java`
  - `cm-agent-core/src/main/java/com/cmagent/core/domain/MessageContentBlock.java`
  - `cm-agent-core/src/main/java/com/cmagent/core/domain/ConversationMessageDraft.java`
  - `cm-agent-core/src/main/java/com/cmagent/core/runtime/AgentRuntime.java`
  - 新增执行进度事件领域类型及对应 Core 测试
- 实现：新增只允许 assistant 使用的 `THINKING` 块；增加向后兼容的进度消费者入口；保持旧 Runtime 默认实现可运行。
- 验证：内容块字段组合、角色限制、文本投影和旧 Runtime 兼容测试。

### 任务 2：映射 AgentScope 思考与工具事件

- 涉及文件：
  - `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeExecutor.java`
  - `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java`
  - `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeAdapter.java`
  - adapter 测试
- 实现：聚合 thinking 块，在块结束时发送完整内容；工具事件只发送名称、标识、阶段和终态；最终消息保留 thinking，工具内容仍从 `ToolCallRecord` 映射。
- 验证：AgentScope 2.0.2 事件映射、工具状态转换、thinking 最终快照和无原始载荷断言。

### 任务 3：贯通服务端 SSE 与持久化

- 涉及文件：
  - `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`
  - `cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java`
  - `cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java`
  - 对应 server 测试
- 实现：在运行编排边界脱敏 thinking；通过 `progress` SSE 发送受控事件；最终 `THINKING` 块随 assistant 消息保存。
- 验证：进度事件顺序、thinking 脱敏、SSE 错误语义、运行完成后消息快照。

### 任务 4：实现聊天页执行轨迹

- 涉及文件：
  - `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`
  - `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
  - `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`
  - console Java/JavaScript 测试
- 实现：assistant 消息增加可折叠执行过程；实时消费 `progress`；历史消息合并 thinking 与同一工具调用；保持 Markdown 与纯文本安全渲染边界。
- 验证：DOM 结构、未知/无 thinking 兼容、工具状态、响应式布局和脚本语法。

### 任务 5：文档收口与验证

- 更新实现说明、进度账本和必要的 `docs/release-notes.md`。
- 确认 `java -version` 与 `mvn -v` 均使用 JDK 21。
- 依次执行：
  - Core 针对性测试；
  - AgentScope adapter 针对性测试；
  - console JavaScript 与 Maven 测试；
  - server 会话/SSE 针对性测试；
  - `mvn -q test`（环境和耗时允许时）。
- 本任务不涉及 Docker、JDBC Repository 或 Flyway 变更，无需启动远程容器验证；若测试意外触发 Testcontainers，按仓库规则改在 `ssh rocky` 环境执行。

## 完成定义

- 设计中的七项验收标准均有代码或测试证据。
- 四份主题文档内容一致，进度账本记录实际命令、结果、遗留项和提交状态。
- 差异中不包含主工作区既有配置修改、生成物、真实凭据或无关格式化。
