# cm-agent-core 代码注释治理实现说明

对应设计：[specs/2026-09-01-core-code-comments-design.md](../specs/2026-09-01-core-code-comments-design.md)
对应计划：[plans/2026-09-01-core-code-comments.md](../plans/2026-09-01-core-code-comments.md)

## 实际实现

全部变更仅限 JavaDoc 与行注释，`git diff` 证实无任何可执行代码行变化（编译与测试结果不变）。

### 主要修正类型与代表位置

1. **缩进统一**：全部成员级 JavaDoc 的 ` *` 错位（渲染为两空格星号）修正为标准缩进。终态复扫 `^ {6}\*` 在主源码零命中。
2. **错误 `@param` 修正**：
   - `ToolRegistry.register`：原文“`@param executor AgentScope 或异步任务执行器`”改为“与该工具绑定的执行器，负责工具的真实执行逻辑”，`definition` 补充“待注册的工具领域定义”。
   - `ToolExecutionRequest` 构造器：原文“`@param source 待转换的源对象`”改为“调用来源，决定运行上下文的校验规则”。
   - `ModelCredentialUnavailableException()` 无参构造器：删除误标的 `@param cause`。
   - `ToolDefinitionRepository.restoreManagedLocalTool`/`restoreDeletedToolForCompensation`：`@return`/`@param` 顺序错乱已修复。
3. **注解顺序修复**：`ToolExecutor`、`ModelCredentialProvider`、`ToolInvocationGateway` 的 `@FunctionalInterface` 与 `FakeAgentRuntime` 流式方法的 `@Override` 从 JavaDoc 之前移到之后（消除“悬空 Javadoc”警告）。
4. **英文注释中文化**：`AuditPageRequest`、`RunPageRequest`、`RunToolCallBatch`（含 `requireTenant`）、`RunRepository.save/complete/listByTenantAndAgent/compareIdsByDatabaseOrder`、`ToolCallRepository.saveAll`、`ToolGrant.roleCode` 行注释、`AuditEventRepository.listByTenant(cursor)`。翻译时并补充“为什么”：复合游标完整性、UUID 按 `CHAR(36)` 字符串序而非数值序的原因、批次校验须在首条写入前。
5. **安全边界说明补充**（触发清单命中项）：
   - `ModelCredential`：`toString()` 是最后防线，不替代“Key 不进 DTO/日志/审计”的主动边界。
   - `ToolInvocationGateway`：每次工具调用都须重新授权；denied/failed 与基础设施异常的分级语义。
   - `DefaultToolAuthorizationPolicy`/`ToolAuthorizationPolicy`：三步校验顺序不可调换、每次调用重新执行的原因。
   - `AuditEventRepository`：写入失败向上抛出的严格审计语义；`appendAll` 原子批次约定。
   - `ToolExecutionRequest`：DEBUG/MCP 禁止绑定 agentId/runId 防审计错归因；LEGACY 仅作过渡。
   - `FakeAgentRuntime`：仅限本地/测试，生产禁用。
   - `HttpToolConfig`：`secretHeaders` 只接受 `secret/...` 引用；GET 禁止 BODY 参数符合 HTTP 语义。
   - `AgentRunRequest`：租户一致性总闸（Agent/模型配置/主体/工具同租户 + 模型绑定一致）。
6. **契约补全**：全部 SPI 方法补齐 `@return`；仓储删除类方法补充幂等语义；`ForUpdate` 方法说明行锁差异；`ToolDefinitionRepository.delete` 说明墓碑外键锚点；`McpToolPublicationRepository.delete` 说明取消发布立即生效。
7. **误导/错误表述修正**：`RunRecord.complete` 异常语义补 `@throws`；`AgentTextDelta` 等保持原有正确中文说明不动（不扩大范围）。

### 修复过程中引入并即时纠正的缺陷

- 一次编辑误将 security 包内容串入 `ToolInvocationSource.java`，已恢复该文件正确内容。
- 一次编辑误删 `ModelCredentialProvider.java` 的 package 声明，已恢复。
- 若干次编辑产生半角逗号、“迳行”、“租 tenantId”等笔误，全部已修正；最终扫描确认无残留。
- `AgentRuntime.java` 曾误加未使用的 `Collectors` import，已删除。

## 关键代码位置

- 变更文件：`cm-agent-core/src/main/java/com/cmagent/core` 下 41 个文件（audit 3、domain 13、repository 9、runtime 9、security 5、tool 5；其余文件既有注释已合格，未扩大修改）。
- 验证环境：JAVA_HOME=F:\java21（本机默认 JDK 17 不满足 `release=21`）。

## 与原方案的差异

- `InMemoryToolRegistry` 及部分 domain record 原注释已符合规范，按“不扩大清单以外代码”原则未作改动，实际修改 41 个文件（原计划未定具体数量）。
- `application.yml`/`application-mysql.yml` 等无关脏文件确认属于用户既有本地调试改动（含本地凭据），本任务保持不动。

