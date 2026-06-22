# 测试启动 Profile 设计

## 背景

当前 `cm-agent-server/src/main/resources/application.yml` 只定义了安全相关配置的环境变量占位：

- `CM_AGENT_JWT_SECRET`
- `CM_AGENT_BOOTSTRAP_ADMIN_USERNAME`
- `CM_AGENT_BOOTSTRAP_ADMIN_PASSWORD`
- `CM_AGENT_BOOTSTRAP_ADMIN_DISPLAY_NAME`

本地启动测试控制台时，需要手动拼接较长的 `spring-boot.run.arguments`。这不利于快速验证，也容易漏掉 `jwt-secret` 或 bootstrap admin 参数。同时，默认配置中还没有统一的环境选择入口，无法通过一个环境变量在启动时切换到不同 profile 配置。

## 目标

新增一个环境选择入口和一个仅用于本地测试启动的 Spring Boot profile 配置，让开发者可以通过 `application.yml` 中的 profile 选择器或命令行参数切换环境，并在 `test` 环境下快速启动服务端和登录控制台。

## 非目标

- 不改变默认 `application.yml` 的安全默认值。
- 不放宽 `prod` 或 `production` profile 下禁止启用 bootstrap admin 的约束。
- 不把测试配置描述为生产可用配置。
- 不引入新的身份源、密钥托管或持久化方案。
- 不在提交的默认配置中把 `test` 设为隐式默认环境。

## 方案

在 `cm-agent-server/src/main/resources/application.yml` 中新增环境选择器：

```yaml
spring:
  profiles:
    active: ${CM_AGENT_PROFILE:local}
```

该配置让服务默认进入 `local` profile，并允许部署或本地启动时通过 `CM_AGENT_PROFILE` 覆盖。命令行 `--spring.profiles.active=...` 仍可作为 Spring Boot 标准方式覆盖 active profile。

新增 `cm-agent-server/src/main/resources/application-test.yml`：

- 配置测试用 `cm-agent.security.jwt-secret`，满足 HMAC-SHA JWT 密钥长度要求。
- 开启 `cm-agent.security.bootstrap-admin-enabled`。
- 设置测试用 bootstrap admin 用户名、密码和显示名。
- 保持 `fake-runtime-enabled: true`，继续服务第一阶段本地端到端验证。

默认 `local` profile 仍保持 bootstrap admin 关闭、JWT 密钥可走现有本地回退策略。生产部署必须显式使用 `CM_AGENT_PROFILE=prod` 或 `CM_AGENT_PROFILE=production`，并通过外部 Secret 注入 `CM_AGENT_JWT_SECRET`。生产 profile 的启动失败保护继续由现有 `JwtSecurityConfiguration` 和 `BootstrapAdminProperties` 负责。

## 使用方式

本地测试启动可以使用环境变量：

```powershell
$env:CM_AGENT_PROFILE='test'
mvn -pl cm-agent-server -am spring-boot:run
```

也可以使用命令行参数：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=test"
```

启动后可使用测试 profile 中配置的 bootstrap admin 凭据登录控制台。

## 文档

更新中文文档：

- `README.md`：在快速开始中补充 `CM_AGENT_PROFILE=test` 和命令行 profile 两种测试启动方式。
- `docs/configuration.md`：说明 `application.yml` 中的 `CM_AGENT_PROFILE` 环境选择器，以及 `application-test.yml` 仅用于本地测试。
- 如有必要，`docs/deployment.md` 继续强调生产不要启用 bootstrap admin。

## 测试

实施后至少验证：

```powershell
$env:JAVA_HOME='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -q -pl cm-agent-server -am '-Dtest=JwtSecurityConfigurationTest,AuthControllerTest,ConsoleSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -q -pl cm-agent-server -am '-DskipTests' package
```

可选手工验证：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=test"
```

然后访问 `http://localhost:8080/actuator/health` 和 `http://localhost:8080/`。

## 风险与约束

测试 profile 会包含可直接使用的本地测试密码，因此文档必须明确它不适用于生产。该配置只能降低本地验证成本，不能替代生产 Secret 注入、正式身份源或受控账号体系。

`application.yml` 只提供 profile 选择器，不应把 `test` 写成默认值。否则打包后的服务在未显式覆盖环境时会进入测试配置，削弱生产化默认安全边界。
