# Production Persistence Progress Ledger

Started: 2026-06-29
Workspace: F:\java\cm-agent\.worktrees\production-persistence
Branch: codex/production-persistence
Plan: docs/superpowers/plans/2026-06-26-production-persistence.md
Spec: docs/superpowers/specs/2026-06-25-production-persistence-design.md

## Baseline

- Worktree created from `dca80ae716983d5fe25b650dc866c11c8c8d1f6a`.
- Baseline command attempted:
  `mvn -q -pl cm-agent-server -Dtest=ApplicationProfileConfigurationTest#productionProfileRejectsMissingJwtSecretWhenConfigDataDefaultsAreLoaded test`
- Baseline result: blocked by local Java compiler support for release 21.
- Evidence: Maven compiler plugin reports `Fatal error compiling` and unsupported release version `21`.
- Decision: continue implementation per user instruction not to stop for confirmation; final verification must re-run after JDK 21 is available.

## Task Status

| Task | Status | Implementer | Spec Review | Code Review | Commit |
| --- | --- | --- | --- | --- | --- |
| Prep: plan and ledger | completed | controller | n/a | n/a | 37c6b4a |
| Task 1: Repository contracts and memory fallback | completed | worker-task-1 | approved | approved after controller fix | 512653b, 8a77541 |
| Task 2: JDBC AgentDefinition repository | completed | controller fallback | approved | approved | bf912d9 |
| Task 3: JDBC mode wiring and AgentController integration | completed | controller fallback | approved | approved | 715d601 |
| Task 4: JDBC ToolDefinition repository | completed | controller fallback | approved | approved | 0d4ca19 |
| Task 5: JDBC ToolGrant repository and run path integration | completed | controller fallback | approved | approved | 87818ea |
| Task 6: Final verification | partially verified, docker blocked | controller | n/a | n/a | 4592be3, pending commit |
| Final code review | completed | controller | n/a | approved with docker verification blocker noted | pending commit |

## Notes

- Each implementation task uses a fresh subagent.
- After every implementation task, a spec review and code quality review must run before marking the task complete.
- If review finds issues, fixes are applied automatically and reviewed again.

## Review Log

### Task 1

- Implementer: worker-task-1 completed commit `512653b`.
- Spec review: subagent approved with no findings.
- Code quality review: subagent unavailable due usage limit; controller performed fallback review.
- Finding fixed: `CmAgentPersistenceProperties` used case-sensitive profile checks for `production`/`prod`, unlike existing security profile handling. Updated to `equalsIgnoreCase`, made null setters defensive, and added uppercase `PRODUCTION` regression test.
- Verification after fix: Maven remains blocked locally because `java -version` is JDK 17 while project requires release 21.

### Task 2

- Subagent execution unavailable after Task 1 due usage limit, so controller implemented the task while preserving the per-task ledger.
- Added `JdbcAgentDefinitionRepositoryTest`.
- Added `jackson-databind` dependency to persistence module.
- Added `JdbcAgentDefinitionRepository`.
- RED and GREEN Maven commands are blocked locally by JDK 17 not supporting project release 21.
- Spec review: approved by controller fallback; methods and tests match Task 2 scope.
- Code quality review: approved by controller fallback; SQL is tenant-scoped and JSON serialization is localized.

### Task 3

- Subagent execution remains unavailable due usage limit; controller implemented the task.
- Added `AgentControllerJdbcPersistenceTest`.
- Added `JdbcPersistenceConfiguration` with explicit DataSource, Flyway, JdbcClient, ObjectMapper, Agent repository, and default tenant/model config initializer.
- Switched `AgentController` from `InMemoryPlatformStore` to `AgentDefinitionRepository`.
- Added server Testcontainers dependencies.
- Hardened JDBC property getters to return empty strings for unset values.
- Maven RED/GREEN command is blocked locally by JDK 17 not supporting project release 21.
- Spec review: approved by controller fallback; Task 3 files and behavior match plan.
- Code quality review: approved by controller fallback; fixed list-order fragility in JDBC integration test and guarded blank driver class handling.

### Task 4

- Subagent execution remains unavailable due usage limit; controller implemented the task.
- Added `JdbcToolDefinitionRepositoryTest`.
- Added `JdbcToolDefinitionRepository`.
- Registered `ToolDefinitionRepository` JDBC bean in `JdbcPersistenceConfiguration`.
- Maven RED/GREEN command is blocked locally by JDK 17 not supporting project release 21.
- Spec review: approved by controller fallback; Task 4 files match plan.
- Code quality review: approved by controller fallback; SQL is tenant-scoped and mapper uses domain enums.

### Task 5

- Subagent execution remains unavailable due usage limit; controller implemented the task.
- Added `JdbcToolGrantRepositoryTest`.
- Added `JdbcToolGrantRepository`.
- Registered `ToolGrantRepository` JDBC bean in `JdbcPersistenceConfiguration`.
- Switched `ToolController` to Agent/Tool/Grant repository interfaces.
- Switched `RunController` to Agent/Tool/Grant repository interfaces.
- Added `RunControllerJdbcPersistenceTest` for authorized tool loading from JDBC.
- Maven RED/GREEN command is blocked locally by JDK 17 not supporting project release 21.
- Spec review: approved by controller fallback; Task 5 files and run path match plan.
- Code quality review: approved by controller fallback after naming the JDBC test runtime bean explicitly to reduce test context bean collisions.

### Task 6

- JDK 21 discovered at `F:\java21`; Maven was rerun with temporary `JAVA_HOME=F:\java21` and `PATH=F:\java21\bin;...`.
- Java/Maven evidence: `mvn -version` reports Java `21.0.11`, vendor Microsoft, runtime `F:\java21`.
- Full compile command passed:
  `mvn -q -DskipTests compile`
- Non-container server verification passed:
  `mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest,RunControllerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- Finding fixed during verification: profile guard tests were loading a full web application context and could be preempted by unrelated `AgentRuntime` test beans or by persistence validation order. The tests now load only production guard configuration and set unrelated guard dependencies explicitly.
- Testcontainers verification remains blocked because Docker Desktop Linux engine is not running.
- Docker evidence: `docker version` reports client version `27.3.1`, then fails connecting to `//./pipe/dockerDesktopLinuxEngine` with "The system cannot find the file specified."
- Targeted persistence command now reaches tests under JDK 21 but is blocked by Docker:
  `mvn -q -pl cm-agent-persistence -am "-Dtest=JdbcAgentDefinitionRepositoryTest,JdbcToolDefinitionRepositoryTest,JdbcToolGrantRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- Testcontainers failure evidence: `Could not find a valid Docker environment`.

Historical pre-JDK21 attempts:

- Targeted persistence test command attempted:
  `mvn -q -pl cm-agent-persistence "-Dtest=JdbcAgentDefinitionRepositoryTest,JdbcToolDefinitionRepositoryTest,JdbcToolGrantRepositoryTest" test`
- Targeted server test command attempted:
  `mvn -q -pl cm-agent-server "-Dtest=ApplicationProfileConfigurationTest,AgentControllerJdbcPersistenceTest,RunControllerTest,RunControllerJdbcPersistenceTest,AuthControllerTest" test`
- Full test command attempted:
  `mvn -q test`
- All Maven commands are blocked before tests execute because only JDK 17 is on PATH and the project requires release 21.
- Evidence: `java -version` reports `openjdk version "17.0.13"` from `F:\java17\bin\java.exe`; Maven compiler reports unsupported release version `21`.
- Non-Maven checks:
  - `rg -n "待实现|临时实现|补充实现" cm-agent-core cm-agent-persistence cm-agent-server` produced no output.
  - `git diff --check` passed.

## Final Code Review

- Reviewed changed controllers, JDBC configuration, repositories, and integration tests.
- Finding fixed: `JdbcPersistenceConfiguration` exposed a persistence-only `ObjectMapper` as an application bean, which could make Spring Boot MVC/Jackson auto-configuration back off in JDBC mode. The mapper is now repository-local.
- Verification after review fix:
  - `mvn -q -DskipTests compile` passed with JDK 21.
  - `mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest,RunControllerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` passed with JDK 21.
- No remaining source-level findings after fixes.
- Known residual risk: Testcontainers coverage has not completed because Docker Desktop Linux engine is unavailable. Start Docker Desktop and re-run the targeted JDBC/Testcontainers commands before merge or release.

## 全量核定记录（2026-11-13）

- 核定结论：待续。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：历史 JDBC/Testcontainers 验证因 Docker 不可用未完成；当前必须按 Rocky 规则补验，不继续旧 Docker Desktop 指引。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：上述剩余事项继续由原任务/验收范围跟踪；本次不执行它们，不依据后来相关主题自动宣布原验收通过，也不补造取消或移交。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。

历史正文中的 Docker Desktop 补验建议保留为当时记录，现行验证环境必须遵循[部署指南](../../deployment.md#rocky-vm-容器验证)的 Rocky/JDK 21 容器要求，不能直接复用旧指引。

## Git 补证核定（2026-11-13）

- 当前核定结论：待续，维持前次分类。
- 共同责任范围：文首 Plan 明确引用 `2026-06-26-production-persistence`，Spec 明确引用 `2026-06-25-production-persistence`；这两组存在真实共同账本，不是凭同名推测承接。
- 依据：`37c6b4a856` 建立实施计划，`4592be30a0` 记录验证阻塞，`d04dfca` 记录后续修复，`497514aeca` 合并；均在当前 HEAD 历史中。原 Task6 仍记为 partially verified/docker blocked，没有本范围容器验收收口证据，合并不等于验收通过。
- 限制与剩余事项：保留原验证缺口，不重新执行旧 Docker Desktop 指引；若后续补验，按现行 Rocky/JDK21 容器规则执行并记录当前版本。没有将后续阶段的笼统通过替代本包验收。
- 核定详情：[6 月 25 日设计](2026-11-13-docs-full-review-ledger.md#git-audit-2026-06-25-production-persistence)、[6 月 26 日计划](2026-11-13-docs-full-review-ledger.md#git-audit-2026-06-26-production-persistence)。本次不提交、推送或部署，也不补造旧账本。
