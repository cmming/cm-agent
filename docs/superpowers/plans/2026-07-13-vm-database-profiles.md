# VM Database Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add selectable `postgres` and `mysql` Spring profiles for the CM Agent virtual-machine databases hosted at `192.168.0.66`.

**Architecture:** The new profiles stay inside the existing configuration architecture: profile YAML files define only `cm-agent.config.*` variables, and the shared `application.yml` maps those variables to real Spring properties. Tests cover variable-to-property binding, while documentation explains which profile to choose and how to start it.

**Tech Stack:** Java 21, Maven multi-module build, Spring Boot 3.5.0 ConfigData, JUnit Jupiter, AssertJ, YAML resource profiles.

## Global Constraints

- Java must remain on POM release 21; validate with `java -version` and `mvn -v` before final verification.
- Do not modify existing Flyway migrations, Repository interfaces, JDBC implementations, controllers, security policy, or schema.
- New profile files must live in `cm-agent-server/src/main/resources`.
- New profile files must define environment differences under `cm-agent.config.*`; do not duplicate `cm-agent.persistence.*` or `cm-agent.security.*` actual binding trees.
- `postgres` profile must use `jdbc:postgresql://192.168.0.66:5432/cm_agent`, username `cmagent`, password `cmagent`, and driver `org.postgresql.Driver`.
- `mysql` profile must use `jdbc:mysql://192.168.0.66:3306/cm_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`, username `root`, password `cmagent`, and driver `com.mysql.cj.jdbc.Driver`.
- Both virtual-machine profiles must set `persistence-mode: jdbc`, `allow-dev-jwt-fallback: false`, and `bootstrap-admin-enabled: false`.
- Both virtual-machine profiles must provide the same standalone VM JWT secret: `cm-agent-vm-profile-jwt-secret-with-at-least-32-bytes-2026`.
- Document that MySQL `root`, password `cmagent`, and `allowPublicKeyRetrieval=true` are only for the current virtual-machine integration environment, not a production hardening pattern.
- Keep existing untracked user files such as `AGENTS.md` and `.superpowers/` unless a task explicitly updates `.superpowers/sdd/progress.md` for the required ledger.

---

## File Structure

- Create `cm-agent-server/src/main/resources/application-postgres.yml`: PostgreSQL virtual-machine profile variables.
- Create `cm-agent-server/src/main/resources/application-mysql.yml`: MySQL virtual-machine profile variables.
- Modify `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`: add tests that prove `postgres` and `mysql` profiles load into both `cm-agent.config.*` and actual `cm-agent.persistence.*` / `cm-agent.security.*` properties.
- Modify `docs/configuration.md`: document the new profiles in the profile and runtime storage sections.
- Modify `docs/deployment.md`: add a virtual-machine database scenario with exact start commands.
- Modify `docs/release-notes.md`: record the profile addition in the snapshot release notes.

---

### Task 1: VM Database Profile YAML and Binding Tests

**Files:**
- Create: `cm-agent-server/src/main/resources/application-postgres.yml`
- Create: `cm-agent-server/src/main/resources/application-mysql.yml`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`

**Interfaces:**
- Consumes: Spring Boot ConfigData loading via `ConfigDataApplicationContextInitializer`.
- Produces: `postgres` and `mysql` Spring profiles that map `cm-agent.config.*` values to `cm-agent.persistence.jdbc.*` and `cm-agent.security.*`.

- [ ] **Step 1: Write the failing profile binding tests**

In `ApplicationProfileConfigurationTest.java`, add these constants near the existing test constants:

```java
    private static final String VM_JWT_SECRET = "cm-agent-vm-profile-jwt-secret-with-at-least-32-bytes-2026";
    private static final String POSTGRES_JDBC_URL = "jdbc:postgresql://192.168.0.66:5432/cm_agent";
    private static final String MYSQL_JDBC_URL = "jdbc:mysql://192.168.0.66:3306/cm_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
```

Add these two tests after `prodProfileActivatesProductionVariableGroup()`:

```java
    @Test
    void postgresProfileLoadsVirtualMachineJdbcConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=postgres")
                .run(context -> assertPostgresProfileLoaded(context.getEnvironment()));
    }

    @Test
    void mysqlProfileLoadsVirtualMachineJdbcConfiguration() {
        contextRunner
                .withPropertyValues("spring.profiles.active=mysql")
                .run(context -> assertMysqlProfileLoaded(context.getEnvironment()));
    }
```

Add these helper methods before `assertLocalProfileLoaded(Environment environment)`:

```java
    private static void assertPostgresProfileLoaded(Environment environment) {
        assertVirtualMachineProfileLoaded(
                environment,
                "postgres",
                POSTGRES_JDBC_URL,
                "cmagent",
                "org.postgresql.Driver"
        );
    }

    private static void assertMysqlProfileLoaded(Environment environment) {
        assertVirtualMachineProfileLoaded(
                environment,
                "mysql",
                MYSQL_JDBC_URL,
                "root",
                "com.mysql.cj.jdbc.Driver"
        );
    }

    private static void assertVirtualMachineProfileLoaded(
            Environment environment,
            String profile,
            String jdbcUrl,
            String username,
            String driverClassName
    ) {
        assertThat(environment.getActiveProfiles()).containsExactly(profile);
        assertThat(environment.getProperty("cm-agent.config.jwt-secret")).isEqualTo(VM_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.security.jwt-secret")).isEqualTo(VM_JWT_SECRET);
        assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
        assertThat(environment.getProperty("cm-agent.persistence.mode")).isEqualTo("jdbc");
        assertThat(environment.getProperty("cm-agent.config.jdbc-url")).isEqualTo(jdbcUrl);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.url")).isEqualTo(jdbcUrl);
        assertThat(environment.getProperty("cm-agent.config.jdbc-username")).isEqualTo(username);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.username")).isEqualTo(username);
        assertThat(environment.getProperty("cm-agent.config.jdbc-password")).isEqualTo("cmagent");
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.password")).isEqualTo("cmagent");
        assertThat(environment.getProperty("cm-agent.config.jdbc-driver-class-name")).isEqualTo(driverClassName);
        assertThat(environment.getProperty("cm-agent.persistence.jdbc.driver-class-name")).isEqualTo(driverClassName);
        assertThat(environment.getProperty("cm-agent.security.allow-dev-jwt-fallback", Boolean.class)).isFalse();
        assertThat(environment.getProperty("cm-agent.security.bootstrap-admin-enabled", Boolean.class)).isFalse();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```powershell
mvn -pl cm-agent-server -Dtest=ApplicationProfileConfigurationTest test
```

Expected: FAIL. The new tests should fail because `application-postgres.yml` and `application-mysql.yml` do not exist yet, so the active profile loads but the asserted `cm-agent.config.*` JDBC values are missing or still defaulted.

- [ ] **Step 3: Add the PostgreSQL profile YAML**

Create `cm-agent-server/src/main/resources/application-postgres.yml` with exactly this content:

```yaml
# Virtual-machine PostgreSQL profile. Values mirror 192.168.0.66:/data/cm-agent/docker-compose.yml.

cm-agent:
  config:
    jwt-secret: cm-agent-vm-profile-jwt-secret-with-at-least-32-bytes-2026
    allow-dev-jwt-fallback: false
    bootstrap-admin-enabled: false
    persistence-mode: jdbc
    jdbc-url: jdbc:postgresql://192.168.0.66:5432/cm_agent
    jdbc-username: cmagent
    jdbc-password: cmagent
    jdbc-driver-class-name: org.postgresql.Driver
```

- [ ] **Step 4: Add the MySQL profile YAML**

Create `cm-agent-server/src/main/resources/application-mysql.yml` with exactly this content:

```yaml
# Virtual-machine MySQL profile. Values mirror 192.168.0.66:/data/cm-agent/docker-compose.yml.

cm-agent:
  config:
    jwt-secret: cm-agent-vm-profile-jwt-secret-with-at-least-32-bytes-2026
    allow-dev-jwt-fallback: false
    bootstrap-admin-enabled: false
    persistence-mode: jdbc
    jdbc-url: jdbc:mysql://192.168.0.66:3306/cm_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    jdbc-username: root
    jdbc-password: cmagent
    jdbc-driver-class-name: com.mysql.cj.jdbc.Driver
```

- [ ] **Step 5: Run the focused tests to verify green**

Run:

```powershell
mvn -pl cm-agent-server -Dtest=ApplicationProfileConfigurationTest test
```

Expected: PASS. The output should show `ApplicationProfileConfigurationTest` completed with zero failures and zero errors.

- [ ] **Step 6: Commit Task 1**

Run:

```powershell
git add -- cm-agent-server/src/main/resources/application-postgres.yml cm-agent-server/src/main/resources/application-mysql.yml cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java
git commit -m "feat: add vm database profiles"
```

---

### Task 2: Documentation for VM Database Profiles

**Files:**
- Modify: `docs/configuration.md`
- Modify: `docs/deployment.md`
- Modify: `docs/release-notes.md`

**Interfaces:**
- Consumes: The profile names and exact values produced by Task 1.
- Produces: Chinese documentation that tells operators when and how to use `postgres` and `mysql` profiles.

- [ ] **Step 1: Run documentation checks to verify the new profile docs are absent**

Run:

```powershell
rg -n "spring.profiles.active=postgres|spring.profiles.active=mysql|192.168.0.66" docs/configuration.md docs/deployment.md docs/release-notes.md
```

Expected: FAIL with exit code 1 and no matching lines, because the virtual-machine profile documentation has not been added yet.

- [ ] **Step 2: Update configuration documentation**

In `docs/configuration.md`, change the `spring.profiles.active` row from:

```markdown
| `spring.profiles.active` | `local` | 运行环境选择器；默认进入本地 profile，可设置为 `test`、`prod` 或 `production` |
```

to:

```markdown
| `spring.profiles.active` | `local` | 运行环境选择器；默认进入本地 profile，可设置为 `test`、`postgres`、`mysql`、`prod`、`production` 或 `supabase` |
```

After the paragraph that ends with `该配置包含可直接使用的测试凭据，只能用于本地测试。`, insert this exact section:

````markdown
### 内网虚拟机数据库 profile

部署节点 `192.168.0.66:/data/cm-agent/docker-compose.yml` 提供 PostgreSQL 和 MySQL 两套虚拟机数据库。需要直接连接这套数据库时，可以选择内置 profile：

- `postgres`：连接 `jdbc:postgresql://192.168.0.66:5432/cm_agent`，账号 `cmagent`，密码 `cmagent`，驱动 `org.postgresql.Driver`。
- `mysql`：连接 `jdbc:mysql://192.168.0.66:3306/cm_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`，账号 `root`，密码 `cmagent`，驱动 `com.mysql.cj.jdbc.Driver`。

两个 profile 都启用 `jdbc` 持久化、关闭 bootstrap admin、关闭开发 JWT fallback，并内置仅用于该虚拟机联调环境的 JWT 密钥。MySQL `root` 账号、`cmagent` 密码和 `allowPublicKeyRetrieval=true` 仅适用于当前虚拟机联调配置；生产化部署仍应改为最小权限账号、强密码和 TLS。

PostgreSQL 启动示例：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=postgres"
```

MySQL 启动示例：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=mysql"
```
````

In the runtime storage paragraph, change:

```markdown
服务端默认使用 memory store，适合本地演示和纵切验证。生产或类生产环境必须使用 JDBC 持久化：
```

to:

```markdown
服务端默认使用 memory store，适合本地演示和纵切验证。`postgres`、`mysql`、`production`、`prod` 和 `supabase` 等数据库 profile 使用 JDBC 持久化：
```

- [ ] **Step 3: Update deployment documentation**

In `docs/deployment.md`, find the paragraph in "数据库场景与项目配置" that starts with `数据库配置建议写入部署节点上的外部 YAML 配置文件，例如本地` and ends with `连接信息必须写入受控外部 YAML。`, then replace the whole paragraph with:

```markdown
数据库配置建议写入部署节点上的外部 YAML 配置文件，例如本地 `./config/application-local.yml`、生产 `/etc/cm-agent/application-production.yml` 或 `/etc/cm-agent/application-supabase.yml`。当前内网虚拟机数据库也提供内置 `postgres` 和 `mysql` profile，可直接复用 `192.168.0.66:/data/cm-agent/docker-compose.yml` 中的连接信息。启动时通过 `--spring.config.additional-location=file:./config/` 或 `--spring.config.additional-location=file:/etc/cm-agent/` 加载外部配置目录，并通过 `--spring.profiles.active=local`、`--spring.profiles.active=postgres`、`--spring.profiles.active=mysql`、`--spring.profiles.active=production` 或 `--spring.profiles.active=supabase` 选择运行 profile。数据库环境变量方式当前不使用；连接信息必须写入受控外部 YAML 或明确的内网虚拟机 profile。
```

After the existing "### 场景三：本地 MySQL，使用 docker-compose" section and before the current "### 场景四：生产或类生产 PostgreSQL" heading, insert this exact new section:

````markdown
### 场景四：内网虚拟机数据库 profile

适用于直接连接部署节点 `192.168.0.66:/data/cm-agent/docker-compose.yml` 中已经启动的数据库。该场景不需要额外编写外部 YAML；profile 已内置当前虚拟机的 JDBC URL、账号、密码和驱动。

PostgreSQL profile：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=postgres"
```

`postgres` 会连接 `jdbc:postgresql://192.168.0.66:5432/cm_agent`，使用账号 `cmagent`、密码 `cmagent` 和驱动 `org.postgresql.Driver`。

MySQL profile：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=mysql"
```

`mysql` 会连接 `jdbc:mysql://192.168.0.66:3306/cm_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`，使用账号 `root`、密码 `cmagent` 和驱动 `com.mysql.cj.jdbc.Driver`。

两个 profile 都启用 JDBC/Flyway，首次连接空库时会自动执行 `classpath:db/migration`。MySQL `root` 账号、`cmagent` 密码和 `allowPublicKeyRetrieval=true` 仅适用于当前虚拟机联调配置；生产化 MySQL 仍应使用最小权限账号、强密码、TLS 和受控外部配置文件。
````

Then renumber these later headings:

```markdown
### 场景四：生产或类生产 PostgreSQL
### 场景五：生产或类生产 MySQL
### 场景六：Supabase PostgreSQL
```

to:

```markdown
### 场景五：生产或类生产 PostgreSQL
### 场景六：生产或类生产 MySQL
### 场景七：Supabase PostgreSQL
```

In "生产部署要点", change:

```markdown
- 生产部署必须显式设置 `spring.profiles.active=prod`、`spring.profiles.active=production` 或用于 Supabase 托管 PostgreSQL 的 `spring.profiles.active=supabase`，避免使用本地测试 profile。
```

to:

```markdown
- 生产部署必须显式设置 `spring.profiles.active=prod`、`spring.profiles.active=production` 或用于 Supabase 托管 PostgreSQL 的 `spring.profiles.active=supabase`，避免使用本地测试 profile；内网虚拟机联调可使用 `spring.profiles.active=postgres` 或 `spring.profiles.active=mysql`。
```

- [ ] **Step 4: Update release notes**

In `docs/release-notes.md`, after this bullet:

```markdown
- 本地开发调试 profile `application-local.yml`，提供本地专用 JWT、bootstrap admin 和 memory 持久化默认值。
```

insert:

```markdown
- 内网虚拟机数据库 profile `application-postgres.yml` 和 `application-mysql.yml`，可直接连接 `192.168.0.66:/data/cm-agent/docker-compose.yml` 中的 PostgreSQL 或 MySQL。
```

- [ ] **Step 5: Run documentation checks to verify green**

Run:

```powershell
rg -n "spring.profiles.active=postgres|spring.profiles.active=mysql|192.168.0.66" docs/configuration.md docs/deployment.md docs/release-notes.md
```

Expected: PASS with matches in all three docs files.

- [ ] **Step 6: Commit Task 2**

Run:

```powershell
git add -- docs/configuration.md docs/deployment.md docs/release-notes.md
git commit -m "docs: document vm database profiles"
```

---

## Final Verification

After both tasks are complete, run:

```powershell
java -version
mvn -v
mvn -pl cm-agent-server -Dtest=ApplicationProfileConfigurationTest test
mvn -pl cm-agent-server -am test
git diff --check
rg -n "spring.profiles.active=postgres|spring.profiles.active=mysql|192.168.0.66" docs/configuration.md docs/deployment.md docs/release-notes.md
```

Expected:

- Java reports a JDK 21 runtime.
- Maven reports it is using Java 21.
- Focused profile tests pass with zero failures and zero errors.
- Server module tests pass with zero failures and zero errors. If Docker is unavailable for Testcontainers, record the exact failure and rerun the non-Docker focused tests.
- `git diff --check` exits 0.
- Documentation search returns matches in `docs/configuration.md`, `docs/deployment.md`, and `docs/release-notes.md`.
