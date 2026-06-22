# 配置说明

本文档说明 CM Agent 第一阶段常用配置项和敏感配置处理要求。

## 基础配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | 服务端监听端口 |
| `CM_AGENT_PROFILE` / `spring.profiles.active` | `local` | 运行环境选择器；默认进入本地 profile，可设置为 `test`、`prod` 或 `production` |
| `cm-agent.default-tenant-code` | `default` | 默认租户标识 |
| `cm-agent.fake-runtime-enabled` | `true` | 第一阶段使用 fake runtime，便于在未接入真实模型时验证 Agent 运行链路 |
| `cm-agent.security.jwt-secret` | 空 | JWT 签名密钥，生产环境必须由外部安全注入 |
| `cm-agent.security.allow-dev-jwt-fallback` | `false` | 是否允许 local/test profile 在缺少 JWT 密钥时使用开发回退密钥；仅限本地调试 |
| `cm-agent.security.bootstrap-admin-enabled` | `false` | 是否启用本地 bootstrap admin 登录；仅限本地开发/演示 |
| `cm-agent.security.bootstrap-admin-username` | `admin` | bootstrap admin 用户名 |
| `cm-agent.security.bootstrap-admin-password` | 空 | bootstrap admin 密码；启用时必须由外部 Secret 或本地命令行显式注入 |
| `cm-agent.security.bootstrap-admin-display-name` | `系统管理员` | bootstrap admin 显示名 |

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

## fake runtime

第一阶段默认使用：

```yaml
cm-agent:
  fake-runtime-enabled: true
```

fake runtime 用于验证 Agent 配置、权限、工具治理、审计和控制台体验。接入真实模型供应商前，可以保留该配置完成端到端联调；准备切换真实运行时时，应先完成模型配置、密钥托管、权限校验和审计策略。

## 运行态存储

第一阶段服务端默认使用内存 store 承载 Agent、Tool、Grant 和 Audit API 的运行态数据，适合本地演示和纵切验证。MySQL/PostgreSQL schema、Flyway 迁移和 JDBC repository 已作为持久化基线存在，但默认 REST 服务端尚未接入这些表保存运行态数据。生产试点前应完成 JDBC store 或等价服务层接入，并重新验证迁移、租户隔离、备份和恢复流程。

## JWT 密钥

生产环境必须配置安全长度的 `cm-agent.security.jwt-secret`，推荐通过部署平台 Secret、环境变量或密钥管理服务注入。不要将密钥提交到 Git、写入镜像层或打印到日志。

本地测试优先使用 `CM_AGENT_PROFILE=test`；需要手动覆盖配置时，也可以临时使用命令行参数：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.security.jwt-secret=cm-agent-local-secret-with-at-least-32-bytes-2026 --cm-agent.security.bootstrap-admin-enabled=true --cm-agent.security.bootstrap-admin-password=<local-dev-only-password>"
```

## Bootstrap Admin

bootstrap admin 默认关闭。需要本地开发或演示登录控制台时，必须显式配置：

```yaml
cm-agent:
  security:
    bootstrap-admin-enabled: true
    bootstrap-admin-password: ${CM_AGENT_BOOTSTRAP_ADMIN_PASSWORD}
```

不要在配置文件、前端代码或文档示例中写入可直接使用的真实密码。`prod` 或 `production` profile 下禁止启用 bootstrap admin；如果启用，服务端会在启动时失败。

## 模型供应商与 API Key

模型供应商配置和 API Key 属于敏感数据：

- API Key 不得明文返回给前端、日志、审计响应或普通查询接口。
- 持久化时应加密保存，或只保存密钥引用，由 secret provider 在运行时解析。
- 配置展示时应只返回脱敏摘要、状态、创建人、更新时间等非敏感字段。
- 轮换 API Key 时应记录审计事件，并避免中断正在执行的运行任务。
