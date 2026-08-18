# Docker Compose Redis 服务设计

## 背景与目标

`dashscope-mcp-agent` 示例新增了 `RedisAgentStateStoreExample`，需要一个可由 Rocky Linux 验证环境启动的 Redis 实例。本次在项目根目录 Docker Compose 配置中增加 Redis 7.0 Alpine 服务，并按用户要求覆盖 Rocky 主机 `/data/cm-agent/docker-compose.yml` 后启动该服务。

## 范围

- 在 `docker-compose.yml` 新增 `redis` 服务，镜像固定为 `redis:7.0-alpine`，映射宿主机 `6379` 端口。
- 更新部署文档中的 Rocky Redis 启动与检查命令。
- 覆盖 Rocky `/data/cm-agent/docker-compose.yml`，执行 Compose 配置校验并仅启动 `redis` 服务。

不修改 MySQL、PostgreSQL、应用配置或数据库迁移；不执行全局 Docker 清理，也不停止现有项目服务。

## 验收标准

- Rocky 主机 Docker 与 Docker Compose 可用，且目标文件路径明确存在。
- 远程 Compose 配置校验通过。
- `docker compose up -d redis` 后，`docker compose ps redis` 显示 Redis 服务处于运行状态。
