# 技能版本发布、依赖预检与指定版本试运行实施计划

> **面向执行代理：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实施；所有步骤使用复选框跟踪。

> **执行结果（2026-09-22）：** T1～T10 已按顺序提交，T11 的文档与本地回归完成。本文保留原始复选框作为执行设计记录；实际提交、已通过验证与尚未完成的 Rocky/浏览器验证以[进度账本](../progress/2026-09-22-skill-release-validation-ledger.md)为准。

**目标：** 在现有技能治理基础上交付候选版本、依赖预检、真实指定版本试运行、可审计发布/回滚，以及 Agent 跟随或固定版本的完整前后端闭环。

**架构：** `SkillVersion` 继续保存不可变内容，`SkillDefinition` 分离候选与发布指针，追加式 `SkillRelease` 表达发布事实。依赖声明随版本保存，环境映射和预检由 Server 治理；`TEST` Run 复用真实模型、工具授权、审批和快照链路，但不创建会话或正式绑定。

**技术栈：** Java 21、Spring Boot 3.5.0、AgentScope 2.0.2、JDBC/Flyway、PostgreSQL 16、MySQL 8.4、原生 HTML/CSS/JavaScript、JUnit 5、MockMvc、Node 内置测试。

**规格：** [已确认设计](../specs/2026-09-22-skill-release-validation-design.md)。执行者必须同时阅读原始 [Skill 支持设计](../specs/2026-09-20-skill-support-design.md)，本文只列本次增量。

## 全局约束

- Java 和 Maven 必须运行在 JDK 21；本需求不升级 AgentScope、MCP、Spring Boot 或数据库版本。
- 每个技能最多一个有效候选；版本正文、资源、依赖声明和摘要均不可变。
- 发布必须满足即时预检、全部跟随型 Agent 必需依赖可用、当前候选 `PASSED` 试运行和管理员显式确认。
- 回滚是重新发布历史版本；不删除、不改写发布记录或历史版本。
- Agent 默认 `FOLLOW_PUBLISHED`，`PINNED` 只能指向曾发布版本；已有 Run 永远使用原技能及依赖映射快照。
- 必需依赖失败阻止试运行、发布和正式绑定；可选依赖只告警；任何声明或映射都不能自动增加 ToolGrant。
- `TEST` Run 使用真实模型和工具治理，可能产生外部副作用；不创建会话、消息或正式绑定。
- 所有 Repository 查询和写入显式携带 tenant；tenant、Agent、技能和工具归属只能来自认证主体或已校验服务端对象。
- 错误响应必须包含中文安全原因；需要定位的失败包含稳定 `ApiErrorCode` 和 `errorId`，且编号与诊断日志一致。
- Java record 的每个组件、公开 API/SPI、安全边界、事务、异常转换和第三方隐式行为按 `AGENTS.md` 补齐中文 JavaDoc。
- PostgreSQL/MySQL 新表和每个字段都有非空中文原生注释；不修改 V12，新增 V13 方言迁移。
- Docker、Flyway、JDBC 和 Testcontainers 验证仅在 `ssh rocky` 的 `maven:3.9.9-eclipse-temurin-21` 容器执行。
- 保留用户现有 `application*.yml`、`.codex/`、`.impeccable/critique/`、`.workbuddy/` 变更；每次只暂存任务列出的路径，禁止 `git add -A`。

## 评审重点

1. **候选并发与重复内容：** 两个管理员基于同一指针上传或发布时只能一个成功；相同候选幂等返回，不能跳号或覆盖。由 T1、T3、T5、T7 的 CAS 测试覆盖。
2. **映射修订导致试运行过期：** 映射变化后旧 `PASSED` 保留历史但不能发布；不相关的前端缓存也不能绕过服务端即时预检。由 T6、T7 覆盖。
3. **TEST Run 等待审批与断线恢复：** 刷新或 SSE 断开不创建第二个 Run，审批恢复继续同一快照，且不产生会话消息。由 T8、T10 覆盖。
4. **旧数据与旧快照：** V12 数据生成 `BASELINE` 发布，绑定转为跟随，format 1 快照仍可恢复；无效当前版本使迁移失败。由 T4、T9 覆盖。
5. **撤销和跨租户输入：** 映射快照不能绕过当前工具启用、ToolGrant、技能停用和租户边界；客户端提交的 tenant/toolId 不能替代服务端解析。由 T6、T8、T9 覆盖。

## 文件与接口总图

| 模块 | 文件或类型 | 职责 |
| --- | --- | --- |
| Core domain | `SkillDefinition`、`SkillVersion`、`SkillDependency`、`SkillRelease` | 候选/发布指针、不可变版本依赖和发布事实 |
| Core domain | `AgentSkillBinding`、`SkillBindingMode`、`SkillSnapshotRef`、`SkillDependencyResolution` | 跟随/固定取版和 Run 内依赖映射快照 |
| Core domain | `SkillRuntimeBundle`、`AgentRunRequest` | 将固定版本和按技能分组的已解析依赖一起交给可替换 Runtime |
| Core domain | `SkillPreflightCheck/Item`、`SkillTrial`、相关 enum | 预检与试运行的可持久化结果 |
| Core domain | `RunRecord`、`RunKind` | 区分 `NORMAL` 和 `TEST`，保持同一运行状态机 |
| Core repository | 新增五类 Repository 并扩展既有技能/运行 Repository | 租户隔离、锁定、CAS、历史分页 |
| Server service | `SkillCandidateService`、`SkillPreflightService`、`SkillReleaseService` | 候选、映射/预检、发布/回滚原子编排 |
| Server runtime | `SkillTrialService`、`SkillRuntimeService`、`RunExecutionService` | TEST Run、临时注入、取版与恢复 |
| Persistence/store | V13、`JdbcSkill*Repository`、`InMemorySkillStore` | 双数据库和 memory 等价合同 |
| Web | `SkillController`、`SkillReleaseController`、`SkillTrialController`、`AgentSkillController` | 认证、权限、请求校验和稳定响应 |
| Adapter | `AgentScopeSkillSession`、`AgentScopeSkillLoadBridge` | 固定版本与解析依赖上下文，不改变工具治理入口 |
| Console | `skills.html`、`skills.js`、`multipage.css`、公共 API 客户端 | 连续发布工作区、版本/依赖/试运行/回滚 |

### 契约字典

下列签名在任务间保持一致；实现时每个 record 独立文件并补齐中文 `@param` JavaDoc 和构造不变量。

```java
enum SkillBindingMode { FOLLOW_PUBLISHED, PINNED }
enum SkillReleaseAction { BASELINE, PUBLISH, ROLLBACK }
enum SkillPreflightScope { STRUCTURAL, AGENT, PUBLISH, BINDING, MAPPING_CHANGE }
enum SkillPreflightStatus { PASSED, PASSED_WITH_WARNINGS, FAILED }
enum SkillPreflightItemStatus { READY, OPTIONAL_WARNING, MAPPING_MISSING, TOOL_UNAVAILABLE, GRANT_MISSING }
enum SkillTrialStatus { RUNNING, WAITING_APPROVAL, PASSED, NOT_TRIGGERED, FAILED }
enum RunKind { NORMAL, TEST }
enum SkillSnapshotOrigin { BINDING, TRIAL }
enum ToolApprovalScope { CONVERSATION, RUN }

record SkillDefinition(UUID id, UUID tenantId, String name,
        UUID candidateVersionId, UUID publishedVersionId,
        boolean enabled, long accessEpoch, long dependencyMappingRevision,
        String createdBy, String updatedBy,
        Instant createdAt, Instant updatedAt) {}

record SkillDependency(UUID tenantId, UUID skillId, UUID versionId,
        String logicalKey, boolean required, String description, int position) {}

record SkillDependencyMapping(UUID tenantId, UUID skillId, String logicalKey,
        UUID toolId, String updatedBy, Instant updatedAt) {}

record SkillRelease(UUID id, UUID tenantId, UUID skillId, long releaseNo,
        SkillReleaseAction action, UUID previousVersionId, UUID versionId,
        UUID preflightId, UUID trialRunId, String createdBy, Instant createdAt) {}

record AgentSkillBinding(UUID id, UUID tenantId, UUID agentId, UUID skillId,
        SkillBindingMode mode, UUID pinnedVersionId, long revision,
        String boundBy, String updatedBy, Instant createdAt, Instant updatedAt) {}

record SkillDependencyResolution(String logicalKey, UUID toolId, boolean required) {}
record SkillSnapshotRef(UUID skillId, UUID versionId, SkillSnapshotOrigin origin,
        UUID authorizationId, long accessEpoch,
        List<SkillDependencyResolution> dependencies) {}

record RunRecord(UUID id, UUID tenantId, UUID agentId, String principalId,
        RunKind kind, RunStatus status, String input, String output,
        String errorMessage, Instant startedAt, Instant finishedAt) {}

record SkillPreflightCheck(UUID id, UUID tenantId, UUID skillId, UUID versionId,
        long mappingRevision, SkillPreflightScope scope, UUID agentId,
        SkillPreflightStatus status, String createdBy, Instant createdAt) {}

record SkillPreflightItem(UUID checkId, UUID agentId, String logicalKey,
        boolean required, UUID toolId, SkillPreflightItemStatus status,
        ApiErrorCode errorCode, String message, String errorId) {}

record SkillTrial(UUID runId, UUID tenantId, UUID skillId, UUID versionId,
        UUID agentId, long mappingRevision, SkillTrialStatus status,
        boolean qualifiesRelease, String createdBy,
        Instant createdAt, Instant updatedAt) {}
```

`SkillSnapshotRef.authorizationId` 在 `BINDING` 来源时是绑定 ID，在 `TRIAL` 来源时是试运行 Run ID。format 1 快照读取时转换为 `BINDING + 原 bindingId + 空依赖映射`；新快照使用 format 2。

### 任务顺序

| 任务 | 依赖 | 独立可评审结果 |
| --- | --- | --- |
| T1 | 无 | Core 状态、错误与 Repository 契约 |
| T2 | T1 | 技能包依赖解析与摘要 |
| T3 | T1 | memory 工作单元与新增 Repository |
| T4 | T1～T3 | V13 双库迁移和 JDBC 合同 |
| T5 | T2～T4 | 候选、版本历史和差异 API |
| T6 | T2～T5 | 映射和两层预检 API |
| T7 | T5、T6 | 发布、回滚和绑定策略 |
| T8 | T6、T7 | TEST Run、临时注入和审批恢复 |
| T9 | T1、T6、T8 | format 2 快照与 AgentScope 依赖上下文 |
| T10 | T5～T9 | v2 连续技能发布工作区 |
| T11 | T1～T10 | 双库、端到端回归和正式文档收口 |

## 验证环境

本地开始每个 Java 任务前执行：

```powershell
$env:JAVA_HOME = 'F:\java\temurin21\jdk-21.0.11+10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -v
```

预期两条命令均显示 Java 21。每项任务先提交能证明缺失行为的失败测试，再写最小实现，重复同一命令得到通过结果。数据库任务先形成只含本需求路径的提交，通过 `git bundle` 传到 `ssh rocky`，确认远程 HEAD 与本地提交一致、工作区干净、Docker 可用，再在 `maven:3.9.9-eclipse-temurin-21` 容器运行指定 Testcontainers 测试；不得使用本机 Docker Desktop。

---

## Task 1：定义发布、依赖、试运行与运行类型契约

**文件：**

- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillBindingMode.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillReleaseAction.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillDependency.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillDependencyMapping.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillRelease.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightScope.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightStatus.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightItemStatus.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightCheck.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightItem.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillTrialStatus.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillTrial.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/RunKind.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillSnapshotOrigin.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/ToolApprovalScope.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillDependencyResolution.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillDefinition.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentSkillBinding.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillSnapshotRef.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/RunSkillSnapshot.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/RunRecord.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillRuntimeBundle.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/domain/ToolApprovalRequest.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/repository/SkillDependencyRepository.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/repository/SkillDependencyMappingRepository.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/repository/SkillReleaseRepository.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/repository/SkillPreflightRepository.java`
- 新增 `cm-agent-core/src/main/java/com/cmagent/core/repository/SkillTrialRepository.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/repository/SkillDefinitionRepository.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/repository/SkillVersionRepository.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/repository/AgentSkillBindingRepository.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/repository/RunRepository.java`
- 修改 `cm-agent-core/src/main/java/com/cmagent/core/repository/ToolApprovalRepository.java`
- 修改 `cm-agent-api/src/main/java/com/cmagent/api/ApiErrorCode.java`
- 修改 `cm-agent-core/src/test/java/com/cmagent/core/domain/SkillDomainTest.java`
- 修改 `cm-agent-core/src/test/java/com/cmagent/core/domain/RunRecordTest.java`
- 新增 `cm-agent-core/src/test/java/com/cmagent/core/domain/ToolApprovalDomainTest.java`
- 修改 `cm-agent-core/src/test/java/com/cmagent/core/domain/AgentRunRequestTest.java`

**接口：**

- 产出契约字典中的领域类型；`SkillRuntimeBundle` 增加 `Map<UUID, List<SkillDependencyResolution>> dependencyResolutions`；`AgentRunRequest` 在既有 `List<SkillVersionView> skills` 后追加同类型 `skillDependencies`。保留原七、八、九参数构造器并默认 `Map.of()`，避免 Java 泛型擦除造成不兼容重载。
- `SkillDefinitionRepository.updatePointers(SkillDefinition next, UUID expectedCandidateId, UUID expectedPublishedId)` 提供候选/发布双指针 CAS。
- `SkillDefinitionRepository.updateDependencyMappingRevision(SkillDefinition next, long expectedRevision)` 在映射集合变化时递增技能级修订，预检和试运行只保存这一权威修订。
- `SkillVersionRepository.list(UUID tenantId, UUID skillId)` 按 `versionNo DESC` 返回不可变历史。
- `AgentSkillBindingRepository.updateStrategy(AgentSkillBinding next, long expectedRevision)` 提供绑定策略 CAS。
- `RunRepository.listByTenantAndAgent(...)` 保持正式历史语义并只返回 NORMAL；新增 `listByTenantAndAgentAndKind(..., RunKind kind, RunPageRequest page)` 供受控 TEST 查询，不能让试运行混入正式运行统计。
- 新 Repository 分别提供 tenant 范围内的 `insert/find/list/lock`，预检以一次 `save(check, items)` 原子保存汇总与明细。
- `ToolApprovalRequest` 增加 `ToolApprovalScope scope`：`CONVERSATION` 必须携带 conversationId，`RUN` 必须由 `TEST` Run 创建且 conversationId 为空；`ToolApprovalRepository` 增加以 `tenantId + agentId + runId + approvalId` 为完整边界的试运行审批查询和条件更新，不放宽正式会话接口。

- [ ] **Step 1：写领域红灯测试。** 在 `SkillDomainTest` 增加：两个版本指针都空、负映射修订、`PINNED` 无版本、`FOLLOW_PUBLISHED` 携带固定版本、重复依赖 key、重复依赖解析、format 2 试运行授权来源，以及 `PUBLISH/ROLLBACK/BASELINE` 对 preflightId/trialRunId 的不同约束；跨技能指针归属留给 T5 的服务测试。在 `RunRecordTest` 断言旧 `create(...)` 默认 `NORMAL`，显式工厂可创建 `TEST`；在 `ToolApprovalDomainTest` 断言 `CONVERSATION` 必须有 conversationId，而 `RUN` 审批只能不带 conversationId。

```java
assertThatThrownBy(() -> new AgentSkillBinding(id, tenantId, agentId, skillId,
        SkillBindingMode.PINNED, null, 0, "admin", "admin", now, now))
        .isInstanceOf(NullPointerException.class);
RunRecord trial = RunRecord.create(runId, tenantId, agentId, "admin", RunKind.TEST, "验证", now);
assertThat(trial.kind()).isEqualTo(RunKind.TEST);
```

- [ ] **Step 2：运行红灯。**

```powershell
mvn -q -pl cm-agent-core -am "-Dtest=SkillDomainTest,RunRecordTest,ToolApprovalDomainTest,AgentRunRequestTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期测试编译失败，错误明确指向新增类型、构造器和工厂缺失。

- [ ] **Step 3：实现最小领域契约。** 按契约字典创建类型；集合使用 `List.copyOf`，逻辑 key 使用 `[a-z0-9](?:[a-z0-9]|-(?!-)){0,62}[a-z0-9]|[a-z0-9]`；可空字段只允许规格声明的场景。旧 `RunRecord.create(...)` 保留并委托 `RunKind.NORMAL` 重载，避免无关调用点一次性破坏。

- [ ] **Step 4：增加 Repository 编译契约和错误码。** 新增 `SKILL_CANDIDATE_CONFLICT`、`SKILL_NOT_PUBLISHED`、`SKILL_VERSION_NOT_RELEASED`、`SKILL_DEPENDENCY_INVALID`、`SKILL_DEPENDENCY_UNMAPPED`、`SKILL_DEPENDENCY_UNAVAILABLE`、`SKILL_AGENT_GRANT_MISSING`、`SKILL_PREFLIGHT_STALE`、`SKILL_TRIAL_REQUIRED`、`SKILL_TRIAL_NOT_TRIGGERED`、`SKILL_RELEASE_FAILED`；只定义稳定枚举，不在 Core 绑定 HTTP 状态。

- [ ] **Step 5：运行 Core 测试。** 重复 Step 2，随后执行：

```powershell
mvn -q -pl cm-agent-core -am test
```

预期全部通过。

- [ ] **Step 6：提交。**

```powershell
$task1Paths = @(
  'cm-agent-api/src/main/java/com/cmagent/api/ApiErrorCode.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillBindingMode.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillReleaseAction.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillDependency.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillDependencyMapping.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillRelease.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightScope.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightStatus.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightItemStatus.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightCheck.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillPreflightItem.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillTrialStatus.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillTrial.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/RunKind.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillSnapshotOrigin.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/ToolApprovalScope.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillDependencyResolution.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillDefinition.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/AgentSkillBinding.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillSnapshotRef.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/RunSkillSnapshot.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/RunRecord.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/SkillRuntimeBundle.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java',
  'cm-agent-core/src/main/java/com/cmagent/core/domain/ToolApprovalRequest.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/SkillDependencyRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/SkillDependencyMappingRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/SkillReleaseRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/SkillPreflightRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/SkillTrialRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/SkillDefinitionRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/SkillVersionRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/AgentSkillBindingRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/RunRepository.java',
  'cm-agent-core/src/main/java/com/cmagent/core/repository/ToolApprovalRepository.java',
  'cm-agent-core/src/test/java/com/cmagent/core/domain/SkillDomainTest.java',
  'cm-agent-core/src/test/java/com/cmagent/core/domain/RunRecordTest.java',
  'cm-agent-core/src/test/java/com/cmagent/core/domain/ToolApprovalDomainTest.java',
  'cm-agent-core/src/test/java/com/cmagent/core/domain/AgentRunRequestTest.java'
)
git add -- $task1Paths
git diff --cached --check
git commit -m "feat: 定义技能发布与试运行契约"
```

## Task 2：解析不可变工具依赖并纳入版本摘要

**文件：**

- 修改 `cm-agent-server/src/main/java/com/cmagent/server/service/ParsedSkillPackage.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillPackageParser.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/service/SkillPackageParserTest.java`

**接口：**

- `ParsedSkillPackage` 增加 `List<ParsedSkillDependency> dependencies`。
- 新增包内 record `ParsedSkillDependency(String logicalKey, boolean required, String description, int position)`，服务层把它转换为 Core `SkillDependency`。
- `dependencies.tools` 只接受对象数组；依赖按声明顺序保存并参与 SHA-256 规范摘要。

- [ ] **Step 1：写解析红灯测试。** 覆盖必需默认值、可选依赖、重复/非法 key、非数组 `tools`、未知依赖字段、控制字符描述、依赖顺序和 required 变化导致摘要变化，以及没有 `dependencies` 的旧包返回空列表。

```java
ParsedSkillPackage parsed = parser.parse(zip("""
        ---
        name: order-analysis
        description: 订单分析
        dependencies:
          tools:
            - key: order-query
              description: 查询订单
        ---
        按需查询订单。
        """), limits);
assertThat(parsed.dependencies()).containsExactly(
        new ParsedSkillDependency("order-query", true, "查询订单", 0));
```

- [ ] **Step 2：运行红灯。**

```powershell
mvn -q -pl cm-agent-server -am "-Dtest=SkillPackageParserTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期失败于依赖解析结果和校验缺失。

- [ ] **Step 3：实现安全解析。** 从已安全冻结的 frontmatter 中移除 `dependencies` 后单独解析；仅允许 `tools/key/required/description`，拒绝额外执行语义。摘要按位置依次加入 key、required 和 description；正文元数据响应不重复暴露依赖内部结构。

- [ ] **Step 4：重复 Step 2。** 预期新增测试和既有 ZIP/YAML 安全测试全部通过。

- [ ] **Step 5：提交。**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/service/ParsedSkillPackage.java cm-agent-server/src/main/java/com/cmagent/server/service/SkillPackageParser.java cm-agent-server/src/test/java/com/cmagent/server/service/SkillPackageParserTest.java
git diff --cached --check
git commit -m "feat: 解析技能工具依赖声明"
```

## Task 3：扩展 memory 工作单元与 Repository

**文件：**

- 修改 `cm-agent-server/src/main/java/com/cmagent/server/store/InMemorySkillStore.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/store/InMemorySkillStoreTest.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/config/ServerRepositoryConfigurationTest.java`

**接口：** 实现 T1 新增 Repository；全部视图共享同一 `InMemorySkillStore` 和现有 `SkillUnitOfWork` 暂存/发布边界。

- [ ] **Step 1：写 memory 红灯测试。** 覆盖候选/发布双 CAS、同技能 releaseNo 唯一、映射修订 CAS、绑定策略修订 CAS、一次预检汇总/明细原子保存、试运行 runId 唯一，以及工作单元抛错后所有新增集合均不发布。

```java
store.execute(() -> {
    store.releases().insert(release);
    store.preflights().save(check, items);
    throw new IllegalStateException("回滚");
});
assertThat(store.releases().list(tenantId, skillId)).isEmpty();
assertThat(store.preflights().find(tenantId, check.id())).isEmpty();
```

- [ ] **Step 2：运行红灯。**

```powershell
mvn -q -pl cm-agent-server -am "-Dtest=InMemorySkillStoreTest,ServerRepositoryConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期失败于新 Repository 视图和 CAS 方法缺失。

- [ ] **Step 3：实现存储状态。** 将新增 Map/List 纳入统一状态快照和复制构造；所有写入在同一可重入锁/`ThreadLocal` 工作单元内执行。版本、发布、预检和试运行为追加式；映射和绑定更新核对 expected revision。

```java
boolean updatePointers(SkillDefinition next, UUID expectedCandidateId, UUID expectedPublishedId) {
    SkillDefinition current = requireDefinition(next.tenantId(), next.id());
    if (!Objects.equals(current.candidateVersionId(), expectedCandidateId)
            || !Objects.equals(current.publishedVersionId(), expectedPublishedId)) {
        return false;
    }
    writableState().definitions.put(next.id(), next);
    return true;
}
```

- [ ] **Step 4：装配 Bean。** `ServerRepositoryConfiguration` 只暴露共享存储的 Repository 视图，不创建彼此独立的内存容器。

- [ ] **Step 5：重复 Step 2。** 预期通过，并确认既有技能存储测试没有回归。

- [ ] **Step 6：提交。**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/store/InMemorySkillStore.java cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java cm-agent-server/src/test/java/com/cmagent/server/store/InMemorySkillStoreTest.java cm-agent-server/src/test/java/com/cmagent/server/config/ServerRepositoryConfigurationTest.java
git diff --cached --check
git commit -m "feat: 扩展技能发布内存工作单元"
```

## Task 4：新增 V13 双数据库迁移与 JDBC 实现

**文件：**

- 新增 `cm-agent-persistence/src/main/resources/db/migration/postgresql/V13__add_skill_release_validation.sql`
- 新增 `cm-agent-persistence/src/main/resources/db/migration/mysql/V13__add_skill_release_validation.sql`
- 新增 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillDependencyRepository.java`
- 新增 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillDependencyMappingRepository.java`
- 新增 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillReleaseRepository.java`
- 新增 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillPreflightRepository.java`
- 新增 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillTrialRepository.java`
- 修改 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillDefinitionRepository.java`
- 修改 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillVersionRepository.java`
- 修改 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcAgentSkillBindingRepository.java`
- 修改 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRunRepository.java`
- 修改 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRunSkillSnapshotRepository.java`
- 修改 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcToolApprovalRepository.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`
- 修改 `cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java`
- 修改 `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcSkillRepositoriesTest.java`
- 修改 `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcRunRepositoryTest.java`
- 新增 `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcToolApprovalRepositoryTest.java`

**接口：** JDBC 实现与 T1/T3 完全相同；发布号、映射修订和绑定修订使用数据库条件更新或唯一约束，不能先查后写后假定成功。

- [ ] **Step 1：写 V13 迁移红灯测试。** 从 V12 基线插入一个已启用技能、两个历史版本、一个绑定、一个 format 1 快照和 NORMAL Run，再迁移到最新版本并断言：

```java
assertThat(column("skill_definitions", "published_version_id").comment()).isNotBlank();
assertThat(queryLong("SELECT COUNT(*) FROM skill_releases WHERE action = 'BASELINE'")).isEqualTo(1);
assertThat(queryString("SELECT resolution_mode FROM agent_skill_bindings WHERE id = ?", bindingId))
        .isEqualTo("FOLLOW_PUBLISHED");
assertThat(queryString("SELECT run_kind FROM runs WHERE id = ?", runId)).isEqualTo("NORMAL");
```

同时构造 `current_version_id` 指向不存在版本的 V12 数据，断言 V13 迁移失败；逐表逐字段检查 PostgreSQL/MySQL 中文注释非空。V13 还增加非空 `approval_scope` 并把 `tool_approval_requests.conversation_id` 改为可空且保留外键，使 TEST Run 可以创建 `RUN` 范围审批；既有行回填 `CONVERSATION` 并保持 conversationId 非空。

- [ ] **Step 2：写 JDBC Repository 红灯测试。** 覆盖双指针 CAS、发布号唯一、映射/绑定修订冲突、预检汇总明细事务、TEST Run round-trip、默认运行历史排除 TEST、按 kind 查询试运行、format 1 读取兼容与 format 2 依赖映射 round-trip。

- [ ] **Step 3：在 Rocky 执行红灯。** 将当前提交打 bundle 到唯一 `/tmp/cm-agent-skill-release-<commit>` 工作区，核对 HEAD 后执行：

```sh
docker run --rm \
  -v "$workspace:$workspace" -w "$workspace" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9.9-eclipse-temurin-21 \
mvn -B -pl cm-agent-persistence -am \
  -Dtest=MigrationTest,JdbcSkillRepositoriesTest,JdbcRunRepositoryTest,JdbcToolApprovalRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期测试失败于 V13、字段和新 Repository 缺失；记录 PostgreSQL 16 与 MySQL 8.4 的实际失败证据。

- [ ] **Step 4：实现 V13。** 两个方言文件完成以下顺序：创建新表；增加 nullable 新指针；校验旧 `current_version_id` 归属；回填 `published_version_id`；以技能 ID 作为跨表唯一且确定性的基线 release ID，生成 `BASELINE`；增加绑定策略/修订、Run kind；最后删除旧 `current_version_id`。对历史 `metadata_json.dependencies.tools` 仅迁移符合新结构的条目；无该结构写空依赖，结构存在但不合法时使迁移失败并要求先修复数据，不能猜测工具映射。

```sql
INSERT INTO skill_releases (
    id, tenant_id, skill_id, release_no, action, version_id, created_by, created_at
)
SELECT id, tenant_id, id, 1, 'BASELINE', current_version_id, updated_by, updated_at
FROM skill_definitions;
```

两个方言分别使用本数据库支持的 JSON 展开和列修改语法，不能把同一 SQL 同时扫描进两种数据库。

- [ ] **Step 5：实现 JDBC 和装配。** SQL 的 WHERE 同时包含 tenant 和资源 ID；锁定方法使用 `FOR UPDATE`；`save(check, items)` 复用当前 `TransactionTemplate`。快照 JSON reader 按 `format_version` 分支：1 转换为 `BINDING` 来源和空依赖，2 读取完整新结构。

- [ ] **Step 6：再次在 Rocky 执行 Step 3。** 预期 PostgreSQL 16 与 MySQL 8.4 全部通过，且 Maven 容器使用 Java 21。

- [ ] **Step 7：提交。**

```powershell
$task4Paths = @(
  'cm-agent-persistence/src/main/resources/db/migration/postgresql/V13__add_skill_release_validation.sql',
  'cm-agent-persistence/src/main/resources/db/migration/mysql/V13__add_skill_release_validation.sql',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillDependencyRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillDependencyMappingRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillReleaseRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillPreflightRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillTrialRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillDefinitionRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcSkillVersionRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcAgentSkillBindingRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRunRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRunSkillSnapshotRepository.java',
  'cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcToolApprovalRepository.java',
  'cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java',
  'cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java',
  'cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcSkillRepositoriesTest.java',
  'cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcRunRepositoryTest.java',
  'cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcToolApprovalRepositoryTest.java'
)
git add -- $task4Paths
git diff --cached --check
git commit -m "feat: 持久化技能发布与预检状态"
```

## Task 5：实现候选版本、版本历史与差异查询

**文件：**

- 新增 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillCandidateService.java`
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillVersionDiffService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillManagementService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillQueryService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillController.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/web/SkillControllerTest.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/web/SkillManagementJdbcPersistenceTest.java`

**接口：**

```java
SkillVersionView createCandidate(PrincipalRef principal, ParsedSkillPackage parsed);
SkillVersionView replaceCandidate(PrincipalRef principal, UUID skillId,
        UUID expectedCandidateId, UUID expectedPublishedId, ParsedSkillPackage parsed);
List<SkillResponses.VersionSummary> versions(PrincipalRef principal, UUID skillId);
SkillResponses.VersionDiff diff(PrincipalRef principal, UUID skillId,
        UUID fromVersionId, UUID toVersionId);
```

`VersionDiff` 返回指令正文和每个资源的 `ADDED/REMOVED/CHANGED/UNCHANGED` 摘要与两侧安全文本，由控制台做并排展示；服务端不引入新的 diff 依赖。

- [ ] **Step 1：写候选管理红灯。** MockMvc 覆盖首次上传只生成 candidate、没有 published 时不能启用、替换候选不改变 published、相同候选幂等、与 published 相同且无候选不新增版本、预期指针冲突返回 409/`SKILL_CANDIDATE_CONFLICT`。

```java
mockMvc.perform(post("/api/skills/{id}/versions", skillId)
        .param("expectedPublishedVersionId", publishedId.toString())
        .file(candidateZip).with(jwtAdmin()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.summary.candidateVersionId").isNotEmpty())
        .andExpect(jsonPath("$.summary.publishedVersionId").value(publishedId.toString()));
```

- [ ] **Step 2：写历史与差异红灯。** 断言跨租户版本返回 404；列表正确推导 `CANDIDATE/CURRENT_PUBLISHED/PREVIOUSLY_PUBLISHED/UNPUBLISHED_HISTORY`；差异不泄露其他版本或内部路径。

- [ ] **Step 3：运行红灯。**

```powershell
mvn -q -pl cm-agent-server -am "-Dtest=SkillControllerTest,SkillManagementJdbcPersistenceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

本地只要求非 Testcontainers 用例红灯；JDBC 用例在 Rocky 执行。

- [ ] **Step 4：实现候选工作单元。** 保存版本、资源和依赖后以双指针 CAS 更新定义，并在同一事务追加审计；CAS 失败整体回滚。初次创建定义只持有 candidate，`setEnabled(true)` 在 published 为空时返回 `SKILL_NOT_PUBLISHED`。

```java
return workUnit.execute(() -> {
    SkillDefinition current = definitions.lock(principal.tenantId(), skillId);
    SkillVersionView candidate = persistImmutableVersion(principal, current, parsed);
    SkillDefinition next = current.withCandidate(candidate.version().id(), principal.principalId(), clock.instant());
    if (!definitions.updatePointers(next, expectedCandidateId, expectedPublishedId)) {
        throw conflict(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "候选版本已变化，请刷新后重试");
    }
    appendAudit(principal, "SKILL_CANDIDATE_CREATE", skillId, "候选版本已创建");
    return candidate;
});
```

- [ ] **Step 5：实现历史和差异。** 发布状态只能由当前指针与 release 历史推导；版本正文和资源查询沿用 `skill:read` 与 tenant 边界。资源差异按规范路径排序并限于已保存文本。

- [ ] **Step 6：运行测试。** 重复 Step 3；JDBC 用例在 Rocky 的 Java 21 Maven 容器执行。预期全部通过。

- [ ] **Step 7：提交。**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/service/SkillCandidateService.java cm-agent-server/src/main/java/com/cmagent/server/service/SkillVersionDiffService.java cm-agent-server/src/main/java/com/cmagent/server/service/SkillManagementService.java cm-agent-server/src/main/java/com/cmagent/server/service/SkillQueryService.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillController.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java cm-agent-server/src/test/java/com/cmagent/server/web/SkillControllerTest.java cm-agent-server/src/test/java/com/cmagent/server/web/SkillManagementJdbcPersistenceTest.java
git diff --cached --check
git commit -m "feat: 增加技能候选与版本历史"
```

## Task 6：实现依赖映射与两层预检

**文件：**

- 新增 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillPreflightService.java`
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillDependencyMappingService.java`
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillDependencyController.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolRuntimeReadiness.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillRequests.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java`
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/service/SkillPreflightServiceTest.java`
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/web/SkillDependencyControllerTest.java`

**接口：**

```java
SkillPreflightResult preflight(PrincipalRef principal, UUID skillId, UUID versionId,
        SkillPreflightScope scope, UUID agentId);
SkillDependencyMapping map(PrincipalRef principal, UUID skillId, String logicalKey,
        UUID toolId, long expectedRevision);
```

`SkillPreflightResult` 包含持久化的 `SkillPreflightCheck` 与排序后的 items；发布场景的 check.agentId 为空，每条 item 带实际受检 Agent ID，结构检查 item 的 agentId 为空。`ToolRuntimeReadiness` 继续只判断本进程执行准备度，不执行工具。

- [ ] **Step 1：写预检红灯。** 覆盖无依赖通过、必需缺映射失败、可选缺映射为 `PASSED_WITH_WARNINGS`、映射跨租户工具拒绝、工具停用/HTTP 配置不完整、本地注册不一致、Agent 缺 ToolGrant、映射修订与结果一致。

```java
SkillPreflightResult result = service.preflight(principal, skillId, versionId,
        SkillPreflightScope.AGENT, agentId);
assertThat(result.check().status()).isEqualTo(SkillPreflightStatus.FAILED);
assertThat(result.items()).extracting(SkillPreflightItem::status)
        .containsExactly(SkillPreflightItemStatus.GRANT_MISSING);
```

- [ ] **Step 2：写映射更新红灯。** 覆盖技能级 expected revision 冲突、一次映射变化只把 `SkillDefinition.dependencyMappingRevision` 加一、技能启用时所有受影响跟随/固定 Agent 必需授权检查、技能停用时允许保存但不自动授权、审计失败回滚，以及客户端 tenant 字段被忽略或不接受。

- [ ] **Step 3：运行红灯。**

```powershell
mvn -q -pl cm-agent-server -am "-Dtest=SkillPreflightServiceTest,SkillDependencyControllerTest,ToolRuntimeReadinessTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- [ ] **Step 4：实现解析和检查。** 先按 tenant/skill/version 解析不可变依赖，再按 skill/logicalKey 读取映射；工具必须通过 `ToolDefinitionRepository`、HTTP 配置或本地注册和 `ToolRuntimeReadiness`。Agent 场景额外查询精确 ToolGrant；不得调用执行器。

```java
for (SkillDependency dependency : dependencies.list(tenantId, skillId, versionId)) {
    SkillDependencyMapping mapping = mappings.find(tenantId, skillId, dependency.logicalKey()).orElse(null);
    items.add(checkDependency(principal, dependency, mapping, agentId));
}
SkillPreflightStatus status = summarize(items);
return preflights.save(newCheck(status, mappingRevision), items);
```

- [ ] **Step 5：实现映射事务和 API。** 映射保存前锁定技能和当前映射修订；启用技能按实际绑定策略解析受影响版本/Agent。保存映射与严格审计同事务；响应不返回 endpoint、HTTP headers 或授权内部字段。

- [ ] **Step 6：重复 Step 3。** 预期全部通过，并执行 `ApiExceptionHandlerTest` 确认新错误码具有正确 HTTP 状态和 `errorId`。

- [ ] **Step 7：提交。**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/service/SkillPreflightService.java cm-agent-server/src/main/java/com/cmagent/server/service/SkillDependencyMappingService.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillDependencyController.java cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolRuntimeReadiness.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillRequests.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java cm-agent-server/src/test/java/com/cmagent/server/service/SkillPreflightServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/web/SkillDependencyControllerTest.java
git diff --cached --check
git commit -m "feat: 增加技能依赖映射与预检"
```

## Task 7：实现发布、回滚和绑定版本策略

**文件：**

- 新增 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillReleaseService.java`
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillReleaseController.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillManagementService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillQueryService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/AgentSkillController.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillRequests.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java`
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/service/SkillReleaseServiceTest.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/web/AgentSkillControllerTest.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/web/SkillControllerTest.java`

**接口：**

```java
SkillRelease publish(PrincipalRef principal, UUID skillId, UUID candidateVersionId,
        UUID expectedPublishedVersionId, UUID qualifyingTrialRunId);
SkillRelease rollback(PrincipalRef principal, UUID skillId, UUID targetVersionId,
        UUID expectedPublishedVersionId);
AgentSkillBinding bindOrUpdate(PrincipalRef principal, UUID agentId, UUID skillId,
        SkillBindingMode mode, UUID pinnedVersionId, Long expectedRevision);
```

- [ ] **Step 1：写发布红灯。** 覆盖候选变化、映射修订后 trial 过期、`NOT_TRIGGERED` 不合格、全部跟随 Agent 即时预检、可选告警允许发布、审计失败整体回滚、发布成功清空 candidate 并追加连续 releaseNo。

- [ ] **Step 2：写回滚和绑定红灯。** 覆盖回滚未发布版本拒绝、回滚不要求 trial 但要求预检、固定候选拒绝、固定曾发布版本成功、跟随策略清空 pinned、revision 冲突 409、技能停用使两种绑定都不可运行。

```java
assertThatThrownBy(() -> service.bindOrUpdate(principal, agentId, skillId,
        SkillBindingMode.PINNED, candidateVersionId, null))
        .isInstanceOfSatisfying(SkillAccessException.class,
                ex -> assertThat(ex.code()).isEqualTo(ApiErrorCode.SKILL_VERSION_NOT_RELEASED));
```

- [ ] **Step 3：运行红灯。**

```powershell
mvn -q -pl cm-agent-server -am "-Dtest=SkillReleaseServiceTest,AgentSkillControllerTest,SkillControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- [ ] **Step 4：实现发布事务。** 在 `SkillUnitOfWork` 中锁定定义，核对两个预期指针；调用 T6 即时预检并验证指定 trial 的 version/mappingRevision/status；插入 release、CAS 更新指针、追加严格审计。预检计算可在事务前准备，但事务内必须复核锁定后的映射修订和授权事实。

```java
return workUnit.execute(() -> {
    SkillDefinition locked = requireExpectedPointers(skillId, candidateVersionId, expectedPublishedVersionId);
    SkillTrial trial = requireQualifyingTrial(qualifyingTrialRunId, candidateVersionId, currentMappingRevision);
    SkillPreflightCheck check = requireCurrentPublishPreflight(principal, locked, currentMappingRevision);
    SkillRelease release = releases.insert(nextRelease(locked, check.id(), trial.runId()));
    definitions.updatePointers(locked.publishCandidate(principal.principalId(), clock.instant()),
            candidateVersionId, expectedPublishedVersionId);
    appendAudit(principal, "SKILL_PUBLISH", skillId, "候选版本已发布");
    return release;
});
```

- [ ] **Step 5：实现回滚和绑定策略。** 回滚目标以 release 历史证明“曾发布”；绑定/策略切换先预检目标 Agent，固定版本不依赖当前 published 指针。保持原 PUT 绑定请求缺少 body 时默认 `FOLLOW_PUBLISHED`，维持 API 兼容。

- [ ] **Step 6：重复 Step 3。** 预期全部通过；另执行 `ApiExceptionHandlerTest` 和管理 JDBC 持久化测试，后者在 Rocky 验证。

- [ ] **Step 7：提交。**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/service/SkillReleaseService.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillReleaseController.java cm-agent-server/src/main/java/com/cmagent/server/service/SkillManagementService.java cm-agent-server/src/main/java/com/cmagent/server/service/SkillQueryService.java cm-agent-server/src/main/java/com/cmagent/server/web/AgentSkillController.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillRequests.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java cm-agent-server/src/test/java/com/cmagent/server/service/SkillReleaseServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/web/AgentSkillControllerTest.java cm-agent-server/src/test/java/com/cmagent/server/web/SkillControllerTest.java
git diff --cached --check
git commit -m "feat: 增加技能发布回滚与固定版本"
```

## Task 8：实现 TEST Run、临时注入和运行级审批

**文件：**

- 新增 `cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillRuntimeSelection.java`
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillTrialService.java`
- 新增 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillTrialController.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunPersistenceService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillRuntimeService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolApprovalService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillRequests.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java`
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillTrialServiceTest.java`
- 新增 `cm-agent-server/src/test/java/com/cmagent/server/web/SkillTrialControllerTest.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/runtime/RunPersistenceServiceTest.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/runtime/ToolApprovalServiceTest.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerTest.java`

**接口：**

```java
record SkillRuntimeSelection(UUID skillId, UUID versionId,
        UUID trialRunId, long mappingRevision) {}

SkillTrial start(PrincipalRef principal, UUID skillId, UUID versionId,
        UUID agentId, String input);
AgentRuntimeResult execute(PrincipalRef principal, SkillTrial trial,
        Consumer<AgentTextDelta> deltaConsumer,
        Consumer<AgentProgressEvent> progressConsumer);
SkillTrial finalizeResult(PrincipalRef principal, UUID runId, AgentRunResult result);
```

`RunExecutionService.runTrialPrepared(...)` 接收 `SkillRuntimeSelection`，内部仍走同一个 `executePrepared`、脱敏、工具治理和持久化收口。正常运行传空选择；审批恢复仅从 format 2 快照恢复，不重新接受客户端选择。

- [ ] **Step 1：写试运行红灯。** 覆盖 `skill:write + agent:run` 组合权限、跨租户隐藏、目标技能未绑定时临时注入、已绑定时只覆盖目标版本、其他技能正常解析、Run kind 为 TEST、无 conversation/message、真实工具列表不被扩大，以及普通运行历史默认排除 TEST。

```java
SkillTrial trial = service.start(principal, skillId, candidateId, agentId, "验证订单技能");
assertThat(runs.findByTenantAndAgentAndId(tenantId, agentId, trial.runId()).orElseThrow().kind())
        .isEqualTo(RunKind.TEST);
assertThat(conversations.findByRunId(tenantId, trial.runId())).isEmpty();
```

- [ ] **Step 2：写结果判定红灯。** 成功 Run 且存在目标 skill/version 的成功读取为 `PASSED`；成功但无读取为 `NOT_TRIGGERED`；受控失败为 `FAILED`；ASK 为 `WAITING_APPROVAL`；其他技能读取不能使目标试运行合格。

- [ ] **Step 3：写运行级审批红灯。** TEST Run 创建 `conversationId=null` 审批；使用 `tenant + agent + run + approval` 查询和决定；SSE 断开后查询仍得到同一 runId；审批恢复继续 format 2 快照并更新同一 `SkillTrial`，不得创建会话。

- [ ] **Step 4：运行红灯。**

```powershell
mvn -q -pl cm-agent-server -am "-Dtest=SkillTrialServiceTest,SkillTrialControllerTest,RunPersistenceServiceTest,ToolApprovalServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- [ ] **Step 5：实现 TEST Run 编排。** `RunPersistenceService.start` 增加 RunKind 重载，旧入口默认 NORMAL。`SkillTrialService.start` 在短事务中保存 TEST Run、trial 和审计；外部模型执行不持锁。执行终态后查询 `SkillLoadRecordRepository` 判定是否实际触发目标版本，再更新 trial 和审计。

```java
RunRecord run = persistence.start(principal, agentId, input, UUID.randomUUID(), RunKind.TEST);
SkillTrial trial = trials.insert(SkillTrial.running(run.id(), principal, skillId, versionId,
        agentId, mappingRevision, clock.instant()));
return execution.runTrialPrepared(principal, agentId, run, input,
        new SkillRuntimeSelection(skillId, versionId, run.id(), mappingRevision), delta, progress);
```

- [ ] **Step 6：实现临时选择和恢复。** `SkillRuntimeService.prepare(principal, run, selection)` 对目标技能创建 `TRIAL + trialRunId` 快照引用；允许候选技能在停用状态下接受显式试运行，但其他正式绑定仍要求技能启用。恢复时只信任已持久化快照和 trial 记录。

- [ ] **Step 7：实现 API 和运行级审批。** 提供创建/流式执行、查询试运行和按 runId 决定审批的接口。事件复用现有 `started/delta/progress/completed/error` 语义并加入 trial status；未知异常只输出脱敏原因和 `errorId`。

- [ ] **Step 8：重复 Step 4。** 预期通过；再执行 `RunControllerTest` 和 `ApiExceptionHandlerTest`，确认 NORMAL 流程无回归。

- [ ] **Step 9：提交。**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillRuntimeSelection.java cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillTrialService.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillTrialController.java cm-agent-server/src/main/java/com/cmagent/server/runtime/RunPersistenceService.java cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillRuntimeService.java cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolApprovalService.java cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillRequests.java cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillTrialServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/web/SkillTrialControllerTest.java cm-agent-server/src/test/java/com/cmagent/server/runtime/RunPersistenceServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/runtime/ToolApprovalServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerTest.java
git diff --cached --check
git commit -m "feat: 增加指定版本技能试运行"
```

## Task 9：固定依赖映射快照并接入 AgentScope 上下文

**文件：**

- 修改 `cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillRuntimeService.java`
- 修改 `cm-agent-server/src/main/java/com/cmagent/server/service/SkillPreflightService.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillRuntimeServiceTest.java`
- 修改 `cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillRuntimeJdbcPersistenceTest.java`
- 修改 `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillRepository.java`
- 修改 `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillSession.java`
- 修改 `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillLoadBridge.java`
- 修改 `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillSessionTest.java`
- 修改 `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillContractTest.java`

**接口：** `SkillRuntimeService.prepare` 返回 `SkillRuntimeBundle` 中的 `List<SkillVersionView>` 和按 skillId 分组的依赖映射；`SkillPreflightService.validateResolvedDependencies(...)` 只复核快照中的具体 toolId，不读取当前逻辑映射，也不保存新的预检历史。

- [ ] **Step 1：写正式取版红灯。** 跟随绑定选 published，固定绑定选 pinned；候选不进入 NORMAL Run；published 为空、固定版本未发布、技能停用均受控失败；更新或回滚后旧快照仍恢复旧版本。

- [ ] **Step 2：写依赖快照红灯。** format 2 保存逻辑 key/toolId/required；映射更新后旧 Run 仍取旧 toolId；工具停用或 ToolGrant 撤销时恢复失败；format 1 快照继续恢复无依赖技能。

- [ ] **Step 3：写 AgentScope 红灯。** `AgentRunRequest` 的每个固定 `SkillVersionView` 与同 skillId 依赖映射一起注册；目录提示只加入逻辑 key、已授权工具名称和必需性，不加入 endpoint、HTTP headers 或工具原始配置；模型未知 skillId/path 仍在网关前拒绝。

```java
assertThat(session.directoryPrompt())
        .contains("order-query", "query_orders", "必需")
        .doesNotContain("https://", "Authorization");
```

- [ ] **Step 4：运行红灯。**

```powershell
mvn -q -pl cm-agent-server,cm-agent-agentscope-adapter -am "-Dtest=SkillRuntimeServiceTest,AgentScopeSkillSessionTest,AgentScopeSkillContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- [ ] **Step 5：实现取版和快照。** 在锁定 Agent 绑定序列后按 mode 解析版本，读取该版本依赖和当前映射，调用 T6 复核具体工具后写 format 2。恢复按 origin 校验：`BINDING` 核对绑定 ID/纪元，`TRIAL` 核对 runId 对应 trial/版本/发起主体；两者都复核快照 toolId 的当前工具和授权状态。

- [ ] **Step 6：实现 Adapter 上下文。** `AgentScopeSkillRepository` 仍只暴露不可变正文/资源；`AgentScopeSkillSession` 根据 `AgentRunRequest.tools()` 把已解析 toolId 转为真实工具名称并生成受限依赖提示。业务工具仍由现有 Toolkit 和 `ToolInvocationGateway` 执行，不能因依赖映射注册额外工具。

```java
Map<UUID, String> names = request.tools().stream()
        .collect(Collectors.toUnmodifiableMap(ToolDefinition::id, ToolDefinition::name));
String dependencyPrompt = request.skillDependencies().getOrDefault(skillId, List.of()).stream()
        .map(item -> item.logicalKey() + " -> " + names.get(item.toolId())
                + (item.required() ? "（必需）" : "（可选）"))
        .collect(Collectors.joining("\n"));
```

- [ ] **Step 7：执行本地与远程验证。** 重复 Step 4；`SkillRuntimeJdbcPersistenceTest` 在 Rocky 分别使用 PostgreSQL 16 和 MySQL 8.4，验证发布后旧快照、映射变化和 format 1 恢复。

- [ ] **Step 8：提交。**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillRuntimeService.java cm-agent-server/src/main/java/com/cmagent/server/service/SkillPreflightService.java cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillRuntimeServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillRuntimeJdbcPersistenceTest.java cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillRepository.java cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillSession.java cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillLoadBridge.java cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillSessionTest.java cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillContractTest.java
git diff --cached --check
git commit -m "feat: 固定技能依赖映射快照"
```

## Task 10：扩展 v2 技能连续发布工作区

**文件：**

- 修改 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/skills.html`
- 修改 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/skills.js`
- 修改 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/multipage.css`
- 修改 `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- 修改加载 `skills.js` 的 v2 HTML 资源版本参数
- 新增 `cm-agent-console/src/test/js/skills.test.cjs`
- 修改 `cm-agent-console/src/test/js/console-core.test.cjs`
- 修改 `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`

**接口：** 页面通过 T5～T8 API 展示服务端权威状态；`skills.js` 的 `createRequestScope` 继续隔离登出、切换技能和迟到响应。

- [ ] **Step 1：写前端红灯。** `skills.test.cjs` 构造最小 DOM/API harness，覆盖候选/发布标识、映射失败定位、可选告警、旧请求不覆盖新技能、`NOT_TRIGGERED` 不显示可发布、mappingRevision 变化使旧 PASSED 失效、断线后按 runId 恢复。

```javascript
assert.equal(view.releaseButton.disabled, true);
assert.match(view.gateMessage.textContent, /目标技能未实际读取/);
scope.select("skill-b");
lateSkillA.resolve(detailA);
assert.equal(view.heading.textContent, "技能 B");
```

- [ ] **Step 2：写资源红灯。** `ConsoleResourceTest` 断言版本历史、依赖、试运行、发布影响、绑定策略和回滚区域存在；所有 v2 页面与登录页加载相同的新 `skills.js` 版本，防止直接 returnTo 后停在加载态。

- [ ] **Step 3：运行红灯。**

```powershell
node --test cm-agent-console/src/test/js/skills.test.cjs cm-agent-console/src/test/js/console-core.test.cjs
mvn -q -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- [ ] **Step 4：实现连续工作区。** 保留左侧技能列表和右侧详情，在详情中依次渲染候选差异、依赖映射、预检、试运行、发布门禁；历史/回滚作为同一详情的次级区域。失败后保留已完成状态，按钮只依据最新服务端响应启用。

```javascript
const canPublish = detail.candidate
    && detail.releaseGate?.preflightPassed
    && detail.releaseGate?.qualifyingTrialRunId
    && detail.releaseGate.mappingRevision === detail.mappingRevision;
publishButton.disabled = !canPublish || !permissions.includes("skill:write");
```

- [ ] **Step 5：实现流式试运行与审批。** 显示 TEST 标识、真实工具副作用提示、输出增量、技能读取、工具进度、审批卡片和 errorId。刷新时以 runId 查询，不自动重跑；审批决定提交到运行级接口。

- [ ] **Step 6：实现 Agent 绑定策略。** Agent 编辑区显示 `FOLLOW_PUBLISHED` 与 `PINNED`，固定版本只列曾发布版本；提交携带 expectedRevision，409 时刷新并保留明确冲突提示。

- [ ] **Step 7：运行语法和自动化测试。**

```powershell
node --check cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/skills.js
node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js
node --test cm-agent-console/src/test/js/skills.test.cjs cm-agent-console/src/test/js/console-core.test.cjs
mvn -q -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

- [ ] **Step 8：真实浏览器验证。** 使用 test profile 和 Provider Stub 完成候选上传→映射修复→预检→试运行→发布→固定版本→回滚；另覆盖 HIGH 工具等待审批、刷新恢复、桌面和 390px。检查 Network 中加载的是源目录对应的新资源版本，不以 `target/classes` 作为修改源。

- [ ] **Step 9：提交。**

```powershell
$task10Paths = @(
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/login.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/overview.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/agents.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/model-configs.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/tools.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/skills.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/runs.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/audit.html',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/skills.js',
  'cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/multipage.css',
  'cm-agent-console/src/main/resources/META-INF/resources/assets/app.js',
  'cm-agent-console/src/test/js/skills.test.cjs',
  'cm-agent-console/src/test/js/console-core.test.cjs',
  'cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java'
)
git add -- $task10Paths
git diff --cached --check
git commit -m "feat: 增加技能连续发布工作区"
```

## Task 11：完成双库、端到端回归与正式文档

**文件：**

- 修改 `README.md`
- 修改 `cm-agent-agentscope-adapter/README.md`
- 修改 `docs/release-notes.md`
- 修改 `docs/superpowers/specs/2026-09-22-skill-release-validation-design.md`
- 修改 `docs/superpowers/plans/2026-09-22-skill-release-validation.md`
- 修改 `docs/superpowers/implementation/2026-09-22-skill-release-validation-implementation-design.md`
- 修改 `docs/superpowers/progress/2026-09-22-skill-release-validation-ledger.md`
- 按回归发现修改本需求已有测试，不新建无行为价值的镜像测试。

**接口：** 不新增产品接口；本任务验证和记录 T1～T10 的最终实现。

- [ ] **Step 1：执行本地 Java 21 回归。**

```powershell
mvn -q -pl cm-agent-core,cm-agent-agentscope-adapter,cm-agent-console -am test
mvn -q -pl cm-agent-server -am "-Dtest=SkillControllerTest,AgentSkillControllerTest,SkillDependencyControllerTest,SkillTrialControllerTest,SkillReleaseServiceTest,SkillPreflightServiceTest,SkillTrialServiceTest,SkillRuntimeServiceTest,GovernedSkillAccessServiceTest,ToolApprovalServiceTest,RunControllerTest,ApiExceptionHandlerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
node --test cm-agent-console/src/test/js/skills.test.cjs cm-agent-console/src/test/js/console-core.test.cjs
```

预期所有存在的指定测试执行且通过；核对 Surefire 报告数量，不能用 `failIfNoSpecifiedTests=false` 掩盖目标类拼写错误。

- [ ] **Step 2：执行 Rocky 双库验证。** 确认远程工作区 HEAD 等于本地待验证提交，在 Maven 3.9.9/Java 21 容器中执行：

```sh
mvn -B -pl cm-agent-persistence,cm-agent-server -am \
  -Dtest=MigrationTest,JdbcSkillRepositoriesTest,JdbcRunRepositoryTest,JdbcToolApprovalRepositoryTest,SkillManagementJdbcPersistenceTest,SkillRuntimeJdbcPersistenceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

分别记录 PostgreSQL 16 和 MySQL 8.4 结果；禁止清理无关容器、卷或镜像。

- [ ] **Step 3：复跑浏览器闭环。** 验证桌面和 390px、刷新恢复、候选并发冲突、可选依赖告警、未触发不能发布、发布影响列表、固定版本不随回滚变化；保存不含凭据的截图和 Network 资源版本证据，测试凭据不得写入仓库。

- [ ] **Step 4：更新正式文档。** README 写明候选/发布区别、默认跟随、固定版本、依赖不自动授权和 TEST Run 真实副作用；Adapter README 写明依赖映射只进入受控提示且工具仍经网关；release notes 记录 V13、API 和控制台行为。实现说明写实际类和调用链，账本逐任务记录提交与验证，不把未执行项写成通过。

- [ ] **Step 5：最终范围和安全检查。**

```powershell
git diff --check
git status --short
git diff --name-only HEAD~1..HEAD
rg -n "Bearer |api[_-]?key|password|secret|jdbc:" docs README.md cm-agent-* -g '!target/**'
```

人工确认没有真实凭据、内部 URL、测试日志、`target/` 或用户已有配置进入提交；注释触发清单逐项复核。

- [ ] **Step 6：提交文档和回归修正。**

```powershell
git add -- README.md cm-agent-agentscope-adapter/README.md docs/release-notes.md docs/superpowers/specs/2026-09-22-skill-release-validation-design.md docs/superpowers/plans/2026-09-22-skill-release-validation.md docs/superpowers/implementation/2026-09-22-skill-release-validation-implementation-design.md docs/superpowers/progress/2026-09-22-skill-release-validation-ledger.md
git diff --cached --check
git commit -m "docs: 完成技能发布与试运行说明"
```

计划执行完成的定义：T1～T11 均有对应提交；本地、Rocky 双库和浏览器验证结果写入账本；规格、实现说明、进度账本和正式文档与代码一致；工作区中用户原有无关修改保持未暂存、未覆盖。
