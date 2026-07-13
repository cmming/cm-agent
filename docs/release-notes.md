# 发布说明

## 0.1.0-SNAPSHOT

`0.1.0-SNAPSHOT` 是 CM Agent 第一阶段版本，目标是交付可本地运行、可演示、可继续扩展的企业级智能体底座。

第一阶段目标包括：

- Maven 多模块工程骨架。
- 核心领域模型、运行接口和 fake runtime。
- Spring Boot Starter，用于自动装配核心能力。
- 独立服务端，包含 JWT、RBAC 基线、Agent 配置、工具治理和审计 API。
- 服务端公共 YAML 已集中，profile 和受控外部 YAML 通过 `cm-agent.config.*` 覆盖环境差异。
- 本地开发调试 profile `application-local.yml`，提供本地专用 JWT、bootstrap admin 和 memory 持久化默认值。
- 内网虚拟机数据库 profile `application-postgres.yml` 和 `application-mysql.yml`，可直接连接 `192.168.0.66:/data/cm-agent/docker-compose.yml` 中的 PostgreSQL 或 MySQL。
- MySQL 与 PostgreSQL 数据库迁移基线。
- 轻量控制台，用于验证登录、Agent 运行和基础治理流程。
- 本地开发数据库 Compose 配置。
- Starter 本地工具注册示例。

## 升级与兼容性

当前版本仍处于第一阶段快照状态，公共 API 和数据库结构可能在后续迭代中调整。生产试点前应固定镜像与提交版本，并保留数据库备份和回滚预案。

`CM_AGENT_PROFILE` 仅作为临时 profile 选择器兼容桥接；新部署应使用 `spring.profiles.active`。除 `postgres`/`mysql` 内网虚拟机集成 profile 可使用内置 VM 连接配置和 VM 专用 JWT secret 外，数据库连接和密钥仍必须由受控外部 YAML 中的 `cm-agent.config.*` 提供；生产部署必须遵循外部 YAML 要求。为避免 VM 联调凭据进入生产样环境，`postgres` 或 `mysql` 与 `prod`、`production`、`supabase` 混用时服务会在 JWT 密钥校验前拒绝启动。
