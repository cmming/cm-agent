# Server YAML Variable Overrides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将服务端公共配置集中到 `application.yml`，并让各环境 profile 仅以 `cm-agent.config.*` 变量覆盖差异值。

**Architecture:** `application.yml` 保留全部 Spring 与 `cm-agent` 实际绑定属性，并通过占位符读取 `cm-agent.config.*`。`local`、`test`、`supabase`、`production` profile 文件只定义变量；外部 YAML 使用同一变量命名空间覆盖生产密钥和数据库连接信息。`prod` profile group 复用 `production` 的安全默认值。

**Tech Stack:** Java 21、Maven 3.9、Spring Boot 3.5、Spring `ConfigDataApplicationContextInitializer`、YAML、JUnit Jupiter、AssertJ。

## Global Constraints

- Maven 构建必须使用 `F:\java21` 的 JDK 21；当前默认 JDK 17 不可用于项目验证。
- 不得写入真实 JWT 密钥、生产数据库密码或生产 JDBC URL；删除 `application.yml` 中现有顶层数据库连接信息。
- 保留 `production`、`prod`、`supabase` 的 JDBC、JWT、bootstrap admin 与 test profile 混用保护；不修改保护逻辑。
- 不覆盖工作区已有的无关改动；只修改本计划列出的配置、测试与文档文件。
- 配置行为修改必须先写失败测试并观察预期失败，再进行 YAML 实现。
- 数据库配置的生产说明必须使用受控外部 YAML 的 `cm-agent.config.*` 变量，不再以 `CM_AGENT_JDBC_*` 或 `CM_AGENT_PERSISTENCE_*` 环境变量示例配置。

---

### Task 1: 用 profile 变量驱动公共 YAML 配置

**Files:**
- Modify: `cm-agent-server/src/main/resources/application.yml`
- Modify: `cm-agent-server/src/main/resources/application-local.yml`
- Modify: `cm-agent-server/src/main/resources/application-test.yml`
- Modify: `cm-agent-server/src/main/resources/application-supabase.yml`
- Create: `cm-agent-server/src/main/resources/application-production.yml`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`

**Interfaces:**
- Consumes: `cm-agent.config.server-port`、`jwt-secret`、`allow-dev-jwt-fallback`、`bootstrap-admin-enabled`、`bootstrap-admin-username`、`bootstrap-admin-password`、`bootstrap-admin-display-name`、`public-api-docs-enabled`、`fake-runtime-enabled`、`default-tenant-code`、`persistence-mode`、`jdbc-url`、`jdbc-username`、`jdbc-password`、`jdbc-driver-class-name`。
- Produces: 公共配置项 `server.port`、`cm-agent.security.*`、`cm-agent.fake-runtime-enabled`、`cm-agent.default-tenant-code`、`cm-agent.persistence.*` 从同名 `cm-agent.config.*` 变量解析。

- [x] **Step 1: 写入失败测试**

在 `ApplicationProfileConfigurationTest` 增加下列断言，并将依赖 `CM_AGENT_PROFILE=test` 的测试替换为显式 `spring.profiles.active=test` 测试：

```java
@Test
void localProfileVariablesOverrideCommonConfiguration() {
    contextRunner
            .withPropertyValues("spring.profiles.active=local")
            .run(context -> {
                Environment environment = context.getEnvironment();

                assertThat(environment.getProperty("cm-agent.config.jwt-secret"))
                        .isEqualTo(LOCAL_JWT_SECRET);
                assertThat(environment.getProperty("cm-agent.security.jwt-secret"))
                        .isEqualTo(LOCAL_JWT_SECRET);
                assertThat(environment.getProperty("cm-agent.config.bootstrap-admin-password"))
                        .isEqualTo(LOCAL_ADMIN_PASSWORD);
            });
}

@Test
void productionProfileProvidesJdbcConfigurationVariables() {
    contextRunner
            .withPropertyValues("spring.profiles.active=production")
            .run(context -> assertThat(context.getEnvironment()
                    .getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc"));
}

@Test
void prodProfileActivatesProductionVariableGroup() {
    contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run(context -> {
                Environment environment = context.getEnvironment();

                assertThat(environment.getActiveProfiles()).contains("prod", "production");
                assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
            });
}
```

在现有 `supabaseProfileLoadsJdbcDefaultsFromConfigData` 中补充：

```java
assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
assertThat(environment.getProperty("cm-agent.config.jdbc-driver-class-name"))
        .isEqualTo("org.postgresql.Driver");
```

- [x] **Step 2: 运行测试并确认失败**

```powershell
$env:JAVA_HOME='F:\java21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：失败，且失败断言显示 `cm-agent.config.jwt-secret` 或 `cm-agent.config.persistence-mode` 为 `null`，因为 profile 尚未定义变量。

- [x] **Step 3: 实施最小 YAML 配置重构**

将 `application.yml` 改为以下完整结构，删除文件顶部所有 `CM_AGENT_*` 条目：

```yaml
server:
  port: ${cm-agent.config.server-port:8080}

spring:
  application:
    name: cm-agent-server
  profiles:
    active: local
    group:
      prod: production

management:
  endpoints:
    web:
      exposure:
        include: health,info

cm-agent:
  security:
    jwt-secret: ${cm-agent.config.jwt-secret:}
    allow-dev-jwt-fallback: ${cm-agent.config.allow-dev-jwt-fallback:false}
    bootstrap-admin-enabled: ${cm-agent.config.bootstrap-admin-enabled:false}
    bootstrap-admin-username: ${cm-agent.config.bootstrap-admin-username:admin}
    bootstrap-admin-password: ${cm-agent.config.bootstrap-admin-password:}
    bootstrap-admin-display-name: ${cm-agent.config.bootstrap-admin-display-name:系统管理员}
    public-api-docs-enabled: ${cm-agent.config.public-api-docs-enabled:true}
  fake-runtime-enabled: ${cm-agent.config.fake-runtime-enabled:true}
  default-tenant-code: ${cm-agent.config.default-tenant-code:default}
  persistence:
    mode: ${cm-agent.config.persistence-mode:memory}
    jdbc:
      url: ${cm-agent.config.jdbc-url:}
      username: ${cm-agent.config.jdbc-username:}
      password: ${cm-agent.config.jdbc-password:}
      driver-class-name: ${cm-agent.config.jdbc-driver-class-name:}
```

将各 profile 文件收敛为变量树：

```yaml
# application-local.yml
cm-agent:
  config:
    jwt-secret: cm-agent-local-dev-jwt-secret-with-at-least-32-bytes-2026
    bootstrap-admin-enabled: true
    bootstrap-admin-username: admin
    bootstrap-admin-password: cm-agent-local-dev-password-only
    bootstrap-admin-display-name: 本地管理员
    persistence-mode: memory

# application-test.yml
cm-agent:
  config:
    jwt-secret: cm-agent-test-jwt-secret-with-at-least-32-bytes
    bootstrap-admin-enabled: true
    bootstrap-admin-username: admin
    bootstrap-admin-password: cm-agent-test-password-only
    bootstrap-admin-display-name: 测试管理员

# application-supabase.yml
cm-agent:
  config:
    allow-dev-jwt-fallback: false
    bootstrap-admin-enabled: false
    public-api-docs-enabled: false
    persistence-mode: jdbc
    jdbc-driver-class-name: org.postgresql.Driver

# application-production.yml
cm-agent:
  config:
    allow-dev-jwt-fallback: false
    bootstrap-admin-enabled: false
    public-api-docs-enabled: false
    persistence-mode: jdbc
```

不在内置 `production` 与 `supabase` profile 中填写 JWT 密钥、JDBC URL、用户名或密码。保留现有 profile 文件注释，并更新其文字为“只定义变量”。

- [x] **Step 4: 运行配置测试并确认通过**

```powershell
$env:JAVA_HOME='F:\java21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：`ApplicationProfileConfigurationTest` 全部通过；`prod` 激活 `production` group，local/test/supabase 的实际绑定属性仍保留原行为。

- [x] **Step 5: 自检并提交任务改动**

```powershell
git diff --check -- cm-agent-server/src/main/resources cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java
git add -- cm-agent-server/src/main/resources/application.yml cm-agent-server/src/main/resources/application-local.yml cm-agent-server/src/main/resources/application-test.yml cm-agent-server/src/main/resources/application-supabase.yml cm-agent-server/src/main/resources/application-production.yml cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java
git commit -m "refactor: 使用 profile 变量组织服务端配置"
```

预期：无空白错误；提交仅包含本任务的 YAML 与测试文件。

### Task 2: 同步配置、部署与发布文档

**Files:**
- Modify: `README.md`
- Modify: `docs/configuration.md`
- Modify: `docs/deployment.md`
- Modify: `docs/release-notes.md`

**Interfaces:**
- Consumes: Task 1 中 `cm-agent.config.*` 变量与 `--spring.profiles.active` 启动方式。
- Produces: 文档以受控外部 YAML 配置生产数据库和密钥，不再提供任何 `CM_AGENT_*` 环境变量配置示例。

- [x] **Step 1: 运行文档静态检查并确认失败**

```powershell
$legacy = rg -n 'CM_AGENT_' README.md docs\configuration.md docs\deployment.md
if ($LASTEXITCODE -eq 0) {
    $legacy
    exit 1
}
if ($LASTEXITCODE -eq 1) {
    exit 0
}
exit $LASTEXITCODE
```

预期：失败，并列出当前文档中已失效的 `CM_AGENT_*` 示例。

- [x] **Step 2: 更新文档以使用配置变量**

在 `README.md` 中仅使用 `--spring.profiles.active=local` 或 `test` 选择 profile，临时覆盖示例改为 `--cm-agent.config.jwt-secret=...`、`--cm-agent.config.bootstrap-admin-enabled=true` 与 `--cm-agent.config.bootstrap-admin-password=...`。

在 `docs/configuration.md` 中声明：`application.yml` 包含实际绑定项，profile 与外部 YAML 仅设置 `cm-agent.config.*`。使用下表说明数据库变量：

```markdown
| 配置变量 | 映射的实际配置项 | 说明 |
| --- | --- | --- |
| `cm-agent.config.jwt-secret` | `cm-agent.security.jwt-secret` | 生产环境由受控外部 YAML 提供 |
| `cm-agent.config.persistence-mode` | `cm-agent.persistence.mode` | `memory` 或 `jdbc` |
| `cm-agent.config.jdbc-url` | `cm-agent.persistence.jdbc.url` | 启用 JDBC 时必填 |
| `cm-agent.config.jdbc-username` | `cm-agent.persistence.jdbc.username` | 数据库用户名 |
| `cm-agent.config.jdbc-password` | `cm-agent.persistence.jdbc.password` | 仅写入受控外部 YAML |
| `cm-agent.config.jdbc-driver-class-name` | `cm-agent.persistence.jdbc.driver-class-name` | PostgreSQL 或 MySQL JDBC 驱动 |
```

在 `docs/deployment.md` 的所有外部 YAML 示例中使用下列结构，并将描述中的 `CM_AGENT_JDBC_*` 改为“数据库环境变量”：

```yaml
cm-agent:
  config:
    jwt-secret: <secret-manager-jwt-secret>
    persistence-mode: jdbc
    jdbc-url: jdbc:postgresql://<db-host>:5432/<database-name>
    jdbc-username: <least-privilege-db-user>
    jdbc-password: <secret-manager-db-password>
    jdbc-driver-class-name: org.postgresql.Driver
```

保留本地 Docker 示例凭据的“仅开发使用”提示。Supabase 外部 YAML 同样使用 `cm-agent.config.*`，并保留 development branch 验证流程。

在 `docs/release-notes.md` 的 `0.1.0-SNAPSHOT` 条目中增加一项：服务端公共 YAML 已集中，profile 和受控外部 YAML 使用 `cm-agent.config.*` 覆盖环境差异。

- [x] **Step 3: 运行静态检查并确认通过**

```powershell
$legacy = rg -n 'CM_AGENT_' README.md docs\configuration.md docs\deployment.md
if ($LASTEXITCODE -eq 0) {
    $legacy
    exit 1
}
if ($LASTEXITCODE -eq 1) {
    'No legacy profile or database environment-variable examples remain.'
    exit 0
}
exit $LASTEXITCODE
```

```powershell
rg -n 'cm-agent\.config\.(jwt-secret|persistence-mode|jdbc-url|jdbc-username|jdbc-password|jdbc-driver-class-name)' README.md docs\configuration.md docs\deployment.md
```

预期：第一条命令输出 `No legacy profile or database environment-variable examples remain.` 并退出 `0`；第二条命令在配置和部署文档中找到新的变量命名。

- [x] **Step 4: 校验 YAML、差异并提交任务改动**

```powershell
$env:JAVA_HOME='F:\java21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check -- README.md docs/configuration.md docs/deployment.md docs/release-notes.md
git add -- README.md docs/configuration.md docs/deployment.md docs/release-notes.md
git commit -m "docs: 说明服务端配置变量覆盖方式"
```

预期：配置测试和差异检查通过；提交仅包含本任务的文档文件。

### Task 3: 修复 legacy profile 选择器的生产安全回退

**Files:**
- Modify: `cm-agent-server/src/main/resources/application.yml`
- Modify: `cm-agent-server/src/main/resources/application-production.yml`
- Modify: `cm-agent-server/src/main/resources/application-supabase.yml`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`
- Modify: `docs/deployment.md`
- Modify: `docs/release-notes.md`

**Interfaces:**
- Consumes: 旧部署提供的 `CM_AGENT_PROFILE`，仅作为 profile 选择器兼容桥接；新部署仍使用 `spring.profiles.active`。
- Produces: `CM_AGENT_PROFILE=production` 激活 production profile，而非 silently 落入 local profile；外部 `application-production.yml` 的 `cm-agent.config.*` 覆盖行为有回归测试。

- [x] **Step 1: 写入失败回归测试并确认 RED**

在 `ApplicationProfileConfigurationTest` 添加：

```java
@Test
void legacyProfileSelectorActivatesProductionInsteadOfFallingBackToLocal() {
    contextRunner
            .withPropertyValues("CM_AGENT_PROFILE=production")
            .run(context -> {
                Environment environment = context.getEnvironment();

                assertThat(environment.getActiveProfiles()).containsExactly("production");
                assertThat(environment.getProperty("cm-agent.config.persistence-mode")).isEqualTo("jdbc");
            });
}
```

再使用 JUnit `@TempDir Path configDirectory` 创建外部 `application-production.yml`，内容为 `cm-agent.config.jwt-secret`、`jdbc-url`、`jdbc-username`、`jdbc-password` 和 `jdbc-driver-class-name`，并断言通过 `spring.config.additional-location=optional:file:<目录>/` 加载后实际 `cm-agent.security.jwt-secret`、`cm-agent.persistence.jdbc.url`、`username`、`password`、`driver-class-name` 与外部变量一致。

运行：

```powershell
$env:JAVA_HOME='F:\java21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：新增 legacy selector 断言失败，显示 active profile 是 `local` 或 persistence mode 是 `memory`。

- [x] **Step 2: 实施兼容桥接和文档清理**

将 `application.yml` 改为：

```yaml
spring:
  profiles:
    # Legacy profile selector is supported temporarily; new deployments use spring.profiles.active.
    active: ${CM_AGENT_PROFILE:local}
```

不要恢复任何 `CM_AGENT_JDBC_*`、`CM_AGENT_PERSISTENCE_*`、JWT 或 bootstrap admin 环境变量占位符。

将 `application-production.yml` 与 `application-supabase.yml` 注释改为“密钥和 JDBC 连接详情必须由受控外部 YAML 或密钥管理服务生成/挂载”，不得再写环境变量注入。

在 `docs/deployment.md` 的 local MySQL 示例 JDBC URL 中显式加入 `allowPublicKeyRetrieval=true`，使其与本地 Docker 专用提示一致。

在 `docs/release-notes.md` 增加迁移说明：旧 `CM_AGENT_PROFILE` 只保留为临时 profile 选择器兼容桥接，新部署应使用 `spring.profiles.active`；数据库和密钥继续使用受控外部 YAML 的 `cm-agent.config.*`。

- [x] **Step 3: 验证、复审并提交**

```powershell
$env:JAVA_HOME='F:\java21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl cm-agent-server -am "-Dtest=ApplicationProfileConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check -- cm-agent-server/src/main/resources/application.yml cm-agent-server/src/main/resources/application-production.yml cm-agent-server/src/main/resources/application-supabase.yml cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java docs/deployment.md docs/release-notes.md
git add -- cm-agent-server/src/main/resources/application.yml cm-agent-server/src/main/resources/application-production.yml cm-agent-server/src/main/resources/application-supabase.yml cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java docs/deployment.md docs/release-notes.md
git commit -m "fix: 兼容 legacy profile 选择器"
```

预期：profile 测试全部通过；old `CM_AGENT_PROFILE=production` 不会启动 local profile；提交仅包含本任务的六个文件。

### Task 4: 使用 Docker 全量回归并修复关联测试

**Files:**
- Modify: `cm-agent-persistence/pom.xml`
- Modify: `cm-agent-persistence/src/test/java/com/cmagent/persistence/Jdbc*RepositoryTest.java`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`
- Create: `cm-agent-server/src/test/java/com/cmagent/server/config/JdbcPersistenceConfigurationTest.java`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/security/JwtSecurityConfigurationTest.java`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/web/ConsoleSmokeTest.java`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerJdbcPersistenceTest.java`

**Progress ledger:**
- [x] 在虚拟机 Docker 的 PostgreSQL 16 Testcontainers 环境重现完整 reactor 失败：Flyway 缺少 PostgreSQL 数据库模块、JDBC 无法推断 `Instant` 参数类型，以及生产 profile 测试夹具不满足 JDBC 前置条件。
- [x] 增加 `flyway-database-postgresql`，将 JDBC 种子与初始化时间参数改为 `Timestamp`，并增加对应单元测试。
- [x] 将 JDBC 运行链路测试的运行时替身改为局部 `@MockBean`，关闭默认 fake runtime，避免测试上下文 Bean 冲突。
- [x] 让控制台生产 profile smoke 测试使用 PostgreSQL Testcontainers；安全测试只加载其职责相关配置，并启用 JavaBean 配置属性绑定。
- [x] 运行 `RunControllerJdbcPersistenceTest`：`Tests run: 1, Failures: 0, Errors: 0`。
- [x] 运行 `JwtSecurityConfigurationTest`：`Tests run: 11, Failures: 0, Errors: 0`。
- [x] 运行 `ConsoleSmokeTest`：`Tests run: 3, Failures: 0, Errors: 0`。
- [x] 修复 JWT 篡改测试修改 Base64URL 尾部未使用位导致的偶发误通过；改为修改签名段首字节，并运行 `AuthControllerTest`：`Tests run: 8, Failures: 0, Errors: 0`。
- [x] 运行 `mvn -B -pl cm-agent-server -am test`：服务端 `Tests run: 61, Failures: 0, Errors: 0`；7 个 reactor 模块全部 `SUCCESS`。
