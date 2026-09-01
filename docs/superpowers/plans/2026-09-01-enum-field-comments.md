# 枚举常量与对象属性注释规则计划

对应设计：[specs/2026-09-01-enum-field-comments-design.md](../specs/2026-09-01-enum-field-comments-design.md)

## 任务拆分与实现顺序

### 任务 1：更新 AGENTS.md

在“代码注释与 JavaDoc 规范”的触发清单首位插入新条款，明确三点义务：枚举常量逐个 JavaDoc、record 组件经类级 `{@code @param}` 逐一说明、属性增删改时同步维护标签。

### 任务 2：枚举常量注释（10 个枚举、33 个常量）

- `core.tool`：`ToolInvocationSource`（4 常量）。
- `core.domain`：`RunStatus`（4）、`ToolType`（4）、`ToolRiskLevel`（3）、`MessageContentType`（3）、`MessageRole`（4）、`ModelProviderType`（2）、`HttpToolMethod`（2）、`HttpParameterLocation`（5）、`HttpParameterDataType`（6，含 `schemaType` 字段与方法 `@return` 补全）。

### 任务 3：record 组件注释（23 个 record）

贴齐“每个组件必须有类级 `@param`”。逐文件处理：

- audit：`AuditEvent`（9 组件）、`AuditPageRequest`（3）。
- domain：`AgentDefinition`(13)、`AgentRunRequest`(8)、`AgentRunResult`(7)、`AgentRuntimeResult`(2)、`AgentTextDelta`(3)、`Conversation`(7)、`ConversationMessage`(9)、`ConversationMessageDraft`(8)、`ConversationPageRequest`(3)、`ConversationRunResult`(5)、`HttpParameterDefinition`(17)、`HttpToolConfig`(7)、`McpToolPublication`(4)、`MessageContentBlock`(5)、`MessagePageRequest`(2)、`ModelConfig`(7)、`RunPageRequest`(3)、`RunRecord`(10)、`RunStatus` 外的枚举记录类型已在任务 2、`RunToolCall`(12)、`RunToolCallBatch`(2)、`ToolCallRecord`(8)、`ToolDefinition`(11)、`ToolGrant`(5)。
- runtime：`ToolInvocationRequest`(8)、`ToolInvocationResult`(4)。
- security：`AuthorizationDecision`(2)。
- tool：`ToolExecutionRequest`(8)、`ToolExecutionResult`(4)。
- 已有组件 `@param` 的文件（`InMemoryToolRegistry` 无 record；`RunToolCall` 等在类级补齐后保留构造器原有标签）。

### 任务 4：验证

- 笔误正则扫描（消消息/租 tenant/迳行/契约繁体/半角逗号行尾等）。
- `mvn -pl cm-agent-core test`（JAVA_HOME=F:\java21）。
- 生成四份 `enum-field-comments` 文档并保持相互引用一致。

## 涉及文件

- `AGENTS.md`
- `cm-agent-core/src/main/java/com/cmagent/core` 下枚举 10 个、record 约 23 个
- `docs/superpowers/{specs,plans,implementation,progress}/2026-09-01-enum-field-comments*`

## 验证方式

见任务 4。编译与测试环境：本机默认 JDK 17，需 `JAVA_HOME=F:\java21`。

