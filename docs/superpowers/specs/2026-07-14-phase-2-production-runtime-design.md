# 阶段 2：生产持久化与安全收口设计

## 背景与目标

CM Agent 第一阶段已具备 Agent、Tool、ToolGrant 的 JDBC/Flyway 持久化基线，以及 fake runtime、JWT、RBAC 和内存审计。`runs`、`tool_calls`、`audit_events` 已在 `V1__init_schema.sql` 中建表，但服务端运行链路尚未把 Run 和 ToolCall 写入数据库；审计查询仍直接依赖 `InMemoryPlatformStore`，而审计写入异常会被捕获后继续业务流程。

阶段 2 的目标是形成可用于生产试点的持久化与安全基线：Run、ToolCall、AuditEvent 在 JDBC 模式持久化且严格租户隔离；关键审计不可被静默吞掉；生产 profile、JWT、bootstrap admin、错误响应与敏感数据边界收紧。阶段 2 不接入真实 AgentScope 执行、流式输出、后台重试或容量治理。

## 阶段 2-5 路线图

| 阶段 | 主题 | 交付边界 |
| --- | --- | --- |
| 阶段 2 | 生产持久化与安全收口 | Run、ToolCall、AuditEvent JDBC/Flyway 持久化；租户隔离；严格审计；安全 profile 和错误脱敏。 |
| 阶段 3 | 真实 AgentScope 运行时 | AgentScope Java runtime、模型配置与密钥抽象、工具执行、超时/取消/失败状态、受控流式输出。 |
| 阶段 4 | 可观测性与运维 | metrics、health、日志脱敏、审计检索、运行追踪、告警、备份恢复、迁移运维和容量治理。 |
| 阶段 5 | 交付与稳定性 | CI、容器镜像、依赖与漏洞治理、集成测试矩阵、性能/并发/故障恢复测试、版本发布和回滚策略。 |

阶段 3-5 仅作为路线图，不在本次实现中提前引入 AgentScope 调用、后台 worker、outbox、指标系统或 CI 资产。

## 方案选择

采用“同步审计、两段式运行持久化”方案。

1. 运行启动时，在短数据库事务中创建 `RUNNING` Run 并写入启动审计；审计失败时事务回滚，不调用 runtime。
2. runtime 调用位于数据库事务外，避免为未来真实模型或工具调用持有连接和事务。
3. 运行结束时，在新的短事务中更新 Run、批量写入 ToolCall 并写入完成审计。审计或运行面持久化失败时，返回中文 `503`，不把异常伪装为成功。
4. 若结束事务失败，已创建的 `RUNNING` Run 保留为可追溯记录；服务会尽力使用独立事务将它更新为 `FAILED` 和固定的受控原因。该补记失败也会向调用方保持 `503`，不会吞掉原始持久化故障。

不采用“运行继续、审计仅告警”的弱一致性方案，因为它不能满足审计可靠性要求；也不采用 outbox 与异步重试，因为这需要新增事件表、调度和运维语义，超出阶段 2 边界。

## 领域、Repository 与数据库设计

在 `cm-agent-core` 新增以下只依赖 Java 类型的领域记录：

- `RunRecord`：`id`、`tenantId`、`agentId`、`principalId`、`status`、`input`、`output`、`errorMessage`、`startedAt`、`finishedAt`。
- `RunToolCall`：`id`、`tenantId`、`runId`、`toolId`、`toolName`、`inputSummary`、`outputSummary`、`status`、`authorized`、`durationMillis`、`errorMessage`、`createdAt`。
- `RunPage`：当前页 `items` 和不透明 `nextCursor`；按 `startedAt`、`id` 倒序进行 keyset 分页。

新增 `RunRepository` 和 `ToolCallRepository` 到 `com.cmagent.core.repository`。所有方法将 tenant 作为显式入参；查询条件始终同时包含 tenant 与资源标识。现有 `AuditEventRepository` 保持兼容，并扩展为同样的受限游标分页查询。

`cm-agent-persistence` 新增 `JdbcRunRepository` 与 `JdbcToolCallRepository`，继续使用 `JdbcClient`、命名参数和显式 row mapper。`JdbcAuditEventRepository` 同步升级为游标读取。绝不修改 `V1__init_schema.sql`；新增 `V2__add_runtime_query_indexes.sql` 与 `V3__add_tool_calls_created_at_index.sql`，只新增如下可同时运行于 PostgreSQL 16 和 MySQL 8.4 的索引：

```sql
CREATE INDEX idx_runs_tenant_agent_started ON runs (tenant_id, agent_id, started_at, id);
CREATE INDEX idx_tool_calls_tenant_run ON tool_calls (tenant_id, run_id, id);
CREATE INDEX idx_audit_events_tenant_time_id ON audit_events (tenant_id, created_at, id);
CREATE INDEX idx_tool_calls_tenant_run_created_at ON tool_calls (tenant_id, run_id, created_at, id);
```

应用层不提供自动删除、归档或固定保留期。阶段 2 仅限制分页大小；保留、归档、备份和容量阈值属于阶段 4。

## 服务端流程与 API

新增 `RunExecutionService` 与 `RunPersistenceService`。Controller 只负责认证主体、`agent:run` 或 `agent:read` 权限入口、校验和 HTTP 映射；工具授权、运行编排、审计和 Repository 调用移入服务。管理面已有的 Agent、Tool、Grant 写操作也改由事务服务与审计同事务提交。

`InMemoryPlatformStore` 只在 `cm-agent.persistence.mode=memory` 时注册。JDBC 模式注册 Agent、Tool、Grant、Run、ToolCall、Audit 的 JDBC Repository；`AuditController` 仅依赖 `AuditEventRepository`，不再注入 `InMemoryPlatformStore`。

保留现有 `POST /api/agents/{agentId}/runs` 的成功响应契约，并新增：

- `GET /api/agents/{agentId}/runs?limit={1..100}&cursor={opaque}`：当前认证租户内、按最新优先的 `RunPage`。
- `GET /api/agents/{agentId}/runs/{runId}`：当前认证租户的 Run 与 ToolCall 摘要。
- `GET /api/audit-events?limit={1..100}&cursor={opaque}`：当前认证租户的审计分页。

Run 读取使用既有 `agent:read`，Run 提交使用 `agent:run`，审计读取使用 `audit:read`。拒绝访问也必须尝试同步写审计；审计不可用时响应 `503`，而不是静默降级为未审计的 `403`。

## 安全与错误边界

引入单一 `SensitiveDataRedactor`，用于 Run 输入/输出/错误、ToolCall 摘要与错误、审计消息和受控日志消息。它屏蔽 JWT、Bearer token、数据库口令、模型 API Key、密码键值和带凭据 JDBC URL；API 响应不回传底层异常消息、SQL、调用栈或密钥。

新增 `@RestControllerAdvice`，返回稳定的中文错误对象 `code`、`message`、`timestamp`：校验错误为 `400`，未认证为 `401`，权限不足为 `403`，资源不可见为 `404`，审计或数据服务不可用为 `503`，未预期错误为 `500`。异常原因仅进入经过脱敏的服务端日志。

删除源码中的开发 JWT 回退密钥，所有 profile 均需明确提供 JWT 密钥。根配置不再默认激活 `local`；未显式选择 profile 时启动失败。`production`、`prod`、`supabase` 必须单独使用 JDBC、外部 JWT 密钥，且禁止 bootstrap admin、开发 JWT 回退及与 `local`、`test`、`postgres`、`mysql` 组合。`postgres` 与 `mysql` 继续仅用于 Rocky VM 联调。

## 验收与验证

阶段 2 完成必须满足：

1. PostgreSQL 16 和 MySQL 8.4 的 Flyway 迁移均应用 `V1`、`V2`、`V3`，并验证新增索引。
2. JDBC Repository 测试证明 Run、ToolCall、Audit 的写入、游标读取和跨租户不可见。
3. MockMvc 测试证明运行成功、运行失败、运行查询、审计查询、权限拒绝、审计失败 `503`、管理面事务回滚和错误脱敏。
4. profile/JWT/bootstrap 测试证明无 profile 启动失败、生产 profile 的错误组合被拒绝、生产路径不注册 `InMemoryPlatformStore`。
5. 在 `ssh rocky` 的 Maven 3.9.9 / Temurin 21 容器中运行 `mvn -q test`，并收集 Surefire 的总数、失败数与错误数。

## 自查

设计没有未定义接口、TBD、TODO 或依赖阶段 3-5 的实现项。它不引入 JPA、Hibernate、MyBatis、新数据库框架、真实凭据、已发布迁移修改或后台异步基础设施。
