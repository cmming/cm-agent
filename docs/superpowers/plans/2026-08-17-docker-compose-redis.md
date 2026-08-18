# Docker Compose Redis 服务实施计划

对应设计：[Docker Compose Redis 服务设计](../specs/2026-08-17-docker-compose-redis-design.md)。

1. 阅读当前 Compose 与部署文档，确认只新增 Redis 服务而不改变现有数据库服务。
2. 将 `redis:7.0-alpine` 和 `6379:6379` 端口映射写入根目录 Compose 文件，并同步部署文档。
3. 通过 SSH 检查 Rocky Docker、Compose、目标路径和远程 Git 提交。
4. 将本地 Compose 文件覆盖到 `/data/cm-agent/docker-compose.yml`，远程执行 `docker compose config -q`、`docker compose up -d redis` 与 `docker compose ps redis`。
5. 在进度账本记录实际验证结果；不清理或停止其他容器。
