# cm-agent-core 代码注释治理计划

对应设计：[specs/2026-09-01-core-code-comments-design.md](../specs/2026-09-01-core-code-comments-design.md)

## 任务拆分与实现顺序

### 任务 1：核对与分类（只读）

- 通读 66 个主源码文件，归类四类缺陷：缩进错位、错误 `@param`、英文注释、缺失 `@return`/触发清单说明。
- 标记需要“为什么”级补充说明的位置：脱敏责任、租户闸门、失败分级、墓碑删除、游标一致性、Secret 引用、LEGACY 兼容边界等。

### 任务 2：tool 包（6 文件）

`ToolExecutor`（注解顺序 + 失败分级）、`ToolInvocationSource`（来源决定校验严格度）、`ToolExecutionResult`（statusCode 语义）、`ToolRegistry`（快照一致性）、`ToolExecutionRequest`（错误的 `@param source`、LEGACY 边界）、`InMemoryToolRegistry`（已有注释无需修改）。

### 任务 3：security 包（5 文件）

权限编码可信来源、拒绝原因透出约束（`AuthorizationDecision.reason`）、默认策略三步校验顺序、每次调用重新授权的原因。

### 任务 4：audit 包（3 文件）

`AuditPageRequest` 英文改中文；`AuditEvent` 不可变与脱敏；`AuditEventRepository` 严格审计、批量原子性、游标分页兼容行为（英文注释改中文）。

### 任务 5：runtime 包（9 文件）

`AgentRuntime` SPI 边界与错字、`FakeAgentRuntime`（`@Override` 顺序 + 生产禁用边界）、`ModelCredential`（最后防线定位）、`ModelCredentialProvider`（注解顺序 + 凭据生命周期）、异常类的消息脱敏策略、`ToolInvocationGateway` 治理边界、`ToolInvocationRequest`/`ToolInvocationResult`。

### 任务 6：domain 包（24 个需修改文件）

紧凑构造器 JavaDoc 缩进统一；`RunRecord` 状态机、`AgentRunRequest` 租户总闸、`HttpToolConfig` Secret 引用与 GET 限制、`RunToolCall` 空串规范化、`ToolGrant` 英文行注释、分页游标完整性（`RunPageRequest`/`AuditPageRequest` 语序一致性）。

### 任务 7：repository 包（10 文件）

`RunRepository` 两段英文注释（含 UUID 字符串序原因）、`ToolCallRepository` 批次校验时序（英文改中文）、`ToolDefinitionRepository` 恢复方法 `@param`/`@return` 顺序错乱 + 删除墓碑语义、各仓储补 `@return` 与幂等说明。

### 任务 8：验证与文档

- 无错位复扫（正则 `^ {6}\*`）。
- `mvn -pl cm-agent-core test`（JDK 21）。
- 全模块 `compile`。
- `git status` 核对变更文件仅为 core 主源码与文档；确认 server yml 等无关脏文件不属于本任务且保持不动。
- 生成 spec/plan/implementation/ledger 四份文档。

## 涉及文件

- 代码：`cm-agent-core/src/main/java/com/cmagent/core` 下约 40 个文件（最终以 git status 为准）。
- 文档：`docs/superpowers/{specs,plans,implementation,progress}/2026-09-01-core-code-comments*.md`。

## 验证方式

见任务 8；编译与测试必须以 `F:\java21` 为 `JAVA_HOME`（本机默认 JDK 为 17）。

