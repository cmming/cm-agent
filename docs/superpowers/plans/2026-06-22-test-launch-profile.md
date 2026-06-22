# 测试环境启动配置实施计划

> **给智能体执行者：** 必须使用子技能：按任务逐项执行本计划时，优先使用 `superpowers:subagent-driven-development`，也可以使用 `superpowers:executing-plans`。执行进度使用复选框（`- [ ]`）语法跟踪。

**目标：** 在 `application.yml` 中提供环境选择器，并新增可直接用于本地测试启动的 `application-test.yml`。

**架构：** 使用 Spring Boot 原生 profile 机制：默认 profile 从 `CM_AGENT_PROFILE` 读取，未设置时使用 `local`；测试环境通过 `application-test.yml` 覆盖 JWT 和 bootstrap admin 配置。开发 JWT 回退默认关闭，只有显式设置 `CM_AGENT_ALLOW_DEV_JWT_FALLBACK=true` 才会启用；生产安全边界仍由现有 `JwtSecurityConfiguration` 和 `BootstrapAdminProperties` 保证，`prod`/`production` 不允许 bootstrap admin，也不能使用开发 JWT 回退。

**技术栈：** Java 21、Spring Boot 3.5、JUnit 5、AssertJ、Maven、YAML 配置、中文 Markdown 文档。

---

## 文件结构

- 新建：`cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`
  - 验证 `application.yml` 会启用默认 `local` profile。
  - 验证 `CM_AGENT_PROFILE=test` 会加载 `application-test.yml`。
  - 验证命令行形式的 `spring.profiles.active=test` 会加载 `application-test.yml`。
- 修改：`cm-agent-server/src/main/resources/application.yml`
  - 增加 `spring.profiles.active: ${CM_AGENT_PROFILE:local}`。
  - 增加 `cm-agent.security.allow-dev-jwt-fallback: ${CM_AGENT_ALLOW_DEV_JWT_FALLBACK:false}`，让本地回退必须显式开启。
- 新建：`cm-agent-server/src/main/resources/application-test.yml`
  - 包含仅用于测试的 JWT 密钥和 bootstrap admin 凭据。
  - 保持 fake runtime 开启，便于本地端到端验证。
- 修改：`README.md`
  - 增加简单的 test profile 启动路径。
  - 保留显式长命令形式的本地启动方式，方便不想使用 test profile 的用户。
- 修改：`docs/configuration.md`
  - 说明 `CM_AGENT_PROFILE`、`application-test.yml` 和测试凭据。
  - 重申生产环境必须从外部注入 Secret。
- 修改：`docs/deployment.md`
  - 说明生产 profile 选择，并警告不要在生产环境使用 test profile。

## 控制器台账

执行期间由控制器维护 `docs/superpowers/progress-ledger.md`，任务 subagent 不维护该文件。在任务 1、任务 2 和最终审查之后追加记录。除非用户明确要求发布，否则不要把该台账纳入任务提交。

---

### 任务 1：增加 Profile 配置和测试

**文件：**
- 新建：`cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`
- 修改：`cm-agent-server/src/main/resources/application.yml`
- 新建：`cm-agent-server/src/main/resources/application-test.yml`

- [ ] **步骤 1：编写失败测试**

使用以下完整内容新建 `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`：

```java
package com.cmagent.server.config;

import com.cmagent.server.CmAgentServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationProfileConfigurationTest {
    private static final String TEST_JWT_SECRET = "cm-agent-test-jwt-secret-with-at-least-32-bytes";
    private static final String TEST_ADMIN_PASSWORD = "cm-agent-test-password-only";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(CmAgentServerApplication.class)
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void defaultConfigurationActivatesLocalProfileFromCmAgentProfileSelector() {
        contextRunner.run(context -> {
            Environment environment = context.getEnvironment();

            assertThat(environment.getActiveProfiles()).containsExactly("local");
            assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isFalse();
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

    @Test
    void productionProfileRejectsMissingJwtSecretWhenConfigDataDefaultsAreLoaded() {
        webContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("生产环境必须外部提供 JWT 密钥");
                });
    }

    private static void assertTestProfileLoaded(Environment environment) {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(TEST_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-username")).isEqualTo("admin");
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-password")).isEqualTo(TEST_ADMIN_PASSWORD);
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-display-name")).isEqualTo("测试管理员");
        assertThat(environment.getProperty("cm-agent.fake-runtime-enabled", Boolean.class)).isTrue();
    }
}
```

- [ ] **步骤 2：运行新测试并确认失败**

使用 Java 21 运行：

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=ApplicationProfileConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：失败。由于当前 `application.yml` 尚未定义 `spring.profiles.active`、`cm-agent.security.allow-dev-jwt-fallback`，也尚未提供 `application-test.yml`，至少应有一个断言失败。有效失败信息包含以下任一内容：

```text
Expecting actual:
  []
to contain exactly:
  ["local"]
```

或：

```text
expected: "cm-agent-test-jwt-secret-with-at-least-32-bytes"
 but was: null
```

- [ ] **步骤 3：更新 `application.yml`**

用以下完整内容替换 `cm-agent-server/src/main/resources/application.yml`：

```yaml
server:
  port: 8080

spring:
  application:
    name: cm-agent-server
  profiles:
    # Production must set CM_AGENT_PROFILE=prod or production and inject CM_AGENT_JWT_SECRET.
    active: ${CM_AGENT_PROFILE:local}

management:
  endpoints:
    web:
      exposure:
        include: health,info

cm-agent:
  security:
    jwt-secret: ${CM_AGENT_JWT_SECRET:}
    # Local/test fallback only; production profiles still reject missing CM_AGENT_JWT_SECRET.
    allow-dev-jwt-fallback: ${CM_AGENT_ALLOW_DEV_JWT_FALLBACK:false}
    bootstrap-admin-enabled: false
    bootstrap-admin-username: ${CM_AGENT_BOOTSTRAP_ADMIN_USERNAME:admin}
    bootstrap-admin-password: ${CM_AGENT_BOOTSTRAP_ADMIN_PASSWORD:}
    bootstrap-admin-display-name: ${CM_AGENT_BOOTSTRAP_ADMIN_DISPLAY_NAME:系统管理员}
    public-api-docs-enabled: ${CM_AGENT_PUBLIC_API_DOCS_ENABLED:true}
  fake-runtime-enabled: true
  default-tenant-code: default
```

- [ ] **步骤 4：增加 `application-test.yml`**

使用以下完整内容新建 `cm-agent-server/src/main/resources/application-test.yml`：

```yaml
# Local test profile only. Do not use this configuration in production.
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

- [ ] **步骤 5：运行新测试并确认通过**

运行：

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=ApplicationProfileConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：通过，退出码为 `0`。

- [ ] **步骤 6：运行聚焦回归测试**

运行：

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=ApplicationProfileConfigurationTest,JwtSecurityConfigurationTest,AuthControllerTest,ConsoleSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

预期：通过，退出码为 `0`。显式 production profile 测试中关于缺少 JWT 的警告可以接受，因为它证明生产环境仍要求注入 Secret。

- [ ] **步骤 7：提交任务 1**

运行：

```powershell
git add 'cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java' 'cm-agent-server/src/main/resources/application.yml' 'cm-agent-server/src/main/resources/application-test.yml'
git commit -m "feat: 添加测试启动 profile 配置"
```

预期：提交成功，且 `git status --short` 只显示与任务 1 无关的文件，例如控制器已更新的 `docs/superpowers/progress-ledger.md`。

---

### 任务 2：更新中文启动文档

**文件：**
- 修改：`README.md`
- 修改：`docs/configuration.md`
- 修改：`docs/deployment.md`

- [ ] **步骤 1：更新 README 快速开始**

在 `README.md` 中，将整个 `## 快速开始` 章节替换为以下精确内容：

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

保持 `README.md` 的其他内容不变。

- [ ] **步骤 2：更新配置文档**

在 `docs/configuration.md` 中，将以下行加入基础配置表，并放在 `server.port` 后面：

```markdown
| `CM_AGENT_PROFILE` / `spring.profiles.active` | `local` | 运行环境选择器；默认进入本地 profile，可设置为 `test`、`prod` 或 `production` |
```

同时将以下行放在 `cm-agent.security.jwt-secret` 后面：

```markdown
| `cm-agent.security.allow-dev-jwt-fallback` | `false` | 是否允许 local/test profile 在缺少 JWT 密钥时使用开发回退密钥；仅限本地调试 |
```

然后在 `## fake runtime` 前插入以下新章节：

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

开发 JWT 回退默认关闭。需要本地无密钥调试时，可以显式设置 `CM_AGENT_ALLOW_DEV_JWT_FALLBACK=true`；生产环境不得启用该开关。

生产部署应设置 `CM_AGENT_PROFILE=prod` 或 `CM_AGENT_PROFILE=production`，并通过外部 Secret 注入 `CM_AGENT_JWT_SECRET`。生产 profile 下启用 bootstrap admin 会导致服务启动失败。
````

然后将 `## JWT 密钥` 下当前写着 `本地开发可以临时使用命令行参数：` 的句子替换为以下精确句子：

```markdown
本地测试优先使用 `CM_AGENT_PROFILE=test`；需要手动覆盖配置时，也可以临时使用命令行参数：
```

- [ ] **步骤 3：更新部署文档**

在 `docs/deployment.md` 的 `## 启动服务端` 下，将以下内容插入到现有句子 `本地开发可以通过命令行传入安全长度的 JWT 密钥启动服务端：` 之前：

````markdown
本地测试可以使用 `test` profile 快速启动：

```powershell
$env:CM_AGENT_PROFILE='test'
mvn -pl cm-agent-server -am spring-boot:run
```

`test` profile 会启用本地 bootstrap admin，测试账号为 `admin`，密码为 `cm-agent-test-password-only`。该 profile 仅用于本地测试。
````

在 `docs/deployment.md` 中，将以下两条加入 `## 生产部署要点`，并放在 JWT secret 条目之后：

```markdown
- 生产部署必须显式设置 `CM_AGENT_PROFILE=prod` 或 `CM_AGENT_PROFILE=production`，避免使用本地测试 profile。
- 不要在生产环境使用 `application-test.yml` 中的测试账号或测试 JWT 密钥。
```

- [ ] **步骤 4：验证文档内容**

运行：

```powershell
rg -n "CM_AGENT_PROFILE|application-test.yml|cm-agent-test-password-only|spring.profiles.active" README.md docs/configuration.md docs/deployment.md
rg -n "prod|production|bootstrap admin|外部 Secret|CM_AGENT_JWT_SECRET" docs/configuration.md docs/deployment.md
```

预期：第一条命令在三个文件中都打印匹配结果；第二条命令在 `docs/configuration.md` 和 `docs/deployment.md` 中都打印生产环境警告。

- [ ] **步骤 5：提交任务 2**

运行：

```powershell
git add README.md docs/configuration.md docs/deployment.md
git commit -m "docs: 补充测试 profile 启动说明"
```

预期：提交成功，且 `git status --short` 只显示与任务 2 无关的文件，例如控制器已更新的 `docs/superpowers/progress-ledger.md`。

---

## 全部任务后的最终验证

从 `F:\java\cm-agent\.worktrees\cm-agent-first-slice` 运行：

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=ApplicationProfileConfigurationTest,JwtSecurityConfigurationTest,AuthControllerTest,ConsoleSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -q -pl cm-agent-core '-Dtest=DefaultToolAuthorizationPolicyTest' test
mvn -q -pl cm-agent-server -am '-DskipTests' package
docker compose config
```

预期：

- 所有 Maven 命令退出码均为 `0`。
- `docker compose config` 退出码为 `0`，并打印 MySQL/PostgreSQL 服务。
- `git status --short --branch` 显示实现分支领先于 `origin/cm-agent-first-slice`；也可能显示未跟踪的进度台账。

## 最终审查

所有任务级规格审查和代码质量审查通过后，为从 `55d5b6d` 到 `HEAD` 的完整实现范围派发一次最终代码审查。审查者必须检查：

- 计划要求已全部实现。
- test profile 凭据已说明仅限本地测试。
- 生产 profile 保护仍然有效。
- 没有修改无关文件。

然后按选项 2 的语义使用 `superpowers:finishing-a-development-branch`：推送分支，并为 `cm-agent-first-slice` 创建或更新 Pull Request。
