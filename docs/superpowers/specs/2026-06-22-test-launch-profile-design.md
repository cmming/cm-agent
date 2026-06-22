# 测试启动 Profile 设计

## 背景

当前 `cm-agent-server/src/main/resources/application.yml` 只定义了安全相关配置的环境变量占位：

- `CM_AGENT_JWT_SECRET`
- `CM_AGENT_BOOTSTRAP_ADMIN_USERNAME`
- `CM_AGENT_BOOTSTRAP_ADMIN_PASSWORD`
- `CM_AGENT_BOOTSTRAP_ADMIN_DISPLAY_NAME`

本地启动测试控制台时，需要手动拼接较长的 `spring-boot.run.arguments`。这不利于快速验证，也容易漏掉 `jwt-secret` 或 bootstrap admin 参数。

## 目标

新增一个仅用于本地测试启动的 Spring Boot profile 配置，让开发者可以通过 `--spring.profiles.active=test` 快速启动服务端并登录控制台。

## 非目标

- 不改变默认 `application.yml` 的安全默认值。
- 不放宽 `prod` 或 `production` profile 下禁止启用 bootstrap admin 的约束。
- 不把测试配置描述为生产可用配置。
- 不引入新的身份源、密钥托管或持久化方案。

## 方案

新增 `cm-agent-server/src/main/resources/application-test.yml`：

- 配置测试用 `cm-agent.security.jwt-secret`，满足 HMAC-SHA JWT 密钥长度要求。
- 开启 `cm-agent.security.bootstrap-admin-enabled`。
- 设置测试用 bootstrap admin 用户名、密码和显示名。
- 保持 `fake-runtime-enabled: true`，继续服务第一阶段本地端到端验证。

默认 profile 仍保持 bootstrap admin 关闭、JWT 密钥外部注入。生产 profile 的启动失败保护继续由现有 `JwtSecurityConfiguration` 和 `BootstrapAdminProperties` 负责。

## 使用方式

本地测试启动使用：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=test"
```

启动后可使用测试 profile 中配置的 bootstrap admin 凭据登录控制台。

## 文档

更新中文文档：

- `README.md`：在快速开始中补充测试 profile 启动方式。
- `docs/configuration.md`：说明 `application-test.yml` 仅用于本地测试，生产仍必须外部注入密钥。
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
