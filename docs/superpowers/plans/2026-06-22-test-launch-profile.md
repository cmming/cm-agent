# 测试环境启动配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `application.yml` 中提供环境选择器，并新增可直接用于本地测试启动的 `application-test.yml`。

**Architecture:** 使用 Spring Boot 原生 profile 机制：默认 profile 从 `CM_AGENT_PROFILE` 读取，未设置时使用 `local`；测试环境通过 `application-test.yml` 覆盖 JWT 和 bootstrap admin 配置。生产安全边界仍由现有 `JwtSecurityConfiguration` 和 `BootstrapAdminProperties` 保证，`prod`/`production` 不允许 bootstrap admin，也不能使用开发 JWT 回退。

**Tech Stack:** Java 21、Spring Boot 3.5、JUnit 5、AssertJ、Maven、YAML 配置、中文 Markdown 文档。

---

## File Structure

- Create: `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`
  - Verifies `application.yml` activates default `local` profile.
  - Verifies `CM_AGENT_PROFILE=test` loads `application-test.yml`.
  - Verifies command-line style `spring.profiles.active=test` loads `application-test.yml`.
- Modify: `cm-agent-server/src/main/resources/application.yml`
  - Adds `spring.profiles.active: ${CM_AGENT_PROFILE:local}`.
  - Adds `cm-agent.security.allow-dev-jwt-fallback: ${CM_AGENT_ALLOW_DEV_JWT_FALLBACK:true}` so the default local profile can use the existing guarded local fallback.
- Create: `cm-agent-server/src/main/resources/application-test.yml`
  - Contains test-only JWT secret and bootstrap admin credentials.
  - Keeps fake runtime enabled for local end-to-end verification.
- Modify: `README.md`
  - Adds simple test profile startup path.
  - Keeps explicit long-form local startup command for users who do not want the test profile.
- Modify: `docs/configuration.md`
  - Documents `CM_AGENT_PROFILE`, `application-test.yml`, and test credentials.
  - Reiterates production must inject secrets externally.
- Modify: `docs/deployment.md`
  - Documents production profile selection and warns against test profile in production.

## Controller Ledger

The controller, not the task subagents, maintains `docs/superpowers/progress-ledger.md` during execution. Append entries after Task 1, after Task 2, and after final review. Do not include the ledger in task commits unless the user explicitly asks to publish it.

---

### Task 1: Add Profile Configuration and Tests

**Files:**
- Create: `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`
- Modify: `cm-agent-server/src/main/resources/application.yml`
- Create: `cm-agent-server/src/main/resources/application-test.yml`

- [ ] **Step 1: Write the failing test**

Create `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java` with this complete content:

```java
package com.cmagent.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationProfileConfigurationTest {
    private static final String TEST_JWT_SECRET = "cm-agent-test-jwt-secret-with-at-least-32-bytes";
    private static final String TEST_ADMIN_PASSWORD = "cm-agent-test-password-only";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void defaultConfigurationActivatesLocalProfileFromCmAgentProfileSelector() {
        contextRunner.run(context -> {
            Environment environment = context.getEnvironment();

            assertThat(environment.getActiveProfiles()).containsExactly("local");
            assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isTrue();
            assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isFalse();
            assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
            assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password")).isEmpty();
        });
    }

    @Test
    void cmAgentProfileCanActivateTestProfileAndLoadTestConfiguration() {
        contextRunner
                .withPropertyValues("CM_AGENT_PROFILE=test")
                .run(context -> assertTestProfileLoaded(context.getEnvironment()));
    }

    @Test
    void explicitSpringProfileArgumentCanActivateTestProfileAndLoadTestConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertTestProfileLoaded(context.getEnvironment()));
    }

    private static void assertTestProfileLoaded(Environment environment) {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(TEST_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password")).isEqualTo(TEST_ADMIN_PASSWORD);
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-display-name")).isEqualTo("测试管理员");
        assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isTrue();
    }
}
```

- [ ] **Step 2: Run the new test and verify it fails**

Run with Java 21:

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=ApplicationProfileConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: FAIL. At least one assertion should fail because the current `application.yml` does not define `spring.profiles.active`, `cm-agent.security.allow-dev-jwt-fallback`, or `application-test.yml`. A valid failure includes one of these messages:

```text
Expecting actual:
  []
to contain exactly:
  ["local"]
```

or:

```text
expected: "cm-agent-test-jwt-secret-with-at-least-32-bytes"
 but was: null
```

- [ ] **Step 3: Update `application.yml`**

Replace `cm-agent-server/src/main/resources/application.yml` with this complete content:

```yaml
server:
  port: 8080

spring:
  application:
    name: cm-agent-server
  profiles:
    active: ${CM_AGENT_PROFILE:local}

management:
  endpoints:
    web:
      exposure:
        include: health,info

cm-agent:
  security:
    jwt-secret: ${CM_AGENT_JWT_SECRET:}
    allow-dev-jwt-fallback: ${CM_AGENT_ALLOW_DEV_JWT_FALLBACK:true}
    bootstrap-admin-enabled: false
    bootstrap-admin-username: ${CM_AGENT_BOOTSTRAP_ADMIN_USERNAME:admin}
    bootstrap-admin-password: ${CM_AGENT_BOOTSTRAP_ADMIN_PASSWORD:}
    bootstrap-admin-display-name: ${CM_AGENT_BOOTSTRAP_ADMIN_DISPLAY_NAME:系统管理员}
    public-api-docs-enabled: ${CM_AGENT_PUBLIC_API_DOCS_ENABLED:true}
  fake-runtime-enabled: true
  default-tenant-code: default
```

- [ ] **Step 4: Add `application-test.yml`**

Create `cm-agent-server/src/main/resources/application-test.yml` with this complete content:

```yaml
cm-agent:
  security:
    jwt-secret: cm-agent-test-jwt-secret-with-at-least-32-bytes
    bootstrap-admin-enabled: true
    bootstrap-admin-username: admin
    bootstrap-admin-password: cm-agent-test-password-only
    bootstrap-admin-display-name: 测试管理员
    public-api-docs-enabled: true
  fake-runtime-enabled: true
```

- [ ] **Step 5: Run the new test and verify it passes**

Run:

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=ApplicationProfileConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS with exit code `0`.

- [ ] **Step 6: Run focused regression tests**

Run:

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=ApplicationProfileConfigurationTest,JwtSecurityConfigurationTest,AuthControllerTest,ConsoleSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: PASS with exit code `0`. Warnings about local/test JWT fallback are acceptable because they prove production secret injection remains required outside local/test contexts.

- [ ] **Step 7: Commit Task 1**

Run:

```powershell
git add 'cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java' 'cm-agent-server/src/main/resources/application.yml' 'cm-agent-server/src/main/resources/application-test.yml'
git commit -m "feat: 添加测试启动 profile 配置"
```

Expected: commit succeeds and `git status --short` shows only files unrelated to Task 1, such as `docs/superpowers/progress-ledger.md` if the controller has updated it.

---

### Task 2: Update Chinese Startup Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/configuration.md`
- Modify: `docs/deployment.md`

- [ ] **Step 1: Update README quick start**

In `README.md`, replace the whole `## 快速开始` section with this exact section:

````markdown
## 快速开始

本地测试启动可以直接使用 `test` profile：

```powershell
mvn -q "-DskipTests" package
$env:CM_AGENT_PROFILE='test'
mvn -pl cm-agent-server -am spring-boot:run
```

也可以使用 Spring Boot 命令行参数显式指定：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=test"
```

测试 profile 会加载 `application-test.yml`。控制台测试登录账号为 `admin`，密码为 `cm-agent-test-password-only`；该密码仅用于本地测试，不得用于生产。

需要手动传参时，可以继续使用显式配置：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.security.jwt-secret=cm-agent-local-secret-with-at-least-32-bytes-2026 --cm-agent.security.bootstrap-admin-enabled=true --cm-agent.security.bootstrap-admin-password=cm-agent-local-dev-password-only"
```
````

Keep the rest of `README.md` unchanged.

- [ ] **Step 2: Update configuration documentation**

In `docs/configuration.md`, add this row to the basic configuration table immediately after `server.port`:

```markdown
| `CM_AGENT_PROFILE` / `spring.profiles.active` | `local` | 运行环境选择器；默认进入本地 profile，可设置为 `test`、`prod` 或 `production` |
```

Then insert this new section immediately before `## fake runtime`:

````markdown
## 环境 Profile

默认配置通过 `CM_AGENT_PROFILE` 选择运行环境：

```yaml
spring:
  profiles:
    active: ${CM_AGENT_PROFILE:local}
```

本地测试可以设置：

```powershell
$env:CM_AGENT_PROFILE='test'
mvn -pl cm-agent-server -am spring-boot:run
```

`test` profile 会加载 `application-test.yml`，用于本地控制台和接口联调。测试登录账号为 `admin`，密码为 `cm-agent-test-password-only`。该配置包含可直接使用的测试凭据，只能用于本地测试。

生产部署应设置 `CM_AGENT_PROFILE=prod` 或 `CM_AGENT_PROFILE=production`，并通过外部 Secret 注入 `CM_AGENT_JWT_SECRET`。生产 profile 下启用 bootstrap admin 会导致服务启动失败。
````

Then replace the sentence under `## JWT 密钥` that currently says `本地开发可以临时使用命令行参数：` with this exact sentence:

```markdown
本地测试优先使用 `CM_AGENT_PROFILE=test`；需要手动覆盖配置时，也可以临时使用命令行参数：
```

- [ ] **Step 3: Update deployment documentation**

In `docs/deployment.md`, under `## 启动服务端`, insert this block before the existing sentence `本地开发可以通过命令行传入安全长度的 JWT 密钥启动服务端：`:

````markdown
本地测试可以使用 `test` profile 快速启动：

```powershell
$env:CM_AGENT_PROFILE='test'
mvn -pl cm-agent-server -am spring-boot:run
```

`test` profile 会启用本地 bootstrap admin，测试账号为 `admin`，密码为 `cm-agent-test-password-only`。该 profile 仅用于本地测试。
````

In `docs/deployment.md`, add these two bullets to `## 生产部署要点` after the JWT secret bullet:

```markdown
- 生产部署必须显式设置 `CM_AGENT_PROFILE=prod` 或 `CM_AGENT_PROFILE=production`，避免使用本地测试 profile。
- 不要在生产环境使用 `application-test.yml` 中的测试账号或测试 JWT 密钥。
```

- [ ] **Step 4: Verify documentation content**

Run:

```powershell
rg -n "CM_AGENT_PROFILE|application-test.yml|cm-agent-test-password-only|spring.profiles.active" README.md docs/configuration.md docs/deployment.md
rg -n "prod|production|bootstrap admin|外部 Secret|CM_AGENT_JWT_SECRET" docs/configuration.md docs/deployment.md
```

Expected: the first command prints matches in all three files; the second command prints production warnings in both `docs/configuration.md` and `docs/deployment.md`.

- [ ] **Step 5: Commit Task 2**

Run:

```powershell
git add README.md docs/configuration.md docs/deployment.md
git commit -m "docs: 补充测试 profile 启动说明"
```

Expected: commit succeeds and `git status --short` shows only files unrelated to Task 2, such as `docs/superpowers/progress-ledger.md` if the controller has updated it.

---

## Final Verification After All Tasks

Run from `F:\java\cm-agent\.worktrees\cm-agent-first-slice`:

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=ApplicationProfileConfigurationTest,JwtSecurityConfigurationTest,AuthControllerTest,ConsoleSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -q -pl cm-agent-core '-Dtest=DefaultToolAuthorizationPolicyTest' test
mvn -q -pl cm-agent-server -am '-DskipTests' package
docker compose config
```

Expected:

- All Maven commands exit with code `0`.
- `docker compose config` exits with code `0` and prints MySQL/PostgreSQL services.
- `git status --short --branch` shows the implementation branch ahead of `origin/cm-agent-first-slice`; it may also show the untracked progress ledger.

## Final Review

After all task-specific spec and code quality reviews pass, dispatch one final code reviewer for the full implementation range from `55d5b6d` to `HEAD`. The reviewer must check:

- The plan requirements are all implemented.
- Test profile credentials are documented as local-test-only.
- Production profile protections remain intact.
- No unrelated files were changed.

Then use `superpowers:finishing-a-development-branch` with option 2 semantics: push the branch and create or update the Pull Request for `cm-agent-first-slice`.
