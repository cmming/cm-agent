# Supabase Persistence Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Supabase-safe deployment and verification path for the existing JDBC/Flyway Agent, Tool, and ToolGrant persistence slice.

**Architecture:** Supabase remains a hosted PostgreSQL target behind the existing JDBC persistence mode. The application gets a `supabase` profile with production-like guardrails, while Supabase project automation happens through the Supabase connector against a development branch only.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, Flyway, PostgreSQL JDBC, Supabase development branches, GitHub PR workflow.

---

## File Structure

- Modify `cm-agent-server/src/main/java/com/cmagent/server/config/CmAgentPersistenceProperties.java`
  - Treat `supabase` as a production-like persistence profile and require JDBC mode.
- Modify `cm-agent-server/src/main/java/com/cmagent/server/security/BootstrapAdminProperties.java`
  - Treat `supabase` like production for bootstrap admin rejection.
- Modify `cm-agent-server/src/main/java/com/cmagent/server/security/JwtSecurityConfiguration.java`
  - Treat `supabase` like production for JWT fallback and test-profile mixing.
- Create `cm-agent-server/src/main/resources/application-supabase.yml`
  - Profile-specific defaults for JDBC persistence and safe security settings.
- Modify `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`
  - Add TDD coverage for Supabase profile loading and guardrails.
- Modify `docs/configuration.md`
  - Document Supabase environment variables and safety constraints.
- Modify `docs/deployment.md`
  - Document Supabase development branch workflow and local launch commands.
- Create `docs/superpowers/progress/2026-06-29-supabase-persistence-automation-ledger.md`
  - Track task progress, subagent fallback if needed, verification, Supabase branch state, and blockers.

## Task 1: Add Supabase Profile Guardrail Tests

**Files:**
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`

- [ ] **Step 1: Write failing tests for Supabase profile behavior**

Add these tests before the `assertTestProfileLoaded` helper in `ApplicationProfileConfigurationTest`:

```java
    @Test
    void supabaseProfileLoadsJdbcDefaultsFromConfigData() {
        contextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .run(context -> {
                    Environment environment = context.getEnvironment();

                    assertThat(environment.getActiveProfiles()).containsExactly("supabase");
                    assertThat(environment.getProperty("cm-agent.persistence.mode")).isEqualTo("jdbc");
                    assertThat(environment.getProperty("cm-agent.persistence.jdbc.driver-class-name"))
                            .isEqualTo("org.postgresql.Driver");
                    assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class))
                            .isFalse();
                    assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class))
                            .isFalse();
                });
    }

    @Test
    void supabaseProfileRejectsMissingJdbcUrlWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("启用 jdbc 持久化模式时必须配置 cm-agent.persistence.jdbc.url");
                });
    }

    @Test
    void supabaseProfileRejectsMemoryPersistenceModeWhenJwtSecretExists() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 必须使用 jdbc 持久化模式");
                });
    }

    @Test
    void supabaseProfileRejectsBootstrapAdminWhenJdbcConfigured() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=true")
                .withPropertyValues("cm-agent.security.bootstrap-admin-password=local-password")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止启用 bootstrap admin");
                });
    }

    @Test
    void supabaseProfileRejectsTestProfileMixingWhenJdbcConfigured() {
        productionGuardContextRunner
                .withPropertyValues("spring.profiles.active=supabase,test")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=jdbc")
                .withPropertyValues("cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent")
                .withPropertyValues("cm-agent.security.bootstrap-admin-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod/supabase profile 禁止与 test profile 同时启用");
                });
    }
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```powershell
$env:JAVA_HOME='F:\java21'
$env:PATH='F:\java21\bin;' + $env:PATH
mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL. At least these assertions fail because `application-supabase.yml` does not exist and `supabase` is not treated as production-like:

```text
expected: "jdbc"
 but was: "memory"
```

and:

```text
Expecting throwable message ... to contain:
  "production/prod/supabase profile 必须使用 jdbc 持久化模式"
```

- [ ] **Step 3: Commit the failing tests**

```powershell
git add cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java
git commit -m "test: 添加 Supabase profile 启动保护测试"
```

## Task 2: Implement Supabase Profile Guardrails

**Files:**
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/config/CmAgentPersistenceProperties.java`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/security/BootstrapAdminProperties.java`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/security/JwtSecurityConfiguration.java`
- Create: `cm-agent-server/src/main/resources/application-supabase.yml`

- [ ] **Step 1: Update persistence validation**

Replace `validate` and add `hasStrictPersistenceProfile` in `CmAgentPersistenceProperties.java`:

```java
    public void validate(Environment environment) {
        boolean strictPersistenceProfileActive = hasStrictPersistenceProfile(environment);
        if (strictPersistenceProfileActive && mode != Mode.JDBC) {
            throw new IllegalStateException("production/prod/supabase profile 必须使用 jdbc 持久化模式");
        }
        if (mode == Mode.JDBC && (jdbc == null || isBlank(jdbc.getUrl()))) {
            throw new IllegalStateException("启用 jdbc 持久化模式时必须配置 cm-agent.persistence.jdbc.url");
        }
    }

    private boolean hasStrictPersistenceProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "production".equalsIgnoreCase(profile)
                        || "prod".equalsIgnoreCase(profile)
                        || "supabase".equalsIgnoreCase(profile));
    }
```

- [ ] **Step 2: Update bootstrap admin production-like profile check**

In `BootstrapAdminProperties.java`, replace `hasProductionProfile` with:

```java
    private boolean hasProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "production".equalsIgnoreCase(profile)
                        || "prod".equalsIgnoreCase(profile)
                        || "supabase".equalsIgnoreCase(profile));
    }
```

Change the exception message in `validate` from:

```java
throw new IllegalStateException("production/prod profile 禁止启用 bootstrap admin");
```

to:

```java
throw new IllegalStateException("production/prod/supabase profile 禁止启用 bootstrap admin");
```

- [ ] **Step 3: Update JWT production-like profile check**

In `JwtSecurityConfiguration.java`, replace the mixed-profile exception:

```java
throw new IllegalStateException("production/prod profile 禁止与 test profile 同时启用，测试 JWT 配置不得用于生产样环境");
```

with:

```java
throw new IllegalStateException("production/prod/supabase profile 禁止与 test profile 同时启用，测试 JWT 配置不得用于生产样环境");
```

Replace `hasProductionLikeProfile` with:

```java
    private boolean hasProductionLikeProfile(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }
        return Arrays.stream(activeProfiles)
                .anyMatch(profile -> "production".equalsIgnoreCase(profile)
                        || "prod".equalsIgnoreCase(profile)
                        || "supabase".equalsIgnoreCase(profile));
    }
```

- [ ] **Step 4: Add Supabase profile config**

Create `cm-agent-server/src/main/resources/application-supabase.yml`:

```yaml
# Supabase profile for hosted PostgreSQL deployments.
# Secrets must be injected by environment variables or a secret manager.
cm-agent:
  security:
    allow-dev-jwt-fallback: false
    bootstrap-admin-enabled: false
  persistence:
    mode: jdbc
    jdbc:
      url: ${CM_AGENT_JDBC_URL:}
      username: ${CM_AGENT_JDBC_USERNAME:}
      password: ${CM_AGENT_JDBC_PASSWORD:}
      driver-class-name: ${CM_AGENT_JDBC_DRIVER_CLASS_NAME:org.postgresql.Driver}
```

- [ ] **Step 5: Run Supabase profile tests and verify they pass**

Run:

```powershell
$env:JAVA_HOME='F:\java21'
$env:PATH='F:\java21\bin;' + $env:PATH
mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: PASS with exit code `0`.

- [ ] **Step 6: Commit implementation**

```powershell
git add cm-agent-server/src/main/java/com/cmagent/server/config/CmAgentPersistenceProperties.java `
        cm-agent-server/src/main/java/com/cmagent/server/security/BootstrapAdminProperties.java `
        cm-agent-server/src/main/java/com/cmagent/server/security/JwtSecurityConfiguration.java `
        cm-agent-server/src/main/resources/application-supabase.yml
git commit -m "feat: 添加 Supabase profile 持久化保护"
```

## Task 3: Document Supabase Deployment Configuration

**Files:**
- Modify: `docs/configuration.md`
- Modify: `docs/deployment.md`

- [ ] **Step 1: Update configuration docs**

In `docs/configuration.md`, replace the current `## 运行态存储` section with:

```markdown
## 运行态存储

服务端默认使用 memory store，适合本地演示和纵切验证。生产或类生产环境必须使用 JDBC 持久化：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `CM_AGENT_PERSISTENCE_MODE` / `cm-agent.persistence.mode` | `memory` | `memory` 或 `jdbc`；`prod`、`production`、`supabase` profile 下必须为 `jdbc` |
| `CM_AGENT_JDBC_URL` / `cm-agent.persistence.jdbc.url` | 空 | JDBC URL；启用 `jdbc` 时必须配置 |
| `CM_AGENT_JDBC_USERNAME` / `cm-agent.persistence.jdbc.username` | 空 | 数据库用户名 |
| `CM_AGENT_JDBC_PASSWORD` / `cm-agent.persistence.jdbc.password` | 空 | 数据库密码，必须由 Secret 注入 |
| `CM_AGENT_JDBC_DRIVER_CLASS_NAME` / `cm-agent.persistence.jdbc.driver-class-name` | 空 | JDBC driver；Supabase 推荐 `org.postgresql.Driver` |

### Supabase PostgreSQL

Supabase 作为托管 PostgreSQL 接入，不需要 Supabase Java SDK。推荐使用 `supabase` profile：

```powershell
$env:CM_AGENT_PROFILE='supabase'
$env:CM_AGENT_JWT_SECRET='value from secret manager with safe length'
$env:CM_AGENT_JDBC_URL='Supabase development branch or production JDBC URL from secret manager'
$env:CM_AGENT_JDBC_USERNAME='Supabase database user from secret manager'
$env:CM_AGENT_JDBC_PASSWORD='Supabase database password from secret manager'
$env:CM_AGENT_JDBC_DRIVER_CLASS_NAME='org.postgresql.Driver'
```

`supabase` profile 会默认启用 JDBC 持久化、禁用 bootstrap admin、禁用开发 JWT fallback。缺少 JDBC URL 或试图使用 memory mode 时，服务会启动失败。

不要把 Supabase 数据库密码、JWT secret 或完整 JDBC URL 提交到 Git、写入镜像层或打印到日志。开发验证应优先使用 Supabase development branch，避免直接对主项目数据库执行 DDL。
```

- [ ] **Step 2: Update deployment docs**

In `docs/deployment.md`, add this section after `## 启动开发数据库`:

```markdown
## 使用 Supabase development branch 验证持久化

Supabase 接入复用现有 JDBC/Flyway 持久化链路。默认不要直接对 Supabase 主项目执行 DDL；先创建 development branch，再在 branch 上验证 schema。

推荐流程：

1. 在 Supabase 中为项目 `hfgdsvsvuosdkqeodked` 创建 development branch，名称建议为 `cm-agent-supabase-persistence`。
2. 在 branch 上检查 `public` schema。
3. 如果缺少 CM Agent 表，在 branch 上应用 `cm-agent-persistence/src/main/resources/db/migration/V1__init_schema.sql`。
4. 确认至少存在 `tenants`、`model_configs`、`agent_definitions`、`tool_definitions`、`tool_grants`。
5. 使用 branch 的 JDBC URL 和数据库凭据启动服务端。

本地启动示例：

```powershell
$env:CM_AGENT_PROFILE='supabase'
$env:CM_AGENT_JWT_SECRET='value from secret manager with safe length'
$env:CM_AGENT_JDBC_URL='Supabase branch JDBC URL from secret manager'
$env:CM_AGENT_JDBC_USERNAME='Supabase database user from secret manager'
$env:CM_AGENT_JDBC_PASSWORD='Supabase database password from secret manager'
$env:CM_AGENT_JDBC_DRIVER_CLASS_NAME='org.postgresql.Driver'
mvn -pl cm-agent-server -am spring-boot:run
```

服务启动时 Flyway 会自动检查并应用 classpath 中的 migration。若数据库凭据不可用，仍可先完成配置测试和 Supabase branch 表结构检查，再由部署环境注入 secret 后运行 smoke test。
```

- [ ] **Step 3: Check docs for secret leaks and placeholders**

Run:

```powershell
$patterns = @('password-only', 'sk-', 'eyJ', '真实密码', 'TO' + 'DO', 'T' + 'BD') -join '|'
rg -n $patterns docs/configuration.md docs/deployment.md
```

Expected: exit code `0` only if existing test-only password text is found in unrelated local-test sections, and no new Supabase secret values appear.

- [ ] **Step 4: Commit docs**

```powershell
git add docs/configuration.md docs/deployment.md
git commit -m "docs: 说明 Supabase JDBC 部署流程"
```

## Task 4: Create Progress Ledger and Run Supabase Branch Schema Verification

**Files:**
- Create: `docs/superpowers/progress/2026-06-29-supabase-persistence-automation-ledger.md`

- [ ] **Step 1: Create progress ledger**

Create `docs/superpowers/progress/2026-06-29-supabase-persistence-automation-ledger.md` with this PowerShell command so the actual worktree path is written into the file:

```powershell
$worktree = git rev-parse --show-toplevel
$ledger = @"
# Supabase Persistence Automation Progress Ledger

Started: 2026-06-29
Workspace: $worktree
Branch: codex/supabase-persistence-automation
Spec: docs/superpowers/specs/2026-06-29-supabase-persistence-automation-design.md
Plan: docs/superpowers/plans/2026-06-29-supabase-persistence-automation.md
Supabase project: hfgdsvsvuosdkqeodked
Supabase branch: pending

## Task Status

| Task | Status | Implementer | Review | Commit |
| --- | --- | --- | --- | --- |
| Task 1: Supabase profile guardrail tests | pending | pending | pending | pending |
| Task 2: Supabase profile guardrails | pending | pending | pending | pending |
| Task 3: Documentation | pending | pending | pending | pending |
| Task 4: Supabase branch schema verification | pending | pending | pending | pending |
| Task 5: Final verification | pending | controller | pending | pending |

## Supabase Verification Log

- Project list: pending
- Branch cost confirmation: pending
- Development branch creation: pending
- Migration list: pending
- Table list before migration: pending
- Migration application: pending
- Table list after migration: pending
- Required tables: pending

## Verification Commands

- `mvn -q -DskipTests compile`: pending
- `mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest,RunControllerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`: pending
"@
Set-Content -Path docs\superpowers\progress\2026-06-29-supabase-persistence-automation-ledger.md -Value $ledger -Encoding UTF8
```

- [ ] **Step 2: Read Supabase project**

Use Supabase connector:

```text
list_projects()
```

Expected: project `hfgdsvsvuosdkqeodked` is present and `status` is `ACTIVE_HEALTHY`.

Record the project status in the ledger.

- [ ] **Step 3: Check branch cost before creating a branch**

Use Supabase connector:

```text
get_cost({
  "organization_id": "jubqiioopciknyyxiwvr",
  "type": "branch"
})
```

Expected: connector returns a cost confirmation ID. If user confirmation is required by the connector, stop and ask only that confirmation question.

- [ ] **Step 4: Create Supabase development branch**

Use Supabase connector with the cost confirmation ID returned by Step 3. In the controller notes, store it as `confirmCostId` and pass that exact value:

```text
create_branch({
  "project_id": "hfgdsvsvuosdkqeodked",
  "name": "cm-agent-supabase-persistence",
  "confirm_cost_id": confirmCostId
})
```

Expected: connector returns a branch with a branch ID and a branch project ref. Record both in the ledger.

- [ ] **Step 5: Wait for branch readiness**

Use Supabase connector:

```text
list_branches({
  "project_id": "hfgdsvsvuosdkqeodked"
})
```

Expected: branch `cm-agent-supabase-persistence` is listed with a usable project ref. If status is still provisioning, poll every 20 seconds up to 5 minutes and record each attempt in the ledger.

- [ ] **Step 6: Inspect migrations and tables on the branch**

Use the branch project ref returned by Step 4, stored in controller notes as `branchProjectRef`. Use the branch project ref, not the main project ID:

```text
list_migrations({
  "project_id": branchProjectRef
})
```

```text
list_tables({
  "project_id": branchProjectRef,
  "schemas": ["public"],
  "verbose": true
})
```

Expected: either CM Agent tables already exist or the required table list is missing. Record the result.

- [ ] **Step 7: Apply CM Agent migration to the branch if required tables are missing**

If any required table is missing, read:

```powershell
Get-Content cm-agent-persistence\src\main\resources\db\migration\V1__init_schema.sql
```

Store the full file contents in controller notes as `cmAgentInitSchemaSql`, then use Supabase connector:

```text
apply_migration({
  "project_id": branchProjectRef,
  "name": "cm_agent_init_schema",
  "query": cmAgentInitSchemaSql
})
```

Expected: migration applies successfully on the development branch. Record the migration response.

- [ ] **Step 8: Verify required tables after migration**

Use Supabase connector:

```text
list_tables({
  "project_id": branchProjectRef,
  "schemas": ["public"],
  "verbose": true
})
```

Expected: these tables exist:

```text
tenants
model_configs
agent_definitions
tool_definitions
tool_grants
```

If any table is missing, record the missing table names and treat Task 4 as failed.

- [ ] **Step 9: Commit ledger**

```powershell
git add docs/superpowers/progress/2026-06-29-supabase-persistence-automation-ledger.md
git commit -m "docs: 记录 Supabase branch 验证进度"
```

## Task 5: Final Verification and PR Preparation

**Files:**
- Modify: `docs/superpowers/progress/2026-06-29-supabase-persistence-automation-ledger.md`

- [ ] **Step 1: Run compile verification**

```powershell
$env:JAVA_HOME='F:\java21'
$env:PATH='F:\java21\bin;' + $env:PATH
mvn -q -DskipTests compile
```

Expected: exit code `0`.

- [ ] **Step 2: Run server non-container tests**

```powershell
$env:JAVA_HOME='F:\java21'
$env:PATH='F:\java21\bin;' + $env:PATH
mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest,RunControllerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: exit code `0`.

- [ ] **Step 3: Run whitespace and placeholder checks**

```powershell
git diff --check
$patterns = @('T' + 'BD', 'TO' + 'DO', '待' + '定', '待' + '补', '占' + '位') -join '|'
rg -n $patterns docs/superpowers/specs docs/superpowers/progress cm-agent-server/src/main cm-agent-server/src/test docs/configuration.md docs/deployment.md
```

Expected: `git diff --check` exits `0`. The `rg` command exits `1` with no output after all implementation artifacts have been cleaned.

- [ ] **Step 4: Update ledger with verification evidence**

Append to the ledger:

```markdown
## Final Verification

- `mvn -q -DskipTests compile`: passed with JDK 21.
- `mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest,RunControllerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`: passed with JDK 21.
- Supabase development branch schema verification: passed or blocked with the exact Supabase connector error recorded above.
- Worktree status before PR: clean.
```

- [ ] **Step 5: Commit final ledger update**

```powershell
git add docs/superpowers/progress/2026-06-29-supabase-persistence-automation-ledger.md
git commit -m "docs: 完成 Supabase 自动化验证记录"
```

- [ ] **Step 6: Prepare PR body**

Use this PR body:

```markdown
## Summary
- Adds a Supabase profile that keeps the existing JDBC/Flyway persistence path and prevents memory fallback.
- Documents Supabase development branch deployment and secret handling.
- Verifies the Supabase project branch schema for Agent/Tool/ToolGrant persistence tables.

## Test Plan
- [ ] `mvn -q -DskipTests compile`
- [ ] `mvn -q -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest,RunControllerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- [ ] Supabase development branch table check for `tenants`, `model_configs`, `agent_definitions`, `tool_definitions`, `tool_grants`
- [ ] Supabase JDBC smoke test with secrets injected by deployment environment, if credentials are available
```

After this task, use `superpowers:verification-before-completion`, then `superpowers:finishing-a-development-branch` to push and create a PR.

## Self-Review

- Spec coverage: The plan covers Supabase JDBC profile, production-like guardrails, docs, development branch schema verification, progress ledger, and final verification.
- Scope: The plan intentionally excludes Supabase Auth, Storage, Realtime, Edge Functions, AuditEvent, Run, and ToolCall persistence.
- Placeholder scan: Plan-only connector placeholders are present only where execution requires runtime IDs returned by Supabase tools. Implementation artifacts must not retain placeholders.
- Type consistency: Test names, property names, and class names match the current codebase.
