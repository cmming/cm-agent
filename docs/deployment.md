# 部署指南

本文档说明 CM Agent 第一阶段在本地和基础生产环境中的构建、数据库启动与服务端启动方式。

## 前置条件

- Java 21
- Maven 3.9 或更高版本
- Docker 与 Docker Compose，用于启动本地开发数据库
- 生产环境需要通过环境变量、密钥管理服务或部署平台注入 `CM_AGENT_JWT_SECRET`

## 本地构建

在项目根目录执行：

```powershell
mvn -q "-DskipTests" package
```

如果在 Windows PowerShell 中需要指定 Java 21，可以先执行：

```powershell
$jdk='C:\Users\chmi\.codex\jdks\microsoft-jdk-21.0.11-extracted\PFiles64\Microsoft\jdk-21.0.11.10-hotspot'
$env:JAVA_HOME=$jdk
$env:Path="$jdk\bin;$env:Path"
```

## 启动开发数据库

项目根目录提供 `docker-compose.yml`，包含 MySQL 8.4 与 PostgreSQL 16：

```powershell
docker compose up -d mysql postgres
```

默认数据库名为 `cm_agent`，本地开发密码为 `cmagent`。该配置只用于本地开发和集成验证，生产环境应使用独立数据库账号、强密码和受控网络访问策略。

## 启动服务端

本地开发可以通过命令行传入安全长度的 JWT 密钥启动服务端：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.security.jwt-secret=cm-agent-local-secret-with-at-least-32-bytes-2026"
```

服务启动后默认监听 `8080` 端口：

- 健康检查：`GET http://localhost:8080/actuator/health`
- 控制台：`http://localhost:8080/`
- OpenAPI 页面：`http://localhost:8080/swagger-ui/index.html`

## 生产部署要点

- 不要依赖本地开发数据库配置；生产数据库凭据必须由密钥系统注入。
- 不要在配置文件、镜像层或日志中写入 JWT 密钥、模型 API Key 或数据库密码。
- `cm-agent.security.jwt-secret` 缺失时，生产环境应保持启动失败，避免使用开发回退密钥。
- 第一阶段默认启用 fake runtime，适合验证控制台、权限、审计和运行链路；接入真实模型前应完成模型供应商和密钥托管配置。
