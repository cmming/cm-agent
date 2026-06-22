# CM Agent

CM Agent 是一个基于 AgentScope Java 的企业级智能体开源底座。第一阶段提供 Java SDK、Spring Boot Starter、独立服务端、轻量控制台、工具治理、轻量多租户、RBAC 和审计能力。

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

服务启动后访问：

- 健康检查：`http://localhost:8080/actuator/health`
- 控制台：`http://localhost:8080/`
- OpenAPI：`http://localhost:8080/swagger-ui/index.html`

需要手动传参时，可以继续使用显式配置：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.security.jwt-secret=cm-agent-local-secret-with-at-least-32-bytes-2026 --cm-agent.security.bootstrap-admin-enabled=true --cm-agent.security.bootstrap-admin-password=cm-agent-local-dev-password-only"
```

## 生产文档

- [部署指南](docs/deployment.md)
- [运维说明](docs/operations.md)
- [配置说明](docs/configuration.md)
- [发布说明](docs/release-notes.md)

## 第一阶段范围

- Maven 多模块骨架
- 核心领域模型和接口
- Spring Boot Starter
- MySQL 和 PostgreSQL 迁移
- JWT、RBAC、API Key 基线
- Agent 配置、工具治理、fake runtime 运行链路
- 审计日志
- 最小控制台

## 文档语言

生产文档默认使用中文。英文文档可以作为翻译补充，但不能替代中文文档。
