# 部署指南

本文档说明 CM Agent 第一阶段在本地和基础生产环境中的构建、数据库启动与服务端启动方式。

## 前置条件

- Java 21
- Maven 3.9 或更高版本
- Docker 与 Docker Compose，用于启动本地开发数据库
- 生产环境需要通过密钥管理服务、部署平台或受控外部配置文件提供 `cm-agent.config.jwt-secret`

## 本地构建

在项目根目录执行：

```powershell
mvn -q "-DskipTests" package
```

如果在 Windows PowerShell 中需要指定 Java 21，可以先执行：

```powershell
$jdk='C:\Program Files\Java\jdk-21'
$env:JAVA_HOME=$jdk
$env:Path="$jdk\bin;$env:Path"
```

请替换为本机 JDK 21 安装路径。

## 启动开发数据库

项目根目录提供 `docker-compose.yml`，包含 MySQL 8.4 与 PostgreSQL 16：

```powershell
docker compose up -d mysql postgres
```

默认数据库名为 `cm_agent`。本地开发凭据为 MySQL `root` 用户/密码 `cmagent`，PostgreSQL `cmagent` 用户/密码 `cmagent`；这些凭据仅用于开发和集成验证，生产环境应使用独立数据库账号、强密码和受控网络访问策略。

local 默认使用 memory mode 保存运行态数据，重启后会丢失。启用 `jdbc` 或 `supabase` profile 后，Agent、Tool 和 ToolGrant 会通过 JDBC/Flyway 持久化；Audit、Run、ToolCall 仍属于后续生产化范围或本阶段尚未完成 service 接线。

## 数据库场景与项目配置

CM Agent 第一阶段的持久化由 `cm-agent.config.persistence-mode` 控制。公共 `application.yml` 将该变量实际绑定到 `cm-agent.persistence.mode`；`memory` 只适合本地开发、演示和自动化测试，`jdbc` 用于 PostgreSQL、MySQL 和 Supabase PostgreSQL。服务端进入 `jdbc` 模式后会创建 Hikari DataSource，并在启动时通过 Flyway 自动执行 `classpath:db/migration` 下的迁移。

数据库配置建议写入部署节点上的外部 YAML 配置文件，例如本地 `./config/application-local.yml`、生产 `/etc/cm-agent/application-production.yml` 或 `/etc/cm-agent/application-supabase.yml`。启动时通过 `--spring.config.additional-location=file:<配置目录>/` 加载外部配置目录，并通过 `--spring.profiles.active=<profile>` 选择运行 profile。数据库环境变量方式当前不使用；连接信息必须写入受控外部 YAML。

通用配置项如下：

| 配置变量 | 实际绑定项 | 说明 |
| --- | --- | --- |
| `cm-agent.config.jwt-secret` | `cm-agent.security.jwt-secret` | 生产或类生产必须配置安全长度密钥；不要写入 Git 管理的配置文件 |
| `cm-agent.config.persistence-mode` | `cm-agent.persistence.mode` | `memory` 或 `jdbc`；`prod`、`production`、`supabase` profile 下必须为 `jdbc` |
| `cm-agent.config.jdbc-url` | `cm-agent.persistence.jdbc.url` | JDBC URL；启用 `jdbc` 时必须配置 |
| `cm-agent.config.jdbc-username` | `cm-agent.persistence.jdbc.username` | 数据库用户名 |
| `cm-agent.config.jdbc-password` | `cm-agent.persistence.jdbc.password` | 数据库密码；生产环境应由密钥系统生成或挂载到受控配置文件，不得提交到 Git |
| `cm-agent.config.jdbc-driver-class-name` | `cm-agent.persistence.jdbc.driver-class-name` | PostgreSQL 使用 `org.postgresql.Driver`，MySQL 使用 `com.mysql.cj.jdbc.Driver` |

### 场景一：本地 memory，无外部数据库

适用于快速开发、控制台体验和接口联调。该场景不需要启动 Docker 数据库，不需要配置 JDBC；数据保存在进程内存，服务重启后丢失。

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local"
```

`local` profile 会加载 `application-local.yml`，默认启用本地 bootstrap admin、本地专用 JWT 密钥和 memory 持久化。该 profile 只能用于本地开发调试，不能用于生产或类生产环境。

### 场景二：本地 PostgreSQL，使用 docker-compose

适用于在本机验证 PostgreSQL JDBC/Flyway、Repository 行为和迁移兼容性。先启动 Compose 中的 PostgreSQL：

```powershell
docker compose up -d postgres
```

然后创建或更新 `./config/application-local.yml`，使用 `local` profile 加 `jdbc` 持久化启动服务端：

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

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --spring.config.additional-location=file:./config/"
```

首次启动会自动执行 Flyway migration，并初始化默认租户和默认模型占位数据。本地 Compose 凭据只用于开发验证；不要复制到生产配置。

### 场景三：本地 MySQL，使用 docker-compose

适用于在本机验证 MySQL 8.4 迁移兼容性和 JDBC Repository 行为。先启动 Compose 中的 MySQL：

```powershell
docker compose up -d mysql
```

然后创建或更新 `./config/application-local.yml`，使用 `local` profile 加 `jdbc` 持久化启动服务端：

```yaml
cm-agent:
  config:
    jwt-secret: <secret-manager-jwt-secret>
    persistence-mode: jdbc
    jdbc-url: jdbc:mysql://<db-host>:3306/<database-name>?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    jdbc-username: <least-privilege-db-user>
    jdbc-password: <secret-manager-db-password>
    jdbc-driver-class-name: com.mysql.cj.jdbc.Driver
```

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local --spring.config.additional-location=file:./config/"
```

`allowPublicKeyRetrieval=true` 仅用于本地 Docker 验证。生产 MySQL 应使用受控账号、TLS、最小权限和受控外部配置文件，不要沿用本地 root 用户或示例密码。

### 场景四：生产或类生产 PostgreSQL

适用于自建 PostgreSQL、云厂商托管 PostgreSQL 或企业内部 PostgreSQL。推荐使用 `production` 或 `prod` profile，并在 `/etc/cm-agent/application-production.yml` 中显式启用 JDBC：

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

```powershell
java -jar cm-agent-server\target\cm-agent-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=production --spring.config.additional-location=file:/etc/cm-agent/
```

如果数据库平台要求 SSL，请在 JDBC URL 中加入平台要求的 SSL 参数，例如 `sslmode=require`。生产数据库账号应具备应用运行和 Flyway migration 所需权限；如果组织要求 DDL 与运行账号分离，应由发布流程先执行 migration，再使用运行账号启动应用。

### 场景五：生产或类生产 MySQL

适用于自建 MySQL 8.4、云厂商托管 MySQL 或企业内部 MySQL。推荐使用 `production` 或 `prod` profile，并在 `/etc/cm-agent/application-production.yml` 中显式启用 JDBC：

```yaml
cm-agent:
  config:
    jwt-secret: <secret-manager-jwt-secret>
    persistence-mode: jdbc
    jdbc-url: jdbc:mysql://<db-host>:3306/<database-name>?useSSL=true&serverTimezone=UTC
    jdbc-username: <least-privilege-db-user>
    jdbc-password: <secret-manager-db-password>
    jdbc-driver-class-name: com.mysql.cj.jdbc.Driver
```

```powershell
java -jar cm-agent-server\target\cm-agent-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=production --spring.config.additional-location=file:/etc/cm-agent/
```

生产 MySQL 不要使用本地示例中的 `root` 用户、`allowPublicKeyRetrieval=true`，也不要把数据库密码写入 Git 管理的配置文件或镜像层。请按平台要求配置 TLS、字符集、备份、恢复演练和连接数上限。

### 场景六：Supabase PostgreSQL

Supabase 接入复用现有 PostgreSQL JDBC/Flyway 链路，不需要引入 Supabase Java SDK。推荐使用 `supabase` profile；该 profile 会默认启用 `jdbc`、禁用 bootstrap admin、禁用开发 JWT fallback，并默认使用 `org.postgresql.Driver`。

默认不要直接对 Supabase 主项目执行 DDL；先创建 development branch，再在 branch 上验证 schema。推荐流程：

1. 在 Supabase 中为项目创建 development branch，名称建议为 `cm-agent-supabase-persistence`。
2. 在 branch 上检查 `public` schema。
3. 如果缺少本阶段 Agent/Tool/ToolGrant 持久化最小验收表，或 Flyway migration 尚未应用，在 branch 上应用 `cm-agent-persistence/src/main/resources/db/migration/V1__init_schema.sql`。
4. 本阶段最小验收需确认至少存在 `tenants`、`model_configs`、`agent_definitions`、`tool_definitions`、`tool_grants`；V1 migration 还会创建审计、运行记录等后续阶段使用的表。
5. 使用 branch 的 JDBC URL 和数据库凭据启动服务端；通过后再按发布流程切换到正式 Supabase 数据库。

本地或部署环境创建对应的外部配置文件，例如 `./config/application-supabase.yml` 或 `/etc/cm-agent/application-supabase.yml`：

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

本地启动示例：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=supabase --spring.config.additional-location=file:./config/"
```

Supabase JDBC URL、数据库密码和 JWT secret 都必须来自 Secret Manager、部署平台或其生成/挂载的受控配置文件，不能提交到 Git、写入镜像层或打印到日志。若数据库凭据不可用，仍可先完成配置测试和 Supabase branch 表结构检查，再由部署环境注入 secret 后运行 smoke test。

## 启动服务端

本地开发调试默认使用 `local` profile 快速启动：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local"
```

`local` profile 会加载 `application-local.yml`，启用 memory 持久化、fake runtime、本地 bootstrap admin 和本地专用 JWT 密钥。开发登录账号为 `admin`，密码为 `cm-agent-local-dev-password-only`。该 profile 仅用于本地开发调试。

自动化测试或需要复用测试凭据时，可以显式使用 `test` profile；测试账号为 `admin`，密码为 `cm-agent-test-password-only`。该 profile 仅用于本地测试。

本地开发可以通过命令行传入安全长度的 JWT 密钥启动服务端：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.config.jwt-secret=cm-agent-local-secret-with-at-least-32-bytes-2026 --cm-agent.config.bootstrap-admin-enabled=true --cm-agent.config.bootstrap-admin-password=<local-dev-only-password>"
```

上面的 bootstrap admin 配置仅用于本地开发/演示。生产环境不要启用 `cm-agent.security.bootstrap-admin-enabled`，也不要在镜像、配置仓库或前端资源中内置管理员密码。

服务启动后默认监听 `8080` 端口：

- 健康检查：`GET http://localhost:8080/actuator/health`
- 控制台：`http://localhost:8080/`
- OpenAPI 页面：`http://localhost:8080/swagger-ui/index.html`

## 生产部署要点

- 不要依赖本地开发数据库配置；生产数据库配置必须使用受控外部配置文件，数据库密码应由密钥系统生成或挂载。
- 不要在 Git 管理的配置文件、镜像层或日志中写入 JWT 密钥、模型 API Key 或数据库密码。
- `cm-agent.config.jwt-secret` 缺失时，生产环境应保持启动失败，避免使用开发回退密钥。
- 生产部署必须显式设置 `spring.profiles.active=prod`、`spring.profiles.active=production` 或用于 Supabase 托管 PostgreSQL 的 `spring.profiles.active=supabase`，避免使用本地测试 profile。
- 不要在生产环境使用 `application-local.yml` 或 `application-test.yml` 中的本地/测试账号和 JWT 密钥。
- `prod`、`production` 或 `supabase` profile 下禁止启用 bootstrap admin；管理员账号应接入正式身份源或受控账号体系。
- 生产试点前必须启用并验证 JDBC/Supabase 持久化，完成 Supabase branch 或目标数据库 schema 校验，不能使用 memory mode 保存 Agent、Tool、Grant。
- 第一阶段默认启用 fake runtime，适合验证控制台、权限、审计和运行链路；接入真实模型前应完成模型供应商和密钥托管配置。
