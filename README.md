# CM Agent

CM Agent 是基于 AgentScope Java 的企业级智能体开源底座。第一阶段完成 Java SDK、Spring Boot Starter、独立服务端、轻量控制台、工具治理、多租户和 RBAC 基线；阶段2完成生产持久化与安全收口。

## 快速开始

本地开发调试必须显式选择 `local` profile。该 profile 加载 `application-local.yml`，启用 memory 持久化、fake runtime、本地 bootstrap admin 和本地专用 JWT 配置。无 profile 启动时不会自动加载 `local`。

```powershell
mvn -q "-DskipTests" package
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local"
```

测试启动同样需要显式选择 `test` profile：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=test"
```

本地和测试 profile 的 bootstrap admin 凭据只由代码/CI 注入或使用本地专用占位配置，不能复制到生产。生产和类生产环境必须使用受控外部 YAML 或 secret manager 提供 JWT secret 和 JDBC 凭据。

服务启动后访问：

- 健康检查：`http://localhost:8080/actuator/health`
- 控制台：`http://localhost:8080/`
- OpenAPI：`http://localhost:8080/swagger-ui/index.html`

临时覆盖本地配置时，请使用占位符并确保只在本地调试范围内生效：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.config.jwt-secret=<local-dev-only-jwt-secret> --cm-agent.config.bootstrap-admin-enabled=true --cm-agent.config.bootstrap-admin-password=<local-dev-only-password>"
```

## 当前状态

- 第一阶段：已交付工程骨架、核心领域接口、Starter、控制台、工具治理、多租户/RBAC 基线和 fake runtime。
- 阶段2：已交付 Run、ToolCall、Audit 的 JDBC Repository 与 Flyway V2/V3 查询索引，租户隔离、严格审计、JWT/profile/bootstrap/error/redaction 安全收口，以及运行启动/完成两段事务和 cursor 查询。
- 阶段3：真实 AgentScope runtime 尚未交付，当前运行链路仍以 fake runtime 为边界。
- 阶段4：可观测性与运维增强尚未交付。
- 阶段5：交付与稳定性工程尚未交付。

完整范围和后续依赖见[中文路线图](docs/roadmap.md)。

## 生产文档

- [中文路线图](docs/roadmap.md)
- [配置说明](docs/configuration.md)
- [部署指南](docs/deployment.md)
- [运维说明](docs/operations.md)
- [发布说明](docs/release-notes.md)

## 文档语言

生产文档默认使用中文。英文文档可以作为翻译补充，但不能替代中文文档。
