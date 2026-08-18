# Docker Compose Redis 服务实现说明

对应[设计](../specs/2026-08-17-docker-compose-redis-design.md)与[实施计划](../plans/2026-08-17-docker-compose-redis.md)。

## 本地配置

根目录 `docker-compose.yml` 新增 `redis` 服务：

- 镜像：`redis:7.0-alpine`；
- 端口：`6379:6379`；
- 未增加认证、持久化卷或应用配置，保持与现有本地数据库服务相同的最小联调定位。

部署文档补充 Rocky 环境的 Redis 启动与检查命令，并明确不得输出包含密码的 `REDIS_URL`。

## 远程执行方式

先确认 `/data/cm-agent/docker-compose.yml` 是目标文件、Docker 与 Compose 可用且远程 Git 提交可与本地 HEAD 对照；随后使用 SCP 覆盖该明确目标，执行 `docker compose config -q` 后仅运行 `docker compose up -d redis`，避免启动、停止或清理无关服务。

实际执行中，`/data/cm-agent` 不含 `.git`，因此无法完成远程提交 SHA 对照；该目录的 Docker 23.0.6 和 Compose 2.29.1 可用。用户已明确授权覆盖该确切文件路径，故在记录该限制后继续执行。远程 JDK 为 17、Maven 为 3.6.3，不符合项目的 JDK 21 / Maven 3.9+ 验证要求；本次未在远程运行 Maven，仅进行 Compose 服务操作。
