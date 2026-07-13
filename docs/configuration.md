# 配置说明

本文档说明 CM Agent 第一阶段常用配置项和敏感配置处理要求。

## 基础配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | 服务端监听端口 |
| `spring.profiles.active` | `local` | 运行环境选择器；默认进入本地 profile，可设置为 `test`、`prod` 或 `production` |
| `cm-agent.default-tenant-code` | `default` | 默认租户标识 |
| `cm-agent.fake-runtime-enabled` | `true` | 第一阶段使用 fake runtime，便于在未接入真实模型时验证 Agent 运行链路 |
| `cm-agent.security.jwt-secret` | 空 | JWT 签名密钥；由 `cm-agent.config.jwt-secret` 绑定，生产环境必须由受控外部 YAML 提供 |
| `cm-agent.security.allow-dev-jwt-fallback` | `false` | 是否允许 local/test profile 在缺少 JWT 密钥时使用开发回退密钥；仅限本地调试 |
| `cm-agent.security.bootstrap-admin-enabled` | `false` | 是否启用本地 bootstrap admin 登录；仅限本地开发/演示 |
| `cm-agent.security.bootstrap-admin-username` | `admin` | bootstrap admin 用户名 |
| `cm-agent.security.bootstrap-admin-password` | 空 | bootstrap admin 密码；启用时必须由外部 Secret 或本地命令行显式注入 |
| `cm-agent.security.bootstrap-admin-display-name` | `系统管理员` | bootstrap admin 显示名 |

## 环境 Profile

公共 `application.yml` 集中定义实际 Spring 绑定项，并从 `cm-agent.config.*` 读取变量值。profile YAML 和受控外部 YAML 只定义这些变量，用于覆盖环境差异；profile 始终通过 `--spring.profiles.active=<profile>` 选择。

```yaml
cm-agent:
  security:
    jwt-secret: ${cm-agent.config.jwt-secret:}
```

本地开发调试默认使用 `local` profile，也可以显式设置：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local"
```

`local` profile 会加载 `application-local.yml`，用于团队本地开发和调试。该 profile 默认使用 memory 持久化、fake runtime、本地专用 JWT 密钥和 bootstrap admin。控制台登录账号为 `admin`，密码为 `cm-agent-local-dev-password-only`。该配置包含可直接使用的本地凭据，只能用于本地开发调试。

自动化测试或需要复用测试凭据时，可以显式设置：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=test"
```

`test` profile 会加载 `application-test.yml`，用于本地控制台和接口联调测试。测试登录账号为 `admin`，密码为 `cm-agent-test-password-only`。该配置包含可直接使用的测试凭据，只能用于本地测试。

开发 JWT 回退默认关闭。需要本地无密钥调试时，可以显式传入 `--cm-agent.config.allow-dev-jwt-fallback=true`；生产环境不得启用该开关。

生产部署应通过 `--spring.profiles.active=prod` 或 `--spring.profiles.active=production` 选择 profile，并在受控外部 YAML 中设置 `cm-agent.config.jwt-secret`。生产 profile 下启用 bootstrap admin 会导致服务启动失败。

## fake runtime

第一阶段默认使用：

```yaml
cm-agent:
  fake-runtime-enabled: true
```

fake runtime 用于验证 Agent 配置、权限、工具治理、审计和控制台体验。接入真实模型供应商前，可以保留该配置完成端到端联调；准备切换真实运行时时，应先完成模型配置、密钥托管、权限校验和审计策略。

## 运行态存储

服务端默认使用 memory store，适合本地演示和纵切验证。生产或类生产环境必须使用 JDBC 持久化：

| 配置变量 | 映射的实际配置项 | 说明 |
| --- | --- | --- |
| `cm-agent.config.jwt-secret` | `cm-agent.security.jwt-secret` | 生产环境由受控外部 YAML 提供 |
| `cm-agent.config.persistence-mode` | `cm-agent.persistence.mode` | `memory` 或 `jdbc` |
| `cm-agent.config.jdbc-url` | `cm-agent.persistence.jdbc.url` | 启用 JDBC 时必填 |
| `cm-agent.config.jdbc-username` | `cm-agent.persistence.jdbc.username` | 数据库用户名 |
| `cm-agent.config.jdbc-password` | `cm-agent.persistence.jdbc.password` | 仅写入受控外部 YAML |
| `cm-agent.config.jdbc-driver-class-name` | `cm-agent.persistence.jdbc.driver-class-name` | PostgreSQL 或 MySQL JDBC 驱动 |

### Supabase PostgreSQL

Supabase 作为托管 PostgreSQL 接入，不需要 Supabase Java SDK。推荐使用 `supabase` profile，并在受控外部 `application-supabase.yml` 中定义变量：

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

`supabase` profile 会默认启用 JDBC 持久化、禁用 bootstrap admin、禁用开发 JWT fallback。缺少 JDBC URL 或试图使用 memory mode 时，服务会启动失败。

不要把 Supabase 数据库密码、JWT secret 或完整 JDBC URL 提交到 Git、写入镜像层或打印到日志。开发验证应优先使用 Supabase development branch，避免直接对主项目数据库执行 DDL。

## JWT 密钥

生产环境必须在受控外部 YAML 中配置安全长度的 `cm-agent.config.jwt-secret`，推荐由部署平台或密钥管理服务生成、挂载或更新该文件。不要将密钥提交到 Git、写入镜像层或打印到日志。

本地开发调试使用 `--spring.profiles.active=local`；自动化测试使用 `--spring.profiles.active=test`。需要手动覆盖配置时，也可以临时使用命令行参数：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.config.jwt-secret=cm-agent-local-secret-with-at-least-32-bytes-2026 --cm-agent.config.bootstrap-admin-enabled=true --cm-agent.config.bootstrap-admin-password=<local-dev-only-password>"
```

## Bootstrap Admin

bootstrap admin 默认关闭。需要本地开发或演示登录控制台时，必须显式配置：

```yaml
cm-agent:
  config:
    bootstrap-admin-enabled: true
    bootstrap-admin-password: <local-dev-only-password>
```

不要在配置文件、前端代码或文档示例中写入可直接使用的真实凭据。`prod`、`production` 或 `supabase` profile 下禁止启用 bootstrap admin；如果启用，服务端会在启动时失败。

## 模型供应商与 API Key

模型供应商配置和 API Key 属于敏感数据：

- API Key 不得明文返回给前端、日志、审计响应或普通查询接口。
- 持久化时应加密保存，或只保存密钥引用，由 secret provider 在运行时解析。
- 配置展示时应只返回脱敏摘要、状态、创建人、更新时间等非敏感字段。
- 轮换 API Key 时应记录审计事件，并避免中断正在执行的运行任务。
