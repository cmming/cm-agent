# 阶段 2：生产持久化与安全收口实施计划

> **面向 agentic workers：** 必须用 `superpowers:subagent-driven-development` 执行；每个任务由新子 agent 实施、另一子 agent 审查，任务状态写入 `.superpowers/sdd/progress.md`，该账本不加入暂存区。

**目标：** 在不引入 JPA、Hibernate、MyBatis 或新数据库框架的前提下，以 JDBC、JdbcClient、Flyway 完成运行数据持久化与生产安全收口。

**架构：** core 新增运行领域对象与 Repository 契约；persistence 为 PostgreSQL 16/MySQL 8.4 提供 JDBC 实现和 V2/V3 索引；server 通过短事务分别保存运行开始与结束，外部 runtime 始终在事务外调用。Controller 只承担 HTTP、认证、授权与 DTO 映射。

**技术栈：** Java 21、Maven 3.9.9、Spring Boot 3.5.0、JdbcClient、Flyway、JUnit 5、AssertJ、MockMvc、Testcontainers。

## 全局约束

- 只在 `ssh rocky` 的 `maven:3.9.9-eclipse-temurin-21` 容器执行 Maven、Docker、Testcontainers、JDBC 和 Flyway 验证。
- 不修改 `cm-agent-persistence/src/main/resources/db/migration/V1__init_schema.sql`。
- JDBC 查询与更新必须显式绑定 `tenantId`；不得从 HTTP 请求体覆盖认证主体 tenant。
- `prod`、`production`、`supabase` 禁止 memory、bootstrap admin、JWT 回退和组合开发 profile。
- 文档、提交和 PR 说明使用中文；不提交真实 secret、生成物或 `.superpowers/` 本地账本。

## Rocky TDD 同步命令

每次运行任何阶段 2 测试前执行下列三条命令，最后一条 Maven 目标按任务给出的完整命令替换：

```powershell
tar -czf "$env:TEMP\cm-agent-phase2-current.tgz" --exclude=.git --exclude=.superpowers --exclude=target -C "F:\java\cm-agent\.worktrees\phase-2-production-runtime" .
scp "$env:TEMP\cm-agent-phase2-current.tgz" rocky:/tmp/cm-agent-phase2-current.tgz
ssh rocky 'set -euo pipefail; rm -rf /tmp/cm-agent-phase2-current; mkdir -p /tmp/cm-agent-phase2-current; tar -xzf /tmp/cm-agent-phase2-current.tgz -C /tmp/cm-agent-phase2-current; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-core -am test'
```

---

### Task 1：运行领域与 Repository 契约

**文件：**
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/domain/RunRecord.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/domain/RunToolCall.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/repository/RunRepository.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/repository/ToolCallRepository.java`
- 新建：`cm-agent-core/src/test/java/com/cmagent/core/domain/RunRecordTest.java`
- 新建：`cm-agent-core/src/test/java/com/cmagent/core/domain/RunToolCallTest.java`

**契约：** `RunRepository.save(UUID, RunRecord)` 创建同 tenant 的 `RUNNING` 记录；`complete(UUID, UUID, RunStatus, String, String, Instant)` 只在相同 tenant 更新；列表以构造即校验的 `RunPageRequest` 进行 keyset 查询。`RunToolCallBatch` 在构造时拒绝混合 tenant，Repository 写入仍显式传入 tenant，并在首条写入前验证批次作用域。

- [ ] **步骤 1：写失败测试**

```java
@Test void createRejectsBlankPrincipal() {
    assertThatThrownBy(() -> RunRecord.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), " ", "input", Instant.now()))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("principalId 不能为空");
}
@Test void toolCallRejectsNegativeDuration() {
    assertThatThrownBy(() -> new RunToolCall(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "echo", "in", "out", RunStatus.SUCCEEDED, true, -1L, "", Instant.now()))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("durationMillis 不能小于 0");
}
```

- [ ] **步骤 2：确认失败**

运行：Rocky TDD 同步命令的 Maven 部分使用：
```powershell
ssh rocky 'set -euo pipefail; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-core -am -Dtest=RunRecordTest,RunToolCallTest test'
```
预期：编译失败，找不到 `RunRecord` 和 `RunToolCall`。

- [ ] **步骤 3：写最小实现**

```java
public record RunRecord(UUID id, UUID tenantId, UUID agentId, String principalId, RunStatus status,
                        String input, String output, String errorMessage, Instant startedAt, Instant finishedAt) {
    public RunRecord {
        Objects.requireNonNull(id, "id 不能为空"); Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空"); Objects.requireNonNull(status, "status 不能为空");
        if (principalId == null || principalId.isBlank()) throw new IllegalArgumentException("principalId 不能为空");
        input = input == null ? "" : input; output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage; Objects.requireNonNull(startedAt, "startedAt 不能为空");
    }
    public static RunRecord create(UUID id, UUID tenantId, UUID agentId, String principalId, String input, Instant startedAt) {
        return new RunRecord(id, tenantId, agentId, principalId, RunStatus.RUNNING, input, "", "", startedAt, null);
    }
    public RunRecord complete(RunStatus status, String output, String errorMessage, Instant finishedAt) {
        if (status == RunStatus.RUNNING) throw new IllegalArgumentException("finalStatus 不能为 RUNNING");
        return new RunRecord(id, tenantId, agentId, principalId, status, input, output, errorMessage, startedAt,
            Objects.requireNonNull(finishedAt, "finishedAt 不能为空"));
    }
}
```

```java
public record RunToolCall(UUID id, UUID tenantId, UUID runId, UUID toolId, String toolName, String inputSummary,
                          String outputSummary, RunStatus status, boolean authorized, Long durationMillis,
                          String errorMessage, Instant createdAt) {
    public RunToolCall {
        Objects.requireNonNull(id, "id 不能为空"); Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空"); Objects.requireNonNull(toolId, "toolId 不能为空");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName 不能为空");
        Objects.requireNonNull(status, "status 不能为空"); Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (durationMillis != null && durationMillis < 0) throw new IllegalArgumentException("durationMillis 不能小于 0");
        inputSummary = inputSummary == null || inputSummary.isBlank() ? "" : inputSummary;
        outputSummary = outputSummary == null || outputSummary.isBlank() ? "" : outputSummary;
        errorMessage = errorMessage == null || errorMessage.isBlank() ? "" : errorMessage;
    }
}
public record RunPageRequest(int limit, Instant beforeStartedAt, UUID beforeId) {
    public RunPageRequest {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        if ((beforeStartedAt == null) != (beforeId == null))
            throw new IllegalArgumentException("beforeStartedAt 与 beforeId 必须同时为空或同时非空");
    }
}
public record RunToolCallBatch(UUID tenantId, List<RunToolCall> toolCalls) {
    public RunToolCallBatch {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls 不能为空"));
        if (toolCalls.stream().anyMatch(call -> !tenantId.equals(call.tenantId())))
            throw new IllegalArgumentException("toolCalls 必须全部属于 tenantId");
    }
    public void requireTenant(UUID tenantId) {
        if (!this.tenantId.equals(Objects.requireNonNull(tenantId, "tenantId 不能为空")))
            throw new IllegalArgumentException("tenantId 与 toolCalls 批次不匹配");
    }
}
public interface RunRepository {
    RunRecord save(UUID tenantId, RunRecord run);
    RunRecord complete(UUID tenantId, UUID runId, RunStatus status, String output, String errorMessage, Instant finishedAt);
    Optional<RunRecord> findByTenantAndAgentAndId(UUID tenantId, UUID agentId, UUID runId);
    List<RunRecord> listByTenantAndAgent(UUID tenantId, UUID agentId, RunPageRequest pageRequest);
}
public interface ToolCallRepository {
    void saveAll(UUID tenantId, RunToolCallBatch toolCalls);
    List<RunToolCall> listByTenantAndRun(UUID tenantId, UUID runId);
}
```

- [ ] **步骤 4：确认通过**

运行：步骤 2 的完整命令。预期：退出码 `0`。

- [ ] **步骤 5：提交**

```powershell
git add cm-agent-core/src/main/java/com/cmagent/core/domain/RunRecord.java cm-agent-core/src/main/java/com/cmagent/core/domain/RunToolCall.java cm-agent-core/src/main/java/com/cmagent/core/domain/RunPageRequest.java cm-agent-core/src/main/java/com/cmagent/core/domain/RunToolCallBatch.java cm-agent-core/src/main/java/com/cmagent/core/repository/RunRepository.java cm-agent-core/src/main/java/com/cmagent/core/repository/ToolCallRepository.java cm-agent-core/src/test/java/com/cmagent/core/domain/RunRecordTest.java cm-agent-core/src/test/java/com/cmagent/core/domain/RunToolCallTest.java cm-agent-core/src/test/java/com/cmagent/core/domain/RunPageRequestTest.java
git commit -m "feat: 定义运行面持久化契约"
```

### Task 2：Flyway V2/V3 与 JDBC Repository

**文件：**
- 新建：`cm-agent-persistence/src/main/resources/db/migration/V2__add_runtime_query_indexes.sql`
- 新建：`cm-agent-persistence/src/main/resources/db/migration/V3__add_tool_calls_created_at_index.sql`
- 新建：`cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRunRepository.java`
- 新建：`cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcToolCallRepository.java`
- 新建：`cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcRunRepositoryTest.java`
- 新建：`cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcToolCallRepositoryTest.java`
- 修改：`cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java`

**契约：** 每条 SQL 都按 `tenant_id` 限定；Run 完成更新数必须为 1；ToolCall 按 `created_at, id` 返回。调用使用 `RunPageRequest` 和 `RunToolCallBatch`；JDBC `saveAll` 必须在首条 SQL 前调用 `toolCalls.requireTenant(tenantId)`。

- [ ] **步骤 1：写失败测试**

```java
@Test void tenantCannotReadAnotherTenantsRun() {
    repository.save(tenantA, RunRecord.create(runA, tenantA, agentA, "a", "in", first));
    repository.save(tenantB, RunRecord.create(runB, tenantB, agentB, "b", "other", second));
    assertThat(repository.findByTenantAndAgentAndId(tenantB, agentB, runA)).isEmpty();
    assertThat(repository.listByTenantAndAgent(tenantA, agentA, new RunPageRequest(20, null, null)))
        .extracting(RunRecord::id).containsExactly(runA);
}
@Test void toolCallReadRequiresSameTenantAndRun() {
    repository.saveAll(tenantA, new RunToolCallBatch(tenantA, List.of(callA)));
    assertThat(repository.listByTenantAndRun(tenantA, runA)).containsExactly(callA);
    assertThat(repository.listByTenantAndRun(tenantB, runA)).isEmpty();
}
```

- [ ] **步骤 2：确认失败**

运行：Rocky TDD 同步后执行：
```powershell
ssh rocky 'set -euo pipefail; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-persistence -am -Dtest=JdbcRunRepositoryTest,JdbcToolCallRepositoryTest test'
```
预期：编译失败，JDBC Repository 不存在。

- [ ] **步骤 3：写最小实现**

```sql
CREATE INDEX idx_runs_tenant_agent_started ON runs (tenant_id, agent_id, started_at, id);
CREATE INDEX idx_tool_calls_tenant_run ON tool_calls (tenant_id, run_id, id);
CREATE INDEX idx_audit_events_tenant_time_id ON audit_events (tenant_id, created_at, id);
CREATE INDEX idx_tool_calls_tenant_run_created_at ON tool_calls (tenant_id, run_id, created_at, id);
```

```java
int updated = jdbcClient.sql("""
    UPDATE runs SET status = :status, output_text = :output, error_message = :errorMessage, finished_at = :finishedAt
    WHERE tenant_id = :tenantId AND id = :runId
    """).param("status", status.name()).param("output", nullIfBlank(output))
    .param("errorMessage", nullIfBlank(errorMessage)).param("finishedAt", Timestamp.from(finishedAt))
    .param("tenantId", tenantId.toString()).param("runId", runId.toString()).update();
if (updated != 1) throw new NoSuchElementException("Run 不存在");
```

`JdbcRunRepository.save(UUID tenantId, RunRecord run)` 先校验 `tenantId.equals(run.tenantId())`，再插入 `runs` 的全部十个列。`listByTenantAndAgent(UUID tenantId, UUID agentId, RunPageRequest pageRequest)` 使用 `pageRequest.limit()`、`pageRequest.beforeStartedAt()` 与 `pageRequest.beforeId()`：无 cursor 时使用 `WHERE tenant_id = :tenantId AND agent_id = :agentId ORDER BY started_at DESC, id DESC LIMIT :limit`；有 cursor 时追加 `AND (started_at < :beforeStartedAt OR (started_at = :beforeStartedAt AND id < :beforeId))`，并将 `beforeId` 绑定为 `pageRequest.beforeId().toString()`，保持 `CHAR(36)` 小写字典序。`JdbcToolCallRepository.saveAll(UUID tenantId, RunToolCallBatch toolCalls)` 先执行 `toolCalls.requireTenant(tenantId)`，成功后才插入 `toolCalls.toolCalls()` 中的 `tool_calls` 全部十二个列。将 `MigrationTest` 的迁移数量断言改为 `3`，并断言 V2、V3 提供的三个索引。

- [ ] **步骤 4：确认通过**

运行：Rocky TDD 同步后执行：
```powershell
ssh rocky 'set -euo pipefail; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-persistence -am test'
```
预期：PostgreSQL 16、MySQL 8.4 均应用三个迁移，退出码 `0`。

- [ ] **步骤 5：提交**

```powershell
git add cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRunRepository.java cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcToolCallRepository.java cm-agent-persistence/src/main/resources/db/migration/V2__add_runtime_query_indexes.sql cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcRunRepositoryTest.java cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcToolCallRepositoryTest.java cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java
git commit -m "feat: 持久化运行与工具调用记录"
```

### Task 3：接线 JDBC、内存模式与严格审计

**文件：**
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/audit/AuditAppender.java`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/audit/AuditPersistenceException.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/web/AuditController.java`
- 新建：`cm-agent-server/src/test/java/com/cmagent/server/audit/AuditAppenderTest.java`

**契约：** memory mode 保留内存 Repository；JDBC mode 不创建 `InMemoryPlatformStore`；审计 Repository 异常必须变为 `AuditPersistenceException("审计写入失败", cause)`。

- [ ] **步骤 1：写失败测试**

```java
@Test void appendRethrowsRepositoryFailure() {
    AuditEventRepository repository = event -> { throw new IllegalStateException("database unavailable"); };
    assertThatThrownBy(() -> new AuditAppender(repository, new SensitiveDataRedactor()).append(
        UUID.randomUUID(), "user", "EVENT", "RESOURCE", "id", "FAILED", "message"))
        .isInstanceOf(AuditPersistenceException.class).hasMessage("审计写入失败");
}
```

- [ ] **步骤 2：确认失败**

运行：Rocky TDD 同步后执行：
```powershell
ssh rocky 'set -euo pipefail; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-server -am -Dtest=AuditAppenderTest,JdbcPersistenceConfigurationTest test'
```
预期：失败，因为 `AuditAppender` 吞掉异常。

- [ ] **步骤 3：写最小实现**

```java
@Bean @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
public InMemoryPlatformStore inMemoryPlatformStore() { return new InMemoryPlatformStore(); }

@Bean AuditEventRepository jdbcAuditEventRepository(JdbcClient client) { return new JdbcAuditEventRepository(client); }
@Bean RunRepository jdbcRunRepository(JdbcClient client) { return new JdbcRunRepository(client); }
@Bean ToolCallRepository jdbcToolCallRepository(JdbcClient client, TransactionTemplate transactionTemplate) { return new JdbcToolCallRepository(client, transactionTemplate); }
@Bean PlatformTransactionManager cmAgentTransactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
@Bean TransactionTemplate cmAgentTransactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
```

`InMemoryPlatformStore` 实现任务 1 的两个 Repository；`AuditController` 构造器只接收 `AuditEventRepository`；`AuditAppender` 不记录后继续，而是包装并抛出异常。

- [ ] **步骤 4：确认通过**

运行：步骤 2 的完整命令。预期：退出码 `0`，JDBC Context 中没有内存 Store。

- [ ] **步骤 5：提交**

```powershell
git add cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java cm-agent-server/src/main/java/com/cmagent/server/audit/AuditAppender.java cm-agent-server/src/main/java/com/cmagent/server/audit/AuditPersistenceException.java cm-agent-server/src/main/java/com/cmagent/server/web/AuditController.java cm-agent-server/src/test/java/com/cmagent/server/audit/AuditAppenderTest.java cm-agent-server/src/test/java/com/cmagent/server/config/JdbcPersistenceConfigurationTest.java
git commit -m "feat: JDBC模式接管审计与运行仓储"
```

### Task 4：两段式运行、管理事务与查询 API

**文件：**
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/runtime/RunPersistenceService.java`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/service/ManagementCommandService.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/web/AgentController.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerJdbcPersistenceTest.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerTest.java`

**契约：** `start` 在短事务内保存 `RUNNING` 与启动审计；runtime 调用在事务外；`complete` 在短事务内保存结果、ToolCall 与审计；详情和游标分页只能读取认证 tenant。

- [ ] **步骤 1：写失败测试**

```java
@Test void runPersistsAndOtherTenantCannotReadIt() throws Exception {
    String runId = postRun(adminToken, agentId, "password=secret");
    mockMvc.perform(get("/api/agents/{agentId}/runs/{runId}", agentId, runId).header(AUTHORIZATION, bearer(adminToken)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(runId))
        .andExpect(jsonPath("$.run.input").value("password=<已脱敏>"));
    mockMvc.perform(get("/api/agents/{agentId}/runs/{runId}", agentId, runId).header(AUTHORIZATION, bearer(otherTenantToken)))
        .andExpect(status().isNotFound());
}
@Test void auditFailureReturns503BeforeRuntime() throws Exception {
    when(auditRepository.append(any())).thenThrow(new IllegalStateException("database unavailable"));
    mockMvc.perform(post("/api/agents/{agentId}/runs", agentId).header(AUTHORIZATION, bearer(adminToken))
        .contentType(APPLICATION_JSON).content("{\"input\":\"hello\"}")).andExpect(status().isServiceUnavailable());
    verify(agentRuntime, never()).run(any());
}
```

- [ ] **步骤 2：确认失败**

运行：Rocky TDD 同步后执行：
```powershell
ssh rocky 'set -euo pipefail; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-server -am -Dtest=RunControllerJdbcPersistenceTest,RunControllerTest test'
```
预期：失败，因为不存在查询路由与 `503` 审计边界。

- [ ] **步骤 3：写最小实现**

```java
return transactionTemplate.execute(status -> {
    RunRecord run = runRepository.save(principal.tenantId(), RunRecord.create(UUID.randomUUID(), principal.tenantId(),
        agent.id(), principal.principalId(), redactor.redact(input), Instant.now()));
    auditAppender.append(principal.tenantId(), principal.principalId(), "AGENT_RUN", "RUN", run.id().toString(),
        "RUNNING", "Agent 运行已启动");
    return run;
});
```

`RunExecutionService` 在 `start` 后调用 `runtime.run`，将 runtime 返回的 id 替换成持久化 Run id，把 `ToolCallRecord.duration().toMillis()` 映射成 `RunToolCall.durationMillis()`，再调用 `runRepository.complete(principal.tenantId(), run.id(), finalStatus, output, errorMessage, finishedAt)`。完成事务用 `toolCallRepository.saveAll(principal.tenantId(), new RunToolCallBatch(principal.tenantId(), toolCalls))` 保存工具调用。Runtime 异常完成 `FAILED` Run 后抛出 `RuntimeExecutionException("Agent 运行失败")`。Controller 使用 Base64 URL 编码 `startedAt|id` 作为不透明 cursor，并追加：

```java
@GetMapping public RunPageResponse list(@PathVariable UUID agentId,
    @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit, Authentication authentication)
@GetMapping("/{runId}") public RunDetailResponse get(@PathVariable UUID agentId, @PathVariable UUID runId,
    Authentication authentication)
public record RunDetailResponse(RunRecord run, List<RunToolCall> toolCalls) {}
public record RunPageResponse(List<RunRecord> items, String nextCursor) { public RunPageResponse { items = List.copyOf(items); } }
```

`list` 解码 cursor 后构造 `RunPageRequest pageRequest = new RunPageRequest(limit, beforeStartedAt, beforeId)`，并调用 `runRepository.listByTenantAndAgent(principal.tenantId(), agentId, pageRequest)`；无 cursor 时两个 cursor 字段均为 `null`。

`ManagementCommandService` 的 Agent 创建、Tool 创建、Tool 授权均在 `TransactionTemplate.execute` 内执行 Repository 写入与审计，Controller 改为调用该服务。

- [ ] **步骤 4：确认通过**

运行：步骤 2 的完整命令。预期：退出码 `0`，覆盖持久化、分页、详情、租户隔离、管理事务和审计失败 `503`。

- [ ] **步骤 5：提交**

```powershell
git add cm-agent-server/src/main/java/com/cmagent/server/runtime/RunPersistenceService.java cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java cm-agent-server/src/main/java/com/cmagent/server/service/ManagementCommandService.java cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java cm-agent-server/src/main/java/com/cmagent/server/web/AgentController.java cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerJdbcPersistenceTest.java cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerTest.java
git commit -m "feat: 运行链路持久化并提供租户查询"
```

### Task 5：JWT、profile、错误响应与脱敏

**文件：**
- 新建：`cm-agent-api/src/main/java/com/cmagent/api/ApiErrorResponse.java`
- 修改：`cm-agent-api/src/main/java/com/cmagent/api/ApiErrorCode.java`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/security/SensitiveDataRedactor.java`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/security/ProfileSafetyValidator.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/security/JwtSecurityConfiguration.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/security/SecurityConfig.java`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/web/ApiExceptionHandler.java`
- 修改：`cm-agent-server/src/main/resources/application.yml`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/security/JwtSecurityConfigurationTest.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`
- 新建：`cm-agent-server/src/test/java/com/cmagent/server/security/SecurityErrorResponseTest.java`
- 新建：`cm-agent-server/src/test/java/com/cmagent/server/audit/AuditAppenderTest.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/web/ConsoleSmokeTest.java`
- 修改：`docs/superpowers/plans/2026-07-14-phase-2-production-runtime.md`

**契约：** 所有 MVC 与 Spring Security 错误均返回 JSON `ApiErrorResponse`，并使用稳定的 `ApiErrorCode`、固定中文消息和 ISO-8601 `timestamp`；未认证为 `401/UNAUTHORIZED/未登录或令牌无效`，无权限为 `403/FORBIDDEN/没有权限执行该操作`，不得写入异常文本、令牌或密钥。JWT 不允许源码回退密钥；未显式选择 profile 不会加载 local；`AuditAppender.append` 在成功写入时必须通过同一个 `SensitiveDataRedactor` 清洗 password、apiKey、Bearer token 和 PostgreSQL/MySQL JDBC 凭据。审计写入异常的严格抛错改造属于 Task 3，本任务不改变现有异常路径。

- [ ] **步骤 1：写失败测试**

```java
@Test void redactorMasksSecrets() {
    assertThat(new SensitiveDataRedactor().redact("Bearer abc.def password=secret apiKey=key jdbc:postgresql://u:p@db/app"))
        .doesNotContain("abc.def", "secret", "key", "u:p").contains("<已脱敏>");
}
@Test void validationResponseDoesNotExposeImplementation() throws Exception {
    mockMvc.perform(get("/api/agents/not-a-uuid").header(AUTHORIZATION, bearer(token)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("请求参数不合法"));
}
@Test void unauthenticatedRequestReturnsControlledChineseJson() throws Exception {
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message").value("未登录或令牌无效"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
}
@Test void requestWithoutPermissionReturnsControlledChineseJson() throws Exception {
    String token = jwtService.createToken(TENANT_ID, "audit-reader", "审计只读用户", List.of("agent:read"));
    mockMvc.perform(get("/api/audit-events").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("没有权限执行该操作"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
}
@Test void appendRedactsSensitiveCredentialsBeforePersistingAuditMessage() {
    UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    CapturingAuditEventRepository repository = new CapturingAuditEventRepository();
    new AuditAppender(repository, new SensitiveDataRedactor()).append(tenantId, "auditor", "CONFIG_UPDATED",
        "CONFIG", "runtime", "SUCCEEDED", "password=audit-test-password apiKey=audit-test-api-key "
            + "Bearer audit.test.token jdbc:postgresql://audit_user:audit_password@localhost:5432/cm_agent");
    assertThat(repository.onlyEvent().message()).contains("<已脱敏>")
        .doesNotContain("audit-test-password", "audit-test-api-key", "audit.test.token", "audit_user:audit_password");
}
private static final class CapturingAuditEventRepository implements AuditEventRepository {
    private final List<AuditEvent> events = new ArrayList<>();
    @Override public void append(AuditEvent event) { events.add(event); }
    @Override public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
        return events.stream().filter(event -> event.tenantId().equals(tenantId)).limit(limit).toList();
    }
    private AuditEvent onlyEvent() {
        assertThat(events).hasSize(1);
        return events.getFirst();
    }
}
```

- [ ] **步骤 2：确认失败**

运行：Rocky TDD 同步后执行：
```powershell
ssh rocky 'set -euo pipefail; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-server -am -Dtest=JwtSecurityConfigurationTest,ApplicationProfileConfigurationTest,SensitiveDataRedactorTest,ApiExceptionHandlerTest test'
```
预期：初始 Task 5 实现前编译失败，找不到错误响应与脱敏器；reviewer 修复红测使用以下命令，初始实现必须因 `SecurityErrorResponseTest` 的空 `Content-Type` 401 失败：

```powershell
ssh rocky 'set -euo pipefail; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=SecurityErrorResponseTest,AuditAppenderTest test'
```

- [ ] **步骤 3：写最小实现**

```java
public record ApiErrorResponse(ApiErrorCode code, String message, Instant timestamp) {
    public ApiErrorResponse {
        Objects.requireNonNull(code, "code 不能为空"); Objects.requireNonNull(timestamp, "timestamp 不能为空");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message 不能为空");
    }
}
```

`ApiErrorCode` 增加 `AUDIT_UNAVAILABLE`、`PERSISTENCE_UNAVAILABLE`、`INTERNAL_ERROR`。删除 `JwtSecurityConfiguration` 中 `LOCAL_FALLBACK_SECRET`、`allowFallback` 与 `allow-dev-jwt-fallback` 参数；缺少密钥固定抛出 `IllegalStateException("未配置 cm-agent.security.jwt-secret")`。删除 `application.yml` 中默认 `local` profile 选择和 JWT 回退映射。异常映射固定为：校验 `400/VALIDATION_FAILED/请求参数不合法`，审计异常 `503/AUDIT_UNAVAILABLE/审计服务暂不可用`，数据异常 `503/PERSISTENCE_UNAVAILABLE/数据服务暂不可用`，其他异常 `500/INTERNAL_ERROR/服务内部错误`。

`SecurityConfig` 通过受控 JSON 写入器接入 `AuthenticationEntryPoint` 与 `AccessDeniedHandler`，忽略 Spring Security 异常详情：

```java
.exceptionHandling(exception -> exception
    .authenticationEntryPoint(authenticationEntryPoint())
    .accessDeniedHandler(accessDeniedHandler()))

private void writeError(HttpServletResponse response, HttpStatus status,
                        ApiErrorCode code, String message) throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getOutputStream(),
        new ApiErrorResponse(code, message, Instant.now()));
}
```

`ConsoleSmokeTest` 的 Supabase profile 构造器调用补传 `new ObjectMapper()`；`AuditAppenderTest` 使用仅保存事件的 `CapturingAuditEventRepository`，只断言成功持久化消息的脱敏结果，不测试也不改变 `AuditAppender` 的异常 catch 路径。

- [ ] **步骤 4：确认通过**

运行：Rocky TDD 同步后执行：
```powershell
ssh rocky 'set -euo pipefail; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase2-current:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-server -am test'
```
预期：退出码 `0`，`SecurityErrorResponseTest` 为 `2 tests, 0 failures, 0 errors`，`AuditAppenderTest` 为 `1 test, 0 failures, 0 errors`，完整服务端回归无失败或错误。

- [ ] **步骤 5：提交**

```powershell
git add cm-agent-server/src/main/java/com/cmagent/server/security/SecurityConfig.java cm-agent-server/src/test/java/com/cmagent/server/security/SecurityErrorResponseTest.java cm-agent-server/src/test/java/com/cmagent/server/audit/AuditAppenderTest.java cm-agent-server/src/test/java/com/cmagent/server/web/ConsoleSmokeTest.java docs/superpowers/plans/2026-07-14-phase-2-production-runtime.md
git commit -m "fix: 统一安全错误响应并验证审计脱敏"
```

### Task 6：中文路线图、生产文档与整体审查

**文件：**
- 新建：`docs/roadmap.md`
- 修改：`README.md`、`docs/configuration.md`、`docs/deployment.md`、`docs/operations.md`、`docs/release-notes.md`

- [ ] **步骤 1：写失败文档检查**

```powershell
rg -n "阶段 2|阶段 3|阶段 4|阶段 5" docs/roadmap.md
rg -n "V2__add_runtime_query_indexes|审计服务暂不可用|ssh rocky|不再默认激活" README.md docs/configuration.md docs/deployment.md docs/operations.md docs/release-notes.md
```
预期：`docs/roadmap.md` 不存在，第二条缺少阶段 2 行为说明。

- [ ] **步骤 2：写最小文档实现**

新增路线图列出阶段 2-5；README 链接路线图；配置文档删除默认 local 与 JWT 回退；部署、运维、发布文档说明 V2 索引、Run/ToolCall/Audit JDBC、审计 `503`、无自动删除、Rocky VM 容器验证及阶段 3-5 未交付范围。所有示例仅使用占位符。

- [ ] **步骤 3：确认通过文档检查**

运行：步骤 1 的两条完整命令。预期：均有匹配，且 `rg -n "cmagent-local-dev-password-only|jdbc:postgresql://[^<]" docs README.md` 不返回生产文档中的可用凭据。

- [ ] **步骤 4：整体审查与提交**

```powershell
git diff master...HEAD --check
git diff master...HEAD -- cm-agent-core cm-agent-persistence cm-agent-server | rg -n "tenant_id|AuditPersistenceException|SensitiveDataRedactor|TransactionTemplate|InMemoryPlatformStore"
git add docs/roadmap.md README.md docs/configuration.md docs/deployment.md docs/operations.md docs/release-notes.md
git commit -m "docs: 更新阶段2生产运行说明"
```

预期：空白检查无输出；所有 JDBC 路径和安全边界可在差异中追溯。

## 计划自查

- 任务 1-2 覆盖领域、Repository、JDBC、V2 与 PostgreSQL/MySQL 迁移；任务 3-4 覆盖内存生产依赖移除、严格审计、事务、运行查询与租户隔离；任务 5 覆盖 JWT、profile、错误和脱敏；任务 6 覆盖中文路线图、生产文档和整体审查。
- 所有任务都有精确路径、失败测试、失败命令、最小实现、通过命令和中文提交命令。
- 类型名称在任务间保持一致：`RunRecord`、`RunToolCall`、`RunRepository`、`ToolCallRepository`、`AuditPersistenceException`、`SensitiveDataRedactor`。
- 执行顺序为任务 1、任务 2、任务 5、任务 3、任务 4、任务 6；任务 5 先提供任务 3 需要的 `SensitiveDataRedactor`。执行结束后必须在 Rocky VM 运行完整 `mvn -q test`、`mvn -q "-DskipTests" package`、`git diff --check`，再推送并创建面向 `master` 的非 Draft PR。
