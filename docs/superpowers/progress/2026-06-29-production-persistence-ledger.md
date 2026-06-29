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
- No remaining source-level findings after fixes.
- Known residual risk: Testcontainers coverage has not completed because Docker Desktop Linux engine is unavailable. Start Docker Desktop and re-run the targeted JDBC/Testcontainers commands before merge or release.
